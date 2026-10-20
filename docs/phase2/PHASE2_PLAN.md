# 云音乐工具箱 · 二期工程规划

- 版本基线：v1.0.0（versionCode 2）
- 目标宿主：网易云音乐 9.6.08（versionCode 9006008）
- 规划日期：2026-10-20
- 调研底稿：
  - `docs/phase2/project-audit.md`（工程收尾审计）
  - `docs/phase2/new-hook-points.md`（新增体验 hook 点，15 个模块）
  - `docs/phase2/abtest-research.md`（ABTest 体系，1555 行）
- 本规划只做方案，不改代码。

---

## 一、现状结论（一句话版）

一期“能跑”，但**可证明性不足**：14 个默认开启项里只有约一半有真机 hit 证据；DexKit 兜底实际从未工作；UI-02/UI-04 是半成品；ABTest 可以读、可以按白名单强开，但官方调试页在正式包不可用，必须自己 hook 查询入口。

### 1.1 一期遗留的硬问题

| 级别 | 问题 | 证据 |
|---|---|---|
| P0 | DexKit 兜底链路断裂：release 包 `extractNativeLibs=false`，代码从模块 native 目录 `System.load` 找不到 `libdexkit.so`；且多数 anchor 是类名/方法名，不是 dex 字符串字面量 | `project-audit.md` §6.5；本次已复核 APK manifest 与 `DexKitLocator.java:45-61` |
| P0 | UI-02 半成品：数据过滤/文本清空/TabView 隐藏/选择守卫四套代码都在，`installSelectGuard` 从未被调用；Feature blocked 后运行时零注册 | `project-audit.md` §2.1 |
| P0 | UI-04 空转：`drawer_items_filter` 可编辑可保存，但 `DrawerHooks` 是空方法、`Hooks` switch 无 `DRAWER` 分支 | `project-audit.md` §2.2 |
| P0 | Hook 健康度不可见：`HookState` 四个状态方法全是空实现；状态卡无法显示“装了没有、命中没有” | `project-audit.md` §6.1 |
| P1 | 文案与行为不一致：PV-05 实际有拦截、UI-06 没有“权限引导”、UI-03 没有“漫游/动态”、footer 仍写 Phase 0-5 | `project-audit.md` §4.2 |
| P1 | 热路径日志无限流：评论每条、MMKV 每次、resources 每次渲染都打 INFO | `project-audit.md` §6.4 |
| P1 | 无单测、R8 keep 过宽、反射工具重复、常量三份 | `project-audit.md` §7 |

### 1.2 新增 hook 点调研结论

15 个模块全覆盖，给出 10 个 P0、10 个 P1。共同特点：**低风险、纯客户端展示层、不碰权益**。最意外的四个发现：

1. 搜索广告官方自带 `RNSearchAdViewManger#hideAllViews()` 总开关；
2. 启动任务有 200+ 个具名 `StartupTask`，基类暴露 5 个可 hook 维度，PV-03 只用了 1 个；
3. 剪贴板有全局统一入口 `utils.h2#d(Context)`；
4. 小组件引导直接读 AB 实验 `ABTEST_WIDGET_ADD_GUIDE_DIALOG`。

### 1.3 ABTest 调研结论

- **有 ABTest，而且是两套**：旧 AB（`ABTestManagerImpl.mModel`）+ Libra AB（`libraModel`），另有独立的启动 AB（`StartupABManager`）。
- **没有 `getGroup/getValue`**：统一走 `IABTestManager.checkExperiment / checkBelong* / checkExperimentWithParam`；分组归一化为 `c / t / t1 / t2 / t3 / t4`。
- **没有逐 key 默认分组表**：优先级为调试覆盖 map（仅 debuggable）> 服务端/本地缓存 > null > 调用方兜底 `"c"`。
- **缓存**：默认 `shared_prefs/AB_TEST_CONFIG_CACHE_FILE_NAME.xml`，key 为 `<包名>EXTRA_STRING_AB_EXPERIMENTS_CONFIG`；启动 AB `wait-task-0805-v2` 命中 T 时改用 MMKV。服务端刷新会整体替换缓存（旧 AB 约 3 小时、Libra 约 1 小时）。
- **官方调试页不可用**：`ABTestDebugActivity` 未 exported，正式包 `adb am start` 打不开；即使 root 打开，`updateExperiment4Dev` 被 `e0.g()`（`FLAG_DEBUGGABLE`）拦截，编辑不生效。
- **可以强开，但必须白名单**：最稳的 5 个入口是 `ABTestManagerImpl.checkExperiment(String,boolean)`、`ABTestConfig/LibraABTestConfig.getGroupKeys()`、`genericCheckExperimentWithParam(...)`、`abtest2.b.x(String,boolean)`、`StartupABManager.getGroup(String)`。
- **红线**：VIP/付费/版权/音质/试听/订单、青少年模式、登录/Cookie/鉴权、免流/免广告、安全风控、`boot-*` 启动 key，一律不碰。

