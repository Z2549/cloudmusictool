package com.cloudmusictool.app.profile;

import android.util.Log;

import com.cloudmusictool.app.dexkit.DexKitLocator;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 MountSpec 解析成真实的 Class/Method。
 *
 * 顺序：精确类名 -> DexKit 结构/锚点兜底 -> 失败返回 null。
 * 所有结果都做包名/成员/签名校验；多候选无法消歧时拒绝，不猜。
 */
public final class MountResolver {

    private static final String TAG = "CloudMusicTool";
    private static final String TARGET_PREFIX = "com.netease.";

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private MountResolver() {
    }

    public static Class<?> resolveClass(MountSpec spec, ClassLoader cl) {
        if (spec == null || cl == null) {
            return null;
        }
        Class<?> cached = CLASS_CACHE.get(spec.id);
        if (cached != null) {
            return cached;
        }
        Class<?> found = null;
        if (spec.classCandidates != null) {
            for (String candidate : spec.classCandidates) {
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                try {
                    Class<?> cls = Class.forName(candidate, false, cl);
                    if (!isTargetClass(cls, cl)) {
                        continue;
                    }
                    if (spec.methodName != null && !hasMethodNamed(cls, spec.methodName)) {
                        continue;
                    }
                    found = cls;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (found == null && spec.anchors != null && spec.anchors.length > 0) {
            try {
                Class<?> fallback = DexKitLocator.findClass(
                        cl, spec.primaryClass(), spec.anchors);
                if (fallback != null && isTargetClass(fallback, cl)
                        && (spec.methodName == null || hasMethodNamed(fallback, spec.methodName))) {
                    found = fallback;
                    Log.i(TAG, "profile fallback class " + spec.id + " -> " + fallback.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "profile fallback failed " + spec.id + ": " + t.getMessage());
            }
        }
        if (found != null) {
            CLASS_CACHE.put(spec.id, found);
        } else {
            Log.w(TAG, "profile class unresolved " + spec.id);
        }
        return found;
    }

    public static Method resolveMethod(MountSpec spec, Class<?> cls, ClassLoader cl) {
        if (spec == null || cls == null || spec.methodName == null) {
            return null;
        }
        Method cached = METHOD_CACHE.get(spec.id);
        if (cached != null) {
            return cached;
        }
        Method found = null;
        Class<?>[] params = TypeResolver.resolveAll(spec.paramTypes, cl);
        if (spec.paramTypes != null && params == null) {
            Log.w(TAG, "profile param types unresolved " + spec.id);
            return null;
        }
        if (params != null) {
            found = findMethodInHierarchy(cls, spec.methodName, params);
        } else {
            Class<?> returnType = TypeResolver.resolve(spec.returnType, cl);
            Method unique = null;
            int count = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!spec.methodName.equals(m.getName())) {
                    continue;
                }
                if (returnType != null && !returnType.equals(m.getReturnType())) {
                    continue;
                }
                unique = m;
                count++;
            }
            if (count == 1) {
                found = unique;
            } else if (count > 1) {
                Log.w(TAG, "profile method ambiguous " + spec.id + " candidates=" + count);
            }
        }
        if (found != null) {
            if (spec.returnType != null) {
                Class<?> expected = TypeResolver.resolve(spec.returnType, cl);
                if (expected != null && !expected.equals(found.getReturnType())) {
                    Log.w(TAG, "profile return mismatch " + spec.id
                            + " expected=" + spec.returnType
                            + " actual=" + found.getReturnType().getName());
                    return null;
                }
            }
            METHOD_CACHE.put(spec.id, found);
        } else {
            Log.w(TAG, "profile method unresolved " + spec.id);
        }
        return found;
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>[] params) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean hasMethodNamed(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName())) {
                    return true;
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isTargetClass(Class<?> cls, ClassLoader cl) {
        String name = cls.getName();
        if (name == null || name.startsWith("com.cloudmusictool.")) {
            return false;
        }
        if (name.startsWith(TARGET_PREFIX)) {
            return true;
        }
        // 混淆后的顶层类（q60.w0、fw0.e 等）不在 com.netease.* 下，
        // 以“由宿主 ClassLoader 加载”作为归属校验，拒绝模块自身类。
        return cls.getClassLoader() == cl;
    }
}
