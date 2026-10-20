package com.cloudmusictool.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ABTest 白名单/黑名单的单一事实来源。
 *
 * 目标进程（强开前二次校验）与模块 App（保存/提升前校验）共用，
 * 避免两边规则漂移。只做精确 key 匹配，禁止前缀/正则放行。
 */
public final class AbTestPolicy {

    /** 经过调研、只影响展示/加载/排序/性能的体验类 key。 */
    public static final String[] CURATED_KEYS = {
            "tabMerge",
            "home-headframe",
            "comment-click",
            "reply-top",
            "comment-loadingAndroid",
            "SearchRN5android",
            "best-search",
            "Hot-search-list",
            "local_search_local_sort_1020",
            "aosCrossFade",
            "aos-player-datasource-7",
            "play-ring",
            "newAudioFocusDialog",
            "notificationReceiverAsyncRegister",
            "AndroidNotificationOnlyForeground",
            "messagecenter_notfication_rn_a",
            "StorageAdjustment0321",
            "FH-DiskScannerTree",
            "FH-DiskCleanForScan2",
            "local-music-file-state-fix",
            "dailyRecommend-refresh",
            "playlist_recommendation"
    };

    public static final Set<String> CURATED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(CURATED_KEYS)));

    /** 可选的强制分组；Libra 的服务端分组名可能更多，但只开放这 6 个。 */
    public static final String[] GROUPS = {"c", "t", "t1", "t2", "t3", "t4"};

    /**
     * 禁止强开的语义。命中即拒绝，即使 key 被用户手动提升。
     * 覆盖：VIP/付费/版权/音质/试听/订单/地区/青少年/登录/Cookie/鉴权/风控/免流/免广告。
     */
    private static final Pattern BLOCKED = Pattern.compile(
            "vip|svip|pay|purchas|privilege|member|album|quality|trial|rights|copyright|"
                    + "order|price|subscribe|oversea|region|country|youth|teen|minor|guardian|privacy|"
                    + "login|cookie|auth|token|risk|security|encrypt|adfree|freeflow|64share|"
                    + "listenTogetherPurchase|freecashier|freetscashier|rewarded[-_]?video|"
                    + "ad[-_]?disable[-_]?ad[-_]?free|alipay|wxpay|xcrash|shieldenable|"
                    + "androidhttps|androidnapm|androidbi|wait-task-|cold-start-|"
                    + "aos[a-z]*https|tombstone|startup[_-]?task|play[_-]?process",
            Pattern.CASE_INSENSITIVE);

    private static final String[] BLOCKED_PREFIXES = {
            "boot-", "kv-", "fh-kv-"
    };

    private AbTestPolicy() {
    }

    public static boolean isCurated(String key) {
        return key != null && CURATED.contains(key);
    }

    public static boolean isBlocked(String key) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : BLOCKED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return BLOCKED.matcher(key).find();
    }

    /** 只有 curated 或用户显式提升过的 key，且不在黑名单里，才允许强开。 */
    public static boolean isAllowed(String key, Set<String> promoted) {
        if (isBlocked(key)) {
            return false;
        }
        return isCurated(key) || (promoted != null && promoted.contains(key));
    }

    public static boolean isValidGroup(String group) {
        if (group == null) {
            return false;
        }
        for (String g : GROUPS) {
            if (g.equals(group)) {
                return true;
            }
        }
        return false;
    }
}