### 1.4 配置中心结论（agy 超时后从事件流回收 + 主代理补查）

- 没有叫 `ConfigCenter/RemoteConfig` 的独立中心；真正的客户端配置体系是 **`core/icustomconfig/ICustomConfig` + `core/customconfig/CustomConfigImpl`**，底层由混淆类 `dd/b`（模型）、`dd/e`（分组注册）、`dd/o`（SP 存储）承载。
- 统一读取入口：`getMainAppCustomConfig(Object defaultValue, String... paths)` → `getAppCustomConfig` / `getCustomSettingConfig` → `dd/b.o(...)`。
- 配置刷新：`CustomConfigImpl.fetch()` → Retrofit → `updateConfig(String)` → 按 group 分发 `dd/b.H(JSONObject)` → 写 SP `<group>fast_config` 并通知监听器。
- key 支持变体：`key#EDIT#<GIT_VERSION>`、`key#CHAN#<channel>`、原始 key；`#SPECIAL_CONFIG_EXTENSION#.startupKeys` 是启动专用 key 清单。
- 已有先例：PV-03 的 `StartupABManager.disable(ICustomConfig, String)` 就是走这套体系。
- 调试页 `CustomDebugActivity/CustomDebugDetailActivity/CustomSearchActivity` 同样未 exported，不能作为入口。
- **建议**：二期只做“只读查看 + 诊断导出”，不做配置值强制覆盖。原因：值类型是 `Object`、路径语义未知、服务端会整体刷新，强改的收益/风险比远低于 ABTest。详见 `docs/phase2/remote-config-research.md`。

---

## 二、二期目标与边界

### 2.1 目标

1. **让已有功能“可证明”**：修掉 DexKit 断点或撤掉宣传；UI-02/UI-04 做干净决策；Hook 健康度可见；文案与行为一致。
2. **补一批高价值、低风险的新体验功能**：播放页提示、搜索广告/热词、播客与 AI 引导、小组件/推送引导、歌词推广、剪贴板。
3. **把 ABTest 变成可控能力**：先只读总览，再按“精确白名单 + 单 key 开关”强开体验类实验。
4. **工程可持续**：Profile/MountSpec 抽离定位坐标；check-mounts 与代码共用一份清单；日志限流；补纯函数单测。

### 2.2 不做

- VIP/付费/版权/音质/试听/专辑购买/订单/地区限制；
- 青少年模式/防沉迷；
- 登录/Cookie/Token/鉴权/隐私协议；
- 设备指纹、风控、加密、反调试；
- 免流、免广告权益、下载限速绕过；
- 核心启动任务（`CoreInitTask`/`NativeInitTask`/`MMKVTask`/`ServiceFacade*` 等）；
- DSL 广告容器通用拦截（会误伤内容卡片）；
- “任意新版本自动兼容”的承诺。

### 2.3 版本策略

建议二期拆成三个可独立发布的版本，避免一次憋大招：

| 版本 | 内容 | 定位 |
|---|---|---|
| **1.1.0** | 可信度收尾 + 8 个新功能 + check-mounts/日志/文案 | 稳定增强版 |
| **1.2.0** | ABTest 只读总览 + 白名单强开（实验性） | 进阶实验版 |
| **2.0.0** | Profile/MountSpec 多版本架构（先支持 9.6.08 + 下一个 9.6.x） | 架构版 |

---

## 三、工作包（WP）

### WP0 · 可信度收尾（1.1.0 必做，P0）

#### WP0-1 DexKit：修好或撤掉（二选一，先做 1 天时间盒）

