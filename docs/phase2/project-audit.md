# 云音乐工具箱 · 二期收尾审计报告

- 审计日期：2026-10-20
- 审计对象：`D:\cloudmusictool`（v1.0.0，versionCode 2，libxposed API 102，纯 Java 17）
- 目标宿主：`com.netease.cloudmusic` 9.6.08（versionCode 9006008）
- 审计方式：只读代码/文档/构建产物审阅，未修改任何源码，未在真机执行
- 覆盖范围：`Feature.java`、`hooks/` 全部 21 个文件（约 3036 行）、`HomeCustomizeFragment.java`、设置页/配置/入口类、`tools/check-mounts.sh`、`docs/*.md`、`CHANGELOG.md`、`README.md`、构建配置与 APK 元数据

---

## 0. 结论摘要

1. **功能面已“能跑”，但“可证明性”不足。** 默认开启的 14 项中，只有 AD-01/03/05/08（部分）、UI-01/03/05/07、PV-03/05 有真机 hit 证据；AD-02/04/06/07、UI-06、UI-09、PV-01/02/04 属于“已注册、未自然触发、未目视确认”，而 `HookState` 四个状态方法全是空实现，导致模块自己也无法在运行时回答“这条 hook 到底装上没有、命中没有”。
2. **UI-02 是半成品，不是 blocked 的干净状态。** 数据层过滤、文本层清空、TabView 隐藏、选择守卫四套代码都还在（其中 `installSelectGuard` 从未被调用），但因为 Feature blocked，运行时一行都不会注册；配置项仍存在（置灰），文档三处对“失败原因”的表述互相矛盾。属于“代码腐烂 + 文案误导”的双重风险。
3. **UI-04 是空转配置。** `drawer_items_filter` 在首页定制页可编辑并持久化，但 `DrawerHooks.install()` 是空方法、`Hooks.installOne()` 的 switch 甚至没有 `DRAWER` 分支，配置永远不会被消费；10 个抽屉项也是规划期猜测值，未与 9.6.08 核对。
4. **DexKit 兜底大概率从未真正工作。** 三个独立原因：APK 为 `extractNativeLibs=false` 而代码用 `System.load(模块 nativeLibraryDir/libdexkit.so)` 加载；绝大多数 anchor 传的是类名/方法名/字段名而不是 dex 字符串字面量，`ClassMatcher.addUsingString` 匹配不到；很多 `findClass` 调用根本没传 anchor。真机日志中也没有任何 `DexKitBridge initialized` / `DexKit fallback` 记录。
5. **多版本兼容不需要重写 3000 行 hook。** 真正的版本耦合集中在“定位”这一步（57 处 `findClass`、约 90 处方法名/字母名），hook 体本身大多与版本无关。最小路径是把定位坐标抽成 `TargetProfile + MountSpec`，把“硬编码优先、DexKit 兜底”改成“profile 候选 → 结构化查询 → 签名扫描”，并让版本门禁从“全局等值 9.6.08”变成“按功能解析结果决定启用/降级”。

---

## 1. 基线盘点

| 项 | 现状 |
|---|---|
| Feature 数量 | 22（AD 8 / UI 9 / PV 5） |
| 默认开启 | 14（AD 8 + UI-01/03/05/06/07/09，共 6 个 UI；UI-08 默认关） |
| blocked | 2（UI-02、UI-04） |
| Hook 文件 | 21 个（含 `HookKit`、`Hooks`、`ProcessFilter`） |
| `HookKit.install(...)` 调用点 | 73 处 |
| `HookKit.findClass(...)` 调用点 | 57 处 |
| 反射辅助方法调用 | 67 处（`findByNameQuiet` / `findByShape` / `findByNameAndArity` / `findMethodQuiet` / `findMethodWithAnchors`） |
| 测试源码集 | 无（`app/src` 下只有 `main`） |
| 构建产物 | `dist/cloudmusictool-1.0.0.apk`、`dist/mapping.txt`；`META-INF/xposed` 单入口、min/target=102、scope 仅网易云 |
| APK 打包 | `android:extractNativeLibs=false`（已用 aapt2 从 release APK 确认） |

---

## 2. blocked 功能盘点

### 2.1 UI-02 顶部频道

**Feature 登记**（`Feature.java:32-34`）

```java
TOP_CHANNELS("UI-02", ..., "ui_02_top_channel", false, Group.UI,
        "9.6.08 频道数据链路已定位，但渲染层无法完全屏蔽空频道，暂不启用"),
```

- `defaultEnabled=false` + `blockedReason` 非空 → `Hooks.installAll` 直接 `continue`，运行时零注册。
- 设置页会置灰并拼接原因（`SettingsFragment.java:46-49`）。

**代码保留了什么**（全部处于“死代码”状态）

| 层 | 代码位置 | 内容 | 现状 |
|---|---|---|---|
| 权限解锁 | `TopTabHooks.java:26-40` | `HomeTopTabInfo#getCanDelete/getCanDrag -> true` | 保留 |
| 旧数据链路 | `TopTabHooks.java:46-73` | `TabExtra#getNowChannelItems`（两个包名） | 保留，真机确认 9.6.08 从不调用 |
| 旧数据链路 | `TopTabHooks.java:81-105` | `UserSettingDTO#getCurrentRes`（两个包名） | 保留，真机确认从不调用 |
| 新数据链路 | `TopTabHooks.java:110-216` | `BaseArkV3Fragment#getTabDataList` 过滤 + `hc/Tc/Lc` 入参过滤 | 保留，曾实测 hit 6→1 |
| 文本层 | `TopTabHooks.java:323-366` | `MusicTabLayout$k` 的 CharSequence setter 置空 + `hideTabView` 置 GONE | 保留，实测命中但空槽位仍可点 |
| 选择守卫 | `TopTabHooks.java:250-298` | `selectSubTabById/setTabByCode/selectSubTabInner` 拒绝切换 | **从未被调用**（全仓库无引用） |
| 配置 | `HomeCustomizeFragment.java:85-91,125,130-134` | 6 项频道保留清单 + 置灰 | 保留，旧值仍留在 remote prefs |
| 文档 | `DEVICE_VERIFICATION.md:161-181`、`RELEASE_CHECKLIST.md:19` | 记录深度修复尝试与结论 | 与代码一致，但与 Feature 原因措辞不一致 |

**缺什么**

1. **渲染层可靠移除入口**：`TabLayout.removeTab/removeView`、`ViewPager2` adapter 同步、tab 索引重映射，四者至少要有其一。当前只做“文本清空 + GONE”，槽位仍占位、仍可点击。
2. **选择守卫接线**：`installSelectGuard` 是唯一能兜住“点进空频道”的代码，但没有被 `install()` 调用；即便调用，它依赖 `SEEN_TITLES`（只增不减的静态集合）判断标题，逻辑也不完整。
3. **列表一致性**：`getTabDataList` 分支原地 `list.clear()/addAll()`（`TopTabHooks.java:153-155`），若宿主返回不可变或共享列表，会抛异常/破坏宿主缓存；当前靠 catch 兜底，行为不确定。
4. **状态清理**：`SEEN_TITLES` 无生命周期，长期运行会累积；过滤后没有通知宿主 tab 数量变化。
5. **干净的 blocked 形态**：要么把整条链路保留为“实验开关”（可手动开启、明确标注风险），要么把代码、配置项、文案全部下架；现在是“代码在、配置在、运行时不在、文档三种说法”。

