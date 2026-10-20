云音乐工具箱 1.1.0（versionCode 3），适配网易云音乐 9.6.08。

## 收尾

- **DexKit 兜底真正可用**：release 启用 legacy packaging，加载顺序为模块 native 目录 → `System.loadLibrary` → APK 解压到目标 cacheDir；隐藏自检实测 `DexKit selftest located=... elapsed=182ms`，AD-01 仍命中。
- **Hook 健康度可见**：状态卡显示「本次尝试 104 个挂载点，14 项未命中」，诊断卡逐项展示 installed/expected/hit。
- **UI-02 顶部频道真实删除**：数据源过滤 + 适配器索引重映射；真机验证 6→1、6→2、空配置回退 6 项，隐藏频道不可点击/滑动。
- **UI-04 下架**：删除空转的抽屉配置入口，文案统一「暂不支持」。

## 新增功能

AD-09 搜索广告净化、AD-10 冷启动广告预加载裁剪、UI-10 播放页提示治理、UI-11 搜索热词轮播、UI-12 播客/AI DJ 引导、UI-13 小组件/推送引导、UI-14 歌词页推广、UI-15 云村/直播推广、PV-06 剪贴板读取拦截、PV-07 启动任务编排。

默认值：AD/UI 默认开启；UI-15、PV-06、PV-07 默认关闭。

## 已知限制

- AD-09、UI-10、UI-12、UI-13、UI-14 已注册但未自然触发；UI-15、PV-07 未开启验证。
- AD-10 已验证冷启动两个任务命中，`AdColdBootEntranceTask.init` 未命中。
- UI-02 频道管理页拖拽/删除未回归；仅适配 9.6.08。
- 不触碰 VIP/付费/版权/地区、青少年模式、登录/Cookie/风控。

完整验证记录见仓库 `docs/phase2/1.1.0-VERIFY-A.md` 与 `docs/phase2/1.1.0-REVIEW.md`。
