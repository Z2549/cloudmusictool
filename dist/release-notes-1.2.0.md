云音乐工具箱 1.2.0（versionCode 4），适配网易云音乐 9.6.08。

## 新增

- **实验页（第 4 个 Tab）**：只读展示实际命中的 ABTest（key / 分组 / 来源 / 次数），数据由目标进程经 StatusProvider 回写。
- **白名单强开**：22 个 curated 体验 key，单 key 选择 `c/t/t1/t2/t3/t4`；已探测但未收录的 key 可手动「提升为强开」；黑名单 key 显示「禁止强开」。
- **一键恢复默认**：清空所有强开配置，重启网易云生效。

## 安全边界

- 精确白名单 + 黑名单：VIP/付费/版权/音质/试听/订单/地区/青少年/登录/Cookie/鉴权/风控/免流/免广告，以及 `boot-`、`kv-`/`fh-kv-`、`wait-task-`、`cold-start-` 等前缀。
- 强开只改 `abtestgroup`，不改 `status`/`clientConfig`（合成对象除外）；启动 AB 只读。
- `:play` 进程不写实验观测，避免空快照覆盖主进程数据。
- 框架未连接时明确提示「配置未保存」，不会静默写本地。

## 验证

- 真机观测 200 个实验，source 覆盖 libra / old / startup。
- `minibarMarquee=t4`、`aosApiHttps=t4` 强开日志确认；一键恢复后 `forceKeys=0`，无崩溃。
- 32 个单测、lint、release 构建通过。

## 已知限制

- `tabMerge` 在 9.6.08 未被查询，无法用它做 UI 分组变化回归。
- 合成 config 的 `clientConfig` 为空 Map，`checkExperimentWithParam` 走默认值；本期不做参数强开。
- 黑名单 UI 展示已真机确认，但未逐项点选全部黑名单 key。
- 仅适配 9.6.08。

完整验证记录见仓库 `docs/phase2/1.2.0-VERIFY.md` 与 `docs/phase2/1.2.0-REVIEW.md`。