**风险**

- 若后续仅把 `blockedReason` 置空就“解锁”，会立刻暴露原地改列表、选择守卫未接线、文本层只清字不删槽位这三个已知问题。
- 代码长期不执行会随宿主版本漂移而腐烂，下一次“解封”成本高于现在。

### 2.2 UI-04 侧栏抽屉

**Feature 登记**（`Feature.java:37-38`）

```java
DRAWER("UI-04", ..., "ui_04_drawer", false, Group.UI, "当前版本无可靠挂载点，暂不启用"),
```

**保留了什么**

- `DrawerHooks.java`：一个空的 `install()`，注释“当前版本无可靠挂载点，不再注册任何全局 Hook”。
- `Config.KEY_DRAWER_ITEMS_FILTER`（`Config.java:29`）与 `Config.load` 的读取（`Config.java:61`）。
- `HomeCustomizeFragment` 中可编辑的 10 项抽屉清单（`HomeCustomizeFragment.java:94-99,126`）。
- `Hooks.installOne` 的 switch **没有 `DRAWER` 分支**（`Hooks.java:56-130`），即便解封也只会落到 `default` 打印 “pending implementation”。

**缺什么**

1. 任何挂载点：没有类名、没有方法、没有数据源，等于 UI-04 从未开始逆向。
2. 消费者：`drawer_items_filter` 全仓库只有“定义/读取/UI 绑定”，没有任何 hook 读取它。
3. 正确的 blocked 交互：配置项应和 UI-02 一样置灰/隐藏，现在可勾选、可保存，用户会以为已生效。
4. 真实清单：10 项（我的消息/我的云贝/装扮中心/创作者中心/最近播放/定时关闭/商城/云村有票/云推歌/我的客服）与 UI-03 当初“规划期猜测”的错误模式相同，未做真机核对。

**风险**

- 这是当前最明确的“UI 与真实数据不符”项：用户操作有反馈（勾选、持久化），但功能完全不存在。
- 若二期不打算做，应删除 UI 入口与配置键，保留 Feature 占位即可；否则应把它和 UI-02 一样明确标注“暂不生效”。

---

## 3. 已注册但从未自然触发验证的功能

判定依据：`DEVICE_VERIFICATION.md` 的 hit 记录 + `RELEASE_CHECKLIST.md` 的勾选状态 + 代码中实际注册的挂载点。注意“registered”只证明 `module.hook()` 成功，不证明逻辑正确。

| 功能 | 注册挂载点 | 已有证据 | 缺口 |
|---|---|---|---|
| AD-02 运营弹窗 | `LaunchDialog` 5 类 show* 方法（按重载展开） | 仅 registered | 服务端未下发；`showOnePassLoginDialog` 保留逻辑未验证；无 hit |
| AD-04 首页信息流 | `Banner#isAdBanner -> false` | 仅 registered；文档称“视觉无广告” | 无 hit；“视觉无广告”可能只是没下发广告，不能证明 hook 生效 |
| AD-06 播放页广告 | `PlayerAdJarDelegateImpl#k/#q`、`MusicGiftModel#isValid`、`AdPlayerFullScreenHelper#checkNeedExpose` | 仅 registered | 无 hit；`meta.Ad` 缺失时会退化为 arity 匹配，可能挂错重载 |
| AD-07 音频/播客 | `AdAudioClipManager#d/#c`、`AdPodcastPlayerPageHelper#h`、`PodcastVideoAdHelperImpl#z` | 仅 registered | 无 hit；`:play` 进程实测不存在；`d/c` 返回 null/Boolean 与真实返回类型未校验 |
| UI-06 引导气泡 | 5 个 `maybeDisplayHint` + `nr2.c#checkAndShowYouthDialog` | 仅 registered | 无 hit；`maybeDisplayHint` 返回类型分支未验证；summary 宣称的“权限引导”根本没有 hook |
| UI-09 头像挂件 | `jn#L -> false` | 仅 registered | 无 hit、无截图；`jn` 是混淆类，方法 `L` 极不稳定 |
| UI-08 强制深色 | `ResourceRouter#isNightTheme/#isUiModeNightYes -> true` | **从未安装**（默认关） | 用户一旦开启即进入完全未测试路径，存在资源/主题崩溃风险 |
| PV-01 OAID | `MusicOaidUploadManager#o`、`AdOaidUploadTask#init` | 仅 registered | 开启路径只验证了“无崩溃”，无 hit |
| PV-02 日志写盘 | `NeedLogToFileTask#init`、`NBLogTask#init` | 仅 registered | 无 hit（PV-03 的 `needExecute` 命中会顺带阻断，容易混淆归因） |
| PV-04 APM | `StatisticImpl#logApm/#forceApmUpload` | 仅 registered | 无 hit；`logApm` 走 `findByNameAndArity(1)`，签名变化会静默跳过 |
| AD-05 子项 | `SpecialComment#getGeneralBanner/...`、`CommentVipRcmdAdapter#getItemCount` | 仅 `isTagValid/getBottomTags` 有 hit | 4 个 getter 与 VIP 适配器无 hit |
| AD-08 子项 | `AdCloudReportTask#execute`、`StartAppHelper$b#startApp` | 仅 2 个 Deeplink task 有 hit | 上报任务与外跳拦截无 hit |
| UI-01 MMKV | `MMKV#decodeString×2/#getString` | 无 | 相关性过滤依赖 `HOME_RECOMMEND`/`MIX_CONTAINER` 字符串，未验证是否命中；无 hit |
| UI-05 子项 | `getStartAppRedPageDelayTime` | 仅 `isNeedDelayStart`/`checkDelayDoAction` 有 hit | 延迟时间归零路径无 hit |
| UI-07 子项 | `SettingActivity#V8`、`jn#m0`、`vt0.r#j` | 仅 `tt0.d#a/d`、`lv0.j#e` 有 hit | 其余 3 个无 hit |
| UI-03 子项 | `SettingActivity#T8/#U8` | 仅 `th0.h#h` 有 hit | 两个设置开关无 hit |

**结论**：默认开启的 14 项里，有 6 项（AD-02/04/06/07、UI-06、UI-09）完全没有行为证据；PV 组开启路径只做了“不崩溃”验证。二期必须把“注册成功”和“逻辑生效”分开记录，否则发布说明里的“82+ 挂载点零失效”会被误读为“功能已验证”。

---

## 4. UI 配置项与真实数据/生效性对照

### 4.1 配置项 ↔ 消费者

