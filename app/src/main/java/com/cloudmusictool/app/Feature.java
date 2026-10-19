package com.cloudmusictool.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致。
 *
 * blockedReason 非空表示本版本未实装或属于后续阶段：只登记、不注册 hook。
 */
public enum Feature {

    SPLASH_AD("AD-01", R.string.f_ad_01_title, R.string.f_ad_01_summary,
            "ad_01_splash", true, Group.AD, null),
    LAUNCH_DIALOG("AD-02", R.string.f_ad_02_title, R.string.f_ad_02_summary,
            "ad_02_launch_dialog", true, Group.AD, null),
    UPDATE_DIALOG("AD-03", R.string.f_ad_03_title, R.string.f_ad_03_summary,
            "ad_03_update_dialog", true, Group.AD, null),
    FEED_AD("AD-04", R.string.f_ad_04_title, R.string.f_ad_04_summary,
            "ad_04_feed", true, Group.AD, null),
    COMMENT_AD("AD-05", R.string.f_ad_05_title, R.string.f_ad_05_summary,
            "ad_05_comment", true, Group.AD, null),
    PLAYER_AD("AD-06", R.string.f_ad_06_title, R.string.f_ad_06_summary,
            "ad_06_player", true, Group.AD, null),
    AUDIO_CLIP_AD("AD-07", R.string.f_ad_07_title, R.string.f_ad_07_summary,
            "ad_07_audio_clip", true, Group.AD, null),
    AD_REPORT_JUMP("AD-08", R.string.f_ad_08_title, R.string.f_ad_08_summary,
            "ad_08_report_jump", true, Group.AD, null),

    STARTUP_DELAY("UI-05", R.string.f_ui_05_title, R.string.f_ui_05_summary,
            "ui_05_startup_delay", true, Group.UI, null),
    HOME_BLOCKS("UI-01", R.string.f_ui_01_title, R.string.f_ui_01_summary,
            "ui_01_home_blocks", true, Group.UI, null),
    TOP_CHANNELS("UI-02", R.string.f_ui_02_title, R.string.f_ui_02_summary,
            "ui_02_top_channel", false, Group.UI,
            "9.6.08 频道数据链路已定位，但渲染层无法完全屏蔽空频道，暂不启用"),
    BOTTOM_NAV("UI-03", R.string.f_ui_03_title, R.string.f_ui_03_summary,
            "ui_03_bottom_nav", true, Group.UI, null),
    DRAWER("UI-04", R.string.f_ui_04_title, R.string.f_ui_04_summary,
            "ui_04_drawer", false, Group.UI, "当前版本无可靠挂载点，暂不启用"),
    HINTS("UI-06", R.string.f_ui_06_title, R.string.f_ui_06_summary,
            "ui_06_hints", true, Group.UI, null),
    AUTOPLAY("UI-07", R.string.f_ui_07_title, R.string.f_ui_07_summary,
            "ui_07_autoplay", true, Group.UI, null),
    DARK_MODE("UI-08", R.string.f_ui_08_title, R.string.f_ui_08_summary,
            "ui_08_dark_mode", false, Group.UI, null),
    PENDANT("UI-09", R.string.f_ui_09_title, R.string.f_ui_09_summary,
            "ui_09_pendant", true, Group.UI, null),

    OAID_REPORT("PV-01", R.string.f_pv_01_title, R.string.f_pv_01_summary,
            "pv_01_oaid", false, Group.PRIVACY, null),
    LOG_TASKS("PV-02", R.string.f_pv_02_title, R.string.f_pv_02_summary,
            "pv_02_log_tasks", false, Group.PRIVACY, null),
    STARTUP_BLACKLIST("PV-03", R.string.f_pv_03_title, R.string.f_pv_03_summary,
            "pv_03_startup_blacklist", false, Group.PRIVACY, null),
    APM("PV-04", R.string.f_pv_04_title, R.string.f_pv_04_summary,
            "pv_04_apm", false, Group.PRIVACY, null),
    STATS_OBSERVE("PV-05", R.string.f_pv_05_title, R.string.f_pv_05_summary,
            "pv_05_stats_observe", false, Group.PRIVACY, null);

    public enum Group {
        AD,
        UI,
        PRIVACY
    }

    public final String id;
    public final int titleRes;
    public final int summaryRes;
    public final String key;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 时用。 */
    public final boolean defaultEnabled;
    public final Group group;
    /** 非空 = 本版本不注册任何 hook。 */
    public final String blockedReason;

    Feature(String id, int titleRes, int summaryRes, String key,
            boolean defaultEnabled, Group group, String blockedReason) {
        this.id = id;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.key = key;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
        this.blockedReason = blockedReason;
    }

    public boolean blocked() {
        return blockedReason != null;
    }
}
