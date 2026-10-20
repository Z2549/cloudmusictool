# 网易云音乐 9.6.08 二期可新增 Hook 点扫描报告

> 目标 APK：`E:\download\base(2).apk`（只读，未做任何修改）
> 工具：`E:\tools\dexsql\dexsql.exe`（SQL over DEX）
> 扫描时间：2026-10-20
> 范围：仅“提升体验”类 hook，不含 VIP/付费/版权/地区、设备指纹/风控绕过、破解下载。

---

## 0. 验证口径（重要）

`methods` 表同时包含 **方法定义** 与 **跨 dex 的方法引用**，单看 `methods` 只能证明“这个名字在 DEX 里出现过”，不能证明有实现。本报告采用三级验证：

1. **类存在**：`SELECT ... FROM classes WHERE descriptor='...'`，确认 `class_def_idx` 与 `file_id`。
2. **方法有实现**：`class_data_methods` 中存在对应 `method_idx`，或直接 `dexsql decode` 成功。
3. **语义可信**：对关键 gate 方法做了 `dexsql decode` 反汇编，报告里标注“已反编译确认”；未反编译的标注“按命名/调用路径推断”。

已反编译确认语义的方法（6 个）：

| 类#方法 | 反编译结论 |
|---|---|
| `com.netease.cloudmusic.activity.MainActivity$k#handleOnBackPressed()` | 无 Fragment 栈时执行 `moveTaskToBack(true)`，**没有退出确认框** |
| `com.netease.cloudmusic.utils.h2#d(Context)` | 就是 `ClipboardUtils.getPrimaryClipDataFirstItem`，全局剪贴板读取入口 |
| `com.netease.cloudmusic.meta.SearchGuide#isValid()` | 直接委托 `SearchGuideExtKt.isRealValid()`，是搜索热词引导的总开关 |
| `com.netease.cloudmusic.music.biz.appwidgets.view.WidgetAddGuideDialogHelper$a#a()` | 读取 AB 实验 `ABTEST_WIDGET_ADD_GUIDE_DIALOG == "t1"`，是小组件引导弹窗的 AB 门 |
| `com.netease.cloudmusic.process.task.main.create.AdColdBootEntranceTask#init()` | 在 MainActivity 里找 `LoadingFragment` 并触发冷启动广告入口逻辑 |
| `com.netease.cloudmusic.music.biz.search.start.rnview.RNSearchAdViewManger#newStyle/oldStyle` | 搜索起始页广告的两种渲染样式，均为实例方法 |

> 类名里的 `$`（内部类）在 SQL 里必须用 `LIKE '%...$...%'` 或 `instr()`，不能直接 `=` 之外的模糊匹配；本报告给出的 `类全名#方法` 已按 JVM 二进制名书写。

---

## 1. 启动 / 退出

### S-01 启动任务“延迟/降级”编排（不是简单跳过）

- **用户价值**：把非首屏必需的重任务从主线程/启动阶段挪走，冷启动更快、首帧更稳；比 PV-03 的“黑名单直接不执行”更安全（保留功能，只改执行时机）。
- **hook 目标**：
  - `com.netease.cloudmusic.core.startup.StartupTask#runOnUIThread()Z`
  - `com.netease.cloudmusic.core.startup.StartupTask#runOnStage()I`
  - `com.netease.cloudmusic.core.startup.StartupTask#priority()I`
  - `com.netease.cloudmusic.core.startup.StartupTask#degradeInit()V`
- **建议默认值**：关；配置粒度 = 按任务名（`taskName()` / `getClass().getSimpleName()`）分组，提供“保持 / 降优先级 / 移到后台阶段 / 跳过”四档。
- **风险**：中。改 `runOnStage` 可能破坏任务依赖顺序；建议只对白名单任务生效。
- **与现有功能重叠**：与 **PV-03 启动任务黑名单** 同属 `StartupTask` 体系，但 PV-03 只 hook 了 `needExecute()` 做“跳过”，本项是“延迟/降级”，属于能力扩展，不是重复实现。
- **证据**：
  ```
  SELECT name,descriptor FROM methods
  WHERE class_descriptor='Lcom/netease/cloudmusic/core/startup/StartupTask;'
    AND name IN ('runOnUIThread','runOnStage','priority','degradeInit','needExecute');
  → runOnUIThread()Z / runOnStage()I / priority()I / degradeInit()V / needExecute()Z
  ```

### S-02 冷启动预加载任务裁剪（可配置任务清单）

- **用户价值**：9.6.08 的启动任务多达 200+ 个具名任务，其中一批是“为后续页面预热”的纯浪费项；裁掉后冷启动 IO/CPU 明显下降。
- **hook 目标**（均为 `StartupTask` 子类，`init()V`）：
  - `com.netease.cloudmusic.process.task.app.attach.NativePreloadTask#init()V`
  - `com.netease.cloudmusic.process.task.app.attach.CoronaPreTask#init()V`（风险偏高，Corona 是运行时框架）
  - `com.netease.cloudmusic.process.task.app.create.RNInitTask#init()V`
  - `com.netease.cloudmusic.process.task.app.create.LiveInitWithPermissionTask#init()V`
  - `com.netease.cloudmusic.process.task.main.create.APreloadRecommendViewTask#init()V`
  - `com.netease.cloudmusic.process.task.main.createproxy.WebCacheStartLoadInitOtherProcessTask#init()V`
  - `com.netease.cloudmusic.process.task.main.createproxy.PodcastRewardAdTask#init()V`
- **建议默认值**：关；默认只勾选 `APreloadRecommendViewTask`、`WebCacheStartLoadInitOtherProcessTask`、`PodcastRewardAdTask` 三个“纯预热/推广”任务。
- **风险**：中。`RNInitTask`、`LiveInitWithPermissionTask` 跳过会导致对应页面首次进入变慢或白屏，必须做成多选。
- **与现有功能重叠**：与 PV-03 重叠（PV-03 默认黑名单只有 3 个任务）。建议直接把本清单作为 PV-03 的“推荐任务库”，而不是新开一套 hook。
- **证据**：
  ```
  SELECT descriptor FROM classes WHERE descriptor LIKE 'Lcom/netease/cloudmusic/process/task/%';
  → 200+ 个具名任务，含 NativePreloadTask / CoronaPreTask / RNInitTask /
    LiveInitWithPermissionTask / APreloadRecommendViewTask /
    WebCacheStartLoadInitOtherProcessTask / PodcastRewardAdTask ...

  SELECT name,descriptor FROM methods WHERE class_descriptor='...NativePreloadTask;';
  → init()V、initTask()V、includeProcess()Ljava/util/List;

  SELECT descriptor,superclass_descriptor FROM classes WHERE descriptor='...RNInitTask;';
  → superclass_descriptor = Lcom/netease/cloudmusic/core/startup/StartupTask;
  ```
