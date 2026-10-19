package com.cloudmusictool.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.cloudmusictool.app.dexkit.DexKitLocator;
import com.cloudmusictool.app.hooks.Hooks;
import com.cloudmusictool.app.hooks.ProcessFilter;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 唯一的 Java 入口类。API 102 热重载规范要求必须恰好声明一个入口。
 * 绝不能在 onModuleLoaded 之前调用框架 API。
 * 严格禁止引用任何 androidx 类型。
 */
public class CloudMusicToolModule extends XposedModule {

    static final String TAG = "CloudMusicTool";
    static final String TARGET_PACKAGE = "com.netease.cloudmusic";

    private String mProcessName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mProcessName = param.getProcessName();
        try {
            ApplicationInfo appInfo = getModuleApplicationInfo();
            if (appInfo != null && appInfo.nativeLibraryDir != null) {
                DexKitLocator.setModuleNativeDir(appInfo.nativeLibraryDir);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setModuleNativeDir failed", t);
        }
        log(Log.INFO, TAG, "loaded process=" + mProcessName
                + " api=" + getApiVersion()
                + " frameworkProps=0x" + Long.toHexString(getFrameworkProperties()));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            detach();
            return;
        }

        boolean playProcess = ProcessFilter.isPlayProcess(mProcessName);
        if (!playProcess && !ProcessFilter.shouldHook(mProcessName)) {
            log(Log.INFO, TAG, "skip secondary process: " + mProcessName);
            detach();
            return;
        }

        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " process=" + mProcessName
                + " first=" + param.isFirstPackage());

        try {
            Config config = Config.load(this);
            String targetVersion = VersionGate.resolveVersionName(param);
            if (!VersionGate.checkVersion(this, targetVersion, config.allowUnsupportedVersion())) {
                detach();
                return;
            }
            if (playProcess) {
                Hooks.installPlayOnly(this, param.getClassLoader(), config);
            } else {
                Hooks.installAll(this, param.getClassLoader(), config);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install aborted", t);
        }
    }
}
