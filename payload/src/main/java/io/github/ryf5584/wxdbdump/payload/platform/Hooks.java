package io.github.ryf5584.wxdbdump.payload.platform;

/**
 * Platform hook abstraction. payload is pure Java and does no bytecode instrumentation
 * itself; the platform layer adapts this interface to XC_MethodHook.
 *
 * <p>Semantics match Xposed: replacing the result before the call skips the original,
 * setting an argument runs it with new values, and replacing the result afterwards
 * overrides the return value or swallows an exception.
 *
 * <p>Callbacks must never block: observe, enqueue and return.
 */
public interface Hooks {

    /** Hooks one method of a loaded class; a null paramTypes takes the first overload. */
    AutoCloseable hookMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes, MethodHook cb);

    /** Hooks every constructor. */
    AutoCloseable hookAllConstructors(Class<?> clazz, MethodHook cb);

    /**
     * Observes a class being loaded in any classloader copy, including ones already loaded.
     * WeChat's Tinker keeps multiple copies, so hooking one would miss the real code path.
     * The callback fires once per distinct Class object.
     */
    default AutoCloseable watchClassLoad(String className, java.util.function.Consumer<Class<?>> onLoaded) {
        return null;
    }

    /** Posts to the host main thread; false when unsupported or failed. */
    boolean postToMainThread(Runnable r);

    /** Whether this runs on the host main thread. */
    boolean onMainThread();

    /** Platform description for logs and status. */
    String describe();

    /** Hook callback context. */
    final class HookCtx {
        public Object thiz;
        public Object[] args;
        public Object result;
        public Throwable error;
        boolean replace;

        public void replaceResult(Object v) {
            this.replace = true;
            this.result = v;
        }

        public boolean isReplace() {
            return replace;
        }

        public Object replacement() {
            return result;
        }

        public void setArg(int i, Object v) {
            if (args != null && i >= 0 && i < args.length) args[i] = v;
        }

        public int intArg(int i) {
            Object v = args[i];
            return v instanceof Number ? ((Number) v).intValue() : 0;
        }

        public String strArg(int i) {
            return args[i] == null ? null : String.valueOf(args[i]);
        }

        public byte[] bytesArg(int i) {
            return args[i] instanceof byte[] ? (byte[]) args[i] : null;
        }
    }

    interface MethodHook {
        default void before(HookCtx ctx) throws Throwable {
        }

        default void after(HookCtx ctx) throws Throwable {
        }
    }
}
