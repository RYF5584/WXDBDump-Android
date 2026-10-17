package io.github.ryf5584.wxdbdump.xposed;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.engine.KeyCapture;
import io.github.ryf5584.wxdbdump.payload.platform.Hooks;

/**
 * The only hook implementation in this project. Everything hangs off WCDB.
 *
 * <h3>Why these anchors survive WeChat updates</h3>
 * {@code com.tencent.wcdb.*} is Tencent's open-source WCDB vendored verbatim, so its class
 * and method names are WCDB's own ABI contract; renaming them would break WCDB itself.
 * No obfuscated WeChat name appears here, so no per-version anchor configuration is needed.
 *
 * <h3>Anchors</h3>
 * <ol>
 *   <li>Every overload of {@code com.tencent.wcdb.core.Database.setCipherKey}: the main
 *       capture point. Matched by name and parameter count so new overloads still hit.</li>
 *   <li>{@code com.tencent.wcdb.database.SQLiteDatabase.openDatabase}, used by legacy
 *       databases including EnMicroMsg.db; the key is in args[1].</li>
 * </ol>
 *
 * <h3>Load timing</h3>
 * WCDB is usually not loaded yet at Application.onCreate, so installation has three layers:
 * try immediately, register a class watcher for missing anchors, then retry on a timer.
 * Each round logs hit and miss counts; otherwise a class that never loads leaves no trace.
 *
 * <h3>Safety rule</h3>
 * Only the key length and its source path are logged, never the key itself.
 */
public final class WcdbKeyHooks {

    private static final String CLS_DATABASE = "com.tencent.wcdb.core.Database";
    private static final String CLS_SQLITE_DATABASE = "com.tencent.wcdb.database.SQLiteDatabase";

    /** Retry count and interval, covering classes that loaded before the watcher existed. */
    private static final int[] RETRY_DELAY_SEC = {1, 3, 8, 20, 45, 90};

