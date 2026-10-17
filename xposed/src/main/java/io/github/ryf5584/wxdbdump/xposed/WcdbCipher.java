package io.github.ryf5584.wxdbdump.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.ryf5584.wxdbdump.payload.Log;

/**
 * Records the SQLiteCipherSpec WeChat uses so it can be passed back verbatim.
 *
 * Stock SQLCipher cannot open these databases even with matching parameters, because
 * Tencent's WCDB differs in implementation, so the real spec is used instead of a guess.
 * Captured from openDatabase arguments, or from WCDB2 setCipherKey as pageSize plus version.
 */
public final class WcdbCipher {

    /** SQLiteCipherSpec from the newer WeChat WCDB. */
    private static final String SPEC_CLS = "com.tencent.wcdb.database.SQLiteCipherSpec";
    /** SQLiteCipherSpec from the WCDB compat package, the older path. */
    private static final String SPEC_CLS_COMPAT = "com.tencent.wcdb.compat.SQLiteCipherSpec";

    private static final Map<String, Object> SPEC_BY_PATH = new ConcurrentHashMap<>();
    /** Path to pageSize and cipherVersion, captured from WCDB2 setCipherKey. */
    private static final Map<String, int[]> PAGE_BY_PATH = new ConcurrentHashMap<>();

    private WcdbCipher() {
    }

    /** Path to the real spec object, possibly null. */
    public static Object captured(String path) {
        return path == null ? null : SPEC_BY_PATH.get(key(path));
    }

    /**
     * Normalizes a path for use as a key.
     *
     * <p>WeChat's own WCDB callbacks report {@code /data/data/<pkg>/...} while enumeration and
     * requests use {@code /data/user/0/<pkg>/...}. Without normalization the captured spec is
     * never found and a reconstructed one is used instead, which surfaces as CANTOPEN on a
     * database that opens perfectly well. Both forms must map to the same key.
     */
    private static String key(String path) {
        if (path == null) return "";
        if (path.startsWith("/data/user/0/")) return "/data/data/" + path.substring("/data/user/0/".length());
        return path;
    }

    /** Temp paths from our own decryption also hit openDatabase; keep them out of the map. */
    private static boolean isOurTemp(String path) {
        return path != null && (path.contains("/wxdump-plain/") || path.contains("/.stage-"));
    }

    public static void recordSpec(String path, Object spec) {
        if (path == null || spec == null || isOurTemp(path)) return;
        if (SPEC_BY_PATH.putIfAbsent(key(path), spec) == null) {
            Log.i("cipher", "saved real SQLiteCipherSpec: "
                    + path.substring(path.lastIndexOf('/') + 1) + " → " + describe(spec));
        }
    }

    public static void recordPage(String path, int pageSize, int cipherVersion) {
        if (path == null || pageSize <= 0 || isOurTemp(path)) return;
        if (PAGE_BY_PATH.putIfAbsent(key(path), new int[]{pageSize, cipherVersion}) == null) {
            Log.i("cipher", "recorded WCDB2 page parameters: "
                    + path.substring(path.lastIndexOf('/') + 1)
                    + " pageSize=" + pageSize + " cipherVersion=" + cipherVersion);
        }
    }

    /**
     * Returns a spec that can open databases:
     * Prefers the captured object, then one rebuilt from the recorded page size and version.
     * Falls back to WeChat's own defaults: page size 4096 and SQLCipher version1.
     *
     * @return a spec object, or null when WCDB is absent from the classloader
     */
    public static Object specFor(ClassLoader cl, String path, int fallbackPageSize) {
        Object captured = captured(path);
        if (captured != null) return captured;

        int pageSize = fallbackPageSize > 0 ? fallbackPageSize : 4096;
        int version = 1;
        int[] rec = path == null ? null : PAGE_BY_PATH.get(path);
        if (rec != null) {
            if (rec[0] > 0) pageSize = rec[0];
            version = rec[1] <= 0 ? 1 : rec[1];
        }
        return build(cl, pageSize, version);
    }

