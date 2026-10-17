package io.github.ryf5584.wxdbdump.payload.crypto;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * Recovers an account's uin. No obfuscated names involved, and the verdict is deterministic.
 *
 * <p>The idea: the account directory name is {@code md5hex("mm" + uin)}, which gives an
 * exact identity check:
 * <pre>
 *   for (candidate : candidateIntegers)
 *       if (md5hex("mm" + candidate).equals(dirName)) -> candidate is the uin
 * </pre>
 * Candidates may therefore contain noise: a match is always the right answer. Nothing here
 * depends on version-specific constants such as the id inside userinfo.
 *
 * <p>Candidate sources, most reliable first:
 * <ol>
 *   <li><b>A</b> {@code default_uin} in {@code system_config_prefs.xml}: the active account.</li>
 *   <li><b>B</b> the account's own MMKV store {@code mmkv_private/ConfigStorage1}, which also
 *       covers historical accounts. It calls the host's {@code com.tencent.mmkv.MMKV}
 *       reflectively (an unobfuscated third-party library) and probes {@code decodeInt}.</li>
 *   <li><b>C</b> neither hits: mark the account as needing activation and ask the user to
 *       switch to it once in WeChat, after which the hook captures the key directly.</li>
 * </ol>
 */
public final class UinResolver {