| 配置键 | UI 位置 | 消费者 | 是否生效 | 问题 |
|---|---|---|---|---|
| `home_blocks_filter` | 首页定制 | `HomeBlockHooks` | 生效（3 条别名已实测，其余未实测） | 30 项中仅 3 项有真机证据 |
| `top_channels_keep` | 首页定制（置灰） | `TopTabHooks`（blocked，不注册） | 不生效 | 原因文案与 Feature/文档不一致；旧值残留 |
| `drawer_items_filter` | 首页定制（可编辑） | **无** | **完全不生效** | 应置灰/隐藏；10 项未核对 |
| `bottom_nav_keep` | 首页定制 | `BottomTabHooks` | 生效（实测 4→2） | 键是标题，依赖 `getTabTitle`；若宿主改返回 code 会失效 |
| `startup_task_blocklist` | **无 UI** | `StartupBlacklistHooks` | 代码支持 | 只能改 remote prefs，普通用户不可达；默认值硬编码 3 项 |
| `allow_unsupported_version` | **无 UI** | `VersionGate` / `Config` | 代码支持 | 文档让用户“高级配置”开启，但设置页没有入口 |

### 4.2 文案 ↔ 行为不一致

| 位置 | 文案 | 实际行为 | 建议 |
|---|---|---|---|
| `strings.xml:83` PV-05 | “仅观测并记录统计上报日志，不拦截业务调用” | `StatisticHooks` 还把 `LogMonitorConfigManager.b/c/d/e` 强制改为 false，属于拦截 | 改文案或拆功能 |
| `strings.xml:66` UI-06 | “屏蔽各类引导气泡、评分弹窗、青少年弹窗及权限引导” | 没有权限引导相关 hook（CHANGELOG 明确说已移除） | 删除“权限引导” |
| `README.md:49` UI-03 | “隐藏漫游/动态，按保留清单过滤底栏” | 9.6.08 真实 tab 是 首页/搜索/关注/我的，没有“漫游/动态” | 改成真实四项 |
| `strings.xml:34` footer | “Phase 0-5 功能已全部实装” | UI-02/UI-04 blocked，UI-08 未验证 | 改成用户可读的状态说明，删除内部 Phase 术语 |
| `strings.xml:88` 首页定制 hint | “底层的 Hook 过滤已启用” | 抽屉无消费者、顶部频道 blocked | 按配置项分别说明 |
| `strings.xml:87` 顶部频道置灰 | “当前版本数据链路未接通，暂不生效” | 数据链路其实已打通（`getTabDataList` 实测 hit），失败在渲染层 | 与 Feature 原因统一为“渲染层无法移除空频道” |
| `Feature.java:33` UI-02 | “9.6.08 频道数据链路已定位，但渲染层无法完全屏蔽空频道” | 与上一条矛盾 | 统一措辞 |
| `strings.xml:75` PV-01 | “阻断 OAID 读取与设备指纹上报” | 只 hook 上传管理器和启动任务，未拦“读取” | 改为“阻断 OAID 上传链路” |
| `strings.xml:39` AD-01 | “清空广告缓存” | `LoadingAdManager#o` 返回空列表，并不清缓存 | 改为“使广告数据源返回空” |
| `README.md:72-73` UI-02 | “数据链路已打通……空频道仍会留下可点击槽位” | 与 DEVICE_VERIFICATION 一致，但与 `strings.xml:87` 不一致 | 统一 |

### 4.3 配置读写机制

- 写：`ConfigStore` 优先 `ServiceBridge.remotePreferences("config")`，未绑定则回退本地并打 WARN（`ConfigStore.java:40-50`）。
- 读：`Config.load` 要求 `PROP_CAP_REMOTE`，否则用 `Feature.defaultEnabled`（`Config.java:45-69`）。
- 集合：`getStringSet` 失败会回退逗号字符串，双向兼容（`Config.java:71-94`、`ConfigStore.java:52-102`）。
- 生效时机：只在 `onPackageReady` 读一次，改配置必须重启目标进程；这一点文档已说明。
- 结论：读写链路是可靠的；问题在“有些键没人消费/没有 UI”，不在存储层。

---

## 5. 硬编码类名/方法名的版本脆弱点（9.6.08 专用）

按“宿主版本升级后是否容易变”分四档。

### 5.1 A 档：语义化业务类（类名大概率保留，方法名/字段名可能变）

`LoadingAdManager`、`LaunchDialog`、`CMUpdateConfig`、`UpdateInfo`、`Comment`、`Banner`、`PlayerAdJarDelegateImpl`、`AdAudioClipManager`、`MusicOaidUploadManager`、`StartTimeHandler`、`ResourceRouter`、`StartupTask`、`StatisticImpl`、`LogMonitorConfigManager`、`MixContainerData`、`DiscoveryBlockData`、`BaseArkV3Fragment`、`MusicTabLayout`、`SettingActivity`、`CommentAdapter`、`MMKV`。

风险点：方法名（`getNeedPopUp`、`isAdBanner`、`isTagValid`、`getBlockList`、`getTabDataList`）和字段名（`ad`、`commentGeneralBanner`、`vipRcmdList`、`liveRcmdList`、`concertInfo`）在宿主混淆策略变化时会一起变；`CommentAdHooks` 的字段反射尤其脆。

### 5.2 B 档：混淆短类（每个版本几乎必变）

| 类 | 使用位置 | 用途 |
|---|---|---|
| `q60.w0` | `SplashAdHooks.java:172-181` | 免广 AB 实验 |
| `th0.h` | `BottomTabHooks.java:47-64` | 底栏列表 |
| `rh0.c` | `BottomTabHooks.java:68-88` | 底栏列表 |
| `tt0.d` | `AutoPlayHooks.java:53-69` | 自动播放开关 |
| `vt0.r` | `AutoPlayHooks.java:73-83` | 自动播放开关 |
| `lv0.j` | `AutoPlayHooks.java:87-118` | `AutoPlayConfig` |
| `fw0.e` | `UpdateHooks.java:87-96` | 应用内更新入口 |
| `appupdate.d` | `UpdateHooks.java:58-69` | 更新判定 |
| `jn` | `AutoPlayHooks.java:38-50`、`PendantHooks.java:20-35` | 自动播放 / 头像挂件 |
| `nr2.c` | `HintHooks.java:60-73` | 青少年弹窗 |
| `StartAppHelper$b` | `AdReportHooks.java:35-47` | 外跳 |

### 5.3 C 档：单字母/数字方法名（最高风险）

`LoadingAdManager#E/G/o`、`appupdate.d#B/I`、`PlayerAdJarDelegateImpl#k/q`、`PodcastVideoAdHelperImpl#z`、`AdPodcastPlayerPageHelper#h`、`AdAudioClipManager#d/c`、`SettingActivity#T8/U8/V8`、`jn#m0/L`、`tt0.d#a/d`、`vt0.r#j`、`lv0.j#e`、`LogMonitorConfigManager#b/c/d/e`、`CommentAdapter#y1`、`BaseArkV3Fragment#hc/Tc/Lc`、`MusicTabLayout$k` 的 setter。

