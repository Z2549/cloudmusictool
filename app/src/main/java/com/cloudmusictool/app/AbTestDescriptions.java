package com.cloudmusictool.app;

import java.util.HashMap;
import java.util.Map;

/**
 * 可强开实验的中文名与说明。
 *
 * 只覆盖 AbTestPolicy.CURATED_KEYS 中的 22 个体验类 key；未收录的 key
 * 由实验页显示为“未收录实验，暂无中文说明”，避免把道听途说的含义写进 UI。
 */
public final class AbTestDescriptions {

    private static final Map<String, String> TITLES = new HashMap<>();
    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static {
        put("tabMerge", "Tab 合并",
                "底栏 Tab 合并展示，可能减少底栏入口");
        put("home-headframe", "首页头图",
                "首页顶部头图/头框样式");
        put("comment-click", "评论点击",
                "评论区的点击行为（展开、跳转方式）");
        put("reply-top", "回复置顶",
                "回复楼层置顶展示");
        put("comment-loadingAndroid", "评论加载样式",
                "评论区加载样式与占位");
        put("SearchRN5android", "搜索 RN 框架",
                "搜索页 RN 框架版本");
        put("best-search", "最佳搜索",
                "搜索结果排序/效果策略");
        put("Hot-search-list", "热搜列表",
                "搜索页热搜列表展示");
        put("local_search_local_sort_1020", "本地搜索排序",
                "本地搜索结果的排序策略");
        put("aosCrossFade", "封面交叉淡入",
                "播放页封面交叉淡入淡出效果");
        put("aos-player-datasource-7", "播放器数据源",
                "播放器数据源策略");
        put("play-ring", "播放环 UI",
                "播放环/播放动效样式");
        put("newAudioFocusDialog", "音频焦点弹窗",
                "音频焦点被其他应用抢占时的提示弹窗");
        put("notificationReceiverAsyncRegister", "通知接收器异步注册",
                "通知接收器异步注册，偏启动性能");
        put("AndroidNotificationOnlyForeground", "仅前台通知",
                "仅在前台显示通知");
        put("messagecenter_notfication_rn_a", "消息中心通知",
                "消息中心通知的 RN 样式");
        put("StorageAdjustment0321", "存储策略调整",
                "缓存/存储清理策略");
        put("FH-DiskScannerTree", "磁盘扫描树",
                "磁盘/缓存扫描的目录树策略");
        put("FH-DiskCleanForScan2", "扫描后清理",
                "扫描完成后的磁盘清理策略");
        put("local-music-file-state-fix", "本地文件状态修复",
                "本地音乐文件状态修复逻辑");
        put("dailyRecommend-refresh", "每日推荐刷新",
                "每日推荐刷新策略");
        put("playlist_recommendation", "歌单推荐",
                "歌单推荐策略");
    }

    private AbTestDescriptions() {
    }

    private static void put(String key, String title, String description) {
        TITLES.put(key, title);
        DESCRIPTIONS.put(key, description);
    }

    public static String title(String key) {
        String t = TITLES.get(key);
        return t != null ? t : key;
    }

    public static String description(String key) {
        String d = DESCRIPTIONS.get(key);
        return d != null ? d : "未收录实验，暂无中文说明";
    }

    public static boolean hasDescription(String key) {
        return DESCRIPTIONS.containsKey(key);
    }
}