- **补充**：`CoronaPreTask#init()V`、`NativePreloadTask#init()V`、`APreloadRecommendViewTask#init()V` 均已验证存在。

### S-03 双击返回退出（可选增强）

- **用户价值**：当前返回键在首页直接 `moveTaskToBack(true)`（退到后台，不退出），部分用户希望“双击返回真正退出”。
- **hook 目标**：`com.netease.cloudmusic.activity.MainActivity$k#handleOnBackPressed()V`
- **建议默认值**：关；粒度 = 全局开关 + 双击间隔（默认 2000ms）。
- **风险**：中。会改变系统返回语义，且 `MainActivity` 是启动主入口，需处理多进程/多 Activity 场景。
- **与现有功能重叠**：无。
- **证据（已反编译）**：
  ```
  dexsql decode --method 'Lcom/netease/cloudmusic/activity/MainActivity$k;->handleOnBackPressed()V'
  → getBackStackEntryCount()==0 时 invoke-virtual {v0, v1}, Landroid/app/Activity;->moveTaskToBack(Z)Z
  ```

### S-04 冷启动白屏

- **结论**：9.6.08 里 `LoadingActivity#setWindowBackground()` 已是空实现，`MainActivity#getWindowBackgroundDrawable()` 返回主题色 `ColorDrawable`；白屏主要来自启动主题，不是某个可安全 hook 的业务方法。**不建议**在二期硬改窗口背景（会与深色/自定义主题冲突）。若一定要做，只能改 `MainActivity#getWindowBackgroundDrawable()` 返回黑色，收益低、风险中，列入 P2 观察。

### S-05 后台保活（省电向）

- **hook 目标**：`com.netease.cloudmusic.module.player.keepalive.AudioKeepAliveConfig#getNormalKeepAlive()Z`
- **用户价值**：关闭“静音音频保活”，降低待机耗电。
- **风险**：中高。可能导致后台播放被系统杀，**不建议默认开启**，仅作为“省电模式”子选项。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='...AudioKeepAliveConfig;'` → `getNormalKeepAlive()Z`、`getDlnaKeepAlive()Z`、`getUsbKeepAlive()Z`。

---

## 2. 播放器页

### P-01 关闭播放页“评论气泡/弹幕”

- **用户价值**：播放页上飘过的评论气泡是最干扰听歌的 UI 之一；关闭后播放页显著干净。
- **hook 目标**：`com.netease.cloudmusic.ui.hint.PlayerCommentPopView#renderCommentListAndShow(Ljava/util/List;)V`（no-op）
- **建议默认值**：开（关闭气泡）；粒度 = 全局开关。
- **风险**：低。只影响播放页评论气泡展示，不影响评论区。
- **重叠**：无（AD-05 是评论区广告，不是播放页气泡）。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/ui/hint/PlayerCommentPopView;';
  → renderCommentListAndShow(Ljava/util/List;)V、show()V、hide()V、isCommentPopViewShowing()Z
  ```

### P-02 关闭播放页“推荐歌曲”提示

- **用户价值**：播放页底部/中部弹出的“为你推荐”提示属于运营位，关闭后减少误触。
- **hook 目标**：`com.netease.cloudmusic.module.hint.view.PlayerRecommendSongHintView#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。标准 HintView 门面，跳过展示不影响播放。
- **重叠**：无（现有 UI-07 只处理自动播放/挂件）。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...PlayerRecommendSongHintView;';
  → maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;...r;)V
  ```

### P-03 关闭播放卡顿/网络错误弹窗

- **用户价值**：弱网时频繁弹“播放卡顿/网络异常”对话框，打断听歌；关闭后只在通知栏提示。
- **hook 目标**：
  - `com.netease.cloudmusic.ui.PlayerJankDialogHelper#showPlayJankNetErrorDialog(Landroid/content/Context;)V`
  - `com.netease.cloudmusic.ui.PlayerJankDialogHelper#showNMCacheIOErrorDialog(Landroid/content/Context;)V`
- **建议默认值**：开（不弹窗）；粒度 = 两个方法分别开关。
- **风险**：低。只抑制弹窗，不影响播放器重试逻辑。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/ui/PlayerJankDialogHelper;';
  → showPlayJankNetErrorDialog(Landroid/content/Context;)V
    showNMCacheIOErrorDialog(Landroid/content/Context;)V
    showAudioRenderRatioGuardErrorDialog(Landroid/content/Context;)V
  ```

### P-04 关闭动态封面（省流量/省电）

- **用户价值**：动态封面是短视频循环播放，费流量、费电、发热；关闭后只显示静态封面。
- **hook 目标**：`com.netease.cloudmusic.module.dynamiccover.DynamicCoverView#s(ZZ)V`（no-op）
- **建议默认值**：关；粒度 = 全局开关 + “仅 Wi-Fi 下允许”。
- **风险**：低。`s(ZZ)` 是播放/暂停控制，反编译可见它对所有子 View 调用 `s(ZZ)`。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...DynamicCoverView;';
  → s(ZZ)V
  dexsql decode ... DynamicCoverViewContainer;->e(Z)V
  → 遍历子 View 调用 DynamicCoverView.s(ZZ)V
  ```

### P-05 播放器手势

- **结论**：主手势入口在 `PlayerActivity#onDoubleTap(Landroid/view/View;)V`（双击红心），其余滑动/音量手势在混淆的 `PlayerActivity` 内部方法中，缺少稳定语义锚点。二期不建议改手势（易与官方版本变化冲突），仅保留观察。

---

## 3. 歌词

### L-01 关闭歌词页底部推广 Hint

- **用户价值**：歌词页底部会弹出运营/推广类 Hint（“歌词由 XX 提供”“去 K 歌”等），关闭后歌词页更纯净。
- **hook 目标**：`com.netease.cloudmusic.module.hint.view.LyricsBottomHintView#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。仅影响歌词页底部 Hint。
- **重叠**：无（现有 HintHooks 只覆盖底部 Tab/推送/直播/评分四类）。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...LyricsBottomHintView;';
  → maybeDisplayHint(...)V、render(String,String)V、renderHint(Hint)V
  ```

### L-02 关闭歌词“选项提示”气泡