这些名字没有任何语义，宿主每次 R8/混淆都可能重排；当前唯一的兜底是按 arity/参数形状猜（`findByNameAndArity`、`findByShape`），猜错时会挂到错误重载上，属于“静默错误”而非“安全失败”。

### 5.4 D 档：DexKit anchor 质量

代码里约 57 处 `findClass`，绝大多数 anchor 是类简名/方法名/字段名（如 `"LoadingAdManager"`、`"Banner"`、`"isAdBanner"`、`"canDelete"`），而 `DexKitLocator` 用的是 `ClassMatcher.addUsingString`，匹配的是 dex 字符串字面量，不是类/方法/字段名。真正可用的字面量 anchor 只有少数几个：

- `"show ad delayed"`（`SplashAdHooks.java:60`）
- `"hit_noad_abtest"`（`SplashAdHooks.java:173,178`）
- `"redPageDelayTime"`（`StartupDelayHooks.java:21,33,35`）
- `"monitorRequest"` / `"ADS2S"`（`AdReportHooks.java:23`，未验证是否真实字面量）

另外 `UpdateHooks.java:58` 给 `appupdate.d` 传的 anchor 是 `"CMUpdateConfig"`，明显是复制错误；一旦 `appupdate.d` 改名，DexKit 可能找到 `CMUpdateConfig` 本身，再找 `B/I` 失败，日志却显示“fallback located class”，具有误导性。

---

## 6. 运行时机制评估

### 6.1 异常处理：整体合格，但“安全失败”不完整

做得好的地方：

- `HookKit.install` 统一 `setExceptionMode(PROTECTIVE)`（`HookKit.java:236-240`）。
- 每个 install 方法都有 `try/catch(Throwable)`，单个功能失败不影响其他功能（`Hooks.java:56-130`）。
- 所有 after-hook 都是“先 proceed，再处理，catch 后返回已拿到的 res”，未发现二次 `proceed()`（CHANGELOG 提到的 8 处已修）。
- `Config.load`、`VersionGate`、`ServiceBridge` 都有兜底，不会因框架能力缺失而崩。

不足：

1. **返回值类型不校验**。`HookKit.constBool` 对任何方法返回 `Boolean`，`skipVoid` 返回 `null`；如果方法实际返回 `void`/`int`/对象，转换失败会被 PROTECTIVE 吞掉，表现为“hook 静默不生效”；如果返回对象而 hook 返回 `null`，宿主可能 NPE（如 `AudioAdHooks#d`、`HintHooks#maybeDisplayHint`）。
2. **`findMethod` 系列语义不统一**。`findMethod(module,f,cls,name)` 只按“唯一同名方法”找，不校验参数、不尝试 DexKit（`HookKit.java:98-107`）；`findMethod(..., returnType, params, anchors)` 才走 DexKit。调用方很容易选错重载，导致“类找到了、方法没找到”且无兜底。
3. **DexKit 结果无类型/成员校验**。`DexKitLocator.findClass` 直接取 `list.get(0)`（`DexKitLocator.java:108-114`），不检查是否为目标包、是否含预期成员；多候选时可能挂错类。
4. **`HookState` 是空壳**。`installed`/`markNotFound`/`hit` 都是空方法（`HookState.java:19-32`），只有 `note` 打日志。因此“某功能 8 个挂载点坏了 1 个”在运行时不可见，也无法在状态卡展示。
5. **`markNotFound` 语义混淆**：任何一个可选挂载点失败都会把整个 Feature 标成 not found（如果将来实现），但很多挂载点是可选的。

### 6.2 进程过滤：可用，但边界偏窄

`ProcessFilter`（`ProcessFilter.java:20-32`）：

- 只放行精确等于 `com.netease.cloudmusic` 的进程；`:patch/:safemode/:heap_analysis/:leakcanary/:play` 全部拒绝，`:play` 单独走 `installPlayOnly`。
- 其他子进程（`:push`、`:daemon`、`:widget`、`:ipc`、`:sandbox` 等）一律跳过，文档未说明这些进程是否需要隐私类 hook（PV-01/02/03 只装主进程）。
- `mProcessName` 来自 `ModuleLoadedParam.getProcessName()`（`CloudMusicToolModule.java:28`）；如果框架返回 null，`shouldHook` 返回 false，模块静默失效，且没有回退到 `PackageReadyParam` 的进程名。
- `isPlayProcess` 用 `endsWith(":play")`，对未来 `:play:xxx` 形式不兼容（概率低）。

结论：主流程正确；建议加 null 回退 + 明确的子进程策略日志。

### 6.3 配置读取/写回：可靠

- 目标进程侧 `Config.load` 用 `PROP_CAP_REMOTE` 判断能力，失败回退编译期默认值（`Config.java:45-69`）。
- 模块侧 `ConfigStore` 优先 remote，未绑定回退本地并 WARN（`ConfigStore.java:32-50`）。
- 集合类型有 `StringSet` → 逗号字符串的双向兼容（`Config.java:71-94`、`ConfigStore.java:52-102`）。
- 唯一问题：`SettingsFragment` 在 service 未绑定时会显示本地旧值，同时把开关置灰（`SettingsFragment.java:76,133`），可能出现“显示值与实际 remote 值不一致”的短暂状态。

### 6.4 日志：能用，但热路径无节制

- 统一 tag `CloudMusicTool`，`registered`/`hit` 格式清晰，便于 `check-mounts` 之外的人工核对。
- **hit 日志没有限流**：`CommentAdapter#y1` 每渲染一条评论打一次、`MMKV` 每次读缓存打一次、`getResources` 每次渲染打一次。真机滑动评论区/首页会产生大量 INFO 日志，既有性能开销，也会淹没真正的问题。
- `StatisticHooks` 自己做了 100 条限流（`StatisticHooks.java:45-51`），说明作者知道这个问题，但没有推广到其他 hook。
- 没有 debug/release 日志分级；release 包默认输出全部 hit。

### 6.5 DexKit 兜底：代码在，链路大概率不通

这是本次审计最重要的发现之一。三个独立断点：

1. **native 库加载路径**
   - `DexKitLocator.getBridge` 先尝试 `System.load(<moduleNativeDir>/libdexkit.so)`（`DexKitLocator.java:45-51`），失败再 `System.loadLibrary("dexkit")`（`DexKitLocator.java:56-61`）。
   - 实测 release APK 的 manifest 是 `android:extractNativeLibs=false`（aapt2 dump 确认），`lib/arm64-v8a/libdexkit.so` 以未压缩形式留在 APK 内，磁盘上的 `nativeLibraryDir` 目录通常没有该文件 → `soFile.exists()` 为 false。
   - `System.loadLibrary("dexkit")` 在目标进程里查找的是**网易云**的 nativeLibraryDir，不是模块的 → 也找不到。
   - 结果：`DexKitBridge.create` 前的加载阶段就失败，所有 fallback 静默返回 null。
