#!/usr/bin/env bash
# 云音乐工具箱 · 挂载点体检
# 用法: tools/check-mounts.sh [APK路径]
# 默认检查 E:\download\base(2).apk（网易云 9.6.08）
set -u

APK="${1:-E:/download/base(2).apk}"
DEXSQL="${DEXSQL:-E:/tools/dexsql/dexsql.exe}"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi
if [ ! -x "$DEXSQL" ] && [ ! -f "$DEXSQL" ]; then
  echo "dexsql not found: $DEXSQL" >&2
  exit 1
fi

PASS=0
FAIL=0
FAILED_LIST=""

count_method() {
  local cls="$1" method="$2"
  "$DEXSQL" query --input "$APK" \
    --sql "SELECT count(*) AS n FROM methods WHERE class_descriptor='$cls' AND name='$method'" 2>/dev/null \
    | awk -F'|' '/\| *[0-9]+ *\|/{gsub(/ /,"",$2); print $2; exit}'
}

check() {
  local cls="$1" method="$2"
  local n
  n=$(count_method "$cls" "$method")
  if [ "${n:-0}" -gt 0 ]; then
    printf 'OK    %-95s #%s (x%s)\n' "$cls" "$method" "$n"
    PASS=$((PASS + 1))
  else
    printf 'GONE  %-95s #%s\n' "$cls" "$method"
    FAIL=$((FAIL + 1))
    FAILED_LIST="${FAILED_LIST}${cls}#${method}\n"
  fi
}

echo "APK: $APK"
echo "--------------------------------------------------------------------------------"

# AD-01 开屏
check 'Lcom/netease/cloudmusic/module/ad/LoadingAdManager;' 'E'
check 'Lcom/netease/cloudmusic/module/ad/LoadingAdManager;' 'G'
check 'Lcom/netease/cloudmusic/module/ad/LoadingAdManager;' 'o'
check 'Lcom/netease/cloudmusic/activity/LoadingAdActivity;' 'onCreate'
check 'Lcom/netease/cloudmusic/fragment/LoadingAdFragment;' 'onViewCreated'
check 'Lcom/netease/cloudmusic/module/ad/boot/LoadingAdShakeHelper;' 'onSensorChanged'
check 'Lq60/w0;' 'g'

# AD-02 运营弹窗
check 'Lcom/netease/cloudmusic/ui/LaunchDialog;' 'showDialog'
check 'Lcom/netease/cloudmusic/ui/LaunchDialog;' 'showMultiModulePopUpDialog'
check 'Lcom/netease/cloudmusic/ui/LaunchDialog;' 'showSinglePicDialog'
check 'Lcom/netease/cloudmusic/ui/LaunchDialog;' 'showPictureDialog'
check 'Lcom/netease/cloudmusic/ui/LaunchDialog;' 'showCompactDialog'

# AD-03 更新
check 'Lcom/netease/cloudmusic/appupdate/CMUpdateConfig;' 'hasNewVersion'
check 'Lcom/netease/cloudmusic/appupdate/d;' 'B'
check 'Lcom/netease/cloudmusic/appupdate/d;' 'I'
check 'Lcom/netease/cloudmusic/appupdate/meta/UpdateInfo;' 'getNeedPopUp'
check 'Lcom/netease/cloudmusic/appupdate/meta/UpdateInfo;' 'getForceUpdate'
check 'Lfw0/e;' 'q'

# AD-04 首页信息流
check 'Lcom/netease/cloudmusic/meta/Banner;' 'isAdBanner'

# AD-05 评论区
check 'Lcom/netease/cloudmusic/adapter/CommentAdapter;' 'y1'
check 'Lcom/netease/cloudmusic/meta/virtual/SpecialComment;' 'getGeneralBanner'
check 'Lcom/netease/cloudmusic/meta/virtual/SpecialComment;' 'getLiveRcmdList'
check 'Lcom/netease/cloudmusic/meta/virtual/SpecialComment;' 'getVipRcmdList'
check 'Lcom/netease/cloudmusic/meta/virtual/SpecialComment;' 'getConcertInfo'
check 'Lcom/netease/cloudmusic/meta/Comment;' 'isTagValid'
check 'Lcom/netease/cloudmusic/meta/Comment;' 'getBottomTags'
check 'Lcom/netease/cloudmusic/adapter/CommentVipRcmdAdapter;' 'getItemCount'

# AD-06 播放页
check 'Lcom/netease/cloudmusic/module/ad/playerjar/PlayerAdJarDelegateImpl;' 'k'
check 'Lcom/netease/cloudmusic/module/ad/playerjar/PlayerAdJarDelegateImpl;' 'q'
check 'Lcom/netease/cloudmusic/module/player/vinylfakelayer/musicgift/meta/MusicGiftModel;' 'isValid'
check 'Lcom/netease/cloudmusic/ui/ad/player/AdPlayerFullScreenHelper;' 'checkNeedExpose'

# AD-07 音频贴片/播客
check 'Lcom/netease/cloudmusic/module/ad/audioclip/AdAudioClipManager;' 'd'
check 'Lcom/netease/cloudmusic/module/ad/audioclip/AdAudioClipManager;' 'c'
check 'Lcom/netease/cloudmusic/module/ad/podcast/AdPodcastPlayerPageHelper;' 'h'
check 'Lcom/netease/cloudmusic/module/ad/podcast/PodcastVideoAdHelperImpl;' 'z'

