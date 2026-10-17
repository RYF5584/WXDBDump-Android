package io.github.ryf5584.wxdbdump.xposed;

import java.io.File;
import io.github.ryf5584.wxdbdump.payload.crypto.SqlCipherPages;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import io.github.ryf5584.wxdbdump.payload.Log;

/**
 * Exports an encrypted database as plaintext using WeChat's own SQLCipher.
 *
 * Flow: copy the database with its sidecars, open the copy through WeChat's
 * SQLiteDatabase, ATTACH an empty-key database, run sqlcipher_export, check the header.
 *
 * Copying first avoids WeChat's locks and lets the copy replay the WAL for fresh data.
 */
public final class PlainDumper {

    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final long size;

        Result(boolean ok, String reason, long size) {
            this.ok = ok;
            this.reason = reason;
            this.size = size;
        }

        public static Result fail(String r) {
            return new Result(false, r, 0);
        }

        public static Result good(long size) {
            return new Result(true, null, size);
        }
    }

    private static final String[] DB_CLASSES = {
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase",
    };

    /** Reflective lookup cache, keyed by classloader. */
    private static final Map<ClassLoader, Ctx> CACHE = new ConcurrentHashMap<>();

    private PlainDumper() {
    }

    private static final class Ctx {
        Class<?> dbClass;
        Method openDatabase;
        Method execSQL;
        Method rawQuery;
        Method close;
        Method cursorMoveToFirst;
        Method cursorClose;
        /**
         * Non-destructive DatabaseErrorHandler.
         *
         * <p>Passing null to {@code openDatabase} installs WeChat's default handler, which
         * <b>deletes the database file</b> when it suspects corruption. Since decryption works on
         * a staged copy, a single failure wiped it, and every later attempt then reported a missing
         * source file while hiding the real error. A dynamic proxy supplies a no-op handler.
         * 
         */
        Object noopErrorHandler;
    }

    /**
     * Decrypts {@code src} into a plaintext SQLite file at {@code dst}.
     *
     * @param spec WeChat's own SQLiteCipherSpec, as returned by {@link WcdbCipher#specFor}
     */
    /**
     * Decrypts {@code src} into a plaintext SQLite file at {@code dst}.
     *
     * <h3>Why the database is copied before decryption</h3>
     * Opening a second connection to a database WeChat is using runs into two problems:
     * <ol>
     *   <li>Lock conflicts: some databases return CANTOPEN in both read-only and read-write
     *       mode because WeChat holds a write transaction.</li>
     *   <li>WAL inconsistency: recent data may still sit in the {@code -wal} file, so reading
     *       the main file alone loses the newest messages.</li>
     * </ol>
     * The trio of .db, .db-wal and .db-shm is therefore copied with plain file IO into the
     * module's private staging directory, which SQLite locks do not reach.
     * SQLite replays the WAL when it opens the copy, giving fresh data without touching locks.
     */
    public static Result export(
            java.lang.ClassLoader cl, String src, byte[] key,
            java.util.List<WcdbCipher.SpecOption> specs, String dst) {
        if (key == null || key.length == 0) return Result.fail("no key");
        if (specs == null || specs.isEmpty()) return Result.fail("no SQLiteCipherSpec available");

        String realSrc = firstExisting(src, altForm(src));
        if (realSrc == null) return Result.fail("source database not found in either path form");

        File dstFile = new File(dst);
        File parent = dstFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // A few databases are rebuilt continuously by WeChat and have no -wal file, so the main
        // file is rewritten in place and a copy can be a torn snapshot that fails to open.
        // Such failures are transient, so a few retries usually catch a consistent state.
        final int MAX_ROUND = 3;
        String lastReason = null;
        for (int round = 1; round <= MAX_ROUND; round++) {
            StageResult sr = stageAndSweep(cl, realSrc, key, specs, dst, parent, round);
            if (sr.result.ok) return sr.result;
            lastReason = sr.result.reason;
            // After the first sweep, fall back to decrypting the pages ourselves.
            // WCDB refuses some databases that are still perfectly decryptable (observed on
            // FTS5IndexMicroMsg_encrypt.db and MicroMsgPriority.db); the page walk only needs
            // the key, not a consistent snapshot, so it also survives a torn copy of a
            // database WeChat is actively rewriting. Running it after round 1 keeps the
            // common case on WCDB (which replays the -wal) without paying for extra retries.
            if (round == 1) {
                Result byPages = dumpByPageWalk(realSrc, dst, key);
                if (byPages.ok) return byPages;
            }

            if (!isTransient(sr.result.reason) || round == MAX_ROUND) break;
            Log.w("plain", "round " + round + " failed, likely a torn snapshot, retrying: " + shorten(sr.result.reason));
            try {
                Thread.sleep(250L * round);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Result.fail(lastReason);
    }

    /** Decrypt the database page by page without going through WCDB. */
    private static Result dumpByPageWalk(String realSrc, String dst, byte[] key) {
        Log.i("pages", "fallback entered: " + baseName(realSrc));
        try {
            if (!SqlCipherPages.decrypt(new File(realSrc), new File(dst), key)) {
                return Result.fail("page walk failed");
            }
            long size = new File(dst).length();
            Log.i("plain", "page-walk fallback ok: " + baseName(realSrc) + " " + size + " bytes");
            return Result.good(size);
        } catch (Throwable t) {
            Log.w("plain", "page-walk fallback failed: " + baseName(realSrc), t);
            return Result.fail("page walk: " + t);
        }
    }

    /** These errors mean an inconsistent snapshot and are worth retrying; others are not. */
    private static boolean isTransient(String reason) {
        if (reason == null) return false;
        return reason.contains("not a database")
                || reason.contains("Corrupt")
                || reason.contains("CANTOPEN")
                || reason.contains("Could not open database");
    }

    private static final class StageResult {
        final Result result;

        StageResult(Result r) {
            this.result = r;
        }
    }

    private static StageResult stageAndSweep(
            java.lang.ClassLoader cl, String realSrc, byte[] key,
            java.util.List<WcdbCipher.SpecOption> specs, String dst, File parent, int round) {

        // ---- 1. Stage a copy including wal and shm ----
        File stage = new File(parent, ".stage-" + baseName(realSrc));
        File stageWal = new File(stage.getAbsolutePath() + "-wal");
        File stageShm = new File(stage.getAbsolutePath() + "-shm");
        deleteQuietly(stage);
        deleteQuietly(stageWal);
        deleteQuietly(stageShm);
        long t0 = System.currentTimeMillis();
        if (!copyFile(new File(realSrc), stage)) {
            return new StageResult(Result.fail("failed to copy the source database: " + realSrc));
        }
        boolean hasWal = copyFile(new File(realSrc + "-wal"), stageWal);
        boolean hasShm = copyFile(new File(realSrc + "-shm"), stageShm);
        Log.i("plain", "round " + round + " staged " + baseName(realSrc) + " " + stage.length() + " bytes"
                + " (wal=" + hasWal + " shm=" + hasShm + ", " + (System.currentTimeMillis() - t0) + "ms)");

        try {
            // ---- 2. Sweep spec combinations against the copy ----
            final int FLAG_RW = 0;   // OPEN_READWRITE
            final int FLAG_RO = 1;   // OPEN_READONLY
            final int[] FLAGS = {FLAG_RW, FLAG_RO};
            String stagePath = stage.getAbsolutePath();
            StringBuilder tried = new StringBuilder();

            for (WcdbCipher.SpecOption opt : specs) {
                for (int flag : FLAGS) {
                    Result r = attempt(cl, stagePath, key, opt.spec, dst, flag);
                    if (r.ok) {
                        Log.i("plain", "decryption combination hit: spec=" + opt.label + " flags=" + flag + " source=staged copy");
                        return new StageResult(r);
                    }
                    if (tried.length() > 0) tried.append(" ；");
                    tried.append(opt.label).append("/f").append(flag)
                            .append("→").append(shorten(r.reason));
                }
            }

            // When every combination fails on the copy, try the original database directly
            for (WcdbCipher.SpecOption opt : specs) {
                for (int flag : FLAGS) {
                    Result r = attempt(cl, realSrc, key, opt.spec, dst, flag);
                    if (r.ok) {
                        Log.i("plain", "decryption combination hit: spec=" + opt.label + " flags=" + flag + " source=original");
                        return new StageResult(r);
                    }
                    tried.append(" ；").append(opt.label).append("/f").append(flag)
                            .append("/direct→").append(shorten(r.reason));
                }
            }
            // Still failing: retry without the -wal and -shm files.
            // WeChat holds a write connection, so a copied WAL can be inconsistent with the main file
            // and SQLite reports "file is not a database" while replaying it, even though decrypting
            // the main file page by page works fine.
            // Dropping the WAL loses only the most recent unflushed data.
            if (hasWal || hasShm) {
                deleteQuietly(stageWal);
                deleteQuietly(stageShm);
                Log.w("plain", "all attempts with wal/shm failed, retrying without them: " + baseName(realSrc));
                for (WcdbCipher.SpecOption opt : specs) {
                    for (int flag : FLAGS) {
                        Result r = attempt(cl, stagePath, key, opt.spec, dst, flag);
                        if (r.ok) {
                            Log.i("plain", "decryption combination hit: spec=" + opt.label + " flags=" + flag
                                    + " source=staged copy (no wal)");
                            return new StageResult(r);
                        }
                    }
                }
            }
            return new StageResult(Result.fail("every combination failed: " + tried));
        } finally {
            // The copy holds chat data, so delete it immediately
            deleteQuietly(stage);
            deleteQuietly(stageWal);
            deleteQuietly(stageShm);
        }
    }

    private static String firstExisting(String... paths) {
        for (String p : paths) {
            if (p != null && new File(p).exists()) return p;
        }
        return null;
    }

    /** Plain file copy, bypassing SQLite and its locks. */
    private static boolean copyFile(File from, File to) {
        if (from == null || !from.exists()) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w("plain", "copy failed " + from + " -> " + to, t);
            return false;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        int i = s.indexOf("Could not open database");
        return i >= 0 ? "CANTOPEN" : (s.length() > 60 ? s.substring(0, 60) : s);
    }

    /** Converts between the /data/user/0 and /data/data path forms. */
    public static String altForm(String p) {
        if (p == null) return null;
        if (p.startsWith("/data/user/0/")) return "/data/data/" + p.substring("/data/user/0/".length());
        if (p.startsWith("/data/data/")) return "/data/user/0/" + p.substring("/data/data/".length());
        return p;
    }

    private static Result attempt(
            java.lang.ClassLoader cl, String src, byte[] key, Object spec, String dst, int openFlags) {
        File srcFile = new File(src);
        if (!srcFile.exists()) return Result.fail("source database missing: " + src);

        File dstFile = new File(dst);
        deleteQuietly(dstFile);
        deleteQuietly(new File(dst + "-journal"));
        deleteQuietly(new File(dst + "-wal"));
        deleteQuietly(new File(dst + "-shm"));
        File parent = dstFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (parent != null) {
            Log.i("plain", "staging directory " + parent.getAbsolutePath()
                    + " exists=" + parent.exists() + " canWrite=" + parent.canWrite());
        }
        // Create the target first: on some devices ATTACH returns CANTOPEN for a file that does
        // not exist yet, while a zero-byte file counts as an empty database.
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dstFile)) {
            fos.flush();
        } catch (Throwable t) {
            Log.w("plain", "failed to pre-create the target file: " + dst, t);
        }

        Ctx ctx = ctx(cl);
        if (ctx == null) return Result.fail("no usable WCDB SQLiteDatabase class found");

        Object db = null;
        long t0 = System.currentTimeMillis();
        try {
            // Parameter count varies across WCDB versions, so arguments are built from the real signature
            Class<?>[] p = ctx.openDatabase.getParameterTypes();
            Object[] argv;
            if (p.length == 7) {
                argv = new Object[]{src, key, spec, null, openFlags, ctx.noopErrorHandler, 0};
            } else if (p.length == 6) {
                argv = new Object[]{src, key, spec, null, openFlags, ctx.noopErrorHandler};
            } else if (p.length == 5) {
                argv = new Object[]{src, key, spec, null, openFlags};
            } else {
                return Result.fail("unexpected openDatabase parameter count: " + p.length);
            }
            db = ctx.openDatabase.invoke(null, argv);
            if (db == null) return Result.fail("openDatabase returned null");
            Log.i("plain", "opened with WeChat's own SQLCipher: " + baseName(src)
                    + "（flags=" + openFlags + ", " + (System.currentTimeMillis() - t0) + "ms）");

            String attach = "ATTACH DATABASE '" + dst.replace("'", "''") + "' AS plaintext KEY ''";
            attachOrFail(ctx, db, attach);
            Log.d("plain", "ATTACH of the plaintext database succeeded");

            // sqlcipher_export is SQLCipher's built-in one-shot export function
            Object cursor = ctx.rawQuery.invoke(db, "SELECT sqlcipher_export('plaintext')", null);
            if (cursor != null) {
                try {
                    ctx.cursorMoveToFirst.invoke(cursor);
                } finally {
                    runQuietly(ctx.cursorClose, cursor);
                }
            }

            ctx.execSQL.invoke(db, "DETACH DATABASE plaintext");
            Log.d("plain", "sqlcipher_export + DETACH OK");

            long size = dstFile.exists() ? dstFile.length() : 0;
            if (!isPlaintextSqlite(dstFile)) {
                return Result.fail("output is not a valid SQLite database (head=" + headHex(dstFile) + ")");
            }
            Log.i("plain", "decrypted " + baseName(src) + " -> " + size + " bytes in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return Result.good(size);
        } catch (Throwable t) {
            Throwable c = unwrap(t);
            String msg = c.getClass().getSimpleName() + ": " + c.getMessage();
            Log.w("plain", "decryption failed " + baseName(src) + " : " + msg);
            deleteQuietly(dstFile);
            return Result.fail(msg);
        } finally {
            if (db != null) {
                runQuietly(ctx.close, db);
            }
        }
    }

    // ------------------------------------------------------------------ Reflection setup

    /**
     * Runs ATTACH through {@code execSQL} first, then {@code rawQuery}.
     *
     * <p>{@code execSQL} is documented as unsuitable for statements that return data, and
     * versions disagree on how ATTACH is classified, so both are attempted.
     */
    private static void attachOrFail(Ctx ctx, Object db, String sql) throws Throwable {
        Throwable first = null;
        try {
            ctx.execSQL.invoke(db, sql);
            return;
        } catch (Throwable t) {
            first = unwrap(t);
            Log.d("plain", "execSQL(ATTACH) failed, retrying with rawQuery: " + first);
        }
        try {
            Object c = ctx.rawQuery.invoke(db, sql, null);
            if (c != null) runQuietly(ctx.cursorClose, c);
        } catch (Throwable t2) {
            Throwable second = unwrap(t2);
            throw new IllegalStateException(
                    "both ATTACH forms failed: execSQL=" + first + " ; rawQuery=" + second, second);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
    }

    private static Ctx ctx(ClassLoader cl) {
        Ctx cached = CACHE.get(cl);
        if (cached != null) return cached;
        Ctx c = new Ctx();
        try {
            for (String name : DB_CLASSES) {
                try {
                    c.dbClass = Class.forName(name, false, cl);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (c.dbClass == null) return null;

            c.openDatabase = findOpenDatabase(c.dbClass);
            c.execSQL = findMethod(c.dbClass, "execSQL", String.class);
            c.rawQuery = findMethod(c.dbClass, "rawQuery", String.class, Object[].class);
            c.close = findMethod(c.dbClass, "close");
            if (c.openDatabase == null || c.execSQL == null || c.rawQuery == null || c.close == null) {
                Log.w("plain", "incomplete WCDB API: openDatabase=" + c.openDatabase
                        + " execSQL=" + c.execSQL + " rawQuery=" + c.rawQuery + " close=" + c.close);
                return null;
            }
            Class<?> cursorCls = c.rawQuery.getReturnType();
            c.cursorMoveToFirst = findMethod(cursorCls, "moveToFirst");
            c.cursorClose = findMethod(cursorCls, "close");

            // The error handler is built from the real parameter type; see makeNoopProxy
            Class<?>[] op = c.openDatabase.getParameterTypes();
            if (op.length >= 6) {
                c.noopErrorHandler = makeNoopProxy(cl, op[5]);
            }
            Log.i("plain", "PlainDumper ready: db=" + c.dbClass.getName()
                    + " openDatabase=" + sig(c.openDatabase)
                    + " errorHandler=" + (c.noopErrorHandler != null
                    ? "replaced with a non-destructive handler (" + (op.length >= 6 ? op[5].getSimpleName() : "?") + ")"
                    : "unavailable"));
            CACHE.put(cl, c);
            return c;
        } catch (Throwable t) {
            Log.w("plain", "failed to set up PlainDumper", t);
            return null;
        }
    }

    /**
     * Matches password-taking openDatabase overloads by parameter shape, never by a fixed
     * signature. {@code (String, byte[], *, *, int, *, int)} wins over the shorter form.
     */
    private static Method findOpenDatabase(Class<?> cls) {
        Method best = null;
        int bestScore = -1;
        for (Method m : cls.getMethods()) {
            if (!"openDatabase".equals(m.getName()) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 5 || p[0] != String.class || p[1] != byte[].class) continue;
            // Prefer more parameters and the presence of a CipherSpec
            int score = p.length;
            if (p.length >= 3 && p[2].getSimpleName().contains("CipherSpec")) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /**
     * Builds a no-op handler from the real type of {@code openDatabase}'s sixth parameter.
     *
     * <p>Guessing the class name fails: WCDB ships two DatabaseErrorHandler types and the
     * wrong one makes {@code Method.invoke} throw {@code IllegalArgumentException}. Reading
     * the type straight from the method signature is reliable.
     */
    private static Object makeNoopProxy(ClassLoader cl, Class<?> iface) {
        if (iface == null || !iface.isInterface()) return null;
        try {
            return java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        Log.d("plain", "DatabaseErrorHandler." + method.getName()
                                + " was invoked (ignored, never deletes files)");
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        return null;
                    });
        } catch (Throwable t) {
            Log.w("plain", "failed to build a non-destructive DatabaseErrorHandler: " + t);
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> k = cls;
        while (k != null && k != Object.class) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                k = k.getSuperclass();
            }
        }
        return null;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> c : m.getParameterTypes()) sb.append(c.getSimpleName()).append(',');
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------------ Helpers

    public static boolean isPlaintextSqlite(File f) {
        if (f == null || !f.exists() || f.length() < 16) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[16];
            if (raf.read(b) != 16) return false;
            return "SQLite format 3\u0000".equals(new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (Throwable t) {
            return false;
        }
    }

    public static String headHex(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[16];
            int n = raf.read(b);
            if (n <= 0) return "<empty>";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "<read failed " + t + ">";
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void runQuietly(Method m, Object target) {
        if (m == null || target == null) return;
        try {
            m.invoke(target);
        } catch (Throwable ignored) {
        }
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
