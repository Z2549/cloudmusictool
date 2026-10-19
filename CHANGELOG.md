# Changelog

## 1.0.0 - 2026-10-19

正式版。已完成 25102RKBEC 真机验证（Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08）。

### 变更

- **真机验证通过**：82+ 挂载点零失效；AD-01/03/05/08、UI-01/03/05/07、PV-03/05 有命中实测；
  UI-01 首页过滤与 AD-03 开关做过开启/关闭双向回归。
- **UI-01 修复**：推荐页改走 DSL/MixContainer 链路（`MixContainerData.getResources/getBlockCodeOrderList`），
  修复「勾选隐藏卡片后无效」的问题；新增快捷入口/雷达歌单/排行榜三条实测别名映射。
- **首页定制清单重排**：按 9.6.08 真实下发 blockCode 重排为 30 项，删除 7 个不存在的 code。
- **UI-03 底栏**：选项修正为真实 tab（首页/搜索/关注/我的），实测 4→2 过滤生效。
- **AD-07**：`:play` 进程按需放行（实测 9.6.08 播放在主进程，属前瞻）。
- **PV-01~05 开启路径**：真机验证无闪退、登录与播放正常，默认仍关闭。

### 已知限制

- **UI-02 顶部频道保留暂不启用**：9.6.08 频道数据链路已定位（`BaseArkV3Fragment.getTabDataList`），
  但渲染层 tab 容器无法从外部可靠删除，空频道仍会留下可点击槽位。功能已置灰。
- **UI-04 抽屉精简暂不启用**：无可靠挂载点，功能已置灰。
- 依赖服务端下发的场景（运营弹窗、播放页贴片、引导气泡）未做自然触发目视验证。
- 网易云版本升级可能导致挂载点失效，届时需用 `tools/check-mounts.sh` 重新体检。

### 默认值

广告与界面类默认开启；隐私类（PV-01~05）默认关闭，需手动开启。

---

## 0.1.0 - 2026-10-19

首个预发布版本（代码完成，待真机验证）。

### 新增

- **广告拦截**
  - 开屏广告三层拦截（判定 / 数据源 / 落地页）+ 摇一摇 + 免广实验分支
  - 运营弹窗屏蔽（保留一键登录）
  - 更新弹窗与强制更新拦截
  - 首页信息流广告、评论区广告、播放页广告
  - 音频贴片与播客广告
  - 广告统计上报、外部 App 静默跳转、曝光预请求阻断
- **界面净化**
  - 首页 blockCode 逐项隐藏（含 MMKV 缓存过滤）
  - 顶部频道删除/排序解锁与保留清单
  - 底部导航精简
  - 启动延迟跳过
  - 引导气泡/评分/VIP/直播弹窗、青少年弹窗屏蔽
  - 自动播放控制（冷启动/信息流/Banner/蓝牙/4G MLog）
  - 实验性强制深色模式
  - 头像挂件关闭
- **隐私**
  - OAID 上报拦截
  - 日志写盘任务屏蔽
  - 启动任务黑名单
  - APM 上报抑制
  - 统计数据观测（只读）

### 工程

- libxposed API 102（单入口、`META-INF/xposed` 元数据、钩子 id、PROTECTIVE 异常模式）
- DexKit 2.3.0 兜底定位（硬编码失败时按 anchors 查询）
- 9.6.08 版本门禁
- Material 3 设置界面：功能 / 首页定制 / 关于
- R8 保留入口、Preference、Provider、DexKit 与 libxposed service

### 审查修复

- 修正 `AdAudioClipManager.d/c` 的返回值（null / Boolean）
- 取消对 `APMTrackerImpl.init`、`AegisSdkInitTask` 的拦截，避免破坏 ServiceFacade 与网络加密
- 修正底栏 `BottomTabInfoVO` getter（`getTabTitle` / `getTabCode`）
- 移除 `n40.b.c`、`CommentAdapter.o1` 等无效或高风险 Hook
- 移除隐私协议/首启/权限引导绕过
- 修复 8 处 after-hook 二次 `proceed()`
- 版本门禁改为轻量 `getPackageInfo`，版本未知时 best-effort 放行
- R8 增加 app 包与 libxposed service keep 规则

### 已知限制

- 仅适配网易云音乐 9.6.08
- UI-04 侧栏抽屉精简暂未启用
- 服务端下发广告只能阻止展示/上报，不能阻止请求
- 统计观测不拦截上报