# AD-08 上报/外跳
check 'Lcom/netease/cloudmusic/module/ad/AdCloudReportTask;' 'execute'
check 'Lcom/netease/cloudmusic/ad/StartAppHelper$b;' 'startApp'
check 'Lcom/netease/cloudmusic/process/task/app/attach/AdExposureDeeplinkPreRequestTask;' 'init'
check 'Lcom/netease/cloudmusic/process/task/app/create/AdExposureDeeplinkTask;' 'init'

# UI-05 启动延迟
check 'Lcom/netease/cloudmusic/module/startup/StartTimeHandler;' 'isNeedDelayStart'
check 'Lcom/netease/cloudmusic/module/startup/StartTimeHandler;' 'getStartAppRedPageDelayTime'
check 'Lcom/netease/cloudmusic/module/startup/StartTimeHandler;' 'checkDelayDoAction'

# UI-01 首页 block
check 'Lcom/netease/cloudmusic/discovery/data/meta/DiscoveryBlockData;' 'getBlockList'
check 'Lcom/tencent/mmkv/MMKV;' 'decodeString'
check 'Lcom/netease/cloudmusic/dsl/container/data/MixContainerData;' 'getBlockCodeOrderList'
check 'Lcom/netease/cloudmusic/dsl/container/data/MixContainerData;' 'getResources'
check 'Lcom/netease/luna/cm/dslwrapper/DslWrapperModel;' 'getOriginData'

# UI-02 顶部频道
check 'Lcom/netease/cloudmusic/discovery/view/module/hometoptab/model/HomeTopTabInfo;' 'getCanDelete'
check 'Lcom/netease/cloudmusic/discovery/view/module/hometoptab/model/HomeTopTabInfo;' 'getCanDrag'
check 'Lcom/netease/cloudmusic/discovery/view/module/hometoptab/model/TabExtra;' 'getNowChannelItems'
check 'Lcom/netease/cloudmusic/discovery/view/newframework/model/TabExtra;' 'getNowChannelItems'
check 'Lcom/netease/cloudmusic/discovery/view/module/hometoptab/model/UserSettingDTO;' 'getCurrentRes'
check 'Lcom/netease/cloudmusic/discovery/view/module/hometoptab/v2/UserSettingDTO;' 'getCurrentRes'

# UI-03 底部导航
check 'Lcom/netease/cloudmusic/music/biz/setting/activity/SettingActivity;' 'T8'
check 'Lcom/netease/cloudmusic/music/biz/setting/activity/SettingActivity;' 'U8'
check 'Lth0/h;' 'h'
check 'Lrh0/c;' 'c'

# UI-06 引导/弹窗
check 'Lcom/netease/cloudmusic/main/hint/push/PushGuideHintView;' 'maybeDisplayHint'
check 'Lcom/netease/cloudmusic/module/hint/view/ScoreHintDialogView;' 'maybeDisplayHint'
check 'Lcom/netease/cloudmusic/discovery/view/module/music/v3/vipcard/HintVipNormalRenewalDialogueView;' 'maybeDisplayHint'
check 'Lcom/netease/cloudmusic/module/hint/view/LiveLocalPushHintView;' 'maybeDisplayHint'
check 'Lcom/netease/cloudmusic/main/hint/BottomTabGuideBubbleNativeView;' 'maybeDisplayHint'
check 'Lnr2/c;' 'checkAndShowYouthDialog'

# UI-07 自动播放 / UI-09 挂件
check 'Lcom/netease/cloudmusic/music/biz/setting/activity/SettingActivity;' 'V8'
check 'Lcom/netease/cloudmusic/utils/jn;' 'm0'
check 'Lcom/netease/cloudmusic/utils/jn;' 'L'
check 'Ltt0/d;' 'a'
check 'Ltt0/d;' 'd'
check 'Lvt0/r;' 'j'
check 'Llv0/j;' 'e'

# UI-08 深色
check 'Lcom/netease/cloudmusic/theme/core/ResourceRouter;' 'isNightTheme'
check 'Lcom/netease/cloudmusic/theme/core/ResourceRouter;' 'isUiModeNightYes'

# PV-01 OAID
check 'Lcom/netease/cloudmusic/oaid/MusicOaidUploadManager;' 'o'
check 'Lcom/netease/cloudmusic/process/task/app/create/AdOaidUploadTask;' 'init'

# PV-02 日志写盘
check 'Lcom/netease/cloudmusic/process/task/app/create/NeedLogToFileTask;' 'init'
check 'Lcom/netease/cloudmusic/process/task/app/create/NBLogTask;' 'init'

# PV-03 启动黑名单
check 'Lcom/netease/cloudmusic/core/startup/StartupTask;' 'needExecute'

# PV-04 APM 上报
check 'Lcom/netease/cloudmusic/core/statistic/StatisticImpl;' 'logApm'
check 'Lcom/netease/cloudmusic/core/statistic/StatisticImpl;' 'forceApmUpload'

# PV-05 统计观测
check 'Lcom/netease/cloudmusic/core/statistic/StatisticImpl;' 'log'
check 'Lcom/netease/cloudmusic/core/statistic/StatisticImpl;' 'logJSON'
check 'Lcom/netease/cloudmusic/statistic/monitor/LogMonitorConfigManager;' 'b'
check 'Lcom/netease/cloudmusic/statistic/monitor/LogMonitorConfigManager;' 'c'
check 'Lcom/netease/cloudmusic/statistic/monitor/LogMonitorConfigManager;' 'd'
check 'Lcom/netease/cloudmusic/statistic/monitor/LogMonitorConfigManager;' 'e'

echo "--------------------------------------------------------------------------------"
echo "RESULT: OK=$PASS GONE=$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "GONE mounts:"
  printf "$FAILED_LIST"
  exit 1
fi
