# 网易云 9.6.08 远程配置中心（CustomConfig）逆向补充

- 来源：agy 子代理 2026-10-20 运行（15 分钟超时，未产出最终报告）
- 回收方式：从 `agy-delegate` 事件流 `jmvgjoiyojynn.ndjson` + `~/.gemini/antigravity-cli/brain/d2a73e67-.../.system_generated/steps/*/output.txt` 提取全部 dexsql 查询与方法反汇编
- 结论：**网易云没有独立的 ConfigCenter/RemoteConfig/FeatureFlag 类**；真正的客户端配置体系是 `core/icustomconfig/ICustomConfig` + `core/customconfig/CustomConfigImpl`，底层由混淆类 `dd/b`（配置模型）、`dd/e`（分组注册）、`dd/o`（SharedPreferences 存储）承载。

---

## 1. 调用链

```
CustomConfigTask.init()                         // 启动任务，classes5.dex
  ├─ StartupABManager.getGroup("qd-default-config-priority")
  ├─ StartupABManager.getGroup("CustomConfigEnableGroupV2") == "t1"
  └─ 组装请求参数（4 个 Pair）
        IuRPVVmc3WWul9fT  ← NeteaseMusicUtils.I(context)   // 设备标识，禁止触碰
        tPJJnts2H31BZXmp  ← IPlayLiveBridge.getPlayLiveBuildConfigLookVersionName()

CustomConfigImpl.fetch()                        // 只发一次（fetched 标志）
  ├─ dd/e.e() ? dd/h.getConfig() : dd/h.a()     // Retrofit
  └─ CustomConfigImpl$c.b(call, response)
        code == 200 && data is Map
        → CustomConfigImpl.updateConfig(String json)

CustomConfigImpl.updateConfig(String)
  ├─ JSON.parseObject
  ├─ dd/e.e() ? legacyAppCustomConfig.H(json)
  │            : 遍历 entry，dd/e.b(groupKey) → dd/b.H(json)
  └─ mUpdated = true; 通知 mListeners

dd/b.H(JSONObject)
  ├─ c = config
  ├─ dd/o.x(groupKey, config.toString())        // 写 SharedPreferences
  ├─ dd/b.F(config)                             // 提取启动专用 key
  └─ dd/b.G()                                   // 通知监听器

dd/b.F(JSONObject)
  ├─ config["#SPECIAL_CONFIG_EXTENSION#"]["startupKeys"] (JSONArray)
  ├─ 把这些 key 的值拷进 dd/b.f
  └─ dd/o.B(groupKey, JSON.toJSONString(f, WriteMapNullValue))

读取：
ICustomConfig.getMainAppCustomConfig(default, String... keys)
  ├─ dd/e.c() != null ? getAppCustomConfig(appKey, default, keys)
  └─ 否则 getCustomSettingConfig(default, keys)
        → dd/b.v(default, keys) / dd/b.o(json, default, keys)
              → dd/b.A(json, key) 做 key 变体解析
              → 按 default 的类型做 Boolean/Byte/Integer/Long/... 转换
```

## 2. 关键方法签名（均已在 APK 中验证）

| 类 | 方法 | 作用 |
|---|---|---|
| `com.netease.cloudmusic.core.icustomconfig.ICustomConfig` | `getMainAppCustomConfig(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;` | 主 App 配置读取入口（varargs path） |
| 同上 | `getAppCustomConfig(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;` | 按 appKey 读取 |
| 同上 | `getCustomSettingConfig(Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;` | 无 appKey 时读取 |
| 同上 | `getMainAppCustomConfigWithType/WithClass(...)` | 带 Type/Class 的强类型读取 |
| `CustomConfigImpl` | `fetch()V` | 网络拉取（只执行一次） |
| `CustomConfigImpl` | `updateConfig(Ljava/lang/String;)V` | **整棵配置树的入口**，解析后分发 |
| `dd.b`（混淆基类） | `H(Lcom/alibaba/fastjson/JSONObject;)V` | 单组配置落盘 + 通知 |
| `dd.b` | `o(Lcom/alibaba/fastjson/JSONObject;Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/Object;` | 路径查找 + 类型转换 |
| `dd.b` | `A(Lcom/alibaba/fastjson/JSONObject;Ljava/lang/String;)Ljava/lang/String;` | key 变体解析 |
| `dd.b` | `F(Lcom/alibaba/fastjson/JSONObject;)V` | 提取 `#SPECIAL_CONFIG_EXTENSION#.startupKeys` |
| `dd.o` | `m(Ljava/lang/String;)Ljava/lang/String;` | 读 SP：key = `<group>fast_config`，默认 `"{}"` |
| `dd.o` | `x(Ljava/lang/String;Ljava/lang/String;)V` | 写 SP |
| `dd.o` | `B(Ljava/lang/String;Ljava/lang/String;)V` | 写启动专用 key |
| `ABTestManagerImpl` | `checkClientConfig(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;` | 从实验 clientConfig map 取一个值（只读） |