2. **anchor 不是字符串字面量**（见 5.4）。即使 bridge 初始化成功，`addUsingString("LoadingAdManager")` 也匹配不到类，因为类名不在 dex 字符串池的“被该类使用”集合里。
3. **覆盖不全**。只有 `findClass` 和带 anchors 的 `findMethod` 会走 DexKit；`findMethod(module,f,cls,name)`、`findByNameQuiet`、`findByShape`、`findByNameAndArity` 都不会。也就是说“类改名”可能兜底，“方法改名”完全没兜底。

另外：

- DexKit 查询是同步的，发生在 `onPackageReady` 主线程；一旦真的开始全 dex 扫描，有 ANR 风险（目前因为前面失败，反而没触发）。
- 真机日志中没有 `DexKitBridge initialized successfully` 或 `DexKit fallback located` 记录，与上述判断一致。
- `Anchors.java` 定义了 5 个常量，但全仓库没有任何引用（`grep Anchors.` 为空），属于“看起来有设计、实际未接线”。

**建议**：在二期把它当作“未验证能力”处理——要么修好并用强制 fallback 测试证明，要么在 README/CHANGELOG 中删除“DexKit 兜底”的表述，避免给用户错误预期。

---

## 7. 代码重复与结构问题

1. **反射工具重复**
   - `BottomTabHooks.shouldKeepTab`（`BottomTabHooks.java:113-139`）与 `TopTabHooks.shouldKeepItem`（`TopTabHooks.java:392-418`）几乎是同一段“遍历 getter、比对集合”的代码，只是 getter 名单不同。
   - `TopTabHooks.firstStringGetter`（`TopTabHooks.java:300-317`）与 `HomeBlockHooks.collectCodes` 的 getter 循环、`CommentAdHooks.findField`（`CommentAdHooks.java:132-142`）与 `HomeBlockHooks.invokeNoArg` 都是同类反射样板。
   - `CommentAdHooks.hookNull`（`CommentAdHooks.java:109-119`）相当于一个缺失的 `HookKit.constNull`。
2. **`HookKit` 重载过多且语义不一致**：4 个 `findMethod` + `findMethodWithAnchors` + `findByNameQuiet` + `findByShape` + `findByNameAndArity`，其中哪些走 DexKit、哪些不校验参数，没有在命名上体现。调用方 67 处，很容易选错。
3. **每个 hook 文件重复 try/catch 脚手架**：21 个文件、3036 行，大部分是“try { findClass; if != null { findMethod; install } } catch { error }”。可以用一个 `Mount`/`HookGroup` 描述器把“定位 + 安装 + 失败记录”收拢。
4. **常量重复**
   - 目标包名：`CloudMusicToolModule.TARGET_PACKAGE`、`ProcessFilter.TARGET_PKG`、`SettingsFragment.TARGET` 三份。
   - `KEY_ALLOW_UNSUPPORTED`：`Config` 与 `VersionGate` 各一份。
   - `TAG = "CloudMusicTool"`：至少 5 个类各一份。
5. **Feature ↔ Hooks 的 switch 需要手工同步**：22 个 Feature、21 个 case（缺 `DRAWER`），新增功能必须同时改枚举和 switch，容易漏。
6. **死代码**
   - `TopTabHooks.installSelectGuard`（250-298）无调用。
   - `DrawerHooks` 整个类无调用，且 switch 无对应 case。
   - `HomeBlockHooks.findPageCode`（295-301）无调用。
   - `Anchors` 整个类无引用。
   - `Feature` 中 `STARTUP_DELAY` 排在 `HOME_BLOCKS` 前面，与 ID 顺序不一致（无功能影响，但影响阅读/日志顺序）。
7. **热路径性能**
   - `HomeBlockHooks.collectModuleKeys` 每次调用都 `Pattern.compile("home_[a-z0-9_]{6,}")`（`HomeBlockHooks.java:185-186`），应提为静态常量。
   - `collectPageCodes` 对每个节点 `String.valueOf(node)`，大对象可能生成巨大字符串；虽然有 16MB 上限，但每个 item 都做一次，首页 9 个模块就是 9 次。
   - `filterMmkvString` 对每个相关缓存字符串做 `new JSONObject/JSONArray` + 递归 + `toString()`，只在 `blockCode` 命中时才需要，但解析是前置的。
8. **`TopTabHooks` 原地修改宿主列表**：`list.clear()/addAll()`（153-155、196-198）可能破坏宿主缓存或触发 CME；应统一返回新 `ArrayList`。
9. **无测试**：`filterCodeListString`、`filterValue`、`Config.loadSet`、`VersionGate` 都是纯函数，完全可以单测；当前 `app/src` 只有 `main`。
10. **R8 keep 过宽**：`proguard-rules.pro` 有 `-keep class com.cloudmusictool.app.** { *; }`，等于对模块自身代码关闭了混淆/裁剪；release 体积优化基本无效，mapping 也失去意义。若没有反射调用全部 app 类的需求，应收窄到入口/Preference/Provider/DexKit。

---

## 8. `tools/check-mounts.sh` 覆盖审计

### 8.1 现状

- 共 85 个 `check`，覆盖 AD-01~08、UI-01/02/03/05/06/07/08/09、PV-01~05 的绝大多数旧挂载点。
- 检查方式：dexsql `SELECT count(*) FROM methods WHERE class_descriptor=... AND name=...`，只验证“类 + 方法名存在”，不验证参数、返回类型、重载数量。
- 默认路径：`APK=E:/download/base(2).apk`、`DEXSQL=E:/tools/dexsql/dexsql.exe`（`check-mounts.sh:7-8`），硬编码本机路径。

### 8.2 漏检清单（按重要性）

| 漏检项 | 为什么重要 |
|---|---|
| `Lcom/netease/cloudmusic/discovery/view/newframework/BaseArkV3Fragment;` `getTabDataList` / `hc` / `Tc` / `Lc` | UI-02 唯一已打通的数据链路；二期若尝试解封，必须第一时间知道它是否还在 |
| `Lcom/netease/cloudmusic/theme/ui/tab/MusicTabLayout$k;` 的 CharSequence setter（`S`/`B`/`K` 等） | UI-02 文本层入口；名字变化会导致“文本清空”失效 |
| `Lcom/netease/cloudmusic/meta/Ad;` | AD-06/AD-07 用它做签名匹配；缺失时静默退化为 arity 猜测 |
| `Lcom/netease/cloudmusic/meta/virtual/CommentListEntry;` | AD-05 `CommentAdapter#y1` 的参数类型；缺失时退化为 arity 1 |
| `Lcom/tencent/mmkv/MMKV;` `getString`、`decodeString(String)`、`mmapID` | UI-01 MMKV 兜底链路的三个关键入口，目前只检查了 `decodeString` 这个名字（任意重载都算 OK） |
| `Lcom/netease/cloudmusic/ui/LaunchDialog;` `showOnePassLoginDialog` | 故意不 hook，但应检查其存在，防止“保留登录弹窗”的假设失效 |
| UI-02 选择守卫 `selectSubTabById` / `setTabByCode` / `selectSubTabInner` | 若二期接线，需要知道方法是否还在 |
| `com.netease.cloudmusic.appupdate.d` 的 `B`/`I` 返回类型 | 当前只查名字，宿主改成 `void` 也会 PASS，但 hook 返回 Boolean 会失效 |

