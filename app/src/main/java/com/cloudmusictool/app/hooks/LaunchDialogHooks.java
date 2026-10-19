package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-02 运营弹窗屏蔽：
 * 屏蔽 LaunchDialog 的各类营销/运营推广弹窗，保留登录弹窗（showOnePassLoginDialog）。
 */
public final class LaunchDialogHooks {

    private LaunchDialogHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.LAUNCH_DIALOG;
        try {
            Class<?> dialogCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.ui.LaunchDialog",
                    "LaunchDialog");
            if (dialogCls == null) {
                return;
            }

            int count = 0;
            for (Method m : dialogCls.getDeclaredMethods()) {
                String name = m.getName();
                // 绝对不碰一键登录对话框
                if ("showOnePassLoginDialog".equals(name) || name.contains("Login")) {
                    continue;
                }
                if ("showDialog".equals(name)
                        || "showMultiModulePopUpDialog".equals(name)
                        || "showSinglePicDialog".equals(name)
                        || "showPictureDialog".equals(name)
                        || "showCompactDialog".equals(name)) {
                    HookKit.install(module, f, "LaunchDialog#" + name + "(" + m.getParameterTypes().length + ")",
                            m, HookKit.skipVoid(module, f, "LaunchDialog." + name));
                    count++;
                }
            }
            HookKit.info(module, f.id + " registered " + count + " LaunchDialog methods");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install failed", t);
        }
    }
}
