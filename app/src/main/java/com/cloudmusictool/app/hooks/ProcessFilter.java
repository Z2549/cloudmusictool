package com.cloudmusictool.app.hooks;

/**
 * 进程过滤器。
 * 跳过 :patch、:safemode、:heap_analysis、:leakcanary 等辅助进程。
 * :play 进程只安装音频贴片（AD-07）相关 hook，其余功能仅主进程。
 */
public final class ProcessFilter {

    public static final String TARGET_PKG = "com.netease.cloudmusic";

    private ProcessFilter() {
    }

    /** 音频播放进程：只装 AD-07。 */
    public static boolean isPlayProcess(String processName) {
        return processName != null && processName.endsWith(":play");
    }

    public static boolean shouldHook(String processName) {
        if (processName == null) {
            return false;
        }
        if (processName.endsWith(":patch")
                || processName.endsWith(":safemode")
                || processName.endsWith(":heap_analysis")
                || processName.endsWith(":leakcanary")
                || processName.endsWith(":play")) {
            return false;
        }
        return TARGET_PKG.equals(processName);
    }
}