### 8.3 脚本自身问题

1. **只查名字，不查签名**：这是 9.6.08 上踩过的坑（UI-03 最初 getter 名不对、UI-02 方法在但链路不调用）。建议 dexsql 增加 `method_params`/`return_type` 列（如果 schema 支持），至少对关键挂载点断言 arity。
2. **不校验 APK 版本**：传入 9.6.09 也会跑，结果可能大量 GONE，但没有“这不是目标版本”的提示；建议先 `aapt2 dump badging` 断言 `versionCode=9006008`（或允许 `--allow-version`）。
3. **路径不可移植**：默认 `E:/download/base(2).apk` 与 `E:/tools/dexsql/dexsql.exe` 是作者本机路径；建议支持 `--apk`、`--dexsql`、环境变量，并在缺失时给出下载/构建提示。
4. **不检查模块元数据**：`java_init.list` 单入口、`module.prop` 的 min/targetApiVersion、`scope.list` 仍靠 RELEASE_CHECKLIST 手工勾选；可以脚本化。
5. **不检查 DexKit anchor 字符串**：对 `hit_noad_abtest`、`redPageDelayTime`、`show ad delayed` 这类真实字面量，可以加 dexsql 的 strings 查询；这比检查方法名更能反映“兜底是否可用”。
6. **输出没有分组/退出码区分**：`GONE` 与“dexsql 执行失败”都表现为 0，建议区分 `ERROR`（工具失败）和 `GONE`（挂载点缺失）。
7. **与代码的同步是手工的**：新增 hook 时容易忘记加 check；建议从 `Feature`/`MountSpec` 自动生成检查项（与第 10 节的多版本 profile 共用一份数据）。

---

## 9. 二期收尾清单

### P0（发布前必须完成）

#### P0-1 证明或移除 DexKit 兜底

- **改什么**：
  - 在 release manifest 显式 `android:extractNativeLibs="true"`（或 packaging `jniLibs.useLegacyPackaging=true`），确保 `libdexkit.so` 可被 `System.load`；或改为把 .so 复制到目标进程可读目录后加载。
  - 把 anchor 从“类名/方法名”换成真实字符串字面量（日志 tag、MMKV key、JSON key、埋点名、Toast 文案），并用 `ClassMatcher` 的 `fields/methods/superClass` 做结构约束。
  - 加一条启动自检日志：`DexKit bridge initialized` / `DexKit disabled: <原因>`。
- **怎么验收**：临时把 `LoadingAdManager` 的硬编码名改错（只改测试构建），冷启动日志必须出现 `DexKit fallback located class ...`，且 AD-01 仍然命中；再在 9.6.08 上确认无 ANR（fallback 扫描耗时打点）。
- **预计风险**：中。DexKit 查询在主线程，可能引入启动延迟；`extractNativeLibs=true` 会增大安装体积；错误 anchor 可能定位到错误类，必须加成员校验。

#### P0-2 UI-02 收口：实现或彻底下架

- **改什么**（二选一）：
  - **方案 A（推荐，成本低）**：彻底下架。删除 `TopTabHooks` 中未接线的 `installSelectGuard`、`hideTabView`、`SEEN_TITLES`，保留 Feature 占位；首页定制页隐藏 `top_channels_keep`（而不是置灰）；统一三处文案为“渲染层无法移除空频道，暂不支持”。
  - **方案 B（成本高）**：补齐渲染层移除（`TabLayout.removeTab`/`removeView` + `ViewPager2` adapter 同步 + 索引重映射），接线 `installSelectGuard`，改为返回新列表，并做“保留 1 个频道”的完整交互回归。
- **怎么验收**：方案 A：全仓库无 `top_channels_keep` 的 UI 入口、无 `installSelectGuard` 引用、文档只有一种说法；方案 B：保留“推荐”后冷启动，tab 栏只剩 1 项、空槽位不可点、左右滑动不进入隐藏频道，并有截图/hit 日志。
- **预计风险**：方案 A 低（只删代码/文案）；方案 B 高（可能影响首页 ViewPager 生命周期，需多轮真机回归）。

#### P0-3 UI-04 配置项停止空转

- **改什么**：短期在 `HomeCustomizeFragment` 把 `drawer_items_filter` 置灰并注明“暂不支持”，或直接移除该 `MultiSelectPreference`；同时在 `Hooks.installOne` 补 `DRAWER` 分支（抛/记日志）或删除 `DrawerHooks`。中期若要做，先定位真实抽屉数据源，再补 `DRAWER_KEYS`。
- **怎么验收**：用户在首页定制页看不到可编辑的抽屉项，或勾选后能产生 hit + 视觉变化；`Hooks` switch 与 `Feature` 枚举一一对应。
- **预计风险**：低（纯 UI/死代码清理）；若选“实现”，风险中高（无挂载点，需逆向）。

#### P0-4 让 Hook 健康度可见 + 补齐未验证矩阵

- **改什么**：
  - 实现 `HookState`：按 Feature 记录 `expected/installed/failed` 计数、最近失败原因、是否已 hit；用线程安全结构；在状态卡增加“本次注入：N/M 挂载点，K 项未命中”摘要。
  - 对 AD-02/04/06/07、UI-06、UI-09、PV-01/02/04 做强制触发验证：服务端不下发时，用本地构造数据/调试入口/抓包回放至少验证一次 hook 体执行；无法构造的，在 README 明确标“未验证”。
  - 把 `DEVICE_VERIFICATION.md` 的“registered”与“hit”分列，禁止把 registered 写成“零失效”。
- **怎么验收**：冷启动后状态卡能显示每项 `installed/expected`；人为删掉一个方法名，状态卡对应功能显示“缺失 1 项”；验证矩阵每项有 hit 日志或“未验证”标记。
- **预计风险**：中。`HookState` 在目标进程内，状态卡在模块进程，需要跨进程传递（可通过 remote prefs 写回，或仅写 logcat 由用户查看）；实现成本主要在 UI 数据通道。

#### P0-5 文案与行为对齐

- **改什么**：按第 4.2 节逐条修正 `strings.xml`、`README.md`、`CHANGELOG.md`；特别是 PV-05 的“只观测”与 `LogMonitorConfigManager` 拦截的矛盾、UI-06 的“权限引导”、UI-03 的“漫游/动态”、footer 的“Phase 0-5 已全部实装”、首页定制 hint 的“Hook 过滤已启用”。
- **怎么验收**：搜索“Phase”“漫游”“权限引导”“仅观测”等关键词，确认无残留；README 功能表与 `Feature`/hook 行为逐项对照通过。
- **预计风险**：极低（纯文档），但能显著降低用户误解和 issue 量。

