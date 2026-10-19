package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import io.github.libxposed.api.XposedModule;

/**
 * UI-04 侧栏抽屉精简（当前版本无可靠挂载点，暂不启用）。
 */
public final class DrawerHooks {

    private DrawerHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        // 当前版本无可靠挂载点，不再注册任何全局 Hook
    }
}
