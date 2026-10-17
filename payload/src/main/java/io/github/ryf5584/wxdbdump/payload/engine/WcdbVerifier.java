package io.github.ryf5584.wxdbdump.payload.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;

/**
 * Re-opens the database with a fresh {@code com.tencent.wcdb.core.Database}, re-applies the
 * key and requires {@code canOpen()} to succeed before the key is accepted.
 *
 * <p>Everything goes through reflection into {@code com.tencent.wcdb.*} inside the target
 * process: Tencent's open-source WCDB, whose names are not obfuscated, which makes it the
 * only anchor that stays stable across WeChat versions.
 *
 * <p>Call chain, identical to how WeChat opens a database itself:
 * <pre>
 *   Database db = new Database(path);
 *   db.setCipherKey(key, pageSize, CipherVersion.versionN);
 *   boolean ok = db.canOpen();
 *   db.close();
 * </pre>
 */
public final class WcdbVerifier {

    public static final String WCDB2_DATABASE = "com.tencent.wcdb.core.Database";
    public static final String WCDB_ENUM = "com.tencent.wcdb.core.Database$CipherVersion";

    /** The CipherVersion WeChat uses: version1, 4000 KDF rounds, HMAC off. */
    public static final int CIPHER_VERSION_V1 = 0;

    private WcdbVerifier() {
    }

    /** Result of one verification attempt. */
    public static final class Verdict {
        public final boolean ok;
        public final int pageSize;
        public final String detail;

        Verdict(boolean ok, int pageSize, String detail) {
            this.ok = ok;
            this.pageSize = pageSize;
            this.detail = detail;
        }
    }

    /**
     * Tries page sizes in the order WeChat does.
     */
    public static Verdict verify(EngineHost host, String path, byte[] key, int pageSize) {
        if (key == null || key.length == 0) {
            Log.w("wcdb", "verify skipped, empty key, path=" + path);
            return new Verdict(false, 0, "empty key");
        }
        // Try the caller's preferred page size first, then the other one
        int first = pageSize == 1024 ? 1024 : 4096;
        Verdict v = tryOnce(host, path, key, first);
        if (v.ok) {
            Log.i("wcdb", "verify result=OK pageSize=" + v.pageSize
                    + " keyLen=" + key.length + " path=" + path);
            return v;
        }
        int second = first == 4096 ? 1024 : 4096;
        Verdict v2 = tryOnce(host, path, key, second);
        if (v2.ok) {
            Log.i("wcdb", "verify result=OK (fallback page size) pageSize=" + v2.pageSize
                    + " keyLen=" + key.length + " path=" + path);
            return v2;
        }
        Log.w("wcdb", "verify result=FAIL keyLen=" + key.length + " path=" + path
                + " · 4096:" + v.detail + " · 1024:" + v2.detail);
        return new Verdict(false, 0, v.detail + " / " + v2.detail);
    }

    private static Verdict tryOnce(EngineHost host, String path, byte[] key, int pageSize) {
        Object db = null;
        try {
            ClassLoader cl = host.loader();
            Class<?> dbCls = Class.forName(WCDB2_DATABASE, false, cl);
            Constructor<?> ctor = dbCls.getConstructor(String.class);
            db = ctor.newInstance(path);

            // Locate setCipherKey(byte[], int, CipherVersion) by parameter count rather than by
            // signature strings, so the enum's own name never has to be guessed.
            Method setCipher = null;
            for (Method m : dbCls.getMethods()) {
                if (!"setCipherKey".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[0] == byte[].class && ps[1] == int.class && ps[2].isEnum()) {
                    setCipher = m;
                    break;
                }
            }
            if (setCipher == null) {
                Log.w("wcdb", "verify pageSize=" + pageSize + " -> no setCipherKey(byte[],int,enum)");
                return new Verdict(false, 0, "setCipherKey(byte[],int,enum) not found");
            }
            Class<?> enumCls = setCipher.getParameterTypes()[2];
            Object[] consts = enumCls.getEnumConstants();
            if (consts == null || consts.length <= CIPHER_VERSION_V1) {
                Log.w("wcdb", "verify pageSize=" + pageSize + " -> not enough CipherVersion constants");
                return new Verdict(false, 0, "not enough CipherVersion constants");
            }
            setCipher.invoke(db, key, pageSize, consts[CIPHER_VERSION_V1]);

            Method canOpen = dbCls.getMethod("canOpen");
            Object r = canOpen.invoke(db);
            boolean ok = r instanceof Boolean && (Boolean) r;
            Log.i("wcdb", "verify try pageSize=" + pageSize + " → canOpen=" + ok);
            return new Verdict(ok, ok ? pageSize : 0, ok ? "canOpen=true" : "canOpen=false");
        } catch (Throwable t) {
            Log.w("wcdb", "verify pageSize=" + pageSize + " -> exception: " + t);
            return new Verdict(false, 0, String.valueOf(t));
        } finally {
            if (db != null) {
                try {
                    db.getClass().getMethod("close").invoke(db);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Reads path and size from a database object through reflection, for display. */
    public static String describe(EngineHost host, String path) {
        try {
            Class<?> dbCls = Class.forName(WCDB2_DATABASE, false, host.loader());
            Object db = dbCls.getConstructor(String.class).newInstance(path);
            try {
                Object p = dbCls.getMethod("getPath").invoke(db);
                return String.valueOf(p);
            } finally {
                try {
                    dbCls.getMethod("close").invoke(db);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d("wcdb", "describe failed " + t);
            return path;
        }
    }
}