- **用户价值**：首次进入歌词页/横屏时会弹出“长按调歌词/滑动切换”的提示，关闭后不打断。
- **hook 目标**：`com.netease.cloudmusic.ui.LyricViewContainer#checkAndShowLrcOptionHint()V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/ui/LyricViewContainer;';
  → checkAndShowLrcOptionHint()V、hideLrcOptionHint()V
  ```

### L-03 关闭“歌词视频”推广弹窗

- **用户价值**：点击歌词/分享时弹出的“制作歌词视频”引导属于推广，关闭后减少打扰。
- **hook 目标**：`com.netease.cloudmusic.fragment.LyricVideoTipsDialog#oc(Landroidx/fragment/app/FragmentActivity;Lcom/netease/cloudmusic/meta/LyricDialogData;)V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...LyricVideoTipsDialog;';
  → oc(Landroidx/fragment/app/FragmentActivity;Lcom/netease/cloudmusic/meta/LyricDialogData;)V
  ```

### L-04 桌面歌词权限弹窗（观察）

- **hook 目标**：`com.netease.cloudmusic.activity.FloatLyricPermissionDialogActivity#onCreate(Landroid/os/Bundle;)V`
- **说明**：只在用户主动开启桌面歌词时触发，不是“骚扰型”弹窗；关闭它会让功能不可用。**不建议**作为默认项，列入 P2。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='...FloatLyricPermissionDialogActivity;'` → `onCreate(Bundle)V`。

### L-05 歌词滚动性能 / BI 日志

- **hook 目标**：`com.netease.cloudmusic.ui.LyricViewContainer#initNEBILog()V`（no-op）
- **用户价值**：减少歌词页 BI 埋点初始化开销。
- **风险**：低-中（会影响歌词相关埋点上报，PV-05 已覆盖统计观测，功能重叠）。
- **结论**：归入性能项，P2。

---

## 4. 评论区

### C-01 隐藏评论列表中的“直播推荐”卡

- **用户价值**：评论区中部插入的直播间推荐卡与听歌无关，隐藏后评论区更纯粹。
- **hook 目标**：`com.netease.cloudmusic.module.comment.CommentLiveViewHolder#onBindViewHolder(Ljava/lang/Object;II)V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：中。ViewHolder 不绑定会留下空白 item，最好同时 hook 数据源过滤；若只做 no-op，需配合 `itemView.setVisibility(GONE)` / 高度 0。
- **重叠**：无（AD-05 只处理 `CommentAd` / `SpecialComment` / `CommentVipRcmdAdapter`，`CommentLiveRcmd` 是直播推广，未被覆盖）。
- **证据**：
  ```
  SELECT method_class,method_name FROM method_used_types WHERE type_name LIKE '%CommentLiveRcmd%';
  → Lcom/netease/cloudmusic/module/comment/CommentLiveViewHolder;->onBindViewHolder(Ljava/lang/Object;II)V
    Lcom/netease/cloudmusic/adapter/CommentAdapter$s;-><init>(...)
  ```

### C-02 评论引导/评论气泡频率

- **结论**：`CommentGuideFrequencyConfig` 的 4 个 getter 只被自己的 Moshi Adapter 调用（`method_calls` 证据），运行时并不直接读 getter，**hook getter 无效**；真正展示走 `PlayerCommentPopView`（见 P-01）和服务端 `commentHint` 字符串。二期不建议新增独立 hook，直接复用 P-01。

### C-03 楼中楼 / 评论折叠 / 评论图片

- **结论**：9.6.08 中这些是核心阅读功能，没有“推广/打扰”属性；未发现可安全关闭的独立 gate。若做“默认展开楼中楼”需要改 Adapter 数据展开逻辑，风险高、收益低，**不建议**。

---

## 5. 搜索

### Q-01 关闭搜索框“热词轮播”引导

- **用户价值**：首页/搜索页搜索框里不断轮播的运营热词（带图、带跳转）是典型推广位，关闭后搜索框回归纯输入。
- **hook 目标**：`com.netease.cloudmusic.meta.SearchGuide#isValid()Z` → `false`
- **建议默认值**：开；粒度 = 全局开关（可再细分“首页搜索框 / 搜索页搜索框”）。
- **风险**：低。`isValid()` 只做有效性判断，返回 false 后调用方不会渲染引导；已反编译确认它委托 `SearchGuideExtKt.isRealValid()`。
- **重叠**：无。
- **证据（已反编译）**：
  ```
  dexsql decode --file-id 7 --method 'Lcom/netease/cloudmusic/meta/SearchGuide;->isValid()Z'
  → invoke-static {v1}, Lcom/netease/cloudmusic/meta/SearchGuideExtKt;->isRealValid(...)Z
  ```

### Q-02 关闭搜索起始页广告位

- **用户价值**：搜索起始页（热搜页）顶部的广告 Banner 与搜索结果无关，关闭后首屏更快更干净。
- **hook 目标**：
  - `com.netease.cloudmusic.music.biz.search.start.rnview.RNSearchAdViewManger#newStyle(Lcom/netease/cloudmusic/music/biz/search/start/rnview/RNSearchAdView;Lcom/netease/cloudmusic/meta/Ad;ILjava/lang/String;)V`
  - `com.netease.cloudmusic.music.biz.search.start.rnview.RNSearchAdViewManger#oldStyle(...)V`
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。两个方法只负责把 Ad 数据渲染到 RN 视图，no-op 后视图为空。
- **重叠**：无（现有 AD-04 是首页信息流，不含搜索页）。
- **证据**：
  ```
  SELECT class_descriptor,name,descriptor FROM methods
  WHERE class_descriptor='...RNSearchAdViewManger;' AND name IN ('newStyle','oldStyle','hideAllViews');
  → newStyle(RNSearchAdView;Ad;ILjava/lang/String;)V
    oldStyle(RNSearchAdView;Ad;ILjava/lang/String;)V
    hideAllViews()V        ← 官方自带的“隐藏全部广告视图”方法
  ```

### Q-03 关闭搜索结果内广告

- **用户价值**：搜索结果中混排的大图/中图广告（`SearchAdItem*View`）与搜索意图无关。
- **hook 目标**：
  - `com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemLargeView#e(Lcom/netease/cloudmusic/meta/Ad;ILkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V`
  - `com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemMediumView#e(...)V`
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：中。只 no-op 可能留下空白占位；建议同时把 `itemView` 设为 `GONE` 或在 Adapter 层过滤。
- **重叠**：无。
- **证据**：
  ```
  SELECT class_descriptor,name,descriptor FROM methods WHERE class_descriptor='...SearchAdItemMediumView;' AND name='e';
  → e(Lcom/netease/cloudmusic/meta/Ad;ILkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V
  （LargeView 同名同签名）
  ```

