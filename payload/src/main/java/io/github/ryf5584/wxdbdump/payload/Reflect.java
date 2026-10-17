package io.github.ryf5584.wxdbdump.payload;

import java.util.ArrayList;
import java.util.List;

/**
 * Lookup and invocation helpers built on plain {@code java.lang.reflect}.
 *
 * <p>Deliberately minimal: payload only needs to resolve a host class through the
 * classloader chain and call it. How to hook is decided by the platform layer.
 */
public final class Reflect {

    private Reflect() {
    }

    /** Resolves a class, trying the extra registered loaders in turn. */
    public static Class<?> findClass(ClassLoader loader, String name) {
        Throwable first = null;
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            first = t;
        }
        for (ClassLoader alt : LoaderChain.extra()) {
            if (alt == null || alt == loader) continue;
            try {
                return Class.forName(name, false, alt);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        throw new NotFound(name + " :: " + first);
    }

    /** Same, but returns null instead of throwing. */
    public static Class<?> maybeClass(ClassLoader loader, String name) {
        try {
            return findClass(loader, name);
        } catch (NotFound n) {
            return null;
        }
    }

    /** Finds a method, walking the superclass chain. Null when absent. */
    public static java.lang.reflect.Method maybeMethod(Class<?> cls, String name, Class<?>... sig) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Method m = (sig == null || sig.length == 0)
                        ? firstByName(c, name)
                        : c.getDeclaredMethod(name, sig);
                if (m != null) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
                // keep walking up
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static java.lang.reflect.Method firstByName(Class<?> c, String name) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /** Invokes and returns null on failure; a hook must never break the host. */
    public static Object tryCall(Object receiver, java.lang.reflect.Method m, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(receiver, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String m) {
            super(m);
        }
    }

    /**
     * Extra classloader candidates.
     *
     * <p>WeChat's Tinker swaps in a custom runtime classloader, so one class can exist as
     * several copies. The platform layer registers the real loaders at startup.
     */
    public static final class LoaderChain {
        private static final List<ClassLoader> EXTRA = new ArrayList<>();

        public static void register(ClassLoader l) {
            if (l != null && !EXTRA.contains(l)) EXTRA.add(l);
        }

        public static List<ClassLoader> extra() {
            return EXTRA;
        }
    }
}
