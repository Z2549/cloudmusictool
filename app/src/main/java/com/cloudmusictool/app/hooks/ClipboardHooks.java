package com.cloudmusictool.app.hooks;

import android.content.ClipData;
import android.content.Context;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * PV-06 剪贴板读取拦截。
 * com.netease.cloudmusic.utils.h2 是宿主的全局 ClipboardUtils，
 * d(Context) 即 getPrimaryClipDataFirstItem，返回 null 后 App 不再自动识别剪贴板内容。
 */
public final class ClipboardHooks {

    private static final String CLIPBOARD_UTILS = "com.netease.cloudmusic.utils.h2";

    private ClipboardHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "PV-06 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLIPBOARD_UTILS);
            if (cls == null) {
                return;
            }
            Method m = HookKit.findMethod(module, f, cls, "d",
                    ClipData.Item.class, new Class<?>[]{Context.class});
            if (m == null) {
                return;
            }
            if (m.getReturnType() != ClipData.Item.class) {
                HookKit.warn(module, f.id + " skip utils.h2#d: unexpected return " + m.getReturnType());
                return;
            }
            HookKit.install(module, f, "utils.h2#d", m, chain -> {
                try {
                    HookKit.hit(module, f, "utils.h2.d -> null");
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error utils.h2#d", t);
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            HookKit.error(module, f.id + " utils.h2 hook failed", t);
        }
    }
}