### Q-04 搜索建议 / 搜索历史

- **结论**：搜索建议由 `SearchSuggestFragment#loadData(Bundle)` 驱动，搜索历史是本地数据，二者都不是推广位；未发现“推广建议”独立类。**二期不做**，避免破坏输入体验。

---

## 6. 下载

> 结论先行：9.6.08 的“我的-下载”页没有独立广告位/推广弹窗，主要推广形态是“本地/下载列表中的播客推荐卡”和“外部下载 App 提示”。

### D-01 隐藏下载/本地列表中的播客推荐卡

- **用户价值**：下载列表里插入的“推荐播客”卡片与本地文件无关。
- **hook 目标**：`com.netease.cloudmusic.music.biz.localmusic.adapter.viewholder.RcmdPodcastViewHolder#onBindViewHolder(Ljava/lang/Object;II)V`（no-op + 高度 0）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：中（需要处理占位）。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...RcmdPodcastViewHolder;';
  → onBindViewHolder(Ljava/lang/Object;II)V、R0(...)、S0(...)
  ```

### D-02 外部下载提示页

- **hook 目标**：
  - `com.netease.cloudmusic.activity.DownloadPromptActivity#a(Landroid/content/Context;)V`
  - `com.netease.cloudmusic.activity.DownloadApkPromptActivity#a(Landroid/content/Context;Landroid/content/Intent;)V`
- **说明**：这是 WebView/深链里“下载网易云 App”的提示页，不是歌曲下载。**不建议**默认关闭（属于正常外链兜底），P2 观察。

### D-03 下载限速

- **hook 目标**：`com.netease.cloudmusic.music.biz.setting.activity.ThrottleSettingActivity#e7(Z)V`
- **说明**：这是“限速设置”页面的开关回调，不是限速提示弹窗；**不存在**“下载限速提示”可关。不要把它误当成“解除限速”（属于明确排除项）。

---

## 7. 通知栏 / 媒体通知

### N-01 关闭推送引导弹窗

- **用户价值**：首页会弹“开启推送通知”的居中弹窗/底部弹窗，关闭后不再骚扰。
- **hook 目标**：
  - `com.netease.cloudmusic.main.hint.push.PushGuideCenterDialogFragment#onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;`
  - `com.netease.cloudmusic.main.hint.push.PushGuideBottomSheetFragment#onCreateView(...)Landroid/view/View;`
- **建议默认值**：开（不展示）；粒度 = 两个弹窗分别开关。
- **风险**：低。返回 `null` 或直接 `dismiss()` 即可；注意 Fragment 返回 null 可能触发 NPE，建议 hook `onCreate` 后 `dismissAllowingStateLoss()` 或 hook `loadData`。
- **重叠**：现有 HintHooks 只覆盖 `PushGuideHintView`（气泡），**未覆盖**这两个 Dialog/BottomSheet。
- **证据**：
  ```
  SELECT class_descriptor,name,descriptor FROM methods
  WHERE class_descriptor='...PushGuideCenterDialogFragment;' AND name='onCreateView';
  → onCreateView(LayoutInflater;ViewGroup;Bundle;)Landroid/view/View;
  （PushGuideBottomSheetFragment 同名同签名）
  ```

### N-02 关闭通知权限说明弹窗

- **hook 目标**：`com.netease.play.notification.NotificationPermissionDialog#onCreateViewInner(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;`
- **用户价值**：Android 13 通知权限被拒后反复弹说明框，可关闭。
- **建议默认值**：关；粒度 = 全局开关。
- **风险**：低-中（可能影响推送到达率，属于用户主动选择）。
- **重叠**：无。
- **证据**：`SELECT ... WHERE class_descriptor='Lcom/netease/play/notification/NotificationPermissionDialog;'` → `onCreateViewInner(...)Landroid/view/View;`。

### N-03 通知样式 / 常驻通知

- **hook 目标**：`com.netease.cloudmusic.service.MainProcessPlayService#changeNotificationStyle()V`
- **说明**：这是“切换通知样式”的入口，不是“常驻通知开关”。9.6.08 的媒体通知构建在混淆代码/系统 MediaSession 中，未找到稳定的“常驻/非常驻”gate。**二期不建议**改通知样式（易与厂商 ROM 冲突），P2 观察。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='...MainProcessPlayService;'` → `changeNotificationStyle()V`。

### N-04 通知初始化任务

- **hook 目标**：`com.netease.cloudmusic.process.task.play.PlayerNotificationTask#init()V`
- **说明**：可作为 S-02 启动任务清单的一项（延迟初始化），不是独立功能。

---

## 8. 桌面小组件

### W-01 关闭“添加小组件”引导弹窗

- **用户价值**：首页/桌面会弹“添加播放器小组件”的引导，关闭后不再推广。
- **hook 目标**：`com.netease.cloudmusic.music.biz.appwidgets.view.WidgetAddGuideDialogHelper$a#a()Z` → `false`
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低。已反编译确认是 AB 实验门（`ABTEST_WIDGET_ADD_GUIDE_DIALOG == "t1"`），返回 false 等价于“不在实验组”。
- **重叠**：无。
- **证据（已反编译）**：
  ```
  dexsql decode --method 'Lcom/netease/cloudmusic/music/biz/appwidgets/view/WidgetAddGuideDialogHelper$a;->a()Z'
  → checkBelongToWhichGroup("ABTEST_WIDGET_ADD_GUIDE_DIALOG") == "t1"
  ```
- **补充**：实际展示方法为 `WidgetAddGuideDialogHelper$a#f(Context,String,Function0)V` / `g(Context,String,int,Function0)V`，可作为二次兜底。

### W-02 小组件刷新频率

- **hook 目标**：`com.netease.cloudmusic.music.biz.appwidgets.data.meta.PlayerWidgetUpdateHelper#getPlayRemoteViews(Landroid/content/Context;)Ljava/util/List;`
- **说明**：这是刷新内容构建，不是频率控制；降低频率需要改 `AppWidgetProvider` 的 `updatePeriodMillis`（在 Manifest/XML 中），Hook 侧收益低。**P2 观察**。

---

## 9. 设置项

> 结论：设置页条目由服务端/DSL + 混淆代码动态生成，**没有稳定的“条目级”hook 点**。直接按文案删条目需要遍历 View 树，风险中高，不建议二期做。

