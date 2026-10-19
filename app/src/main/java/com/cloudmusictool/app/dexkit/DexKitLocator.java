package com.cloudmusictool.app.dexkit;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * DexKit 动态扫描定位引擎。
 * 懒加载 DexKitBridge 并按 ClassLoader 缓存；当硬编码定位失败时作为兜底机制介入。
 * 严格禁止引用任何 androidx 类型。
 */
public final class DexKitLocator {

    private static final String TAG = "CloudMusicTool";
    private static final Map<ClassLoader, DexKitBridge> sBridgeCache = new WeakHashMap<>();
    private static volatile String sModuleNativeDir = null;

    private DexKitLocator() {
    }

    public static void setModuleNativeDir(String dir) {
        sModuleNativeDir = dir;
    }

    public static synchronized DexKitBridge getBridge(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        DexKitBridge bridge = sBridgeCache.get(cl);
        if (bridge == null) {
            try {
                boolean loaded = false;
                if (sModuleNativeDir != null && !sModuleNativeDir.isEmpty()) {
                    try {
                        java.io.File soFile = new java.io.File(sModuleNativeDir, "libdexkit.so");
                        if (soFile.exists()) {
                            System.load(soFile.getAbsolutePath());
                            loaded = true;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit load from moduleNativeDir failed: " + t.getMessage());
                    }
                }
                if (!loaded) {
                    try {
                        System.loadLibrary("dexkit");
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit loadLibrary failed: " + t.getMessage());
                    }
                }
                bridge = DexKitBridge.create(cl, false);
                sBridgeCache.put(cl, bridge);
                Log.i(TAG, "DexKitBridge initialized successfully for ClassLoader: " + cl);
            } catch (Throwable t) {
                Log.w(TAG, "DexKitBridge initialization failed: " + t.getMessage());
            }
        }
        return bridge;
    }

    /**
     * 兜底查找类：
     * 有 anchors 时，只按 anchors 查询，不要再加 className 约束；
     * 没有 anchors 时直接返回 null（硬编码已经失败，按名字再查一次没有意义）。
     * 命中后如果有多个结果，取第一个并记录候选数量。
     */
    public static Class<?> findClass(ClassLoader cl, String className, String... anchors) {
        if (anchors == null || anchors.length == 0) {
            return null;
        }
        boolean hasValidAnchor = false;
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                hasValidAnchor = true;
                break;
            }
        }
        if (!hasValidAnchor) {
            return null;
        }

        DexKitBridge bridge = getBridge(cl);
        if (bridge == null) {
            return null;
        }
        try {
            FindClass findClass = FindClass.create();
            ClassMatcher matcher = ClassMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher.addUsingString(anchor);
                }
            }
            findClass.matcher(matcher);
            ClassDataList list = bridge.findClass(findClass);
            if (list != null && !list.isEmpty()) {
                int count = list.size();
                ClassData data = list.get(0);
                Class<?> cls = data.getInstance(cl);
                Log.i(TAG, "DexKit fallback found class (candidates=" + count + ", chosen=" + cls.getName()
                        + ", targetHint=" + className + ")");
                return cls;
            } else {
                Log.w(TAG, "DexKit findClass: 0 candidates found for targetHint=" + className);
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findClass error for " + className + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * 兜底查找方法：
     * 分两次尝试：
     *  第一次：declaredClass(actualClassName) + name(methodName) + anchors；
     *  第二次（第一次无结果或未通过校验时）：只按 anchors 查询，不限制类名/方法名/返回类型/参数类型。
     *  命中后如果调用方提供了 returnType，做一次返回类型 sanity check（不一致就丢弃并记录日志）；参数类型不做强约束。
     */
    public static Method findMethod(ClassLoader cl, String actualClassName, String methodName,
                                    Class<?> returnType, Class<?>[] paramTypes, String... anchors) {
        if (anchors == null || anchors.length == 0) {
            return null;
        }
        boolean hasValidAnchor = false;
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                hasValidAnchor = true;
                break;
            }
        }
        if (!hasValidAnchor) {
            return null;
        }

        DexKitBridge bridge = getBridge(cl);
        if (bridge == null) {
            return null;
        }

        // 第一次尝试：declaredClass(actualClassName) + name(methodName) + anchors
        try {
            FindMethod query1 = FindMethod.create();
            MethodMatcher matcher1 = MethodMatcher.create();
            if (actualClassName != null && !actualClassName.isEmpty()) {
                matcher1.declaredClass(actualClassName);
            }
            if (methodName != null && !methodName.isEmpty()) {
                matcher1.name(methodName);
            }
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher1.addUsingString(anchor);
                }
            }
            query1.matcher(matcher1);
            MethodDataList list1 = bridge.findMethod(query1);
            if (list1 != null && !list1.isEmpty()) {
                Method m = selectMatchingMethod(cl, list1, returnType, "attempt1", actualClassName, methodName);
                if (m != null) {
                    return m;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 1 error: " + t.getMessage());
        }

        // 第二次尝试（第一次无结果或未通过校验时）：只按 anchors 查询，不限制类名/方法名/返回类型/参数类型
        try {
            FindMethod query2 = FindMethod.create();
            MethodMatcher matcher2 = MethodMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher2.addUsingString(anchor);
                }
            }
            query2.matcher(matcher2);
            MethodDataList list2 = bridge.findMethod(query2);
            if (list2 != null && !list2.isEmpty()) {
                Method m = selectMatchingMethod(cl, list2, returnType, "attempt2", actualClassName, methodName);
                if (m != null) {
                    return m;
                }
            } else {
                Log.w(TAG, "DexKit findMethod attempt 2: 0 candidates found for anchors on " + actualClassName + "#" + methodName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 2 error: " + t.getMessage());
        }

        return null;
    }

    private static Method selectMatchingMethod(ClassLoader cl, MethodDataList list, Class<?> returnType,
                                              String attemptLabel, String targetClass, String targetMethod) {
        int count = list.size();
        for (int i = 0; i < count; i++) {
            MethodData data = list.get(i);
            try {
                Method m = data.getMethodInstance(cl);
                if (returnType != null) {
                    if (!isReturnTypeCompatible(returnType, m.getReturnType())) {
                        Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " (" + m.getDeclaringClass().getName()
                                + "#" + m.getName() + ") returnType mismatch: expected "
                                + returnType.getName() + ", got " + m.getReturnType().getName() + "; discarded");
                        continue;
                    }
                }
                Log.i(TAG, "DexKit " + attemptLabel + " chosen method (candidates=" + count + ", chosen="
                        + m.getDeclaringClass().getName() + "#" + m.getName()
                        + ", targetHint=" + targetClass + "#" + targetMethod + ")");
                return m;
            } catch (Throwable t) {
                Log.w(TAG, "DexKit " + attemptLabel + " getMethodInstance error: " + t.getMessage());
            }
        }
        return null;
    }

    private static boolean isReturnTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == null || actual == null) {
            return true;
        }
        if (expected == actual || expected.equals(actual) || expected.isAssignableFrom(actual)) {
            return true;
        }
        if (expected == boolean.class && actual == Boolean.class) return true;
        if (expected == Boolean.class && actual == boolean.class) return true;
        if (expected == int.class && actual == Integer.class) return true;
        if (expected == Integer.class && actual == int.class) return true;
        if (expected == long.class && actual == Long.class) return true;
        if (expected == Long.class && actual == long.class) return true;
        if (expected == void.class && actual == Void.class) return true;
        return false;
    }
}