## 3. key 变体规则（重要）

`dd/b.A(json, key)` 的查找顺序：

1. `key#EDIT#<BuildInfo.GIT_VERSION>`（当前构建版本覆盖）
2. `key#CHAN#<utils.b2.c channel>`（渠道覆盖）
3. 原始 `key`

这意味着配置支持“按构建/渠道覆盖”。对模块的意义：**不要自己拼 `#EDIT#`/`#CHAN#` 去伪造配置**——这些是官方灰度机制，伪造会与服务端下发冲突，且 `GIT_VERSION`/`channel` 属于设备/包信息。

## 4. 存储与刷新

- **本地缓存**：SharedPreferences（`dd/o.l()` 惰性获取），每个 group 一个 key：`<group>fast_config`，值是整组 JSON 字符串。
- **刷新时机**：`CustomConfigTask.init()` 启动阶段；`fetch()` 有 `fetched` 幂等标志；服务端返回后 `updateConfig` 整体替换并通知监听器。
- **启动配置**：`#SPECIAL_CONFIG_EXTENSION#` 里的 `startupKeys` 会被单独抽出来落盘，供启动阶段使用。
- **接口路径**（strings 证据）：
  - `middle/clientcfg/config/full/delivery`
  - `middle/clientcfg/config/pushed/list/v2`
  - `middle/clientcfg/config/v2/full/delivery`
  - 缓存/日志标识：`wlclientcfg`、`ClientCfg null`
- **调试页**：`CustomDebugActivity` / `CustomDebugDetailActivity` / `CustomSearchActivity` 在 Manifest 中未 exported，不能作为模块入口。

## 5. 对二期的可用点

### 5.1 推荐：只读配置总览（低风险）

挂 `CustomConfigImpl.updateConfig(String)` 或 `dd.b.H(JSONObject)`：

- `updateConfig` 能拿到**整棵配置树**（所有 group 一次到手），最适合做“配置总览”；
- `dd.b.H` 能拿到单组 JSON，适合按组展示；
- 只记录 group、key 数、值的类型分布、变更 diff；不改返回值；
- 与 ABTest 总览合并成一个“实验与配置”页面，用户体验更好。

### 5.2 可选：单 key 强制（高风险，默认不做）

挂 `dd.b.o(...)` 或 `dd.b.H(...)` 注入白名单 key：

- 必须在 `dd.b.o` 做，因为这里是唯一统一读取点；
- 必须按 `default` 的类型做类型匹配（Boolean/Integer/Long/String），否则会 ClassCastException；
- 必须精确白名单，且排除所有付费/版权/登录/风控/设备标识 key；
- 服务端每次 `updateConfig` 会整体替换，强制值要么在 `H` 之后重新注入，要么在 `o` 读取时覆盖；
- **建议 1.2.0 不做**，先积累只读数据，确认哪些 key 真正影响体验。

### 5.3 启动 key 的观察价值

`#SPECIAL_CONFIG_EXTENSION#.startupKeys` 是官方用来控制启动阶段配置的清单。可以只读记录它，用于理解 PV-03 的 `StartupTask.needExecute` 为什么跳过/不跳过某些任务；**不建议直接改写**。

## 6. 红线

- 请求参数里的 `IuRPVVmc3WWul9fT ← NeteaseMusicUtils.I(context)` 是设备标识，**不 hook、不伪造、不上报**。
- `dd/e` 的分组注册、`dd/o` 的 SP 写入属于配置基础设施，**不做全局替换**，避免影响登录态/风控/推送。
- `middle/clientcfg/*` 是服务端接口，**不重放、不篡改响应**。
- 任何 `vip/pay/quality/trial/rights/order/region/youth/login/cookie/auth/risk/security` 语义的 key 一律不进白名单。

---

## 7. 对二期计划的修订建议

原计划 WP2-3 写的是“hook `ICustomConfig.getMainAppCustomConfig(...)` 只读”。根据本次回收，**更优的只读点是 `CustomConfigImpl.updateConfig(String)`**：一次拿到整棵树，避免在 getter 热路径上反复打日志。建议改为：

- WP2-3a：只读 `updateConfig(String)` + `dd.b.H(JSONObject)`，导出 group/key/类型统计；
- WP2-3b（可选）：只读 `dd.b.o(...)` 采样高频 key，用于判断哪些配置真正被业务读取；
- WP2-3c（暂不做）：单 key 强制。
