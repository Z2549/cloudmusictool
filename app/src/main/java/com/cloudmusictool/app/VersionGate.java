package com.cloudmusictool.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class VersionGate {

    public static final String SUPPORTED_VERSION = "9.6.08";
    public static final String KEY_ALLOW_UNSUPPORTED = "allow_unsupported_version";
    public static final boolean DEFAULT_ALLOW_UNSUPPORTED = false;

    private static final String TAG = "CloudMusicTool";

    private VersionGate() {
    }

    /**
     * 读取目标网易云音乐版本名。
     * 1. 优先从 ActivityThread.currentActivityThread().getApplication() 获取 Context 并查询 PackageManager。
     * 2. 失败后从 ActivityThread.currentActivityThread().getSystemContext() 的 PackageManager 查询。
     */
    public static String resolveVersionName(PackageReadyParam param) {
        if (param == null) {
            return null;
        }
        // 1. 优先从 ActivityThread.currentActivityThread().getApplication() 取 PackageManager
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object currentAt = at.getMethod("currentActivityThread").invoke(null);
            if (currentAt != null) {
                Object app = at.getMethod("getApplication").invoke(currentAt);
                if (app instanceof Context) {
                    PackageInfo pi = ((Context) app).getPackageManager().getPackageInfo(param.getPackageName(), 0);
                    if (pi != null && pi.versionName != null) {
                        return pi.versionName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. 用 ActivityThread.currentActivityThread().getSystemContext() 的 PackageManager.getPackageInfo(packageName, 0)
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object currentAt = at.getMethod("currentActivityThread").invoke(null);
            if (currentAt != null) {
                Object systemCtx = at.getMethod("getSystemContext").invoke(currentAt);
                if (systemCtx instanceof Context) {
                    PackageInfo pi = ((Context) systemCtx).getPackageManager().getPackageInfo(param.getPackageName(), 0);
                    if (pi != null && pi.versionName != null) {
                        return pi.versionName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 校验目标版本是否符合要求。
     * 仅当 9.6.08 时放行；若开启 allow_unsupported_version 则放行其他版本。
     * 若版本未知（null），写 WARN 并返回 true（fail-open）。
     */
    public static boolean checkVersion(XposedModule module, String versionName, boolean allowUnsupported) {
        if (versionName == null) {
            module.log(Log.WARN, TAG, "unable to resolve target version; 版本未知，按 best-effort 继续");
            return true;
        }
        if (isSupportedVersion(versionName)) {
            module.log(Log.INFO, TAG, "target version matched: " + versionName);
            return true;
        }
        if (allowUnsupported) {
            module.log(Log.WARN, TAG, "target version unsupported (" + versionName
                    + "), but allow_unsupported_version is enabled");
            return true;
        }
        module.log(Log.WARN, TAG, "target version unsupported: " + versionName
                + " (supported: " + SUPPORTED_VERSION + "), skipping hooks");
        return false;
    }

    public static boolean isSupportedVersion(String versionName) {
        return SUPPORTED_VERSION.equals(versionName);
    }

    /** 纯函数版本门禁：未知版本放行，已知但未适配则看 allowUnsupported。 */
    public static boolean shouldProceed(String versionName, boolean allowUnsupported) {
        return versionName == null || isSupportedVersion(versionName) || allowUnsupported;
    }
}
