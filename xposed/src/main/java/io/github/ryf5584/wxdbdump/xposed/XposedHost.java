package io.github.ryf5584.wxdbdump.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.Reflect;

/**
 * Xposed implementation of {@link EngineHost}.
 *
 * <p>Runs inside WeChat's process, so it already has WeChat's uid:
 * <ul>
 *   <li>{@link java.io.File} can read {@code /data/user/0/com.tencent.mm/} directly</li>
 *   <li>WCDB and MMKV classes already loaded in the process can be called reflectively</li>
 * </ul>
 * This is why Xposed mode needs no root to read the encrypted databases.
 */
public final class XposedHost implements EngineHost {

    private final Context appContext;
    private final ClassLoader loader;
    private final String processName;
    private final int pid;
    private final Fs fs = new Fs();

    public XposedHost(Context appContext, ClassLoader yukiLoader, String processName) {
        this.appContext = appContext;
        // Load-bearing detail: the class copies WeChat actually uses live in the app's
        // ClassLoader (a Tinker delegate chain); static fields are only set on those copies.
        // Same-named classes in lpparam.classLoader are stale copies with empty static state.
        ClassLoader primary = yukiLoader;
        try {
            ClassLoader appLoader = appContext.getClassLoader();
            if (appLoader != null) primary = appLoader;
        } catch (Throwable ignored) {
        }
        this.loader = primary;
        this.processName = processName;
        this.pid = android.os.Process.myPid();
        Reflect.LoaderChain.register(loader);
        Reflect.LoaderChain.register(yukiLoader);
        Log.i("host", "loader primary=" + loader.getClass().getName() + "@" + System.identityHashCode(loader)
                + " yuki=" + (yukiLoader == null ? "null" : yukiLoader.getClass().getName())
                + " same=" + (loader == yukiLoader));
    }

    @Override
    public int pid() {
        return pid;
    }

    @Override
    public String processName() {
        return processName;
    }

    @Override
    public ClassLoader loader() {
        return loader;
    }

    @Override
    public Object globalContext() {
        try {
            return appContext.getApplicationContext();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String filesDir() {
        try {
            return appContext.getFilesDir().getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * WeChat's own cacheDir, where decrypted SQLite temporary files are written.
     *
     * <p>The path form matters: WeChat's WCDB reports {@code /data/data/<pkg>/...} while
     * {@code getCacheDir()} returns {@code /data/user/0/<pkg>/...}. They are usually
     * equivalent, but native SQLite is sensitive to the form and ATTACH on the
     * {@code /data/user/0/...} form returns CANTOPEN, so this prefers WeChat's form.
     */
    @Override
    public String cacheDir() {
        try {
            String primary = appContext.getCacheDir().getAbsolutePath();
            String dataForm = altForm(primary);
            if (!dataForm.equals(primary)) {
                File f = new File(dataForm);
                if (f.exists() || f.mkdirs()) {
                    Log.i("host", "cacheDir using the /data/data form: " + dataForm);
                    return dataForm;
                }
            }
            File g = new File(primary);
            if (g.exists() || g.mkdirs()) return primary;
            Log.w("host", "neither cacheDir form is usable: " + primary);
            return primary;
        } catch (Throwable t) {
            Log.w("host", "failed to resolve cacheDir", t);
            return null;
        }
    }

    /** {@code /data/user/0/<pkg>/…} ↔ {@code /data/data/<pkg>/…} */
    public static String altForm(String p) {
        if (p == null) return "";
        if (p.startsWith("/data/user/0/")) return "/data/data/" + p.substring("/data/user/0/".length());
        if (p.startsWith("/data/data/")) return "/data/user/0/" + p.substring("/data/data/".length());
        return p;
    }

    @Override
    public io.github.ryf5584.wxdbdump.payload.platform.Hooks hooks() {
        return HOOKS;
    }

    @Override
    public FileSource files() {
        return fs;
    }

    private final XposedHooks HOOKS = new XposedHooks();

    /** Process name, read from Application on Android 9+ and ActivityThread below that. */
    public static String currentProcessName(Context ctx) {
        try {
            Method m = Context.class.getMethod("getProcessName");
            Object v = m.invoke(ctx.getApplicationContext());
            if (v != null && String.valueOf(v).length() > 0) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            Object v = m.invoke(null);
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        return ctx.getPackageName();
    }

    // ---------------- File access: direct reads in Xposed mode ----------------

    static final class Fs implements FileSource {

        @Override
        public boolean exists(String path) {
            try {
                return new File(path).exists();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String[] list(String dir) {
            try {
                String[] r = new File(dir).list();
                if (r != null) return r;
            } catch (Throwable ignored) {
            }
            // Fallback: swap /data/user/0 for /data/data; symlink handling differs across ROMs
            try {
                String alt = dir.replaceFirst("^/data/user/0/", "/data/data/");
                if (!alt.equals(dir)) {
                    String[] r = new File(alt).list();
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {
            }
            return new String[0];
        }

        @Override
        public long size(String path) {
            try {
                long n = new File(path).length();
                return n > 0 ? n : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        @Override
        public byte[] readHead(String path, int n) {
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(path, "r");
                byte[] out = new byte[n];
                int read = raf.read(out);
                if (read <= 0) return null;
                if (read == n) return out;
                byte[] trimmed = new byte[read];
                System.arraycopy(out, 0, trimmed, 0, read);
                return trimmed;
            } catch (Throwable t) {
                return null;
            } finally {
                close(raf);
            }
        }

        @Override
        public byte[] readAll(String path) {
            FileInputStream in = null;
            try {
                File f = new File(path);
                if (!f.exists() || f.length() > 8L * 1024 * 1024) return null;
                in = new FileInputStream(f);
                byte[] out = new byte[(int) f.length()];
                int off = 0;
                while (off < out.length) {
                    int r = in.read(out, off, out.length - off);
                    if (r < 0) break;
                    off += r;
                }
                return out;
            } catch (Throwable t) {
                return null;
            } finally {
                close(in);
            }
        }

        @Override
        public boolean copyTo(String src, String dst) {
            java.io.FileInputStream in = null;
            java.io.FileOutputStream out = null;
            try {
                File parent = new File(dst).getParentFile();
                if (parent != null) parent.mkdirs();
                in = new java.io.FileInputStream(src);
                out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
                return true;
            } catch (Throwable t) {
                Log.e("fs", "copyTo failed " + src + " -> " + dst, t);
                return false;
            } finally {
                close(in);
                close(out);
            }
        }

        @Override
        public String[] walk(String root) {
            List<String> out = new ArrayList<>();
            walkInto(new File(root), out, 0);
            return out.toArray(new String[0]);
        }

        private void walkInto(File dir, List<String> out, int depth) {
            if (depth > 4) return;
            File[] cs = dir.listFiles();
            if (cs == null) return;
            for (File f : cs) {
                if (f.isDirectory()) walkInto(f, out, depth + 1);
                else out.add(f.getAbsolutePath());
            }
        }

        private static void close(java.io.Closeable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
