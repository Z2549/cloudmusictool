package com.cloudmusictool.app.dexkit;

/**
 * 备用 DexKit 扫描锚点定义（Phase 0-2 骨架，供后续阶段动态定位抗改版）。
 * 严格禁止引用任何 androidx 类型。
 */
public final class Anchors {

    private Anchors() {
    }

    // 开屏广告相关特征字符串
    public static final String STR_LOADING_AD = "LoadingAdManager";
    public static final String STR_HIT_NOAD_ABTEST = "hit_noad_abtest";

    // 弹窗相关特征字符串
    public static final String STR_LAUNCH_DIALOG = "LaunchDialog";
    public static final String STR_UPDATE_CONFIG = "CMUpdateConfig";

    // 启动延迟特征字符串
    public static final String STR_START_TIME_HANDLER = "StartTimeHandler";
}
