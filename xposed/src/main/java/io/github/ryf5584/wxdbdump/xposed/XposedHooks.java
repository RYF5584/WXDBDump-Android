package io.github.ryf5584.wxdbdump.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.platform.Hooks;

/**
 * Xposed implementation of {@link Hooks} on top of XC_MethodHook.
 *
 * <p>Threading: callbacks run on the intercepted thread, so payload callbacks must be fast.
 */
public final class XposedHooks implements Hooks {

    @Override
    public AutoCloseable hookMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes, MethodHook cb) {
        Member member = resolve(clazz, methodName, paramTypes);
        if (member == null) {
            throw new IllegalArgumentException("method not found: " + clazz.getName() + "." + methodName);
        }
        return hookMember(member, cb, methodName);
    }

    private AutoCloseable hookMember(Member member, MethodHook cb, String label) {
        XC_MethodHook bridge = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                HookCtx ctx = new HookCtx();
                ctx.thiz = param.thisObject;
                ctx.args = param.args;
                try {
                    cb.before(ctx);
                } catch (Throwable t) {
                    Log.e("hooks", "before callback threw, letting the original run " + label, t);
                    return;
                }
                if (ctx.isReplace()) param.setResult(ctx.replacement());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                HookCtx ctx = new HookCtx();
                ctx.thiz = param.thisObject;
                ctx.args = param.args;
                ctx.result = param.getResult();
                ctx.error = param.hasThrowable() ? param.getThrowable() : null;
                try {
                    cb.after(ctx);
                } catch (Throwable t) {
                    Log.e("hooks", "after callback threw " + label, t);
                    return;
                }
                if (ctx.isReplace()) {
                    param.setResult(ctx.replacement());
                    if (param.hasThrowable()) param.setThrowable(null);
                }
            }
        };
        final XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(member, bridge);
        return () -> {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {
            }
        };
    }

    // ---- watchClassLoad: one hook on ClassLoader.loadClass, deduped per consumer ----

    private static final Object WATCH_LOCK = new Object();
    private static volatile XC_MethodHook.Unhook watchUnhook;
    private static final Map<String, List<Object[]>> WATCHES = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable watchClassLoad(String className, Consumer<Class<?>> onLoaded) {
        synchronized (WATCH_LOCK) {
            List<Object[]> list = WATCHES.get(className);
            if (list == null) {
                list = new ArrayList<>();
                WATCHES.put(className, list);
            }
            list.add(new Object[]{
                    onLoaded,
                    java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>())
            });
            ensureWatchHook();
        }
        return () -> {
            /* Watches live for the process lifetime */
        };
    }

    private void ensureWatchHook() {
        if (watchUnhook != null) return;
        try {
            Method m = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            XC_MethodHook bridge = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable() || param.getResult() == null) return;
                    String name = (String) param.args[0];
                    if (name == null) return;
                    List<Object[]> list = WATCHES.get(name);
                    if (list == null) return;
                    Class<?> c = (Class<?>) param.getResult();
                    for (Object[] e : list) {
                        @SuppressWarnings("unchecked")
                        Set<Integer> seen = (Set<Integer>) e[1];
                        // Tinker keeps several copies of one class; dedupe by identityHashCode and call each
                        if (seen.add(System.identityHashCode(c))) {
                            try {
                                @SuppressWarnings("unchecked")
                                Consumer<Class<?>> consumer = (Consumer<Class<?>>) e[0];
                                consumer.accept(c);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            };
            watchUnhook = XposedBridge.hookMethod(m, bridge);
            Log.i("hooks", "ClassLoader.loadClass observer installed");
        } catch (Throwable t) {
            Log.e("hooks", "failed to install the loadClass observer", t);
        }
    }

    @Override
    public AutoCloseable hookAllConstructors(Class<?> clazz, MethodHook cb) {
        List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
        for (java.lang.reflect.Constructor<?> c : clazz.getDeclaredConstructors()) {
            try {
                XC_MethodHook bridge = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        HookCtx ctx = new HookCtx();
                        ctx.thiz = param.thisObject;
                        ctx.args = param.args;
                        try {
                            cb.after(ctx);
                        } catch (Throwable ignored) {
                        }
                    }
                };
                unhooks.add(XposedBridge.hookMethod(c, bridge));
            } catch (Throwable t) {
                Log.d("hooks", "constructor hook failed: " + c + " :: " + t);
            }
        }
        if (unhooks.isEmpty()) throw new IllegalStateException("no constructor to hook: " + clazz.getName());
        return () -> {
            for (XC_MethodHook.Unhook u : unhooks) {
                try {
                    u.unhook();
                } catch (Throwable ignored) {
                }
            }
        };
    }

    @Override
    public boolean postToMainThread(Runnable r) {
        try {
            Class<?> looperCls = Class.forName("android.os.Looper");
            Class<?> handlerCls = Class.forName("android.os.Handler");
            Object mainLooper = looperCls.getMethod("getMainLooper").invoke(null);
            Object handler = handlerCls.getConstructor(looperCls).newInstance(mainLooper);
            handlerCls.getMethod("post", Runnable.class).invoke(handler, r);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onMainThread() {
        try {
            Class<?> looperCls = Class.forName("android.os.Looper");
            Object main = looperCls.getMethod("getMainLooper").invoke(null);
            Object mine = looperCls.getMethod("myLooper").invoke(null);
            return main != null && main.equals(mine);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String describe() {
        return "Xposed (YukiHookAPI + XposedBridge)";
    }

    private static Member resolve(Class<?> clazz, String name, Class<?>[] paramTypes) {
        try {
            if (paramTypes == null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(name)) return m;
                }
                return null;
            }
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