**方案 A（推荐）修好**
1. `app/build.gradle` 增加 `packaging { jniLibs { useLegacyPackaging = true } }`，或 manifest `android:extractNativeLibs="true"`，确保 `nativeLibraryDir/libdexkit.so` 存在。
2. 加载顺序改为：模块 `nativeLibraryDir` → `System.loadLibrary` → 从 APK 解压到目标进程 `codeCacheDir` 再 `System.load`（三选一，按实测）。
3. 把 anchor 从“类名/方法名”换成真实 dex 字符串字面量（日志 tag、MMKV key、JSON key、埋点名、Toast 文案），并用 `ClassMatcher` 的 fields/methods/superClass 做结构约束。
4. 启动自检日志：`DexKit bridge initialized` / `DexKit disabled: <原因>`；查询耗时打点，避免主线程 ANR。
5. 强制 fallback 测试：临时改错 `LoadingAdManager` 硬编码名，冷启动必须出现 `DexKit fallback located class ...`，且 AD-01 仍命中。

**方案 B 撤掉**：删除 `DexKitLocator`/`Anchors`/`dexit` 依赖与 README 中的“DexKit 兜底”表述，多版本工作包改为“只做 Profile 硬编码”。

**验收**：方案 A 有 fallback 实测日志且无 ANR；方案 B 全仓库无 DexKit 引用、文档一致。

#### WP0-2 UI-02 收口（推荐方案 A：下架）

- 删除 `TopTabHooks` 中未接线的 `installSelectGuard`、`hideTabView`、`SEEN_TITLES`，只保留数据层过滤代码并标注“实验未启用”；
- 首页定制页隐藏 `top_channels_keep`（不是置灰）；
- 三处文案统一为“9.6.08 顶部 tab 是预创建视图 + 增量 addTab，渲染层无法移除空频道，暂不支持”；
- 如果后续要做，单独立项（见 WP3 时间盒）。

**验收**：全仓库无 `top_channels_keep` 的 UI 入口、无 `installSelectGuard` 引用、文档只有一种说法。

#### WP0-3 UI-04：先停止空转，再决定做不做

- 短期：首页定制页隐藏/置灰 `drawer_items_filter`，`Hooks.installOne` 补 `DRAWER` 分支（记录“未实现”）或删除 `DrawerHooks`；
- 中期：给 1 天时间盒逆向抽屉数据源（`MainDrawerConfig` / `MainDrawerDynamicConfig` / MMKV `side_bar_new_first`），失败则彻底删除 UI 入口；
- 10 个抽屉项必须按真实数据重写，不能沿用猜测值。

**验收**：用户看不到可编辑但无效的抽屉项；若实现，勾选后必须有 hit + 视觉变化。

#### WP0-4 Hook 健康度可见

- 实现 `HookState`：按 Feature 记录 `expected / installed / failed / hit`、最近失败原因；线程安全。
- 状态卡增加“本次注入：N/M 挂载点，K 项未命中”摘要；目标进程通过 remote prefs 回写（或只写 logcat + 模块页“查看日志”说明）。
- `HookKit.install` 统一登记 `expected/installed`；`markNotFound` 区分 critical/optional。

**验收**：人为删掉一个方法名，状态卡对应功能显示“缺失 1 项”；冷启动能看到每项 `installed/expected`。

#### WP0-5 验证矩阵与文案对齐

- 把 `DEVICE_VERIFICATION.md` 拆成 `registered / hit / 未验证` 三列，禁止把 registered 写成“零失效”；
- 修正 §4.2 全部文案（PV-05、UI-06、UI-03、footer、首页 hint、PV-01、AD-01）；
- `RELEASE_CHECKLIST.md` 的过期状态（0.1.0、未 git init）清理。

**验收**：搜索“Phase”“漫游”“权限引导”“仅观测”无残留；README 功能表与行为逐项对照通过。

#### WP0-6 UI-08 深色模式定性

- 要么做完整真机回归（首页/播放页/评论/设置/WebView 无崩溃黑屏）后转正；
- 要么标 blocked + 风险提示，默认关。

---

### WP1 · 新增体验功能（1.1.0，P0）

新增 8 个 Feature（编号接续现有 AD/UI/PV）：

