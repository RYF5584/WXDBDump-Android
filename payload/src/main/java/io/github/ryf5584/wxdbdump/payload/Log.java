package io.github.ryf5584.wxdbdump.payload;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified logging. A fixed tag lets one adb logcat capture both processes.
 * Message format: [process][module] text.
 */
public final class Log {

    /** Fixed logcat tag shared by both processes. */
    public static final String TAG = "WxDbExport";

    private static final int RING = 2000;
    private static final Queue<String> RING_BUF = new ConcurrentLinkedQueue<>();
    private static final List<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Process label, e.g. app or com.tencent.mm. */
    private static volatile String proc = "?";

    private static volatile Method logV, logD, logI, logW, logE;
    private static volatile boolean logReady = false;

    private Log() {
    }

    public interface Sink {
        void line(String level, String tag, String msg);
    }

    /**
     * Attaches logcat output. Both processes must call this.
     * Only works on Android; a plain JVM keeps System.out and the ring buffer.
     */
    public static void tryLogcat() {
        if (logReady) return;
        try {
            Class<?> c = Class.forName("android.util.Log");
            logV = grab(c, "v");
            logD = grab(c, "d");
            logI = grab(c, "i");
            logW = grab(c, "w");
            logE = grab(c, "e");
            logReady = logI != null;
        } catch (Throwable ignored) {
            logReady = false;
        }
    }

    private static Method grab(Class<?> c, String name) {
        try {
            return c.getMethod(name, String.class, String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sets the process label used as a message prefix. */
    public static void init(String processLabel) {
        tryLogcat();
        if (processLabel != null && !processLabel.isEmpty()) proc = processLabel;
    }

    /** Whether the logcat channel is ready. Diagnostics only. */
    public static boolean logcatReady() {
        return logReady;
    }

    public static String procLabel() {
        return proc;
    }

    public static void attach(Sink s) {
        if (s != null) SINKS.add(s);
    }

    public static void detach(Sink s) {
        SINKS.remove(s);
    }

    // ---------------------------------------------------------------- Levels

    public static void v(String tag, String msg) { emit("V", tag, msg); }
    public static void d(String tag, String msg) { emit("D", tag, msg); }
    public static void i(String tag, String msg) { emit("I", tag, msg); }
    public static void w(String tag, String msg) { emit("W", tag, msg); }
    public static void e(String tag, String msg) { emit("E", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        emit("E", tag, msg + " :: " + t + " @ " + firstStack(t));
    }

    public static void d(String tag, String msg, Throwable t) {
        emit("D", tag, msg + " :: " + t + " @ " + firstStack(t));
    }

    public static void i(String tag, String msg, Throwable t) {
        emit("I", tag, msg + " :: " + t + " @ " + firstStack(t));
    }

    public static void w(String tag, String msg, Throwable t) {
        emit("W", tag, msg + " :: " + t + " @ " + firstStack(t));
    }

    /**
     * Multi-line banner with separators, so a phase is easy to spot in a long log.
     * Example: banner("ENGINE START", "proc=com.tencent.mm pid=1234")
     */
    public static void banner(String title, String detail) {
        String line = "==================== " + title + " ====================";
        emit("I", "banner", line);
        if (detail != null && !detail.isEmpty()) {
            for (String l : detail.split("\n")) {
                emit("I", "banner", "  | " + l);
            }
        }
    }

    /** Stack trace of the first {@code n} frames. */
    public static String stack(Throwable t, int frames) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t).append('\n');
        StackTraceElement[] st = t.getStackTrace();
        if (st != null) {
            int n = Math.min(frames, st.length);
            for (int i = 0; i < n; i++) sb.append("    at ").append(st[i]).append('\n');
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("  caused by: ").append(cause).append('\n');
            StackTraceElement[] cs = cause.getStackTrace();
            if (cs != null) {
                int n = Math.min(frames, cs.length);
                for (int i = 0; i < n; i++) sb.append("    at ").append(cs[i]).append('\n');
            }
        }
        return sb.toString();
    }

    private static String firstStack(Throwable t) {
        if (t == null) return "";
        StackTraceElement[] st = t.getStackTrace();
        return st != null && st.length > 0 ? st[0].toString() : "";
    }

    // ---------------------------------------------------------------- Internals

    private static void emit(String level, String tag, String msg) {
        String line;
        try {
            line = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        } catch (Throwable t) {
            line = "? " + level + "/" + tag + ": " + msg;
        }

        try {
            RING_BUF.add(line);
            while (RING_BUF.size() > RING) RING_BUF.poll();
        } catch (Throwable ignored) {
        }

        try {
            toLogcat(level, tag, msg);
        } catch (Throwable ignored) {
        }

        try {
            System.out.println(TAG + " [" + proc + "][" + tag + "] " + level + ": " + msg);
        } catch (Throwable ignored) {
        }

        for (Sink s : SINKS) {
            try {
                s.line(level, tag, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void toLogcat(String level, String tag, String msg) {
        if (!logReady) return;
        try {
            Method m;
            switch (level) {
                case "V": m = logV; break;
                case "W": m = logW; break;
                case "E": m = logE; break;
                case "D": m = logD; break;
                default: m = logI; break;
            }
            if (m == null) m = logI;
            if (m == null) return;
            // The tag stays fixed; process identity travels inside the message body.
            m.invoke(null, TAG, "[" + proc + "][" + tag + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Last {@code n} lines, for in-app viewing or export. */
    public static List<String> tail(int n) {
        String[] arr = RING_BUF.toArray(new String[0]);
        int from = Math.max(0, arr.length - Math.max(1, n));
        List<String> out = new ArrayList<>(arr.length - from);
        for (int i = from; i < arr.length; i++) out.add(arr[i]);
        return out;
    }

    /** Current buffered line count. */
    public static int ringSize() {
        return RING_BUF.size();
    }
}