### P1（二期应完成）

#### P1-1 返回值/签名安全

- **改什么**：`HookKit.constBool`/`skipVoid` 在安装前校验 `Method.getReturnType()`；不匹配时拒绝安装并记 WARN。`findMethod` 全系列统一为“名字 → 签名 → DexKit”三级，并校验返回类型。`AudioAdHooks#d`、`HintHooks#maybeDisplayHint` 等未知返回类型的地方改为显式判断。
- **怎么验收**：故意把某个 boolean 方法改成 void 的测试类，日志出现 `return type mismatch`，且不安装；真机无新增异常。
- **预计风险**：低到中。校验过严可能让某些“可兼容”的装箱类型被拒，需要白名单（已有 `isReturnTypeCompatible` 可复用）。

#### P1-2 `check-mounts.sh` 补漏与升级

- **改什么**：补第 8.2 节全部漏检项；增加 arity/返回类型断言；增加 APK 版本断言；支持 `--apk/--dexsql` 参数与自动 `adb pull`；区分 `ERROR`/`GONE`；从 profile 自动生成检查项。
- **怎么验收**：在 9.6.08 上 `OK=全部`；把 UI-02 的 `getTabDataList` 从测试 APK 删除后脚本必须报 GONE；传入 9.6.09 时先报“版本不匹配”。
- **预计风险**：低（工具脚本），但 dexsql schema 若不支持参数/返回类型，需要改用 dexdump 或增加二次解析。

#### P1-3 日志限流与分级

- **改什么**：`HookKit.hit` 增加 per-feature 计数/时间窗限流（例如每功能每 5 秒最多 1 条，或前 N 条后降为 DEBUG）；增加 `debug_log` 配置或编译期开关；热路径（评论、MMKV、resources）默认只打前若干条。
- **怎么验收**：连续滑动评论区 1 分钟，`CloudMusicTool` 日志不超过设定条数；关键 `registered`/`fallback` 日志仍完整。
- **预计风险**：低。限流可能掩盖首次命中，需要保留“首次必打”。

#### P1-4 进程过滤兜底

- **改什么**：`mProcessName == null` 时回退到 `PackageReadyParam` 的进程名（若 API 提供）或 `Application.getProcessName()`；对未知子进程打一条明确的 `skip secondary process` 日志；明确 `:push` 等进程是否要装隐私 hook。
- **怎么验收**：构造/模拟 `mProcessName=null`，模块仍能注入主进程；日志能看到跳过原因。
- **预计风险**：低。

#### P1-5 去重与死代码清理

- **改什么**：把 `shouldKeepTab`/`shouldKeepItem`/`firstStringGetter`/`findField`/`invokeNoArg`/`hookNull` 收进 `HookKit`；删除 `Anchors`、`findPageCode`、`installSelectGuard`（或接线）、`DrawerHooks`（或实现）；统一 `TARGET_PACKAGE`、`KEY_ALLOW_UNSUPPORTED`、`TAG`；`Hooks` switch 与 `Feature` 一一对应（可用 `Feature.installer` 函数式字段替代 switch）。
- **怎么验收**：`grep` 无重复实现；编译通过；行为不变（回归 AD-01/03、UI-01/03、PV-03/05）。
- **预计风险**：低到中（机械重构，需回归）。

#### P1-6 UI-08 未验证路径处理

- **改什么**：在 UI-08 未做真机验证前，把它标为 blocked 或加确认弹窗/风险提示；验证 `ResourceRouter` 强制 true 是否导致资源加载异常、WebView/播放页是否可接受。
- **怎么验收**：开启 UI-08 后冷启动、首页、播放页、评论区、设置页均无崩溃/黑屏；或保持默认关并在 README 标注“未验证”。
- **预计风险**：中（主题系统影响面大）。

#### P1-7 `allow_unsupported_version` 暴露方式

- **改什么**：要么在设置页加“高级：允许未适配版本（不保证）”开关，要么从 README 删除该说明并保留仅代码可改；同时把版本门禁从 `versionName` 等值改为 `versionCode`/前缀匹配。
- **怎么验收**：设置页可见/不可见与文档一致；改版本号测试能触发对应日志。
- **预计风险**：低。

#### P1-8 TopTabHooks 列表安全

- **改什么**：过滤后返回新 `ArrayList`，不原地 `clear/addAll`；`SEEN_TITLES` 改为按页面/会话清理或使用有界 LRU；若保留 blocked，则这些代码随 P0-2 一起处理。
- **怎么验收**：若解封，首页 tab 数据源在多次重建后无 CME/数据丢失。
- **预计风险**：低。

### P2（可排后）

1. **性能**：预编译 `PAGE_CODE`/`home_*` 正则；`collectPageCodes` 避免对每个 item 做 `String.valueOf`；MMKV 过滤先做轻量 `contains("blockCode")` 再解析；`filterValue` 用显式栈替代递归。
2. **单测**：为 `filterCodeListString`、`filterValue`、`Config.loadSet`、`VersionGate.checkVersion`、`ProcessFilter` 增加 JVM 单测（无需 Android 设备）。
3. **文档清理**：`RELEASE_CHECKLIST.md:1-3` 仍写“0.1.0 预发布”，`:62` 仍写“当前目录尚未 git init”（实际已有 4 个提交）；`DEVICE_VERIFICATION.md:146` 说“删除 6 个”但列出 7 个；`README.md:49` UI-03 描述过期。建议把“已验证/未验证/已知限制”做成一张表。
4. **R8**：收窄 `-keep class com.cloudmusictool.app.** { *; }`，只保留入口、Preference、Provider、DexKit 相关类。
5. **拆分 `HomeBlockHooks`**：DSL 过滤、经典发现页、MMKV 缓存、JSON 工具拆成 3~4 个类，便于单测和复用。
6. **构建可重复性**：`local.properties` 与 `keystore.properties` 不入库是正确的；建议补充 `gradle.properties` 的 `org.gradle.java.home` 或 README 中的 JDK 路径说明，避免换机构建失败。

---

## 10. 多版本兼容最小改造方案（不要求实现）

### 10.1 核心判断

当前 3036 行 hook 代码里，真正与版本强耦合的是“定位”：

- 57 处 `findClass` 的类名；
- 约 90 处方法名/字母名；
- 少量字段名（`CommentAdapter` 的 4 个字段）；
- 少数真实字符串 anchor。

hook 体（返回 false、清空列表、置 GONE、跳过任务）绝大多数与版本无关。因此**不需要为每个版本复制 hook**，只需要把“坐标”从代码里抽出来。

### 10.2 目标结构