| 新 ID | 功能 | 挂载点（dexsql 已验证） | 默认 |
|---|---|---|---|
| **AD-09** | 搜索广告净化 | `RNSearchAdViewManger#newStyle/#oldStyle` no-op + `#hideAllViews()`；`SearchAdItemLargeView#e(...)` / `SearchAdItemMediumView#e(...)` | 开 |
| **UI-10** | 播放页提示治理 | `ui.hint.PlayerCommentPopView#renderCommentListAndShow(List)`；`PlayerRecommendSongHintView#maybeDisplayHint(...)`；`PlayerJankDialogHelper#showPlayJankNetErrorDialog/showNMCacheIOErrorDialog`；`PlayerIncentiveAdView#maybeDisplayHint(...)` | 开 |
| **UI-11** | 搜索热词轮播 | `meta.SearchGuide#isValid()Z -> false` | 开 |
| **UI-12** | 播客 / AI DJ 引导 | `PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide/#isTimeToShowVoiceBookGuide`；`PlayerAIDJSwitchGuideView#showGuide`；`FMAIDJSwitchView#maybeDisplayHint` | 开 |
| **UI-13** | 小组件 / 推送引导 | `WidgetAddGuideDialogHelper$a#a()Z -> false`；`PushGuideCenterDialogFragment#onCreateView` / `PushGuideBottomSheetFragment#onCreateView`（先核对是否已被 UI-06 覆盖） | 开 |
| **UI-14** | 歌词页推广 | `LyricsBottomHintView#maybeDisplayHint`；`LyricViewContainer#checkAndShowLrcOptionHint`；`LyricVideoTipsDialog#oc(...)` | 开 |
| **UI-15** | 云村 / 直播推广 | `LivePromotion#listFromJson(JSONObject) -> 空列表`；`NotesPublishGuideHint/NotesSearchGuideHint#maybeDisplayHint`；`CommentLiveViewHolder#onBindViewHolder` | 开（UI-15 可默认关） |
| **PV-06** | 剪贴板读取拦截 | `utils.h2#d(Context) -> null`（全局 ClipboardUtils 入口） | 开 |

**P1 候选（1.1.x 或 1.2.0）**

- **PV-07 启动任务编排**：`StartupTask#runOnStage()I / #runOnUIThread()Z / #priority()I / #degradeInit()`，扩展 PV-03；必须配“核心任务保护名单”。
- **AD-10 广告预加载裁剪**：`AdColdBootEntranceTask`、`PodcastRewardAdTask`、`APreloadRecommendViewTask`。
- **F-01 低内存模式**：`ViewPreloadManager#setNoNeedToLoadViewCache`。
- **N-02 通知权限说明弹窗**：`NotificationPermissionDialog#onCreateViewInner`。
- **D-01 下载页播客推荐卡**：`RcmdPodcastViewHolder#onBindViewHolder`。

**验收方式**：每个新功能必须有“开启 → 触发场景 → hit 日志 → 截图 → 关闭恢复”五步；服务端不下发的场景标记“未验证”，不得写“已生效”。

---

### WP2 · ABTest 与配置中心（1.2.0，P1）

#### WP2-1 只读总览（先做，低风险）

- Hook `ABTestManagerImpl.checkExperiment(String,boolean)`、`ABTestConfig.getGroupKeys()`、`LibraABTestConfig.getGroupKeys()`、`genericCheckExperimentWithParam(...)`、`abtest2.b.x(String,boolean)`、`StartupABManager.getGroup(String)`；
- 只记录：key、返回分组、来源（Libra/旧 AB/启动 AB）、调用次数；不改变返回值；
- 模块 App 增加“实验总览”页：搜索、按命中/未命中筛选、导出 JSON（通过 remote prefs 回写或诊断文件）；
- 用途：让用户看到自己实际命中了哪些实验，为后续白名单提供依据。

#### WP2-2 白名单强开（实验性，默认关）

- UI：只展示**精确白名单**中的 key，每个 key 单独开关 + 分组选择（`c/t/t1/t2/t3/t4`）；
- 运行时二次校验：精确匹配 + 正则黑名单（vip/pay/purchas/privilege/quality/trial/rights/copyright/order/region/youth/teen/privacy/login/cookie/auth/token/risk/security/encrypt/adfree 等）；
- 只改 `getGroupKeys()` / `checkExperiment` 的返回值，不改 `status` / `clientConfig`；
- 不做“全局默认 t”，不按前缀放行；
- 每个 key 必须有“恢复默认”按钮；配置写 remote prefs，重启目标进程生效。

**首批白名单建议**（来自 `abtest-research.md` §8.2，先小范围验证）：
`tabMerge`、`home-headframe`、`comment-click`、`reply-top`、`comment-loadingAndroid`、`SearchRN5android`、`best-search`、`Hot-search-list`、`aosCrossFade`、`play-ring`、`newAudioFocusDialog`、`AndroidNotificationOnlyForeground`、`dailyRecommend-refresh`。

