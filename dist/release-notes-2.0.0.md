云音乐工具箱 2.0.0（versionCode 5），适配网易云音乐 9.6.08。

## 架构升级

- **Profile / MountSpec / MountResolver**：133 个挂载点集中到 `Profile9608`，支持多候选类名、参数/返回类型校验、DexKit 结构/锚点兜底。
- **版本门禁**：改为 versionCode + `ProfileRegistry`；未适配版本按 `allowUnsupported` best-effort，按功能降级。
- **Hook 健康度**：`expected = attempted`；`degraded` 由 critical 挂载点失败驱动，`unavailable` 为 installed == 0；状态卡/诊断卡显示一致。
- **check-mounts v2**：从 Profile 自动生成检查项，断言 versionCode、类/方法存在、`method_kind IS NOT NULL`、参数/返回类型精确匹配。

## 行为等价

- 9.6.08 冷启动 110 条 `registered` 与 1.2.0 逐行一致，关键 hit 一致。
- 133 个挂载点 `check-mounts` 全 OK；状态卡 `104/104`、`degradedFeatures=0`、无 FATAL/ANR。

## 兼容性

- 仅承诺 9.6.08。新增版本 = 新增 Profile 分片 + 跑 check-mounts，不承诺任意版本自动兼容。

## 已知限制

- UI-04 仍不支持。
- 部分 UI 类没有可靠的 DexKit 字符串锚点，只能精确类名匹配；类改名需人工补 Profile。
- 1.2.0 的未自然触发项（AD-09/UI-10/UI-12/UI-13/UI-14）状态不变。
- Profile 解析失败会回退硬编码，回退路径的 critical 判定依赖 Profile 是否收录该坐标。

完整验收记录见仓库 `docs/phase2/2.0.0-VERIFY.md` 与 `docs/phase2/2.0.0-REVIEW.md`。