    /** default_uin inside the preferences XML. */
    private static final Pattern INT_PREF =
            Pattern.compile("<int\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"");
    private UinResolver() {
    }

    /** Recovery result, carrying any identity read from the account MMKV store. */
    public static final class Result {
        public final int uin;
        public final String source;
        /** wxid recorded in the account's own MMKV store. */
        public String wxid = "";
        /** Nickname recorded in the account's own MMKV store, when present. */
        public String nickname = "";

        Result(int uin, String source) {
            this.uin = uin;
            this.source = source;
        }

        public boolean ok() {
            return uin != 0;
        }
    }

    /**
     * Tries to recover the uin for one account directory.
     *
     * @param host       platform host
     * @param accountDir absolute account directory path
     * @param dirName   account directory name, 32 hex characters
     */
    public static Result resolve(EngineHost host, String accountDir, String dirName) {
        // ---- A. default_uin from system_config_prefs.xml ----
        Integer fromPrefs = readDefaultUin(host);
        if (fromPrefs != null && matches(fromPrefs, dirName)) {
            Log.i("uin", "matched default_uin=" + fromPrefs + " -> " + dirName);
            Result r = new Result(fromPrefs, "system_config_prefs");
            // Also pull the identity from the account MMKV store, useful for older accounts
            readIdentityFromMmkv(host, accountDir, r);
            return r;
        }

        // ---- B. the account's own MMKV store ----
        Integer fromMmkv = probeMmkv(host, accountDir, dirName);
        Result r = new Result(fromMmkv == null ? 0 : fromMmkv, fromMmkv == null ? "" : "mmkv_private");
        readIdentityFromMmkv(host, accountDir, r);
        if (fromMmkv != null) {
            Log.i("uin", "matched MMKV=" + fromMmkv + " -> " + dirName);
            return r;
        }

        Log.w("uin", "cannot recover uin for " + dirName
                    + "; its directory name does not follow the md5 convention,"
                    + " so root mode cannot handle it");
        return r;
    }

    /**
     * Reads identity strings from the account's own MMKV store.
     *
     * <p>This is the only source of nickname and wxid for historical accounts, since the global
     * preferences only cover the most recently used one. Missing values stay empty.
     */
    private static void readIdentityFromMmkv(EngineHost host, String accountDir, Result out) {
        try {
            ClassLoader cl = host.loader();
            Class<?> mmkvCls = Class.forName("com.tencent.mmkv.MMKV", false, cl);
            java.lang.reflect.Method factory = mmkvCls.getMethod(
                    "mmkvWithID", String.class, int.class, String.class, String.class);
            Object mmkv = factory.invoke(null, WeChatPaths.MMKV_CONFIG_STORAGE, 2, null,
                    host.mmkvOpenRoot(accountDir));
            if (mmkv == null) return;
            java.lang.reflect.Method decodeString =
                    mmkvCls.getMethod("decodeString", String.class, String.class);
            out.wxid = firstNonEmpty(out.wxid, str(decodeString, mmkv, WeChatPaths.KEY_LOGIN_WXID));
            out.nickname = firstNonEmpty(out.nickname, str(decodeString, mmkv, WeChatPaths.KEY_LAST_NICKNAME));
            if (out.wxid.isEmpty()) {
                out.wxid = firstNonEmpty(out.wxid, str(decodeString, mmkv, "login_user_name"));
            }
            if (!out.wxid.isEmpty() || !out.nickname.isEmpty()) {
                Log.i("uin", "identity from account MMKV: wxid=" + out.wxid + " nick=" + out.nickname);
            }
        } catch (Throwable t) {
            Log.d("uin", "account MMKV identity unavailable (acceptable): " + t);
        }
    }

    private static String str(java.lang.reflect.Method m, Object mmkv, String key) {
        try {
            Object v = m.invoke(mmkv, key, null);
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    /** Identity check between a directory name and a uin. */
    public static boolean matches(int uin, String dirName) {
        return uin != 0 && WeChatPaths.accountDirName(uin).equals(dirName);
    }

    /** Reads default_uin from system_config_prefs.xml by plain text parsing. */
    public static Integer readDefaultUin(EngineHost host) {
        try {
            byte[] raw = host.files().readAll(WeChatPaths.SYSTEM_CONFIG_PREFS);
            if (raw == null || raw.length == 0) return null;
            String xml = new String(raw, StandardCharsets.UTF_8);
            Matcher m = INT_PREF.matcher(xml);
            while (m.find()) {
                if (WeChatPaths.KEY_DEFAULT_UIN.equals(m.group(1))) {
                    int v = Integer.parseInt(m.group(2));
                    return v == 0 ? null : v;
                }
            }
        } catch (Throwable t) {
            Log.e("uin", "failed to read system_config_prefs", t);
        }
        return null;
    }

    /**
     * Calls {@code com.tencent.mmkv.MMKV} reflectively (an unobfuscated Tencent library) to open
     * ConfigStorage1 in the account's {@code mmkv_private} directory and probe integers.
     *
     * <p>Matches still go through the md5 identity check, so noise cannot cause a wrong verdict.
     */
    private static Integer probeMmkv(EngineHost host, String accountDir, String dirName) {
        try {
            ClassLoader cl = host.loader();
            Class<?> mmkvCls = Class.forName("com.tencent.mmkv.MMKV", false, cl);

            // The account MMKV store is unencrypted (null crypt key) and lives inside the account
            // directory, so the account path can be passed straight in for both modes.
            java.lang.reflect.Method factory = mmkvCls.getMethod(
                    "mmkvWithID", String.class, int.class, String.class, String.class);
            Object mmkv = factory.invoke(null, WeChatPaths.MMKV_CONFIG_STORAGE, 2, null,
                    host.mmkvOpenRoot(accountDir));
            if (mmkv == null) return null;

            // MMKV keys are strings. The int-typed overloads this used to call do not exist on
            // com.tencent.mmkv.MMKV at all - the real ones are decodeInt(String, int) and
            // decodeString(String, String) - so every call threw and this whole source silently
            // found nothing, which is exactly the case it was written for: historical accounts
            // that the global preferences do not describe.
            java.lang.reflect.Method decodeInt = mmkvCls.getMethod("decodeInt", String.class, int.class);
            java.lang.reflect.Method decodeString = mmkvCls.getMethod("decodeString", String.class, String.class);
            java.lang.reflect.Method allKeys = mmkvCls.getMethod("allKeys");

            Object keysObj = allKeys.invoke(mmkv);
            String[] keys = keysObj instanceof String[] ? (String[]) keysObj : new String[0];
            Log.i("uin", "account MMKV keys=" + keys.length + " for " + dirName);
            for (String key : keys) {
                if (key == null || key.isEmpty()) continue;

                try {
                    Object v = decodeInt.invoke(mmkv, key, 0);
                    if (v instanceof Number) {
                        int candidate = ((Number) v).intValue();
                        if (candidate != 0 && matches(candidate, dirName)) {
                            Log.i("uin", "matched MMKV int " + key + "=" + candidate);
                            return candidate;
                        }
                    }
                } catch (Throwable ignored) {
                    // Not an int, or not a key this instance can decode
                }

                // Some builds store the uin as text rather than as an integer.
                try {
                    Object v = decodeString.invoke(mmkv, key, null);
                    if (v instanceof String) {
                        String text = ((String) v).trim();
                        if (text.length() >= 5 && text.length() <= 11 && isDigits(text)) {
                            int candidate = Integer.parseInt(text);
                            if (matches(candidate, dirName)) {
                                Log.i("uin", "matched MMKV string " + key + "=" + text);
                                return candidate;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Not a string
                }
            }

            // Last resort: numeric keys that allKeys() did not surface.
            for (int id = 0; id <= 512; id++) {
                Object v;
                try {
                    v = decodeInt.invoke(mmkv, Integer.toString(id), 0);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(v instanceof Number)) continue;
                int candidate = ((Number) v).intValue();
                if (candidate != 0 && matches(candidate, dirName)) return candidate;
            }
        } catch (Throwable t) {
            Log.d("uin", "MMKV probing unavailable (acceptable): " + t);
        }
        return null;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Display helper: renders the uin as a four-part wxid-style string. Not reversible. */
    public static String maskUin(int uin) {
        if (uin == 0) return "--";
        String s = Integer.toString(Math.abs(uin));
        if (s.length() <= 4) return "wxid_" + s;
        return "wxid_" + s.substring(0, 4) + "…" + s.substring(s.length() - 3);
    }
}