**禁止强开**：`LimitTimeFreeTrialPrivilege`、`playerhquality`、`hy-audio-quality-upgrade`、`purchasedAlbumGapLessOpt`、所有 `album-purchase-*`、`Podcast_RelatedRec_Pay`、`VIP_*`、`homepage-VIPbar`、`hy-*vip*`、`androidpaydelay`、`orderPolling-*`、`ad_disable_ad_free_play_task*`、`FH-kv-*`、`jsonParseOptYouthTimeLockConfig`、所有 `login/cookie/auth/token/risk/security` 相关 key、`boot-*`。

#### WP2-3 配置中心只读（可选，已按 agy 回收结果修订）

- **首选只读点改为 `CustomConfigImpl.updateConfig(String)`**：一次拿到整棵配置树（所有 group），比在 getter 热路径上反复打日志更合适；
- 辅助只读 `dd.b.H(JSONObject)`（单组落盘）与 `dd.b.o(...)`（采样高频读取 key）；
- 只记录 group、key 数、值类型分布、变更 diff；不做值覆盖；
- 补充：`ABTestManagerImpl.checkClientConfig(String,String)` 可只读实验附带的 clientConfig 值。

> 配置中心细节见 `docs/phase2/remote-config-research.md`（含 `dd/b`、`dd/e`、`dd/o` 调用链、SP key `<group>fast_config`、`#EDIT#/#CHAN#` 变体规则、`#SPECIAL_CONFIG_EXTENSION#.startupKeys`）。

**验收**：只读模式下目标进程行为零变化；白名单模式下只影响被勾选 key，且关闭后重启恢复；不出现登录/播放/崩溃异常。

---

### WP3 · 多版本工程化（2.0.0，P1）

目标不是“自动兼容任意版本”，而是**新增版本 = 新增一个 Profile 文件**。

1. **抽离坐标**：新建 `Profile9608`，把 57 处类名、约 90 处方法名/字母名、字段名、真实 anchor 搬进去；hook 文件只保留“取 MountSpec + hook 体”。
2. **统一解析器** `MountResolver`：`classCandidates` 精确匹配 → DexKit 结构查询 → 签名扫描；每步校验返回类型与成员，结果带 `source`（HARDCODED/DEXKIT/SIGNATURE）与 `confidence`。
3. **按功能降级**：`VersionGate` 不再全局等值判断；每个 Feature 解析 critical mount，全部成功=启用，部分成功=降级，全部失败=跳过并明确日志。
4. **check-mounts 共用清单**：从 Profile 生成检查项，避免代码/脚本两套清单漂移；增加版本断言、arity/返回类型断言、`ERROR`/`GONE` 区分。
5. **优先级**：先迁移 8 个高风险混淆类（`q60.w0`、`th0.h`、`rh0.c`、`tt0.d`、`vt0.r`、`lv0.j`、`fw0.e`、`jn`），再迁移语义化业务类。

**时间盒**：UI-02 渲染层（`TabLayout.removeTab` + ViewPager2 同步 + 索引重映射）单独给 2 天，失败即关闭，不阻塞 2.0.0。

---

### WP4 · 质量与发布（贯穿）

- **日志**：`HookKit.hit` 按 feature 限流（每 5 秒最多 1 条，首次必打）；新增 `debug_log` 配置；release 默认只打 `registered/fallback/error`。
- **返回值安全**：`constBool/skipVoid` 安装前校验 `Method.getReturnType()`；不匹配拒绝安装并 WARN；`AudioAdHooks#d`、`HintHooks#maybeDisplayHint` 显式判断返回类型。
- **进程过滤**：`mProcessName == null` 回退 `Application.getProcessName()`；未知子进程打明确 `skip secondary process`。
- **去重**：`shouldKeepTab/shouldKeepItem/firstStringGetter/findField/invokeNoArg/hookNull` 收进 `HookKit`；统一 `TARGET_PACKAGE`/`KEY_ALLOW_UNSUPPORTED`/`TAG`；`Hooks` switch 与 Feature 一一对应（可用函数式 installer 替代）。
- **单测**：`filterCodeListString`、`filterValue`、`Config.loadSet`、`VersionGate`、`ProcessFilter`。
- **R8**：收窄 `-keep class com.cloudmusictool.app.** { *; }`，只保留入口/Preference/Provider/DexKit。
- **发布**：1.1.0 tag `3-1.1.0`（官方仓库）、源码 tag `v1.1.0`、APK + mapping；CHANGELOG 写清“已验证/未验证/已知限制”。

---

## 四、里程碑与排期（按单人有效工时估算）

