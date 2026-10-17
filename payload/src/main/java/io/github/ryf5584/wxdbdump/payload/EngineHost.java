package io.github.ryf5584.wxdbdump.payload;

import java.lang.reflect.Method;

/**
 * Platform injection point. {@code payload} is pure Java with no Android dependency, so
 * every platform capability arrives through this interface.
 */
public interface EngineHost {

    /** Current process id. */
    int pid();

    /** Current process name, e.g. com.tencent.mm. */
    String processName();

    /** ClassLoader of the host application. */
    ClassLoader loader();

    /** Application Context held as Object, since payload cannot import android. Null until ready. */
    Object globalContext();

    /** Absolute path of the current process's private files directory. */
    String filesDir();

    /**
     * Absolute path of a cache directory usable for temporary files.
     *
     * <p>Inside the module this is WeChat's own cacheDir, because the module runs inside
     * WeChat's process. It holds decrypted plaintext, which the system may reclaim under pressure.
     */
    default String cacheDir() {
        return filesDir() + "/cache";
    }

    /** Platform hook channel. Null when unsupported, e.g. in root mode. */
    io.github.ryf5584.wxdbdump.payload.platform.Hooks hooks();

    /** File access abstraction: direct in Xposed mode, staged through su in root mode. */
    FileSource files();

    /**
     * The account-level MMKV directory the engine should actually open.
     *
     * <p>Inside WeChat this is the real path; in root mode the files are staged into the
     * app's private directory first, and the staged path is returned instead.
     */
    default String mmkvOpenRoot(String accountDir) {
        return accountDir + "mmkv_private";
    }

    /** File access abstraction: the only difference between the two modes. */
    interface FileSource {
        /** Whether the path exists. */
        boolean exists(String path);

        /** Directory listing by relative name; empty array when absent. */
        String[] list(String dir);

        /** File length, or -1 on failure. */
        long size(String path);

        /** First {@code n} bytes, used for the SQLite header check; null on failure. */
        byte[] readHead(String path, int n);

        /** Reads a whole file. Use copyTo for large files. */
        byte[] readAll(String path);

        /** Copies src into an app-visible dst. Returns whether it succeeded. */
        boolean copyTo(String src, String dst);

        /** Recursively lists absolute file paths under a directory. */
        String[] walk(String root);

        /**
         * Optional: enumerate every database in the tree with a single call.
         *
         * <p>Root mode must implement this: each directory listing there costs a {@code su} process,
         * so walking a few thousand directories takes minutes and looks like a hang.
         * One command that finds *.db, reads sizes and headers and checks for sidecar files
         * brings the whole scan down to a single process.
         *
         * <p>Returns null when unsupported, and the caller falls back to per-directory recursion.
         */
        default java.util.List<io.github.ryf5584.wxdbdump.payload.discovery.DbScan> scanDatabases(
                String root, int maxDepth) {
            return null;
        }
    }

    /** Optional reflective call helper for classes inside the host process. */
    default Object callStatic(String className, String methodName, Class<?>[] sig, Object... args) {
        try {
            Class<?> c = Class.forName(className, false, loader());
            Method m = c.getDeclaredMethod(methodName, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            Log.d("host", "callStatic failed " + className + "." + methodName + " :: " + t);
            return null;
        }
    }
}