```java
final class MountSpec {
    final String featureId;        // "AD-01"
    final String logicalName;      // "LoadingAdManager#E"
    final String[] classCandidates;// 精确类名（稳定类）
    final String[] stringAnchors;  // 真实 dex 字面量（用于 DexKit）
    final String methodName;       // 可空
    final Class<?>[] params;       // 可空，用于签名匹配
    final Class<?> returnType;     // 必填，用于校验
    final boolean critical;        // 缺失则整个功能不可用
}

final class TargetProfile {
    final long versionCode;        // 9006008
    final Map<String, MountSpec> mounts;
}

final class MountResolver {
    ResolvedMount resolve(MountSpec spec, ClassLoader cl);
    // 1) classCandidates 逐个 Class.forName + 成员校验
    // 2) DexKit：classCandidates 失败 → 按 stringAnchors + 结构（fields/methods/super）查类
    // 3) 方法：名字 + params + returnType → 签名扫描 → DexKit MethodMatcher
    // 4) 返回 source = HARDCODED | DEXKIT | SIGNATURE，confidence
}
```

### 10.3 最小改造步骤（按投入排序）

1. **机械抽取（0.5~1 天，零行为变化）**
   - 新建 `Profile9608`，把 57 个类名、方法名、字段名、真实 anchor 从各 hook 文件搬进去。
   - hook 文件只保留 `Feature f = ...; MountSpec spec = profile.mount("...");` 和 hook 体。
   - 这一步不改任何行为，但让“新增版本 = 新增一个 Profile 文件”成为可能。

2. **统一解析器（1~2 天）**
   - 把 `HookKit.findClass/findMethod*` 全部改为走 `MountResolver`，删除 `findByNameQuiet`/`findByShape`/`findByNameAndArity` 这些“猜”的入口（或降级为 resolver 内部策略）。
   - 每个解析结果必须校验返回类型；失败时记录 `featureId + logicalName + 原因`，不再静默。

3. **真实 anchor + 结构查询（2~3 天）**
   - 用 dexsql 对每个目标类 dump“该类引用的字符串”，挑选稳定字面量（日志 tag、MMKV key、JSON key、埋点名、Toast 文案）。
   - 对混淆短类（`q60.w0`、`tt0.d`、`jn` 等）改用 `ClassMatcher` 的 `fields`/`methods`/`superClass` + 方法签名，而不是类名。
   - 这一步是 DexKit 真正可用的前提，也是 P0-1 的一部分。

4. **版本门禁改为“按功能解析结果”**
   - `VersionGate` 只负责判断“是否在支持列表/是否允许 best-effort”，不再决定全局跳过。
   - `Hooks.installAll` 对每个 Feature：解析全部 critical mount；全部成功 → 安装；部分失败 → 安装可用部分 + 状态卡标记降级；全部失败 → 跳过 + 明确日志。
   - 未知版本：默认 best-effort（或按配置），但每项功能都有独立的“可用/降级/不可用”状态。

5. **Profile 与检查脚本共用一份数据**
   - `tools/check-mounts.sh` 从 `Profile*.java` 或导出的 JSON 生成检查项，避免代码/脚本两套清单漂移。
   - CI 对每个支持的版本跑一次；新版本接入流程 = 复制 Profile → 跑 check-mounts → 补 GONE 项 → 真机验证。

6. **优先级**
   - 先迁移 8 个高风险混淆类（`q60.w0`、`th0.h`、`rh0.c`、`tt0.d`、`vt0.r`、`lv0.j`、`fw0.e`、`jn`），因为它们每个版本必变；
   - 再迁移语义化业务类（`LoadingAdManager`、`UpdateInfo`、`Comment` 等），它们类名稳定，主要是方法签名校验；
   - 最后处理 UI-02 这类需要渲染层逆向的功能，不建议纳入“自动多版本”承诺。

### 10.4 现实预期

- **不要承诺“任意新版本自动兼容”**：混淆短类和单字母方法名无法靠字符串 anchor 稳定定位，必须每个大版本做一次人工核对。
- 合理目标是：**“最近 N 个版本白名单 + 结构探测兜底 + 按功能降级”**，并在状态卡明确告诉用户“当前版本适配度”。
- 最小可行版本（MVP）：只做 1（抽取 Profile）+ 2（统一解析器）+ 4（按功能降级），不追求 DexKit 全自动；这样下一个 9.6.x 版本只需补一个 Profile 文件，而不是改 20 个 hook 文件。

---

## 11. 附录

### 11.1 关键证据索引

| 结论 | 证据 |
|---|---|
| UI-02 运行时零注册 | `Hooks.java:32-35`（blocked 直接 continue） |
| UI-02 选择守卫未接线 | `TopTabHooks.java:250` 定义，全仓库无调用 |
| UI-04 配置空转 | `HomeCustomizeFragment.java:126` 绑定；`Config.java:29,61` 读取；无任何 hook 读取该键 |
| `Hooks` 缺 DRAWER 分支 | `Hooks.java:56-130`（21 个 case，22 个 Feature） |
| HookState 空实现 | `HookState.java:19-32` |
| DexKit 原生库加载路径 | `DexKitLocator.java:45-62`；APK `extractNativeLibs=false`（aapt2 实测） |
| anchor 多为类/方法名 | 见 5.4；`DexKitLocator.java:101-105` 用 `addUsingString` |
| `appupdate.d` anchor 复制错误 | `UpdateHooks.java:58` 传入 `"CMUpdateConfig"` |
| 热路径日志无限流 | `CommentAdHooks.java:42`、`HomeBlockHooks.java:108/136/470` |
| check-mounts 漏 UI-02 渲染链 | `check-mounts.sh:114-120` 只有旧链路，无 `BaseArkV3Fragment`/`MusicTabLayout` |
| 文档版本状态过期 | `RELEASE_CHECKLIST.md:1-3,62`；`DEVICE_VERIFICATION.md:146`；`README.md:49` |
| 无测试源码集 | `app/src` 下只有 `main` |

### 11.2 建议的二期验证记录模板

```text
功能: AD-06 播放页广告
目标版本: 9.6.08 (9006008)
挂载点: PlayerAdJarDelegateImpl#k, #q, MusicGiftModel#isValid, AdPlayerFullScreenHelper#checkNeedExpose
解析结果: 4/4 HARDCODED
触发场景: 播放一首带贴片广告的歌曲（服务端下发）
预期: 无黑胶广告层、无送礼贴纸
实际: [hit 日志 + 截图]
结论: 通过 / 降级 / 未验证
备注: 若服务端未下发，标记“未验证”，不得写“零失效”
```

### 11.3 本次审计未做的事

- 未在真机执行，未复现 UI-02/UI-04 的交互问题；
- 未反编译 9.6.08 验证 `Ad`/`CommentListEntry` 等依赖类是否存在（仅按代码推断）；
- 未运行 `tools/check-mounts.sh`（依赖本机 dexsql 与 APK 路径）；
- 未验证 DexKit 在目标进程的实际加载结果（结论基于打包配置与代码路径，建议按 P0-1 做强制 fallback 测试确认）。