| 阶段 | 内容 | 工时 | 交付 |
|---|---|---|---|
| Sprint 1 | WP0 全部（DexKit 时间盒、UI-02/04 收口、HookState、文案） | 4-5 天 | 1.1.0-alpha1 |
| Sprint 2 | WP1 的 8 个新功能 + 真机矩阵 | 5-6 天 | 1.1.0-beta1 |
| Sprint 3 | WP4 质量项 + 1.1.0 发布 | 2-3 天 | 1.1.0 |
| Sprint 4 | WP2 只读 + 白名单 | 5-6 天 | 1.2.0-beta |
| Sprint 5 | WP3 Profile/Resolver + 2.0.0 | 5-7 天 | 2.0.0 |

合计约 3-4 周（不含等待服务端下发广告/弹窗的观察时间）。

---

## 五、验收标准（总）

1. **每个功能**都有五步记录：开启 → 触发 → hit 日志 → 截图/行为证据 → 关闭恢复。
2. **每个挂载点**有解析来源（HARDCODED/DEXKIT/SIGNATURE）与 expected/installed 计数。
3. **默认开启项**不允许出现“只有 registered、没有任何 hit 证据”却不标注“未验证”。
4. **ABTest 白名单**只影响被勾选 key；关闭后重启恢复；不触发登录/播放/风控异常。
5. **多版本**只承诺白名单版本；未知版本按功能降级，状态卡显示适配度。
6. **发布前** `tools/check-mounts.sh` 全 OK、版本断言通过、无 FATAL/ANR。

---

## 六、风险与红线

| 风险 | 应对 |
|---|---|
| 服务端下发/灰度导致“看不到效果” | 区分“已注册/已命中/未验证”；用本地构造或调试入口补验证 |
| Tinker 热补丁替换实现 | 优先 hook 门面/模型/配置；DexKit 兜底；每版本跑 check-mounts |
| ABTest 强开导致 UI 错乱/崩溃 | 精确白名单 + 单 key 开关 + 恢复默认 + 默认关；先只读 |
| 强制分组被服务端校验 | 只改客户端返回值，不伪造上报；出现异常立即回退 |
| 多进程/子进程漏装 | 明确子进程策略；`:play` 单独安装 AD-07 |
| 主题/渲染层改动影响面大 | UI-08 先定性；UI-02 时间盒，失败即关 |
| 日志/性能开销 | hit 限流；热路径只打首次；DexKit 查询异步化 |

**红线（再次明确）**：不碰 VIP/付费/版权/音质/试听/订单/地区、不碰青少年模式、不碰登录/Cookie/鉴权/隐私协议、不碰设备指纹/风控/加密、不做免流/免广告权益/下载限速绕过。

---

## 七、子代理分工建议

| 子代理 | 任务 | 产出 |
|---|---|---|
| A（逆向） | WP1 每个新功能的精确签名复核 + 真实触发路径 | `docs/phase2/mount-specs.md` |
| B（实现） | WP1 8 个新 hook 文件 + Feature/UI 接线 | 代码 + 单测 |
| C（实现） | WP0-1 DexKit 修复 + 强制 fallback 测试 | 代码 + 实测日志 |
| D（实现） | WP0-4 HookState + 状态卡 + remote prefs 回写 | 代码 |
| E（逆向/实现） | WP2 ABTest 只读 + 白名单 | 代码 + 实验清单 |
| F（架构） | WP3 Profile/MountSpec/Resolver + check-mounts 生成 | 代码 + 迁移报告 |
| G（QA） | 真机矩阵、五步记录、发布检查 | `docs/DEVICE_VERIFICATION_1.1.md` |

主代理负责：范围控制、冲突合并、版本门禁、发布与文档。

---

## 八、需要拍板的三件事（已给推荐默认）

1. **UI-02**：推荐 1.1.0 先“下架收口”（方案 A），把渲染层尝试放到 2.0.0 时间盒。若你希望二期必须做出来，则按方案 B 追加 2 天。
2. **ABTest 强开**：推荐 1.2.0 先只读总览，白名单强开默认关、单 key 开启。若你希望更激进，可把首批白名单扩到 20 个，但需接受 UI 变脸风险。
3. **多版本**：推荐 2.0.0 只承诺“9.6.08 + 下一个 9.6.x”，不做任意版本自动兼容。

> 未拍板前，默认按以上推荐执行；三项都不影响 1.1.0 的 Sprint 1/2 开工。