    /** How many skip reasons of one kind to log before going quiet. */
    private static final int SKIP_LOG_LIMIT = 5;

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wxdump-hook-retry");
                t.setDaemon(true);
                return t;
            });

    /** Class names already hooked, used as an idempotency key. */
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();

    private final EngineHost host;
    private final KeyCapture capture;
    private final List<AutoCloseable> installed = new ArrayList<>();

    private final AtomicInteger attemptNo = new AtomicInteger();
    private final AtomicBoolean keyBannerLogged = new AtomicBoolean();
    private final AtomicInteger skipNullPath = new AtomicInteger();
    private final AtomicInteger skipEmptyKey = new AtomicInteger();

    public WcdbKeyHooks(EngineHost host, KeyCapture capture) {
        this.host = host;
        this.capture = capture;
    }

    /** Installs hooks and returns a one-line summary for logging. */
    public String install() {
        final Hooks hooks = host.hooks();
        if (hooks == null) {
            Log.e("hook", "platform hook channel unavailable, no hooks installed");
            return "no-hook-channel";
        }
        // 1. Try immediately
        attempt(hooks);
        // 2. Watch for classes that have not loaded yet; Tinker keeps several copies of each
        hooks.watchClassLoad(CLS_DATABASE, c -> hookWcdb2(hooks, c));
        hooks.watchClassLoad(CLS_SQLITE_DATABASE, c -> hookLegacy(hooks, c));
        // 3. Retry on a timer
        for (int i = 0; i < RETRY_DELAY_SEC.length; i++) {
            try {
                SCHED.schedule(() -> attempt(hooks), RETRY_DELAY_SEC[i], TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        }
        return report();
    }

    /** Idempotently installs the anchors and records hits and misses for this round. */
    private void attempt(Hooks hooks) {
        int n = attemptNo.incrementAndGet();
        ClassLoader cl = host.loader();
        try {
            if (!DONE.contains(CLS_DATABASE)) {
                Class<?> c = maybeClass(cl, CLS_DATABASE);
                if (c != null) hookWcdb2(hooks, c);
            }
            if (!DONE.contains(CLS_SQLITE_DATABASE)) {
                Class<?> c = maybeClass(cl, CLS_SQLITE_DATABASE);
                if (c != null) hookLegacy(hooks, c);
            }
        } catch (Throwable t) {
            Log.e("hook", "attempt#" + n + " threw", t);
        }
        Log.i("hook", "attempt#" + n + " " + report());
    }

    /** Snapshot of matched and missing anchors. */
    private String report() {
        StringBuilder miss = new StringBuilder();
        int hitCount = 0;
        for (String name : new String[]{CLS_DATABASE, CLS_SQLITE_DATABASE}) {
            if (DONE.contains(name)) {
                hitCount++;
            } else {
                if (miss.length() > 0) miss.append(", ");
                miss.append(simple(name));
            }
        }
        return "matched " + hitCount + "/2, missing=[" + miss + "]";
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    // ------------------------------------------------------------------ Anchor 1

    private void hookWcdb2(Hooks hooks, Class<?> cls) {
        if (cls == null || !DONE.add(CLS_DATABASE)) return;
        // Hook callback: copy arguments and enqueue; the worker does the real work.
        Hooks.MethodHook cb = new Hooks.MethodHook() {
            @Override
            public void before(Hooks.HookCtx ctx) {
                byte[] key = null;
                int pageSize = 0;
                int cipherVersion = 0;
                for (Object a : ctx.args == null ? new Object[0] : ctx.args) {
                    if (key == null && a instanceof byte[]) key = (byte[]) a;
                    else if (pageSize == 0 && a instanceof Number) pageSize = ((Number) a).intValue();
                    else if (cipherVersion == 0 && a instanceof Enum && a.getClass().isEnum()) {
                        cipherVersion = ((Enum<?>) a).ordinal();
                    }
                }
                String path = pathOf(ctx.thiz);

                // This path only yields the page size and version; keep them for spec reconstruction
                if (path != null && pageSize > 0) {
                    WcdbCipher.recordPage(path, pageSize, cipherVersion);
                }

                // ---- Skip reasons must be recorded, or a missing key is undiagnosable ----
                if (path == null) {
                    int c = skipNullPath.incrementAndGet();
                    if (c <= SKIP_LOG_LIMIT) {
                        Log.w("hook", "skipping capture, getPath() unavailable (thiz="
                                + (ctx.thiz == null ? "null" : ctx.thiz.getClass().getName())
                                + ")" + (c == SKIP_LOG_LIMIT ? " (further skips of this kind suppressed)" : ""));
                    }
                    return;
                }
                if (key == null || key.length == 0) {
                    int c = skipEmptyKey.incrementAndGet();
                    if (c <= SKIP_LOG_LIMIT) {
                        Log.d("hook", "skipping capture, empty key path=" + path
                                + (c == SKIP_LOG_LIMIT ? " (further skips of this kind suppressed)" : ""));
                    }
                    return;
                }

                // Banner only on the first non-empty key, and only its length
                if (keyBannerLogged.compareAndSet(false, true)) {
                    Log.banner("KEY CAPTURED", "path=" + path
                            + "\nkeyLen=" + key.length
                            + "\npageSize=" + pageSize
                            + "\nsource=hook/wcdb2");
                }
                capture.offer(path, key, pageSize, 0, "hook/wcdb2");
            }
        };
        int n = hookAllNamed(hooks, cls, "setCipherKey", cb);
        Log.i("hook", "hooked " + CLS_DATABASE + ".setCipherKey overloads x" + n);
    }

    // ------------------------------------------------------------------ Anchor 2

    private void hookLegacy(Hooks hooks, Class<?> cls) {
        if (cls == null || !DONE.add(CLS_SQLITE_DATABASE)) return;
        Hooks.MethodHook cb = new Hooks.MethodHook() {
            @Override
            public void before(Hooks.HookCtx ctx) {
                String path = null;
                byte[] key = null;
                Object spec = null;
                for (Object a : ctx.args == null ? new Object[0] : ctx.args) {
                    if (path == null && a instanceof String) path = (String) a;
                    else if (key == null && a instanceof byte[]) key = (byte[]) a;
                    else if (a != null && a.getClass().getName().contains("CipherSpec")) spec = a;
                }
                // Key diagnostic: print the SQLiteCipherSpec fields WeChat actually uses. Guessing the
                // compat flag or page size keeps failing, and this is the only way to see the real values.
                if (spec != null && path != null) {
                    logCipherSpecOnce(spec, path);
                    // Keep a reference to the object so WeChat's own parameters can be reused later
                    WcdbCipher.recordSpec(path, spec);
                }
                if (path == null || key == null || key.length == 0) {
                    return;
                }
                capture.offer(path, key, 0, 0, "hook/legacy");
            }
        };
        int n = hookAllNamed(hooks, cls, "openDatabase", cb);
        Log.i("hook", "hooked " + CLS_SQLITE_DATABASE + ".openDatabase overloads x" + n);
    }

    /** Paths that already produced a banner, to avoid repetition. */
    private static final Set<String> SPEC_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * Reads the real SQLiteCipherSpec fields reflectively and prints them.
     * They decide which PRAGMAs decryption needs: page size, KDF rounds, HMAC and algorithms.
     */
    private static void logCipherSpecOnce(Object spec, String path) {
        if (!SPEC_LOGGED.add(path)) return;
        StringBuilder sb = new StringBuilder("path=").append(path).append('\n');
        Class<?> c = spec.getClass();
        sb.append("class=").append(c.getName()).append('\n');
        // Guessing field names misses some, so every declared field is printed at once
        // instead of a hand-picked subset.
        java.util.List<java.lang.reflect.Field> all = new java.util.ArrayList<>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                all.add(f);
            }
            k = k.getSuperclass();
        }
        for (java.lang.reflect.Field f : all) {
            Object v;
            try {
                f.setAccessible(true);
                v = f.get(spec);
            } catch (Throwable t) {
                v = "<read failed " + t.getClass().getSimpleName() + ">";
            }
            sb.append(f.getName()).append('=').append(v).append(" (")
                    .append(f.getType().getSimpleName()).append(")\n");
        }
        Log.banner("CIPHER SPEC (actual WeChat parameters)", sb.toString());
    }

    private static Object readField(Class<?> c, Object obj, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                java.lang.reflect.Field fl = k.getDeclaredField(name);
                fl.setAccessible(true);
                return fl.get(obj);
            } catch (Throwable ignored) {
                k = k.getSuperclass();
            }
        }
        return "<no such field>";
    }

    // ------------------------------------------------------------------ Helpers

    /** Hooks every overload of a method name and returns how many succeeded. */
    private int hookAllNamed(Hooks hooks, Class<?> cls, String name, Hooks.MethodHook cb) {
        int ok = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            try {
                installed.add(hooks.hookMethod(cls, name, m.getParameterTypes(), cb));
                ok++;
            } catch (Throwable t) {
                Log.d("hook", "failed to hook " + cls.getSimpleName() + "." + name + sig(m), t);
            }
        }
        if (ok == 0) {
            Log.w("hook", "no method named " + name + " in " + cls.getName() + " (WCDB changed?)");
        }
        return ok;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> c : m.getParameterTypes()) sb.append(c.getSimpleName()).append(',');
        return sb.append(')').toString();
    }

    /** Reads {@code Database.getPath()} reflectively. */
    private static String pathOf(Object db) {
        if (db == null) return null;
        try {
            Class<?> c = db.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod("getPath");
                    m.setAccessible(true);
                    Object v = m.invoke(db);
                    return v == null ? null : String.valueOf(v);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            Log.d("hook", "pathOf reflection failed: " + t);
        }
        return null;
    }

    private static Class<?> maybeClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    public void uninstall() {
        for (AutoCloseable c : installed) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
        installed.clear();
        DONE.clear();
        Log.i("hook", "all hooks removed");
    }
}
