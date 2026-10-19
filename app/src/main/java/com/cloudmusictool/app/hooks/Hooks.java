package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.netease.cloudmusic）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "CloudMusicTool";

    private Hooks() {
    }

    public static void installAll(XposedModule module, ClassLoader cl, Config config) {
        if (config == null) {
            module.log(Log.ERROR, TAG, "config is null");
            return;
        }
        if (!config.anyEnabled()) {
            module.log(Log.INFO, TAG, "all features disabled, nothing to hook");
            return;
        }
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                continue;
            }
            if (!config.enabled(f)) {
                module.log(Log.INFO, TAG, f.id + " disabled");
                continue;
            }
            HookState.enable(f);
            installOne(module, cl, f, config);
        }
    }

    /** :play 进程只安装音频贴片拦截。 */
    public static void installPlayOnly(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.AUDIO_CLIP_AD;
        if (!config.enabled(f)) {
            module.log(Log.INFO, TAG, f.id + " disabled in :play");
            return;
        }
        HookState.enable(f);
        installOne(module, cl, f, config);
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            switch (f) {
                case SPLASH_AD:
                    SplashAdHooks.install(module, cl);
                    break;
                case LAUNCH_DIALOG:
                    LaunchDialogHooks.install(module, cl);
                    break;
                case UPDATE_DIALOG:
                    UpdateHooks.install(module, cl);
                    break;
                case AD_REPORT_JUMP:
                    AdReportHooks.install(module, cl);
                    break;
                case FEED_AD:
                    FeedAdHooks.install(module, cl);
                    break;
                case COMMENT_AD:
                    CommentAdHooks.install(module, cl);
                    break;
                case PLAYER_AD:
                    PlayerAdHooks.install(module, cl);
                    break;
                case AUDIO_CLIP_AD:
                    AudioAdHooks.install(module, cl);
                    break;
                case STARTUP_DELAY:
                    StartupDelayHooks.install(module, cl);
                    break;
                case HOME_BLOCKS:
                    HomeBlockHooks.install(module, cl, config);
                    break;
                case TOP_CHANNELS:
                    TopTabHooks.install(module, cl, config);
                    break;
                case BOTTOM_NAV:
                    BottomTabHooks.install(module, cl, config);
                    break;
                case HINTS:
                    HintHooks.install(module, cl);
                    break;
                case AUTOPLAY:
                    AutoPlayHooks.install(module, cl);
                    break;
                case DARK_MODE:
                    ThemeHooks.install(module, cl);
                    break;
                case PENDANT:
                    PendantHooks.install(module, cl);
                    break;
                case OAID_REPORT:
                    OaidHooks.install(module, cl);
                    break;
                case LOG_TASKS:
                    LogTaskHooks.install(module, cl);
                    break;
                case STARTUP_BLACKLIST:
                    StartupBlacklistHooks.install(module, cl, config);
                    break;
                case APM:
                    ApmHooks.install(module, cl);
                    break;
                case STATS_OBSERVE:
                    StatisticHooks.install(module, cl);
                    break;
                default:
                    module.log(Log.INFO, TAG, f.id + " pending implementation in later phase");
                    break;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            HookState.note(f, "安装异常");
        }
    }
}
