package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-12 播客 / AI DJ 引导屏蔽。
 */
public final class PodcastGuideHooks {

    private static final String BOTTOM_GUIDE_MANAGER =
            "com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager";
    private static final String PLAYER_AIDJ_GUIDE =
            "com.netease.cloudmusic.ui.hint.aidj.PlayerAIDJSwitchGuideView";
    private static final String FM_AIDJ_SWITCH =
            "com.netease.cloudmusic.hint.aidj.FMAIDJSwitchView";

    private PodcastGuideHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-12 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 播客 FM 自动播放 / 听书权益引导条
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, BOTTOM_GUIDE_MANAGER);
            if (cls != null) {
                installConstFalse(module, f, cls, "isTimeToShowFMAutoPlayGuide",
                        "PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide");
                installConstFalse(module, f, cls, "isTimeToShowVoiceBookGuide",
                        "PodcastBottomGuideManager#isTimeToShowVoiceBookGuide");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PodcastBottomGuideManager hook failed", t);
        }

        // 2. 播放页 AI DJ 切换引导
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, PLAYER_AIDJ_GUIDE);
            if (cls != null) {
                Method m = HookKit.findMethod(module, f, cls, "showGuide");
                if (m != null) {
                    if (m.getReturnType() == void.class) {
                        HookKit.install(module, f, "PlayerAIDJSwitchGuideView#showGuide", m,
                                HookKit.skipVoid(module, f, "PlayerAIDJSwitchGuideView.showGuide"));
                    } else {
                        HookKit.warn(module, f.id
                                + " skip PlayerAIDJSwitchGuideView#showGuide: unexpected return "
                                + m.getReturnType());
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PlayerAIDJSwitchGuideView hook failed", t);
        }

        // 3. FM 页 AI DJ 切换引导气泡
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, FM_AIDJ_SWITCH);
            if (cls != null) {
                Method m = HookKit.findMethod(module, f, cls, "maybeDisplayHint");
                if (m != null) {
                    if (m.getReturnType() == void.class) {
                        HookKit.install(module, f, "FMAIDJSwitchView#maybeDisplayHint", m,
                                HookKit.skipVoid(module, f, "FMAIDJSwitchView.maybeDisplayHint"));
                    } else {
                        HookKit.warn(module, f.id
                                + " skip FMAIDJSwitchView#maybeDisplayHint: unexpected return "
                                + m.getReturnType());
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " FMAIDJSwitchView hook failed", t);
        }
    }

    private static void installConstFalse(XposedModule module, Feature f, Class<?> cls,
                                          String name, String label) {
        try {
            Method m = HookKit.findMethod(module, f, cls, name,
                    boolean.class, new Class<?>[0]);
            if (m == null) {
                return;
            }
            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                HookKit.warn(module, f.id + " skip " + label + ": unexpected return " + m.getReturnType());
                return;
            }
            HookKit.install(module, f, label, m,
                    HookKit.constBool(module, f, label + " -> false", false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + label + " hook failed", t);
        }
    }
}