### T-01 隐藏第三方推送 SDK 配置页入口

- **hook 目标**：`com.netease.cloudmusic.music.biz.setting.activity.GetuiConfigActivity#onCreate(Landroid/os/Bundle;)V`（`finish()` 或隐藏入口）
- **说明**：该页是“个推”调试/配置页，普通用户不应看到；若确实出现在设置里，可隐藏。
- **风险**：低（页面级）；但**入口本身**可能来自远程配置，Hook 页面只能“点进去再退出”，体验不好。
- **证据**：`SELECT ... WHERE class_descriptor='...GetuiConfigActivity;'` → `onCreate(Bundle)V`。

### T-02 个性化广告标签设置

- **hook 目标**：`com.netease.cloudmusic.music.biz.setting.activity.AdRecommentTagSettingActivity#onCreate(Landroid/os/Bundle;)V`
- **说明**：该页管理“广告推荐标签”，可自动关闭全部标签（隐私向）。但现有 AD-01~08 已经屏蔽广告，收益有限，P2。

### T-03 通知设置页

- **hook 目标**：`com.netease.cloudmusic.music.biz.setting.activity.NotifySettingActivity`（混淆方法，无稳定语义锚点）
- **结论**：不建议 hook，交给系统通知权限管理。

---

## 10. 分享 / 评分 / 反馈

### R-01 评分/好评弹窗

- **hook 目标**：`com.netease.cloudmusic.module.hint.view.ScoreHintDialogView#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`
- **重叠**：**与现有 HintHooks 重叠**（现有代码已引用 `ScoreHintDialogView`）。二期不需要重复实现；若要增强，只补“永不展示”的配置项即可。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='...ScoreHintDialogView;'` → `maybeDisplayHint(...)V`。

### R-02 反馈诊断 / 分享初始化启动任务

- **hook 目标**：
  - `com.netease.cloudmusic.process.task.app.create.FeedbackDiagnoseTask#init()V`
  - `com.netease.cloudmusic.process.task.app.create.ShareTask#init()V`
- **说明**：两者都是启动任务，适合并入 S-02 的“可延迟任务清单”（不是新功能）。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...FeedbackDiagnoseTask;';
  → init()V
  SELECT name,descriptor FROM methods WHERE class_descriptor='...ShareTask;';
  → init()V、cropImageUrl()Z
  ```

### R-03 分享推广

- **结论**：未发现独立的“分享推广弹窗”类；分享面板是系统/自绘 BottomSheet，属于核心功能。**不建议**改。

---

## 11. 青少年模式 / 模式切换

- **结论**：9.6.08 只有 3 个青少年模式类：
  ```
  Lcom/netease/cloudmusic/module/youthmode/YouthModePageAdapterUriHandler;
  Lcom/netease/cloudmusic/module/youthmode/YouthModePageUriHandler;
  Lcom/netease/cloudmusic/module/youthmode/YouthModeTimeLockActivity;
  ```
  `YouthModeTimeLockActivity#onCreate(Bundle)V` 是强制时间锁页面；`YouthModePageUriHandler#shouldHandle(UriRequest)Z` 是路由分发。
- **建议**：**明确不做**。青少年模式属于合规功能，绕过或降低弹窗频率都不应实现。
- **“模式切换弹窗频率”**：未找到独立频率控制类（没有 `YouthModeDialogConfig` 之类），无法做“只降频不绕过”。

---

## 12. 直播 / 云村动态 / 播客 / 听书 / AI

### V-01 关闭播客 FM 自动播放引导

- **用户价值**：进入播客页时底部弹出的“开启 FM 自动播放”引导条，关闭后不再打扰。
- **hook 目标**：`com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide()Z` → `false`
- **建议默认值**：开；粒度 = FM / 听书分别开关。
- **风险**：低。命名即语义（“是否到了展示 FM 自动播放引导的时间”），只影响引导条。
- **重叠**：无（UI-07 处理的是“自动播放行为”，不是引导条）。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...PodcastBottomGuideManager;';
  → isTimeToShowFMAutoPlayGuide()Z
    isTimeToShowVoiceBookGuide()Z
    getFMAutoPlayGuideNotification(Context;)LStandardBottomNotification;
    getVoiceBookGuideNotification(RightsPopVO;Context;)LStandardBottomNotification;
  ```

### V-02 关闭听书权益引导

- **hook 目标**：`com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager#isTimeToShowVoiceBookGuide()Z` → `false`
- **说明**：同上，针对“听书权益”引导条。
- **风险**：低。
- **证据**：同上。

### V-03 关闭 AI DJ 切换引导

- **用户价值**：播放页/歌单页会弹“切换到 AI DJ/私人 DJ”的引导气泡，关闭后不再推广 AI 功能。
- **hook 目标**：
  - `com.netease.cloudmusic.ui.hint.aidj.PlayerAIDJSwitchGuideView#showGuide(Ljava/lang/String;Ljava/lang/String;J)V`（no-op）
  - `com.netease.cloudmusic.hint.aidj.FMAIDJSwitchView#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`（no-op）
- **建议默认值**：开；粒度 = 播放页 / FM 分别开关。
- **风险**：低。两者都是“引导展示”方法，不影响 AI DJ 本身的开关。
- **重叠**：无。
- **证据**：
  ```
  SELECT class_descriptor,name,descriptor FROM methods
  WHERE class_descriptor IN ('...PlayerAIDJSwitchGuideView;','...FMAIDJSwitchView;');
  → showGuide(String;String;J)V
    maybeDisplayHint(AbsHintContainer;a;r;)V
  ```

### V-04 隐藏播客首页 AI Radar 推广位

- **hook 目标**：`com.netease.cloudmusic.music.biz.voice.homepage.recommend.rcmd.airadar.AiRadarPlacementViewHolder#D1(Lcom/netease/cloudmusic/music/biz/voice/homepage/recommend/rcmd/airadar/AiRadarPlacementBlock;IIZ)V`（no-op）
- **建议默认值**：关（内容型推荐，不是纯广告）；P2。
- **风险**：中（会留空白 item）。
- **证据**：`SELECT ... WHERE class_descriptor='...AiRadarPlacementViewHolder;'` → `D1(AiRadarPlacementBlock;IIZ)V`。

### V-05 隐藏播客播放页“推荐”Tab

