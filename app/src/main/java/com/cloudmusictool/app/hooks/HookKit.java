package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.dexkit.DexKitLocator;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * HookKit 工具类。
 * 约束：
 * 1) 先走硬编码 Class.forName / getDeclaredMethod；失败则调用 DexKitLocator 进行特征锚点兜底。
 * 2) 定位/hook 失败只记日志并返回 null/false，绝不向外抛异常。
 * 3) 每个 intercept 体整段包在 try/catch(Throwable) 内，catch 后记日志并 proceed 原逻辑。
 * 4) 命中日志固定 "<功能id> hit ..."，tag 固定 CloudMusicTool。
 * 5) 严格禁止引用任何 androidx 类型。
 */
final class HookKit {

    static final String TAG = "CloudMusicTool";

    private HookKit() {
    }

    static void info(XposedModule module, String msg) {
        module.log(Log.INFO, TAG, msg);
    }

    static void warn(XposedModule module, String msg) {
        module.log(Log.WARN, TAG, msg);
    }

    static void error(XposedModule module, String msg, Throwable t) {
        module.log(Log.ERROR, TAG, msg, t);
    }

    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name, String... anchors) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            if (anchors != null && anchors.length > 0) {
                try {
                    Class<?> fallback = DexKitLocator.findClass(cl, name, anchors);
                    if (fallback != null) {
                        info(module, f.id + " DexKit fallback located class " + name + " -> " + fallback.getName());
                        return fallback;
                    }
                } catch (Throwable dexT) {
                    warn(module, f.id + " DexKit class query failed for " + name + ": " + dexT.getMessage());
                }
            }
            warn(module, f.id + " skip: class not found " + name);
            HookState.markNotFound(f);
            HookState.note(f, "类未找到 " + name);
            return null;
        }
    }

    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        return findClass(module, f, cl, name, (String[]) null);
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name,
                             Class<?> returnType, Class<?>[] params, String... anchors) {
        Method m = findMethodQuiet(cls, name, params);
        if (m == null && cls != null && anchors != null && anchors.length > 0) {
            try {
                ClassLoader cl = cls.getClassLoader();
                m = DexKitLocator.findMethod(cl, cls.getName(), name, returnType, params, anchors);
                if (m != null) {
                    info(module, f.id + " DexKit fallback located method " + cls.getName() + "#" + name);
                    return m;
                }
            } catch (Throwable dexT) {
                warn(module, f.id + " DexKit method query failed: " + dexT.getMessage());
            }
        }
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f);
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name,
                             Class<?>... params) {
        return findMethod(module, f, cls, name, null, params, (String[]) null);
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name) {
        Method m = findByNameQuiet(cls, name);
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f);
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    static Method findMethodWithAnchors(XposedModule module, Feature f, Class<?> cls, String name,
                                        String... anchors) {
        Method m = findByNameQuiet(cls, name);
        if (m == null && cls != null && anchors != null && anchors.length > 0) {
            try {
                ClassLoader cl = cls.getClassLoader();
                m = DexKitLocator.findMethod(cl, cls.getName(), name, null, null, anchors);
                if (m != null) {
                    info(module, f.id + " DexKit fallback located method " + cls.getName() + "#" + name);
                    return m;
                }
            } catch (Throwable dexT) {
                warn(module, f.id + " DexKit method query failed: " + dexT.getMessage());
            }
        }
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f);
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    static Method findByNameQuiet(Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findByShape(Class<?> cls, String name, Class<?>... leading) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < leading.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < leading.length; i++) {
                    if (p[i] != leading[i]) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findByNameAndArity(Class<?> cls, String name, int arity) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != arity) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findMethodQuiet(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean install(XposedModule module, Feature f, String label, Method m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        return install(module, f, label, (Executable) m, hooker);
    }

    static boolean install(XposedModule module, Feature f, String label,
                           Executable m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        try {
            module.hook(m)
                    .setId(f.id + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            HookState.installed(f, label);
            info(module, f.id + " registered " + label);
            return true;
        } catch (Throwable t) {
            error(module, f.id + " hook failed " + label, t);
            HookState.markNotFound(f);
            HookState.note(f, "hook 失败 " + label);
            return false;
        }
    }

    static void hit(XposedModule module, Feature f, String detail) {
        module.log(Log.INFO, TAG, f.id + " hit " + detail);
    }

    static XposedInterface.Hooker skipVoid(XposedModule module, Feature f, String detail) {
        return chain -> {
            try {
                hit(module, f, detail);
                return null;
            } catch (Throwable t) {
                error(module, f.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        };
    }

    static XposedInterface.Hooker constBool(XposedModule module, Feature f, String detail, boolean value) {
        return chain -> {
            try {
                hit(module, f, detail);
                return value ? Boolean.TRUE : Boolean.FALSE;
            } catch (Throwable t) {
                error(module, f.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        };
    }
}
