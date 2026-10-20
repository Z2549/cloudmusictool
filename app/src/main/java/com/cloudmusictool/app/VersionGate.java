package com.cloudmusictool.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class VersionGate {

    public static final String SUPPORTED_VERSION = "9.6.08";
    public static final long SUPPORTED_VERSION_CODE = 9006008L;
    public static final String KEY_ALLOW_UNSUPPORTED = "allow_unsupported_version";
    public static final boolean DEFAULT_ALLOW_UNSUPPORTED = false;

    private static final String TAG = "CloudMusicTool";

    private VersionGate() {
    }

    /**
     * 读取目标网易云音乐 versionCode。
     *
     * 优先 PackageReadyParam.getApplicationInfo().getLongVersionCode()；该 API 在部分
     * compileSdk stub 中不可直接引用，因此用反射调用（运行时存在即生效）。
     * 失败后回退 PackageManager.getPackageInfo(...).getLongVersionCode()；再失败返回 -1，
     * 由调用方回退 versionName。
     */
    public static long resolveVersionCode(PackageReadyParam param) {
        if (param == null) {
            return -1L;
        }
        try {
            ApplicationInfo info = param.getApplicationInfo();
            if (info != null) {
                java.lang.reflect.Method m =
                        ApplicationInfo.class.getMethod("getLongVersionCode");
                Object value = m.invoke(info);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = targetContext();
            if (ctx != null) {
                PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(param.getPackageName(), 0);
                if (pi != null) {
                    return pi.getLongVersionCode();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /** 反射 ActivityThread 取当前应用/系统 Context，失败返回 null。 */
    private static Context targetContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object currentAt = at.getMethod("currentActivityThread").invoke(null);
            if (currentAt == null) {
                return null;
            }
            Object app = at.getMethod("getApplication").invoke(currentAt);
            if (app instanceof Context) {
                return (Context) app;
            }
            Object systemCtx = at.getMethod("getSystemContext").invoke(currentAt);
            if (systemCtx instanceof Context) {
                return (Context) systemCtx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 版本门禁 + 活动 Profile 选择。
     *
     * 1) versionCode 可读：9006008 激活 Profile 并放行；未适配时按 allowUnsupported
     *    best-effort（active=null，回退硬编码）或跳过。
     * 2) versionCode 不可读：回退原有 versionName 逻辑（未知 fail-open，未适配看 allowUnsupported）。
     *
     * 返回 false 时调用方应 detach()，不安装任何 hook。
     */
    public static boolean prepareProfile(XposedModule module, PackageReadyParam param,
                                         boolean allowUnsupported) {
        long versionCode = resolveVersionCode(param);
        if (versionCode > 0) {
            TargetProfile profile = ProfileRegistry.forVersionCode(versionCode);
            if (profile != null) {
                ProfileRegistry.setActive(profile);
                module.log(Log.INFO, TAG, "target version matched: " + versionCode
                        + " (" + profile.versionName + ")");
                module.log(Log.INFO, TAG, "profile mounts=" + profile.mounts.size()
                        + " versionCode=" + versionCode);
                return true;
            }
            ProfileRegistry.setActive(null);
            if (allowUnsupported) {
                module.log(Log.WARN, TAG, "target versionCode unsupported (" + versionCode
                        + "), but allow_unsupported_version is enabled; best-effort with hardcoded mounts");
                return true;
            }
            module.log(Log.WARN, TAG, "target versionCode unsupported: " + versionCode
                    + " (supported: " + SUPPORTED_VERSION_CODE + "), skipping hooks");
            return false;
        }

        // versionCode 不可读：保留原有 versionName 兜底逻辑。
        String versionName = resolveVersionName(param);
        if (versionName != null) {
            TargetProfile profile = ProfileRegistry.forVersionName(versionName);
            if (profile != null) {
                ProfileRegistry.setActive(profile);
                module.log(Log.INFO, TAG, "target version matched: " + versionName
                        + " (versionCode unavailable)");
                module.log(Log.INFO, TAG, "profile mounts=" + profile.mounts.size()
                        + " versionName=" + versionName);
                return true;
            }
        }
        ProfileRegistry.setActive(null);
        return checkVersion(module, versionName, allowUnsupported);
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