- **hook 目标**：`com.netease.cloudmusic.music.biz.voice.player.rcmd.VoicePlayerRcmdFragment#loadData(Landroid/os/Bundle;)V`（no-op）
- **建议默认值**：关；P2。
- **风险**：中（Fragment 空数据可能显示空列表）。
- **证据**：`SELECT ... WHERE class_descriptor='...VoicePlayerRcmdFragment;'` → `loadData(Bundle)V`、`onCreateView(...)`。

### V-06 关闭直播间推广位

- **用户价值**：直播间内“游戏推广/专辑推广/电台推广”卡片是典型商业位，关闭后直播间更干净。
- **hook 目标**：`com.netease.play.livepage.promotion.LivePromotion#listFromJson(Lorg/json/JSONObject;)Ljava/util/List;` → 返回空列表
- **建议默认值**：关（直播推广较克制）；P1 可选。
- **风险**：低-中。返回空列表即可让上层不渲染推广；需确认调用方对空列表有兜底。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/play/livepage/promotion/LivePromotion;';
  → listFromJson(Lorg/json/JSONObject;)Ljava/util/List;
  ```

### V-07 关闭云村“发布/搜索”引导气泡

- **hook 目标**：
  - `com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui.NotesPublishGuideHint#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`
  - `com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui.NotesSearchGuideHint#maybeDisplayHint(...)V`
- **建议默认值**：开；粒度 = 发布 / 搜索分别开关。
- **风险**：低。
- **重叠**：无。
- **证据**：
  ```
  SELECT ... WHERE class_descriptor='...NotesPublishGuideHint;';
  → maybeDisplayHint(AbsHintContainer;a;r;)V
  ```

---

## 13. 广告新形态（激励视频 / 插屏 / 原生 / SDK 初始化）

### A-01 关闭播放页激励视频浮层

- **用户价值**：播放页“看广告得权益/免费听”浮层属于激励视频形态，关闭后不遮挡播放控件。
- **hook 目标**：`com.netease.cloudmusic.module.hint.view.PlayerIncentiveAdView#maybeDisplayHint(Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;Lcom/netease/cloudmusic/module/hint/view/a;Lcom/netease/cloudmusic/module/hint/view/r;)V`（no-op）
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：中。关闭后用户无法通过看广告获取权益（但权益本身属于付费/会员体系，二期不碰），只影响“入口展示”。
- **重叠**：与 AD-06 播放页广告部分重叠，但现有实现针对 `PlayerAdJarDelegateImpl` / `AdPlayerFullScreenHelper`，**未覆盖** `PlayerIncentiveAdView`。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...PlayerIncentiveAdView;';
  → maybeDisplayHint(...)V、showCountDownStyle()V、showRightsGainedStyle()V
  ```

### A-02 关闭 DSL 激励视频权益请求

- **hook 目标**：`com.netease.cloudmusic.ui.ad.dslview.common.utils.AdDSLIncentiveVideoRightsHelper#requestRightsGainInner(Lcom/netease/cloudmusic/meta/Ad;)V`（no-op）
- **说明**：DSL 广告容器里的激励视频权益请求；关闭后不会触发广告播放。
- **风险**：中（可能影响 DSL 广告容器的状态机）。
- **重叠**：与 AD-08 广告上报/外跳部分重叠。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='...AdDSLIncentiveVideoRightsHelper;'` → `requestRightsGainInner(Ad)V`。

### A-03 冷启动广告任务裁剪

- **hook 目标**：
  - `com.netease.cloudmusic.process.task.main.create.AdColdBootEntranceTask#init()V`
  - `com.netease.cloudmusic.process.task.main.createproxy.PodcastRewardAdTask#init()V`
- **说明**：`AdColdBootEntranceTask` 已反编译确认与 `LoadingFragment` 冷启动广告入口相关；`PodcastRewardAdTask` 从命名看是播客激励广告预加载。二者都是**开屏以外的广告初始化**，可并入 S-02。
- **风险**：中。`AdColdBootEntranceTask` 与开屏广告强相关，关掉可能与 AD-01 叠加产生状态不一致；建议只关 `PodcastRewardAdTask`。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='...AdColdBootEntranceTask;';
  → init()V、degradeInit()V
  SELECT name,descriptor FROM methods WHERE class_descriptor='...PodcastRewardAdTask;';
  → init()V
  ```

### A-04 插屏 / 原生广告

- **结论**：未发现独立 `Interstitial` / `NativeAd` 类；插屏与原生广告走 DSL 容器（`ui/ad/dslview`）。二期不建议对 DSL 容器做通用拦截（会误伤内容卡片），只做 A-01/A-02 两个具名入口。

### A-05 广告缓存

- **hook 目标**：`com.netease.cloudmusic.module.ad.cache.AdCacheSize#parse(Ljava/lang/String;)Lcom/netease/cloudmusic/module/ad/cache/AdCacheSize;`
- **说明**：可把广告图片/视频缓存上限调小，但广告已屏蔽，收益低，P2。

---

## 14. 权限与隐私

### X-01 禁止自动读取剪贴板

- **用户价值**：App 启动/回前台时会读取剪贴板做“口令/深链识别”，属于隐私敏感行为；关闭后不再读取。
- **hook 目标**：`com.netease.cloudmusic.utils.h2#d(Landroid/content/Context;)Landroid/content/ClipData$Item;` → `null`
- **建议默认值**：开；粒度 = 全局开关。
- **风险**：低-中。`h2` 是全局 ClipboardUtils，返回 null 后“识别剪贴板口令/自动填充”会失效；登录验证码输入走的是 `CodeInputViewV2#g`（直接调 `getPrimaryClip`），不受影响。
- **重叠**：无（PV-01 是 OAID，不涉及剪贴板）。
- **证据（已反编译）**：
  ```
  dexsql decode --file-id 16 --method 'Lcom/netease/cloudmusic/utils/h2;->d(Landroid/content/Context;)Landroid/content/ClipData$Item;'
  → 日志 tag "C502"，字符串 "com/netease/cloudmusic/utils/ClipboardUtils.class:getPrimaryClipDataFirstItem"
    → ClipboardManager.getPrimaryClip()
  ```

### X-02 关闭摇一摇广告/传感器监听

- **hook 目标**：
  - `com.netease.cloudmusic.module.ad.boot.LoadingAdShakeHelper#onCreate()V`（开屏广告摇一摇）
  - `com.netease.cloudmusic.module.ad.style.helper.SensorManagerHelperController#onCreate()V`（样式化广告摇一摇）
