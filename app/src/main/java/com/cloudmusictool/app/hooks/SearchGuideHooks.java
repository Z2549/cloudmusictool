package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-11 关闭搜索框热词轮播引导。
 */
public final class SearchGuideHooks {

    private static final String SEARCH_GUIDE = "com.netease.cloudmusic.meta.SearchGuide";

    private SearchGuideHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-11 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        try {
            Class<?> cls = HookKit.findClass(module, f, cl, SEARCH_GUIDE);
            if (cls == null) {
                return;
            }
            Method m = HookKit.findMethod(module, f, cls, "isValid",
                    boolean.class, new Class<?>[0]);
            if (m == null) {
                return;
            }
            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                HookKit.warn(module, f.id + " skip SearchGuide#isValid: unexpected return "
                        + m.getReturnType());
                return;
            }
            HookKit.install(module, f, "SearchGuide#isValid", m,
                    HookKit.constBool(module, f, "SearchGuide.isValid -> false", false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SearchGuide hook failed", t);
        }
    }
}