    /** Builds a SQLCipherSpec reflectively, matching how WeChat configures one. */
    public static Object build(ClassLoader cl, int pageSize, int sqlCipherVersion) {
        Class<?> c = loadSpecClass(cl);
        if (c == null) {
            Log.w("cipher", "SQLiteCipherSpec class not found, cannot build a spec");
            return null;
        }
        try {
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object spec = ctor.newInstance();
            // setSQLCipherVersion sets kdfIteration, hmac and both algorithms in one call, exactly as
            // WeChat does; the page size is set separately.
            callIntSetter(c, spec, "setSQLCipherVersion", sqlCipherVersion <= 0 ? 1 : sqlCipherVersion);
            callIntSetter(c, spec, "setPageSize", pageSize);
            Log.i("cipher", "built spec pageSize=" + pageSize + " cipherVersion=" + sqlCipherVersion
                    + " → " + describe(spec));
            return spec;
        } catch (Throwable t) {
            Log.w("cipher", "failed to build SQLiteCipherSpec", t);
            return null;
        }
    }

    private static Class<?> loadSpecClass(ClassLoader cl) {
        for (String name : new String[]{SPEC_CLS, SPEC_CLS_COMPAT}) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void callIntSetter(Class<?> c, Object spec, String name, int v) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Method m = k.getDeclaredMethod(name, int.class);
                m.setAccessible(true);
                m.invoke(spec, v);
                return;
            } catch (Throwable ignored) {
                k = k.getSuperclass();
            }
        }
        Log.w("cipher", "method not found: " + name + "(int)");
    }

    /**
     * Candidate specs. One database can be opened with different parameters depending on the
     * code path, so all of them are tried; failure only costs an open attempt.
     */
    public static final class SpecOption {
        public final String label;
        public final Object spec;

        SpecOption(String label, Object spec) {
            this.label = label;
            this.spec = spec;
        }
    }

    public static java.util.List<SpecOption> specOptions(ClassLoader cl, String path, int hintPageSize) {
        java.util.List<SpecOption> out = new java.util.ArrayList<>();
        java.util.Set<String> built = new java.util.LinkedHashSet<>();

        // 1. The object WeChat itself passed in is the most trustworthy
        Object cap = captured(path);
        if (cap != null) {
            int ps = pageSizeOf(cap);
            out.add(new SpecOption("captured(" + ps + ")", cap));
            built.add(ps + ":1");
        }

        // 2. Then reconstructed candidates.
        //    Two dimensions vary in practice, so both are swept:
        //      - page size: usually 1024, but 4096 on the legacy path
        //      - cipher version: almost always version1, though newer versions cannot be ruled out;
        //        it decides the KDF rounds and HMAC (v1: 4000/off, v2: 4000/on, v3: 64000/on)
        java.util.List<Integer> pages = new java.util.ArrayList<>();
        if (hintPageSize > 0) pages.add(hintPageSize);
        int[] rec = path == null ? null : PAGE_BY_PATH.get(key(path));
        if (rec != null && rec[0] > 0 && !pages.contains(rec[0])) pages.add(rec[0]);
        for (int ps : new int[]{1024, 4096}) if (!pages.contains(ps)) pages.add(ps);

        int[] versions = {1, 2, 3, 4};
        for (int v : versions) {
            for (int ps : pages) {
                String tag = ps + ":" + v;
                if (built.contains(tag)) continue;
                Object s = build(cl, ps, v);
                if (s != null) {
                    out.add(new SpecOption("built(ps=" + ps + ",v=" + v + ")", s));
                    built.add(tag);
                }
            }
        }
        return out;
    }

    private static int pageSizeOf(Object spec) {
        if (spec == null) return -1;
        Class<?> k = spec.getClass();
        while (k != null && k != Object.class) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField("pageSize");
                f.setAccessible(true);
                Object v = f.get(spec);
                return v instanceof Integer ? (Integer) v : -1;
            } catch (Throwable ignored) {
                k = k.getSuperclass();
            }
        }
        return -1;
    }

    /** Reads every field reflectively for logging. Not used by any logic. */
    public static String describe(Object spec) {
        if (spec == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        Class<?> k = spec.getClass();
        while (k != null && k != Object.class) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    sb.append(f.getName()).append('=').append(f.get(spec)).append(' ');
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        return sb.toString().trim();
    }

    public static int capturedCount() {
        return SPEC_BY_PATH.size();
    }
}