- **建议默认值**：开（不注册监听）；粒度 = 两类广告分别开关。
- **风险**：低（仅广告传感器）。注意 `LoadingAdShakeHelper` 与 AD-01 重叠，若 AD-01 已彻底屏蔽开屏，本项可只保留 `SensorManagerHelperController`。
- **证据**：
  ```
  SELECT method_class,method_name,callee FROM method_calls
  WHERE callee_name='registerListener' AND method_class LIKE 'Lcom/netease/cloudmusic/%';
  → LoadingAdShakeHelper#a → SensorManager.registerListener
    SensorManagerHelperController 相关类存在
  ```

### X-03 应用列表读取

- **hook 目标**：`Li5/b;->j(Landroid/content/Context;Ljava/util/List;)V`
- **说明**：全 APK 只有这一处 `getInstalledPackages` 调用，位于混淆类 `Li5/b`（第三方 SDK 特征），不是网易云主业务。**不建议**动第三方 SDK（可能影响推送/统计初始化），P2。
- **证据**：
  ```
  SELECT method_class,method_name,callee FROM method_calls WHERE callee_name='getInstalledPackages';
  → Li5/b#j → PackageManager.getInstalledPackages(I)
  ```

### X-04 非必要权限申请

- **结论**：权限申请分散在 `module/permission/fragment/*`（定位/日历/蓝牙/录音/文件），没有统一 gate；要“自动拒绝”只能 hook `Activity#requestPermissions`，影响面大、易被风控识别。**二期不做**。

---

## 15. 性能

### F-01 视图预加载缓存开关（低内存模式）

- **用户价值**：9.6.08 自带 `ViewPreloadManager`，会缓存 Tab 页 View 以加速切换；低端机可关闭以省内存。
- **hook 目标**：
  - `com.netease.cloudmusic.ui.ViewPreloadManager#setNoNeedToLoadViewCache(Z)V`
  - `com.netease.cloudmusic.ui.ViewPreloadManager#getNoNeedToLoadViewCache()Z`
- **建议默认值**：关（保持官方行为）；粒度 = 全局“低内存模式”开关。
- **风险**：低。这是官方自带的开关方法，直接调用即可。
- **重叠**：无。
- **证据**：
  ```
  SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/ui/ViewPreloadManager;';
  → setNoNeedToLoadViewCache(Z)V、getNoNeedToLoadViewCache()Z、
    setNoNeedToPreloadViewCache()V、clearViewCache()V
  ```

### F-02 图片加载降级

- **hook 目标**：`com.netease.cloudmusic.image.ImageImplV2#createBaseImageRequestBuilder(IILjava/lang/String;ZFZIZZFLandroid/graphics/RectF;Lcom/facebook/drawee/drawable/ScalingUtils$ScaleType;)Lcom/facebook/imagepipeline/request/ImageRequestBuilder;`
- **说明**：可在低内存/省流量模式下强制降采样。但参数复杂、返回 Builder 需重新构造，**风险中高**，P2。
- **证据**：`SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/image/ImageImplV2;'` → 上述签名。

### F-03 启动任务线程收敛

- **hook 目标**：`com.netease.cloudmusic.core.startup.StartupTask#runOnUIThread()Z`
- **说明**：与 S-01 同一个 hook，用于把白名单里的重任务强制移出主线程。
- **风险**：中。

### F-04 歌词 BI 日志

- **hook 目标**：`com.netease.cloudmusic.ui.LyricViewContainer#initNEBILog()V`（no-op）
- **说明**：减少歌词页埋点初始化；与 PV-05 统计观测重叠，P2。

---

## 16. 排序

### P0（低风险高收益，二期必做，10 个）

| # | 功能 | Hook 目标 | 默认 |
|---|---|---|---|
| 1 | 关闭播放页评论气泡 | `ui.hint.PlayerCommentPopView#renderCommentListAndShow(List)` | 开 |
| 2 | 关闭播放页推荐歌曲提示 | `module.hint.view.PlayerRecommendSongHintView#maybeDisplayHint(...)` | 开 |
| 3 | 关闭搜索框热词轮播 | `meta.SearchGuide#isValid()Z` → false | 开 |
| 4 | 关闭搜索起始页广告 | `...RNSearchAdViewManger#newStyle(...)` / `#oldStyle(...)` | 开 |
| 5 | 关闭播客 FM/听书引导条 | `PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide()Z` / `#isTimeToShowVoiceBookGuide()Z` | 开 |
| 6 | 关闭 AI DJ 切换引导 | `PlayerAIDJSwitchGuideView#showGuide(String,String,long)`；`FMAIDJSwitchView#maybeDisplayHint(...)` | 开 |
| 7 | 关闭小组件添加引导 | `WidgetAddGuideDialogHelper$a#a()Z` → false | 开 |
| 8 | 关闭歌词页底部推广 Hint | `LyricsBottomHintView#maybeDisplayHint(...)` | 开 |
| 9 | 禁止自动读剪贴板 | `utils.h2#d(Context)` → null | 开 |
| 10 | 关闭推送引导弹窗 | `PushGuideCenterDialogFragment#onCreateView(...)` / `PushGuideBottomSheetFragment#onCreateView(...)` | 开 |

### P1（值得做，10 个）

| # | 功能 | Hook 目标 | 风险 |
|---|---|---|---|
| 1 | 启动任务延迟/降级编排 | `StartupTask#runOnStage()I` / `#runOnUIThread()Z` / `#priority()I` | 中（扩展 PV-03） |
| 2 | 冷启动预加载任务裁剪 | `APreloadRecommendViewTask#init` / `WebCacheStartLoadInitOtherProcessTask#init` / `PodcastRewardAdTask#init` | 中 |
| 3 | 关闭搜索结果广告 | `SearchAdItemLargeView#e(Ad,int,Function1,Function1)` / `SearchAdItemMediumView#e(...)` | 中 |
| 4 | 隐藏评论直播推荐卡 | `CommentLiveViewHolder#onBindViewHolder(Object,int,int)` | 中 |
| 5 | 关闭直播间推广位 | `play.livepage.promotion.LivePromotion#listFromJson(JSONObject)` → 空列表 | 低-中 |
| 6 | 关闭云村发布/搜索引导气泡 | `NotesPublishGuideHint#maybeDisplayHint(...)` / `NotesSearchGuideHint#maybeDisplayHint(...)` | 低 |
| 7 | 关闭播放页激励视频浮层 | `PlayerIncentiveAdView#maybeDisplayHint(...)` | 中 |
| 8 | 关闭歌词选项提示 | `LyricViewContainer#checkAndShowLrcOptionHint()V` | 低 |
| 9 | 关闭播放卡顿/网络错误弹窗 | `PlayerJankDialogHelper#showPlayJankNetErrorDialog(Context)` / `#showNMCacheIOErrorDialog(Context)` | 低 |
| 10 | 关闭歌词视频推广弹窗 | `LyricVideoTipsDialog#oc(FragmentActivity,LyricDialogData)` | 低 |

### P2（观察，不进二期主线）

- 双击返回退出（`MainActivity$k#handleOnBackPressed`）——行为变更，需产品确认。
- 关闭动态封面（`DynamicCoverView#s(ZZ)`）——省流省电，但影响观感。
- 通知权限说明弹窗（`NotificationPermissionDialog#onCreateViewInner`）。
- 下载/本地列表播客推荐卡（`RcmdPodcastViewHolder#onBindViewHolder`）。
- 通知样式（`MainProcessPlayService#changeNotificationStyle`）。
- 小组件刷新（`PlayerWidgetUpdateHelper#getPlayRemoteViews`）。
- 设置页第三方 SDK 页（`GetuiConfigActivity`）、个性化广告标签（`AdRecommentTagSettingActivity`）。
- 摇一摇广告传感器（`LoadingAdShakeHelper#onCreate` / `SensorManagerHelperController#onCreate`）。
- 应用列表读取（`Li5/b#j`）。
- 低内存模式（`ViewPreloadManager#setNoNeedToLoadViewCache`）、图片降级（`ImageImplV2#createBaseImageRequestBuilder`）。
- 省电：`AudioKeepAliveConfig#getNormalKeepAlive()Z` → false。
- 歌词 BI 日志（`LyricViewContainer#initNEBILog`）。

---

## 17. 明确不建议做

1. **青少年模式**：`YouthModeTimeLockActivity`、`YouthModePage*UriHandler` 属于合规功能，绕过或降频都不做。
2. **VIP / 付费 / 版权 / 地区限制**：任何 `isVipQuality`、`SoundQualityTrialInfo`、`AuditionHint`、`LoginAndDrawNewUserVIPThenDownloadTSSongBottomSheet` 等都不碰。
3. **设备指纹 / 风控**：`AegisSdkInitTask`、`ExternalDeviceIdInitTask`、`GetYIdunTask`、`ThreadHookTask`、`ReflectionUnsealTask`、`NativeCrashTestTask` 一律不动。
4. **破解下载 / 解除限速**：`ThrottleSettingActivity` 只作为设置页观察，不做绕过。
5. **核心启动任务**：`CoreInitTask`、`NativeInitTask`、`MMKVTask`、`ServiceFacade*`、`WorkPathTask`、`CookiePreloadTask`、`NetworkTask`、`SessionTask` 等跳过会直接崩溃或登录态异常，禁止加入黑名单。
6. **`SearchActivity` / `SettingActivity` 的混淆方法**：方法名（`A8`/`B8`/`q8`…）无语义且随版本变化，不适合作为稳定 hook 点。
7. **DSL 广告容器的通用拦截**：`ui/ad/dslview/**` 同时承载内容卡片，通用 no-op 会误伤首页/搜索内容。

---

## 18. 最意外的发现

1. **官方自带搜索广告“总开关”**：`RNSearchAdViewManger#hideAllViews()V` 直接存在，说明搜索起始页广告本身有“全部隐藏”能力；配合 `newStyle/oldStyle` no-op 可以做到零残留。
2. **启动任务粒度极细**：`process/task/**` 下有 200+ 个具名 `StartupTask` 子类，且基类同时暴露 `runOnStage/runOnUIThread/priority/degradeInit/needExecute` 五个可 hook 维度——PV-03 目前只用了 `needExecute` 一个，二期还有 4 个维度可挖。
3. **剪贴板有统一入口**：`com.netease.cloudmusic.utils.h2` 就是全局 ClipboardUtils（反编译可见 `ClipboardUtils.class:getPrimaryClipDataFirstItem` 日志），一个方法即可覆盖全 App 的剪贴板读取。
4. **冷启动就预加载广告**：`AdColdBootEntranceTask`（已反编译确认与 `LoadingFragment` 联动）和 `PodcastRewardAdTask` 都在启动阶段跑，说明“开屏以外的广告初始化”确实存在独立入口。
5. **小组件引导是 AB 实验门**：`WidgetAddGuideDialogHelper$a#a()` 直接读 `ABTEST_WIDGET_ADD_GUIDE_DIALOG`，返回 false 即可稳定关闭，不需要改 UI。

---

## 附录：证据查询清单（可复现）

```bash
DEX=/e/tools/dexsql/dexsql.exe
APK="/e/download/base(2).apk"

# 1. 启动任务基类
$DEX query -i "$APK" -s "SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/core/startup/StartupTask;'"

# 2. 启动任务清单
$DEX query -i "$APK" -s "SELECT descriptor FROM classes WHERE descriptor LIKE 'Lcom/netease/cloudmusic/process/task/%'"

# 3. 返回键
$DEX decode -i "$APK" --method 'Lcom/netease/cloudmusic/activity/MainActivity$k;->handleOnBackPressed()V'

# 4. 播放页评论气泡
$DEX query -i "$APK" -s "SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/ui/hint/PlayerCommentPopView;'"

# 5. 搜索热词
$DEX decode -i "$APK" --file-id 7 --method 'Lcom/netease/cloudmusic/meta/SearchGuide;->isValid()Z'

# 6. 搜索广告
$DEX query -i "$APK" -s "SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/music/biz/search/start/rnview/RNSearchAdViewManger;'"

# 7. 剪贴板
$DEX decode -i "$APK" --file-id 16 --method 'Lcom/netease/cloudmusic/utils/h2;->d(Landroid/content/Context;)Landroid/content/ClipData$Item;'

# 8. 小组件引导
$DEX decode -i "$APK" --method 'Lcom/netease/cloudmusic/music/biz/appwidgets/view/WidgetAddGuideDialogHelper$a;->a()Z'

# 9. 播客引导
$DEX query -i "$APK" -s "SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/cloudmusic/music/biz/voice/home/PodcastBottomGuideManager;'"

# 10. 直播推广
$DEX query -i "$APK" -s "SELECT name,descriptor FROM methods WHERE class_descriptor='Lcom/netease/play/livepage/promotion/LivePromotion;'"
```

> 备注：`--file-id` 需以 `classes` 表中该类的 `file_id` 为准（本报告中 `SearchGuide` 为 7、`h2` 为 16）。多 dex 下同一方法名可能只存在于“引用表”，务必先用 `class_data_methods` 或 `decode` 确认有实现。
