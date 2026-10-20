# 网易云音乐 Android 9.6.08 ABTest 体系逆向分析

> 目标样本：`E:\download\base(2).apk`（versionName 9.6.08 / versionCode 9006008 / targetSdk 33 / compileSdk 34）
> 分析方式：只读。使用 `E:\tools\dexsql\dexsql.exe` 对多 dex 做 SQL 查询与 smali 反汇编；使用 jadx 仅导出 AndroidManifest.xml。
> 结论先行：ABTest2 是一个“服务端下发 + 本地 JSON 缓存 + 兜底分组 c”的客户端实验框架；正式包没有可用的调试后门（`ABTestDebugActivity` 未导出，且其写入口被 `FLAG_DEBUGGABLE` 门禁拦住）。要稳定“强制指定分组”，必须在客户端运行时 hook 查询入口，或 root 后改本地缓存（会被服务端刷新覆盖）。

---

## 0. 结论摘要

1. **公开取分组/取值的入口不是 `getGroup/getValue`，而是 `IABTestManager` 上的 `checkExperiment*` / `checkBelong*` / `checkClientConfig`。**
2. **命中判断**：`checkExperiment` 返回 `ABTestConfig`；`ABTestConfig.getGroupKeys()` 把服务端分组归一化为 `c / t / t1 / t2 / t3 / t4`；`isEnable()` 只看 `status == 1`；`isHit(name)` 只比较实验名。
3. **优先级**：Libra 模型优先于旧 AB 模型；同一模型内，调试覆盖 map `l`（仅 debuggable） > 服务端/本地缓存 map `e` > 无配置返回 null > 调用方兜底 `"c"`。代码里没有逐 key 的默认分组表。
4. **缓存**：默认写 SharedPreferences XML `AB_TEST_CONFIG_CACHE_FILE_NAME.xml`（经 `TreasurePreferences`/`TreasureProvider` 代理），key 为 `<包名>EXTRA_STRING_AB_EXPERIMENTS_CONFIG`；当启动 AB `wait-task-0805-v2` 命中 T 时，改用 MMKV `AB_TEST_CONFIG_MMKV_FILE_NAME`。服务端每次刷新会整体替换该 JSON 数组。
5. **拉取时机**：`ABInitTask` 启动任务只加载本地缓存；真正网络拉取由 `PushService` 的两条轮询负责：旧 AB 约 3 小时一次（debug 15 分钟），Libra 约 1 小时一次（debug 1 分钟）；另有跨进程广播同步与日期变更清理。
6. **ABTestDebugActivity**：Manifest 中未声明 `android:exported`、无 intent-filter，targetSdk 33 下等价于 **exported=false**，正式包不能直接用 `adb am start` 打开；即使 root 打开，正式包 `e0.g()` 为 false，编辑分组走 `updateExperiment4Dev` 会被直接 return，**不会生效**。
7. **最值得做的 hook 点**：`ABTestManagerImpl.checkExperiment(String,boolean)`、`ABTestConfig.getGroupKeys()`、`ABTestManagerImpl.genericCheckExperimentWithParam(...)`、`com.netease.cloudmusic.abtest2.b.x(String,boolean)`、`StartupABManager.getGroup(String)`。
8. **明确不能碰**：VIP/付费/版权/音质/试听/订单/专辑购买、青少年模式、登录/Cookie/隐私/鉴权、KV/Cookie 存储、免流/免广告、安全风控相关 key。

---

## 1. 样本与方法

### 1.1 基础信息

| 项 | 值 |
|---|---|
| APK | `E:\download\base(2).apk` |
| versionName | 9.6.08 |
| versionCode | 9006008 |
| targetSdkVersion | 33 |
| minSdkVersion | 23 |
| compileSdkVersion | 34 |
| 多 dex | classes.dex ~ classes19.dex（共 19 个） |
| abtest2 包 | 57 个类（含内部类），401 个有方法体的方法 |

### 1.2 证据命令

```bash
# 包内类
/e/tools/dexsql/dexsql.exe query --input "/e/download/base(2).apk" \
  --sql "SELECT descriptor, superclass_descriptor FROM classes \
         WHERE descriptor LIKE 'Lcom/netease/cloudmusic/abtest2/%' ORDER BY descriptor"

# 方法
/e/tools/dexsql/dexsql.exe query --input "/e/download/base(2).apk" \
  --sql "SELECT class_descriptor,name,descriptor FROM methods \
         WHERE class_descriptor='Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;' \
         AND opcode_count IS NOT NULL"

# 方法体
/e/tools/dexsql/dexsql.exe decode --input "/e/download/base(2).apk" \
  --method 'Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;->checkExperiment(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;'
```

---

## 2. `com.netease.cloudmusic.abtest2` 包结构总览

### 2.1 核心类型

| 类 | 角色 |
|---|---|
| `IABTestManager` | 对外接口。初始化、刷新、取实验、取分组、取参数、取全部配置、上报日志都在这里 |
| `ABTestManagerImpl` | 单例实现。持有 `mModel`（旧 AB）与 `libraModel`（Libra AB） |
| `ABTestConfig` | 实验配置模型：`abtestname`、`abtestgroup`、`status`、`clientConfig`；Parcelable |
| `LibraABTestConfig` | 继承 `ABTestConfig`，多 `log`、`expInfo`、`timestamp` |
| `b`（ABModel） | **混淆后的抽象基类**，真正的存储/查找/刷新/广播同步逻辑 |
| `q` | 旧 AB 模型实现，继承 `b` |
| `p` | Libra AB 模型实现，继承 `b` |
| `a` | 网络请求参数/自定义配置拼装（`abtest#...`） |
| `c` | 运行期开关单例：`a()/b(ZZ)/c()`，决定调试态与缓存介质 |
| `d` | 动态 AB 定义注册表，读取 `abtest#dynamicABDefines` |
| `e` | 静态工厂 `a(Z)`：创建并初始化 `ABTestManagerImpl`，注册到 `ServiceFacade` |
| `f` | key 注册表聚合器：`c([Ll;)` 注册 14 个注册表，`a()` 取实验 key，`b()` 取开关 key |
| `h` | ABTestKeysUtil 调用监控：未注册 key 的告警、`abtest_prefer_file` 开关 |
| `i` | 调试悬浮窗/点击处理（`WindowManager`、`View`） |
| `j` | 实验回调接口：`getName` / `notifyExperiments` / `updateABControl` |
| `k` | Retrofit API 接口（旧 AB / Libra 两个请求） |
| `l` | key 注册表接口：`a()` 实验 key 列表，`b()` 开关 key 列表 |
| `m` | 刷新回调 `onRefreshCallback(JSONArray)` |
| `n` | 刷新完成回调 `a()` |
| `o` | RPC AB key 管理器接口 |
| `r` | 5 个布尔开关（`a()`~`e()`） |
| `s` | RN AB key 管理器 |
| `t` | ABTest1/2/3/4 更新监听 |
| `g` | 调试页 ViewModel |
| `ABTestDebugActivity` + `$a~$d`、`ABTestViewHolder` | 调试 UI |

### 2.2 key 注册表（`l` 的实现）

`ABInitTask.init()` 一次性注册 14 个注册表：

```text
com.netease.cloudmusic.abtest2.d      -> abtest#dynamicABDefines
k5.b  212 个实验 key + 8 个开关 key   -> 播放器/音频
k5.i 1111 个实验 key + 125 个开关 key -> 主 App 大杂烩（广告/首页/搜索/评论/下载/通知/推荐/付费）
k5.f   60 个实验 key + 26 个开关 key  -> 直播
k5.j   40 个实验 key + 11 个开关 key  -> 网络/HTTPDNS/QUIC
k5.g   18 个实验 key + 6 个开关 key   -> 直播/Web
k5.h   13 个实验 key                 -> Mlog
k5.e    2 个实验 key                 -> KTV/跳过前奏
k5.l   39 个实验 key + 39 个开关 key  -> KV/SP 存储
k5.a    1 个实验 key                 -> APM
k5.m    1 个实验 key                 -> 视频读缓冲
k5.d    4 个实验 key                 -> DAI/设备等级
k5.c    1 个实验 key                 -> JSON 解析
k5.k    1 个实验 key                 -> 热更新版本
```

去重后约 **1712 个 key**（实验 1503 条 + 开关 215 条，跨注册表有少量重复）。每个注册表 `a()` 返回实验 key，`b()` 返回开关 key；开关 key 查询时会自动加 `FH-` 前缀（见 `b.x`）。

> 证据：`ABInitTask.init()` 里 `f.c([...])` 的 14 个 `new-instance`；`f.a()/f.b()` 遍历注册表；`b.x` 中 `const-string v2, "FH-"`。

---

## 3. 核心调用链

### 3.1 初始化入口

`com.netease.cloudmusic.process.task.app.create.ABInitTask`（启动任务，classes5.dex）：

```smali
# ABInitTask.init()
invoke-virtual {v0, v2, v1}, Lcom/netease/cloudmusic/abtest2/c;->b(ZZ)V   # c.b(isABInitTaskGroupT(), isABInitTaskGroupT())
invoke-static {v0}, Lcom/netease/cloudmusic/abtest2/f;->c([Lcom/netease/cloudmusic/abtest2/l;)V
invoke-static {v2}, Lcom/netease/cloudmusic/abtest2/e;->a(Z)Lcom/netease/cloudmusic/abtest2/IABTestManager;
```

- `excludeProcess()` 返回 `[14, 15]`，这两个进程不跑 ABInitTask。
- `dependency()` 在 `StartupABManager.isFixABClearUserLog()` 为 true 时依赖 `CustomConfigTask`、`CustomConfigReducedTask`、`AppGlobalEventTask`、`MMKVTask`、`SessionTask`。
- `c.b(isABInitTaskGroupT(), isABInitTaskGroupT())` 同时设置 `c.b`/`c.c`；`c.c()` 决定后续缓存走 MMKV 还是 SharedPreferences。
- `e.a(false)`：设置 `DISABLE_LIBRA=false`，`getInstance()`，`init(context, process)`，`addABTestCodes(f.a(), f.b())`，最后 `ServiceFacade.put(IABTestManager.class, impl)`。

`e.a(Z)` 的关键字节码：

```smali
sput-boolean v2, Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;->DISABLE_LIBRA:Z
invoke-static {}, Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;->getInstance()...
invoke-interface {v2, v0, v1}, Lcom/netease/cloudmusic/abtest2/IABTestManager;->init(Landroid/content/Context;I)V
invoke-static {}, Lcom/netease/cloudmusic/abtest2/f;->a()Ljava/util/List;
invoke-static {}, Lcom/netease/cloudmusic/abtest2/f;->b()Ljava/util/List;
invoke-interface {v2, v0, v1}, Lcom/netease/cloudmusic/abtest2/IABTestManager;->addABTestCodes(Ljava/util/List;Ljava/util/List;)V
invoke-static {v0, v2}, Lcom/netease/cloudmusic/common/ServiceFacade;->put(...)
```

非主进程/降级时会用 `EmptyABTestManager`：

```smali
# EmptyABTestManager.checkExperiment(String)
const/4 v1, 0x0
return-object v1
# checkBelongToWhichGroupWithoutLog(String)
const-string v1, "c"
return-object v1
```

> 结论：hook 必须命中主进程的 `ABTestManagerImpl`；`EmptyABTestManager` 是空实现。

### 3.2 `IABTestManager` 公开方法签名

以下为接口上实际有方法体的签名（`access_flags=1025`，public abstract）：

```text
init(Landroid/content/Context;I)V
addABTestCodes(Ljava/util/List;Ljava/util/List;)V
refresh(JLcom/netease/cloudmusic/abtest2/m;)V
refreshLibra(J)V
refreshLibra(JLcom/netease/cloudmusic/abtest2/n;)V
refreshLocalCache(Ljava/lang/String;Lcom/netease/cloudmusic/abtest2/m;)V
refreshLibraLocalCache(Ljava/lang/String;Lcom/netease/cloudmusic/abtest2/m;)V
isLocalCacheExit()Z

checkExperiment(Ljava/lang/String;)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
checkExperiment(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
checkExperiment(Lcom/netease/cloudmusic/abtest2/j;)V

checkBelongToWhichGroup(Ljava/lang/String;)Ljava/lang/String;
checkBelongToWhichGroup(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
checkBelongToWhichGroupWithoutLog(Ljava/lang/String;)Ljava/lang/String;

checkBelongGroupC(Ljava/lang/String;)Z
checkBelongGroupC(Ljava/lang/String;Z)Z
checkBelongGroupC(Ljava/lang/String;Landroidx/lifecycle/ViewModelStoreOwner;)Z
checkBelongGroupC(Ljava/lang/String;Landroidx/lifecycle/ViewModelStoreOwner;Z)Z

checkBelongGroupT(Ljava/lang/String;)Z
checkBelongGroupT(Ljava/lang/String;Z)Z
checkBelongGroupT(Ljava/lang/String;Landroidx/lifecycle/ViewModelStoreOwner;)Z
checkBelongGroupT(Ljava/lang/String;Landroidx/lifecycle/ViewModelStoreOwner;Z)Z

checkClientConfig(Ljava/lang/String;)Ljava/util/Map;
checkClientConfig(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;

checkExperimentWithParam(Ljava/lang/String;Ljava/lang/String;Z)Z
checkExperimentWithParam(Ljava/lang/String;Ljava/lang/String;J)J
checkExperimentWithParam(Ljava/lang/String;Ljava/lang/String;D)D
checkExperimentWithParam(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

getAllConfig()Ljava/util/Map;
getAllSwitchConfigs()Ljava/util/Set;
getExpInfo(Ljava/lang/String;)Ljava/util/Map;
getBucketInfoByGroupName(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
getLogStr()Ljava/lang/String;
getLibraLogStr()Ljava/lang/String;
getLibraLogStrOriginal()Ljava/lang/String;
logServerExperiments(Ljava/lang/String;)V
getRpcABTestKeyManager()Lcom/netease/cloudmusic/abtest2/o;

updateExperiment4Dev(Ljava/lang/String;Lcom/netease/cloudmusic/abtest2/ABTestConfig;)V
removeExperiment4Dev(Ljava/lang/String;)V
updateExperimentSwitch4Dev(Ljava/lang/String;)V
removeExperimentSwitch4Dev(Ljava/lang/String;)V
updateLibraExperiment4Dev(Ljava/lang/String;Lcom/netease/cloudmusic/abtest2/LibraABTestConfig;)V
removeLibraExperiment4Dev(Ljava/lang/String;)V
updateLibraExperimentSwitch4Dev(Ljava/lang/String;)V
removeLibraExperimentSwitch4Dev(Ljava/lang/String;)V
updateLibraCheckedConfig(Ljava/lang/String;Ljava/lang/String;)Z
setFixABTestLog(Ljava/lang/String;)V
```

**没有 `getGroup`/`getValue`/`getBool` 这类方法。** 取值统一走：

- 分组：`checkBelongToWhichGroup*` / `checkBelongGroupC/T` / `ABTestConfig.getGroupKeys()`
- 参数：`checkExperimentWithParam(expName, paramKey, default)` 或 `checkClientConfig(expName, paramKey)`
- 整个实验对象：`checkExperiment(expName)`

### 3.3 `ABTestConfig` / `LibraABTestConfig`

字段：

```text
ABTestConfig:
  abtestname   Ljava/lang/String;
  abtestgroup  Ljava/lang/String;
  status       I
  clientConfig Ljava/util/Map;
  CREATOR      Landroid/os/Parcelable$Creator;

LibraABTestConfig extends ABTestConfig:
  log        Ljava/lang/String;
  expInfo    Ljava/util/Map;
  timestamp  J
```

关键方法：

```smali
# ABTestConfig.isEnable()
iget v0, v2, Lcom/netease/cloudmusic/abtest2/ABTestConfig;->status:I
const/4 v1, 0x1
if-ne v0, v1, :label_0006
...
return v1                     # status == 1 -> true

# ABTestConfig.isHit(String)
iget-object v0, v1, ...->abtestname:Ljava/lang/String;
invoke-static {v2, v0}, Landroid/text/TextUtils;->equals(...)Z
return v2                     # 只比较实验名

# ABTestConfig.getGroupKeys()
# 对 abtestgroup 做 sparse-switch：
#   "c"  -> "c"
#   "t"  -> "t"
#   "t1" -> "t1"
#   "t2" -> "t2"
#   "t3" -> "t3"
#   "t4" -> "t4"
# 其他 -> "c"
```

`LibraABTestConfig.getGroupKeys()` 不复用上面的 switch，而是：

```smali
iget-object v0, v1, ABTestConfig;->abtestgroup:Ljava/lang/String;
invoke-static {v0}, Landroid/text/TextUtils;->isEmpty(...)Z
if-eqz ... :label_000b
const-string v0, "c"
return-object v0
:label_000b
iget-object v0, ...->abtestgroup:Ljava/lang/String;
return-object v0
```

> 因此 Libra 的分组字符串不限于 `c/t/t1-t4`，可以是任意服务端分组名；旧 AB 则会被归一化成 `c` 或 `t*`。

### 3.4 `ABTestManagerImpl` 的查询实现

```smali
# checkExperiment(String) -> checkExperiment(String, true)
const/4 v0, 0x1
invoke-virtual {v1, v2, v0}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;Z)...

# checkExperiment(String, boolean)
iget-object v0, v1, ABTestManagerImpl;->libraModel:Lcom/netease/cloudmusic/abtest2/b;
if-eqz v0, :label_0009
invoke-virtual {v0, v2, v3}, Lcom/netease/cloudmusic/abtest2/b;->x(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
move-result-object v0
...
:label_0009
const/4 v0, 0x0
:label_000a
if-nez v0, :label_0012
iget-object v0, v1, ABTestManagerImpl;->mModel:Lcom/netease/cloudmusic/abtest2/b;
invoke-virtual {v0, v2, v3}, Lcom/netease/cloudmusic/abtest2/b;->x(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
...
return-object v0
```

即：**Libra 优先，Libra 没有再用旧 AB。**

```smali
# checkBelongToWhichGroupWithoutLog(String)
invoke-virtual {v1, v2, v0}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;Z)...
if-nez v2, :label_000a
const-string v2, "c"
goto :label_000e
:label_000a
invoke-virtual {v2}, ABTestConfig;->getGroupKeys()Ljava/lang/String;
:label_000e
return-object v2
```

```smali
# checkBelongGroupC(String, boolean)
invoke-virtual {v0, v1}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;)...
if-nez v1, :label_0007
goto :label_0011
:label_0007
const-string v2, "c"
invoke-virtual {v1}, ABTestConfig;->getGroupKeys()...
invoke-virtual {v2, v1}, Ljava/lang/String;->equals(...)Z
:label_0011
return v2
```

```smali
# checkBelongGroupT(String, boolean)
invoke-virtual {v0, v1}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;)...
if-nez v1, :label_0007
goto :label_0016
:label_0007
const-string v2, "c"
invoke-virtual {v1}, ABTestConfig;->getGroupKeys()...
invoke-virtual {v2, v1}, Ljava/lang/String;->equals(...)Z
if-nez v1, :label_0015
const/4 v2, 0x1
goto :label_0016
:label_0015
const/4 v2, 0x0
:label_0016
return v2
```

> 默认参数不同：1 参 `checkBelongGroupC(String)` 传 `true`，1 参 `checkBelongGroupT(String)` 传 `false`。所以**无配置时视为 C 组**（`checkBelongGroupC` 为 true，`checkBelongGroupT` 为 false）；`checkBelongToWhichGroup(String)` 返回 `"c"`。

### 3.5 `b.x(String, boolean)`：真正的查找核心

`b` 是混淆后的 `ABModel`，`x` 是查表入口（`file_id=5, method_idx=7891`）：

```smali
.method x(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
    const/4 v0, 0x0
    if-nez v4, :label_0004
    return-object v0                       # key 为空 -> null
    :label_0004
    invoke-static {v4}, Lcom/netease/cloudmusic/abtest2/h;->a(Ljava/lang/String;)V
    # 调试开关集合 n / 实验集合 i / 开关集合 j
    iget-object v1, v3, ...->n:Ljava/util/Set;
    invoke-direct {v3, v4, v1}, ...->y(Ljava/lang/String;Ljava/util/Set;)V
    iget-object v1, v3, ...->j:Ljava/util/Set;
    invoke-interface {v1, v4}, Ljava/util/Set;->contains(Ljava/lang/Object;)Z
    const-string v2, "FH-"
    if-eqz v1, :label_002b
    invoke-virtual {v4, v2}, Ljava/lang/String;->startsWith(...)Z
    if-nez v1, :label_002b
    # 开关 key 自动加 FH- 前缀
    ...
    :label_002b
    # 读锁
    iget-object v1, v3, ...->k:Ljava/util/concurrent/locks/ReentrantReadWriteLock;
    ...
    invoke-virtual {v3}, ...->U()Z            # e map 是否为空
    if-nez v1, :label_0049
    return-object v0
    :label_0049
    # 只有 debuggable 才看调试覆盖 map l
    invoke-static {}, Lcom/netease/cloudmusic/utils/e0;->g()Z
    move-result v2
    if-eqz v2, :label_005f
    iget-object v2, v3, ...->l:Ljava/util/concurrent/ConcurrentHashMap;
    if-eqz v2, :label_005f
    iget-object v0, v3, ...->l:Ljava/util/concurrent/ConcurrentHashMap;
    invoke-virtual {v0, v4}, Ljava/util/concurrent/ConcurrentHashMap;->get(...)
    check-cast v0, Lcom/netease/cloudmusic/abtest2/ABTestConfig;
    :label_005f
    if-nez v0, :label_0069
    iget-object v0, v3, ...->e:Ljava/util/concurrent/ConcurrentHashMap;
    invoke-virtual {v0, v4}, Ljava/util/concurrent/ConcurrentHashMap;->get(...)
    check-cast v0, Lcom/netease/cloudmusic/abtest2/ABTestConfig;
    :label_0069
    # 命中且 enabled 时做上报/广播
    if-eqz v0, :label_0090
    invoke-virtual {v0}, ABTestConfig;->isEnable()Z
    if-eqz v2, :label_0090
    if-eqz v5, :label_0090
    if-eqz v1, :label_007b
    invoke-virtual {v3}, ...->D()Z
    if-nez v5, :label_0090
    :label_007b
    iget-object v5, v0, ABTestConfig;->abtestname:Ljava/lang/String;
    invoke-virtual {v3, v4}, ...->E(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v4
    invoke-direct {v3, v5, v4}, ...->x0(...)Z
    if-eqz v4, :label_0090
    # 广播 ACTION_LIVE_EXPERIMENTS_CONFIG_CHECKED
    ...
    :label_0090
    return-object v0
.end method
```

查找顺序可以精确写成：

1. `l`（调试覆盖 map，**仅 `e0.g()==true`，即 APK 可调试**）
2. `e`（服务端/本地缓存 map）
3. 都没有 -> `null` -> 调用方兜底 `"c"`

`f` 是“已检查/已上报”标记 map，不参与分组取值。

### 3.6 取值参数实现

```smali
# genericCheckExperimentWithParam(expName, paramKey, default)
invoke-virtual {v2, v3}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;)...
if-nez v0, :label_0007
return-object v5                        # 无实验 -> default
:label_0007
iget-object v0, v0, ABTestConfig;->clientConfig:Ljava/util/Map;
if-eqz v0, :label_0062
invoke-interface {v0}, Ljava/util/Map;->size()I
if-gtz v1, :label_0012
goto :label_0062
:label_0012
invoke-direct {v2, v0, v3, v4}, ABTestManagerImpl;->checkParam(...)Z
if-eqz v3, :label_0062
# 按 default 的类型转换
# Long / String / Double / Boolean("1" -> true)
...
:label_0062
return-object v5
```

`checkParam` 在参数不存在时：debug 包抛 `IllegalArgumentException`，正式包返回 false（于是 `genericCheckExperimentWithParam` 返回默认值）。

```smali
# checkClientConfig(String)
invoke-virtual {v0, v1}, ABTestManagerImpl;->checkExperiment(Ljava/lang/String;)...
if-eqz v1, :label_0009
iget-object v1, v1, ABTestConfig;->clientConfig:Ljava/util/Map;
return-object v1
:label_0009
const/4 v1, 0x0
return-object v1
```

---

## 4. 分组值来源、优先级与缓存

### 4.1 优先级（从高到低）

| 优先级 | 来源 | 载体 | 是否正式包可用 |
|---|---|---|---|
| 1 | Libra 模型 | `ABTestManagerImpl.libraModel`（`p`） | 是 |
| 2 | 旧 AB 模型 | `ABTestManagerImpl.mModel`（`q`） | 是 |
| 3（模型内） | 调试覆盖 map `l` | `<pkg>EXTRA_STRING_AB_EXPERIMENTS_CONFIG_DEBUG` | **否**，`e0.g()` 门禁 |
| 4（模型内） | 服务端/本地缓存 map `e` | `<pkg>EXTRA_STRING_AB_EXPERIMENTS_CONFIG` | 是 |
| 5 | 无配置 | `null` | 是 |
| 6 | 调用方兜底 | `"c"`（`checkBelongToWhichGroup`/`WithoutLog`） | 是 |

注意：

- `libraModel` 与 `mModel` 是两套独立 map；同名 key 时 **Libra 先返回**。
- 旧 AB 的 `getGroupKeys()` 只认 `c/t/t1/t2/t3/t4`；其他字符串一律归为 `c`。
- `status != 1` 时 `isEnable()` 为 false，但 `checkExperiment` 仍会返回该对象（`x` 里的 `isEnable` 只控制上报/广播，不控制返回）。
- 代码里**没有逐 key 默认分组表**。默认分组就是“服务端没下发/本地没缓存时的 `c`”。

### 4.2 服务端下发

旧 AB 请求：`q.J(String)` 使用 `codeNames` 参数；响应 `data` 是 JSONArray：

```smali
# b.u0(JSONArray, callback)
invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
invoke-virtual {v2, v3, v0, v1}, Lcom/netease/cloudmusic/abtest2/b;->d0(Lorg/json/JSONArray;J)...
move-result-object v0
iput-object v0, v2, Lcom/netease/cloudmusic/abtest2/b;->e:Ljava/util/concurrent/ConcurrentHashMap;
invoke-virtual {v3}, Lorg/json/JSONArray;->toString()...
invoke-direct {v2, v0}, ...->m0(Ljava/lang/String;)V
iget v1, v2, ...->c:I
invoke-direct {v2, v1, v0}, ...->s(ILjava/lang/String;)V
```

`d0` 逐项解析：

```text
abtestname   -> ABTestConfig.abtestname
abtestgroup  -> ABTestConfig.abtestgroup
status       -> ABTestConfig.status
clientConfig -> ABTestConfig.clientConfig (JSONObject -> HashMap)
```

Libra 请求：`p.J(String)` 使用 `expNames` 参数；`p.C0` 序列化 `expName/expGroupName/status/log/clientConfig/expInfo/timestamp`。

### 4.3 本地缓存（重点）

`b.S(context, process)` 初始化时：

```smali
invoke-direct {v5}, ...->X()Ljava/util/concurrent/ConcurrentHashMap;
move-result-object v0
iput-object v0, v5, ...->e:Ljava/util/concurrent/ConcurrentHashMap;
invoke-direct {v5}, ...->T()V
```

`X()`：

```smali
invoke-virtual {v2}, ...->L()Ljava/lang/String;      # L() = getPackageName()
const-string v1, "EXTRA_STRING_AB_EXPERIMENTS_CONFIG"
invoke-direct {v2, v0, v1}, ...->Q(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
move-result-object v0
invoke-virtual {v2, v0}, ...->e0(Ljava/lang/String;)Ljava/util/concurrent/ConcurrentHashMap;
```

`Q(key, default)`：

```smali
sget-object v0, Lcom/netease/cloudmusic/abtest2/c;->a:Lcom/netease/cloudmusic/abtest2/c;
invoke-virtual {v0}, Lcom/netease/cloudmusic/abtest2/c;->c()Z
if-eqz v0, :label_0011
invoke-direct {v2}, ...->O()Lvf2/e;
invoke-interface {v0, v3, v1}, Landroid/content/SharedPreferences;->getString(...)
...
:label_0014
iget-object v0, v2, ...->d:Landroid/content/Context;
const-string v1, "AB_TEST_CONFIG_CACHE_FILE_NAME"
invoke-static {v0, v1}, Lorg/xjy/android/treasure/TreasurePreferences;->getInstance(...)
invoke-virtual {v0, v3, v4}, TreasurePreferences;->getString(...)
```

`O()` / `P()` / `H()`：

| 方法 | 返回 | 存储名 | 用途 |
|---|---|---|---|
| `O()` | MMKV | `AB_TEST_CONFIG_MMKV_FILE_NAME` | `c.c()==true` 时的配置缓存 |
| `P()` | MMKV 或 Treasure | 同上 / `AB_TEST_CONFIG_CACHE_FILE_NAME` | 调试开关监听 |
| `H()` | MMKV | `AB_TEST_CHECKED_CONFIG_MMKV` | 已检查/已上报标记同步 |

`i0(key, value)` 写入时同时写 MMKV（如果 `c.c()`）和 `TreasurePreferences("AB_TEST_CONFIG_CACHE_FILE_NAME")`；`j0` 写 StringSet。

**最终路径（默认情况，`c.c()==false`）：**

```text
/data/data/com.netease.cloudmusic/shared_prefs/AB_TEST_CONFIG_CACHE_FILE_NAME.xml
  <string name="com.netease.cloudmusicEXTRA_STRING_AB_EXPERIMENTS_CONFIG">[...JSON...]</string>
```

依据：

- `TreasurePreferences.getInstance(context, "AB_TEST_CONFIG_CACHE_FILE_NAME")` -> `TreasurePrefsOriginalImpl` -> `ContentProvider`。
- `TreasureProvider` 的 `query/update` 最终调用 `context.getSharedPreferences(name, mode)`，`name` 就是 `AB_TEST_CONFIG_CACHE_FILE_NAME`，mode 为 0（MODE_PRIVATE）。
- Manifest：

```xml
<provider
    android:name="org.xjy.android.treasure.provider.TreasureProvider"
    android:exported="false"
    android:multiprocess="false"
    android:authorities="com.netease.cloudmusic.treasurepreferences"/>
```

**MMKV 分支（`c.c()==true`）：**

```text
/data/data/com.netease.cloudmusic/files/mmkv/AB_TEST_CONFIG_MMKV_FILE_NAME
key = com.netease.cloudmusicEXTRA_STRING_AB_EXPERIMENTS_CONFIG
```

`c.c()` 由 `ABInitTask.init()` 的 `c.b(isABInitTaskGroupT(), isABInitTaskGroupT())` 设置；`isABInitTaskGroupT()` = `isWaitTaskOptEnable()` = `StartupABManager.isStartOptABGroupT("wait-task-0805-v2")`。也就是说，**启动 AB `wait-task-0805-v2` 命中 T 时走 MMKV，否则走 SharedPreferences**。

### 4.4 其它存储

| 名称 | 位置/类型 | 用途 |
|---|---|---|
| `AB_TEST_CHECKED_CONFIG_MMKV` | MMKV | `b.b0/w0` 把 `f` map（已检查/已上报）同步到 MMKV |
| `abtest_prefer_file` | SharedPreferences | `h.b()` 读 `abTestCallMonitorSwitch`（默认 true）；另有 `abtest_start_up_check`、`k_abtest_interval_flag`、`k_abtest_re_pull_time` 等字符串 |
| `startup.config` | `cacheDir/startup.config`（Parcel 序列化） | `StartupABManager` 独立启动 AB 配置 |
| `ab_test_table` | SQLite | 旧 ABTest1 历史表（`CREATE TABLE ab_test_table(...)`），不是 ABTest2 |

`StartupABManager` 专项：

```smali
# configFile()
new-instance v0, Ljava/io/File;
sget-object v1, ...->mContext:Landroid/content/Context;
invoke-virtual {v1}, Landroid/content/Context;->getCacheDir()Ljava/io/File;
const-string v2, "startup.config"
invoke-direct {v0, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
```

```smali
# getGroup(String)
sget-object v0, ...->configMap:Ljava/util/Map;
invoke-interface {v0, v4}, Ljava/util/Map;->get(...)
check-cast v0, Lcom/netease/cloudmusic/module/startup/StartupABManager$Config;
if-eqz v0, :label_005f
invoke-virtual {v0}, Config;->getGroup()Ljava/lang/String;
...
const-string v1, "disable"
invoke-static {v0, v1}, Intrinsics;->areEqual(...)Z
if-eqz v1, :label_004c
const-string v4, "c"
return-object v4
:label_004c
const-string v1, "Unknown"
...
:label_005f
invoke-direct {v3, v4}, ...->getDefaultABGroup(Ljava/lang/String;)Ljava/lang/String;
return-object v4
```

`getDefaultABGroup` 固定返回 `"c"`。`Config` 字段只有 `key/group/log/CREATOR`。`loadConfig()` 用 `Parcel.unmarshall` + `createTypedArrayList` 读取；`errorFile` 存在时直接跳过加载。

> 这是与 ABTest2 并行的第二套系统，专门服务启动任务（`cold-start-26H2-aos`、`wait-task-0805-v2`、广告冷启动等）。如果要强制启动阶段的分组，必须单独 hook `StartupABManager.getGroup` 或改 `cacheDir/startup.config`。

### 4.5 拉取时机

| 时机 | 代码 | 说明 |
|---|---|---|
| App 启动 | `ABInitTask.init()` -> `e.a(false)` -> `init()` -> `b.S()` -> `X()` | **只读本地缓存**，不发网络 |
| PushService 启动 | `PushService.startABTestPoll()` | 注册两条轮询 |
| 旧 AB 轮询 | `PushService$5.run()` -> `IABTestManager.refresh(0, callback)` | 重排：debug 15 分钟 / 正式 3 小时（`0xdbba0` / `0xa4cb80`） |
| Libra 轮询 | `PushService$7.run()` -> `e80/e.b.S(false)` | 重排：debug 1 分钟 / 正式 10 分钟；实际拉取受 `qc/a` 节流 |
| 网络刷新 | `b.l0(J, callback)` -> `b$d.run()` -> `J(url)` -> 响应 `data` -> `u0()` | 整体替换 `e` 并持久化 |
| 本地缓存刷新 | `refreshLocalCache(String, callback)` -> `b.n0()` | 解析传入 JSON 的 `data` 数组，替换 `e` 并持久化 |
| 跨进程同步 | `b$b.onReceive` 的 `ACTION_LIVE_EXPERIMENTS_CONFIG_UPDATE` 等 | 只改内存 `e`，不持久化 |
| 日期变更 | `b$c.onDateChange()` -> `A()` + `q(process)` | 清空 `f`/`g`，广播 `ACTION_LIVE_EXPERIMENTS_CONFIG_CLEAR` |

`qc/a` 常量（release）：

| 字段 | debug | release | 含义 |
|---|---|---|---|
| `a` | 60s | 3600s | 1h |
| `b` | 10s | 120s | 2min |
| `c` | 60s | 600s | 10min |
| `d` | 60s | 3600s | 1h |
| `e` | 60s | 600s | 10min |
| `f` | 120s | 30 天 | 长周期 |
| `g` | 120s | 1 天 | 日周期 |

`PushService$7` 的逻辑是“每 10 分钟醒来一次，但距上次刷新超过 `qc/a.e`（release 10 分钟）或 `qc/a.d`（release 1 小时）才真正拉”；具体用哪个取决于 `kg.c()`。因此正式包可能是 **10 分钟或 1 小时一次**，常见配置下按 **1 小时** 估算。

### 4.6 本地缓存能否直接改？

| 场景 | 可行性 | 说明 |
|---|---|---|
| 无 root、外部 App | **不可行** | `TreasureProvider` `exported=false`；SP 文件在 app 私有目录 |
| 无 root、Frida/LSPosed 注入 | **可行** | 直接调 `b.A0`/`b.m0`/`b.i0`，或 hook 查询入口 |
| root 改 SP XML | **可行但易被覆盖** | 路径 `.../shared_prefs/AB_TEST_CONFIG_CACHE_FILE_NAME.xml`；需 force-stop 后改，保持 owner/SELinux；下次服务端刷新会整体替换 |
| root 改 MMKV | **可行但需知道当前分支** | 仅当 `wait-task-0805-v2` 为 T 时使用；文件 `.../files/mmkv/AB_TEST_CONFIG_MMKV_FILE_NAME` |
| 改 `startup.config` | **可行（仅启动 AB）** | `cacheDir/startup.config`，Parcel 格式；只覆盖 `StartupABManager` 的 key |
| 通过 `ABTestDebugActivity` 改 | **正式包无效** | 见下一节 |

---

## 5. `ABTestDebugActivity` 专项

### 5.1 Manifest 声明

```xml
<activity
    android:theme="@style/sh"
    android:label="ABTest信息配置"
    android:name="com.netease.cloudmusic.abtest2.ABTestDebugActivity"
    android:screenOrientation="locked"
    android:configChanges="screenSize|orientation|keyboardHidden"
    android:windowSoftInputMode="adjustResize|stateHidden"/>
```

- **没有 `android:exported`**。
- **没有 `<intent-filter>`**（自闭合标签）。
- targetSdk 33，按 Android 规则：无 intent-filter 且未显式 `exported` -> **exported=false**。
- 没有权限保护、没有单独开关；Activity 本身不检查 debuggable。

### 5.2 启动方式

类里只有一个公开静态入口：

```smali
# ABTestDebugActivity.d(Context)
new-instance v0, Landroid/content/Intent;
const-class v1, Lcom/netease/cloudmusic/abtest2/ABTestDebugActivity;
invoke-direct {v0, v2, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
invoke-virtual {v2, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
```

按 `method_idx` 反查（file 5 idx 7658、file 13 idx 5509）**未发现 Java 调用方**，说明正式包的入口在编译期被移除或只通过反射/调试菜单调用。

`onCreate` 的列表来源是 `f.a()`（14 个注册表的实验 key）+ `IABTestManager.getAllConfig()`；`getAllConfig()` 返回 `mModel.F()` 与 `libraModel.F()` 的并集，而 `F()` 在 `e0.g()==true` 时会把调试覆盖 map `l` 合并进来。因此调试页在 release 下即使打开，也只能看到缓存里的服务端配置，看不到/改不动调试覆盖。

`ABTestDebugActivity$c.onClick` 的行为：

- 输入框为空：按 `LibraABTestConfig` / `ABTestConfig` 分别调用 `removeLibraExperiment4Dev` / `removeExperiment4Dev`，再调 `checkBelongToWhichGroup(name, oldGroup)` 把当前分组写回对象；
- 输入框非空：先改内存里的 `config.abtestgroup`，再调用 `updateLibraExperiment4Dev` / `updateExperiment4Dev`；
- 两条路径最终都落到 `b.A0 -> b.n`，受 `e0.g()` 门禁。

### 5.3 能否在正式包直接打开？

**不能（非 root）。** `adb shell am start -n com.netease.cloudmusic/com.netease.cloudmusic.abtest2.ABTestDebugActivity` 会因 `not exported` 被 ActivityManager 拒绝。

**root（多数 ROM）可以强制打开：**

```bash
adb shell su -c "am start -n com.netease.cloudmusic/com.netease.cloudmusic.abtest2.ABTestDebugActivity"
```

**但打开后编辑分组仍然无效**：

```smali
# ABTestDebugActivity$c.onClick -> updateExperiment4Dev/updateLibraExperiment4Dev
invoke-interface {v0, v2, v1}, IABTestManager;->updateExperiment4Dev(...)

# ABTestManagerImpl.updateExperiment4Dev
iget-object v0, v1, ABTestManagerImpl;->mModel:Lcom/netease/cloudmusic/abtest2/b;
invoke-virtual {v0, v2, v3}, Lcom/netease/cloudmusic/abtest2/b;->A0(...)

# b.A0
invoke-static {}, Lcom/netease/cloudmusic/utils/e0;->g()Z
move-result v1
if-eqz v1, :label_000c
if-nez v2, :label_0009
goto :label_000c
:label_0009
invoke-direct {v0, v2}, ...->n(Lcom/netease/cloudmusic/abtest2/ABTestConfig;)V
:label_000c
return-void
```

`e0.g()` 的实现是 `ApplicationInfo.flags & 0x2`（`FLAG_DEBUGGABLE`）：

```smali
iget v4, v4, Landroid/content/pm/ApplicationInfo;->flags:I
and-int/lit8 v4, v4, 0x2
if-eqz v4, :label_0056
...
```

Manifest 里 `<application ... android:debuggable="false">`，正式包不是 debuggable，`e0.g()` 返回 false，所以：

- `b.A0` 直接 return，不写调试 JSON；
- `b.x` 不会读 `l` map；
- 调试页仍会弹“更新成功”，但只是 UI 提示，**没有实际效果**。

### 5.4 若要让它真正生效

只有三种方式：

1. 重新打包为 `android:debuggable="true"`（会改变签名/完整性，不属于“只读分析”，且风险高）。
2. hook `com.netease.cloudmusic.utils.e0.g()Z` 恒返回 true，再通过 Frida 调 `ABTestDebugActivity.d(context)` 或直接调 `updateExperiment4Dev`。
3. 绕过调试页，直接 hook 查询入口或写缓存（推荐）。

---

## 6. 体验相关 ABTest key 清单

> 说明：
> - “默认分组”统一写 **`c`（兜底）**。APK 内没有逐 key 默认值；线上实际分组由服务端下发，未下发/未命中时客户端就是 `c`。
> - “类型”中“实验”来自注册表 `a()`，“开关”来自 `b()`；开关查询会自动加 `FH-` 前缀。
> - “付费/版权”列中“广告商业化”表示不直接涉及用户付费权益，但属于商业收入，强开需谨慎。

### 6.1 启动 / 冷启动

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `startup_opt_anr_1014_v1` | k5.i 实验 | c | 启动阶段 ANR 优化 | 否 |
| `qd-cold-launch-empty-notification-1230-v1` | k5.i 实验 | c | 冷启动空通知 | 否 |
| `fetchwhenlaunch` | k5.i 实验 | c | 启动时预取 | 否 |
| `rn-engine-initialize` | k5.i 实验 | c | RN 引擎初始化 | 否 |
| `newFrameworkPreload` | k5.i 实验 | c | 新框架预加载 | 否 |
| `qd-preloadmainview-aos-0722-v2` | k5.i 实验 | c | 主界面预加载 | 否 |
| `boot-browser-process` | k5.i 实验 | c | 启动浏览器子进程 | 否（有稳定性风险） |
| `boot-push-process` | k5.i 实验 | c | 启动推送子进程 | 否（有稳定性风险） |
| `startup_task_play_process` | k5.i 实验 | c | 播放进程启动任务 | 否 |
| `qd-app-global-event-lazy-init-0708-v1` | k5.i 实验 | c | 全局事件懒初始化 | 否 |
| `startup.config` 内 `wait-task-0805-v2` | StartupAB | c | 启动任务等待/排序；同时决定 AB 缓存介质 | 否 |

### 6.2 首页 / 底栏 / 顶栏 / 侧栏

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `tabMerge` | k5.i 实验 | c | 底栏 tab 合并 | 否 |
| `home-headframe` | k5.i 实验 | c | 首页头图/头框 | 否 |
| `hp-new-homepageV3.1` | k5.i 实验 | c | 首页新版布局 | 否 |
| `bottom_icon_from_link_switch` | k5.i 实验 | c | 底栏图标来源 | 否 |
| `ab-speed-main` | k5.i 实验 | c | 首页加载速度 | 否 |
| `home-poplayer-android` | k5.i 实验 | c | 首页浮层播放器 | 否 |
| `mine-rn-android` | k5.i 实验 | c | “我的”页 RN 化 | 否 |
| `podcast_tab` | k5.i 实验 | c | 播客 tab | 否（入口可能影响付费播客） |
| `main-sidebar-rn-v2` | k5.i 实验 | c | 侧边栏 RN v2 | 否 |
| `FH-sidebar-init-test` | k5.i 实验 | c | 侧边栏初始化 | 否 |
| `home_commenttab` | k5.i 实验 | c | 首页评论 tab | 否 |
| `hp-looktab-name` | k5.i 实验 | c | “看” tab 名称 | 否 |
| `LiveNativeTab` / `FH-LiveNativeTab` | k5.g 实验/开关 | c | 直播原生 tab | 否 |

### 6.3 播放器 / 音频

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `aos-player-datasource-7` | k5.b 实验 | c | 播放器数据源策略 | 否 |
| `aosCrossFade` | k5.b 实验 | c | 交叉淡入淡出 | 否 |
| `player-cache-update-A` / `FH-player-cache-update-A` | k5.b 实验/开关 | c | 播放缓存更新 | 否 |
| `aosPlayerCacheFindOpt` | k5.b 实验 | c | 缓存查找优化 | 否 |
| `aosImmerseAAC-1229-2` / `aosImmerseV3aac` | k5.b 实验 | c | 沉浸声 AAC | 间接（音质相关，但不直接鉴权） |
| `play-ring` | k5.i 实验 | c | 播放环 UI | 否 |
| `newAudioFocusDialog` | k5.b 实验 | c | 音频焦点弹窗 | 否 |
| `notificationReceiverAsyncRegister` | k5.b 实验 | c | 通知接收器异步注册 | 否 |
| `AndroidNotificationOnlyForeground` | k5.b 实验 | c | 仅前台通知 | 否 |
| `Notification_delete_STOPPLAY` | k5.b 实验 | c | 删除通知停止播放 | 否 |
| `simple_media_player_ncm_audio_player` | k5.b 实验 | c | 播放器实现选择 | 否 |
| `AndroidStartPlayServiceForeground0708` | k5.b 实验 | c | 播放服务前台化 | 否 |

### 6.4 评论区 / 动态

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `comment-click` | k5.i 实验 | c | 评论点击行为 | 否 |
| `reply-top` | k5.i 实验 | c | 回复置顶 | 否 |
| `comment-loadingAndroid` | k5.i 实验 | c | 评论加载样式 | 否 |
| `commentsquare-pub` | k5.i 实验 | c | 评论广场发布 | 否 |
| `comment-prefetch-androidv2` | k5.i 实验 | c | 评论预取 | 否 |
| `Comment_quick_emoji` | k5.i 实验 | c | 快捷表情 | 否 |
| `DislikeinComments` | k5.i 实验 | c | 评论“踩” | 否 |
| `anonymous-comment` | k5.i 实验 | c | 匿名评论 | 否（隐私敏感，慎用） |
| `pic-comment` | k5.i 实验 | c | 图片评论 | 否 |
| `comment_content_color` | k5.i 实验 | c | 评论内容配色 | 否 |

### 6.5 搜索

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `SearchRN5android` | k5.i 实验 | c | 搜索 RN 框架 | 否 |
| `best-search` | k5.i 实验 | c | 最佳搜索 | 否 |
| `Hot-search-list` | k5.i 实验 | c | 热搜列表 | 否 |
| `search-label` | k5.i 实验 | c | 搜索标签 | 否 |
| `new-search-rc` | k5.i 实验 | c | 新搜索 RC | 否 |
| `2026newframe_searchtabtest_distribution` | k5.i 实验 | c | 搜索 tab 分流 | 否 |
| `local_search_local_sort_1020` | k5.i 实验 | c | 本地搜索排序 | 否 |
| `SearchTabIssue3_SearchTab_Is_the_SoleEntryPoint` | k5.i 实验 | c | 搜索 tab 入口 | 否 |
| `new_home_search_rec_list_reverse` | k5.i 实验 | c | 首页搜索推荐倒序 | 否 |

### 6.6 下载 / 存储 / 本地音乐

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `StorageAdjustment0321` | k5.i 实验 | c | 存储策略调整 | 否 |
| `FH-DiskScannerTree` | k5.i 实验 | c | 磁盘扫描树 | 否 |
| `FH-DiskCleanForScan2` | k5.i 实验 | c | 磁盘清理 | 否 |
| `local-music-file-state-fix` | k5.i 实验 | c | 本地音乐文件状态 | 否 |
| `hy-saf-permission-download` | k5.i 实验 | c | SAF 下载权限 | 否 |
| `FH-RemovePodcastDownloadEntry` | k5.i 实验 | c | 移除播客下载入口 | 间接（可能影响付费播客下载） |
| `CloudDiskUpload` / `FH-CloudDiskUpload` | k5.i 实验 | c | 云盘上传 | 间接（云盘权益） |
| `FH-cacheCtrl3` | k5.i 实验 | c | 缓存控制 | 否 |
| `FH-preloadCache` | k5.i 实验 | c | 预加载缓存 | 否 |

### 6.7 通知 / 推送 / 消息

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `messagecenter_notfication_rn_a` | k5.i 实验 | c | 消息中心通知 RN | 否 |
| `push_android_click2open_optimize_2603` | k5.i 实验 | c | 推送点击打开优化 | 否 |
| `push_turnon_0511` | k5.i 实验 | c | 推送开关引导 | 否 |
| `notificationReceiverAsyncRegister` | k5.b 实验 | c | 通知接收器异步注册 | 否 |
| `AndroidNotificationOnlyForeground` | k5.b 实验 | c | 仅前台通知 | 否 |
| `Notification_delete_STOPPLAY` | k5.b 实验 | c | 删除通知停止播放 | 否 |
| `qd-cold-launch-empty-notification-1230-v1` | k5.i 实验 | c | 冷启动空通知 | 否 |

### 6.8 推荐 / 算法 / 内容策略

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `Random-by-alg-N` | k5.i 实验 | c | 随机推荐算法 | 否 |
| `dailyRecommend-refresh` | k5.i 实验 | c | 每日推荐刷新 | 否 |
| `dailyRecommend-label` | k5.i 实验 | c | 每日推荐标签 | 否 |
| `DailyRec-Ladder` | k5.i 实验 | c | 每日推荐阶梯策略 | 否 |
| `playlist_recommendation` | k5.i 实验 | c | 歌单推荐 | 否 |
| `preload_follow_during_recommend` | k5.i 实验 | c | 推荐时预加载关注 | 否 |
| `hp_daily_mix_autoplay_recommend_0120` | k5.i 实验 | c | 每日混播自动推荐 | 否 |
| `hotstrat_refresh1031` | k5.i 实验 | c | 热榜策略刷新 | 否 |
| `Recommendcard01` | k5.i 实验 | c | 推荐卡片样式 | 否 |
| `podcast_rec_list_pop` | k5.i 实验 | c | 播客推荐列表 | 否（付费播客另见禁止项） |

### 6.9 广告（商业敏感，谨慎）

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `ad-cold-boot-main-page-ad-order-change-20260713` | k5.i 实验 | c | 冷启动首页广告排序 | 广告商业化 |
| `FH-AdNewRandomAdvertisement` | k5.i 开关 | c | 广告随机策略 | 广告商业化 |
| `ad-search-newpattern` | k5.i 实验 | c | 搜索广告样式 | 广告商业化 |
| `ad-searchresults-newpattern` | k5.i 实验 | c | 搜索结果广告样式 | 广告商业化 |
| `rewarded-video-ads` | k5.i 实验 | c | 激励视频广告 | 广告商业化/付费权益 |
| `ad-loading-cold-screen-shot-enable` | k5.i 实验 | c | 冷启动加载截屏 | 广告商业化 |
| `ad_cold_boot_close_ip_direct_link` | k5.i 实验 | c | 冷启动直链关闭 | 广告商业化 |
| `android-banner-ad` | k5.i 实验 | c | Banner 广告 | 广告商业化 |
| `ad_disable_ad_free_play_task` | k5.i 实验 | c | 免广告播放任务 | **广告/付费边界，禁止** |

### 6.10 青少年模式（合规，禁止）

| key | 注册表/类型 | 默认分组 | 影响面 | 付费/版权 |
|---|---|---|---|---|
| `jsonParseOptYouthTimeLockConfig` | k5.i 实验 | c | 青少年时间锁配置解析 | **合规敏感，禁止强开** |

> 该 key 是注册表里唯一带 `Youth/Teen` 语义的 key；青少年模式还受服务端/本地其他开关控制，不要试图通过 AB 绕过。

---

## 7. 可落地的 hook 方案

### 7.1 方案总览

| 优先级 | hook 点 | 作用 | 适用 |
|---|---|---|---|
| ★★★★★ | `ABTestManagerImpl#checkExperiment(String,boolean)` | 中心查询入口；白名单 key 直接返回伪造 `ABTestConfig` | 覆盖绝大多数业务 |
| ★★★★★ | `ABTestConfig#getGroupKeys()` + `LibraABTestConfig#getGroupKeys()` | 只改分组、不改其它字段；覆盖所有 `checkBelongGroupC/T`、`checkBelongToWhichGroup` | 最稳的分组覆盖（注意 Libra 重写了该方法，必须两个都 hook） |
| ★★★★☆ | `ABTestManagerImpl#genericCheckExperimentWithParam(...)` | 覆盖 `checkExperimentWithParam` 的返回值 | 需要改 `clientConfig` 参数 |
| ★★★★☆ | `com.netease.cloudmusic.abtest2.b#x(String,boolean)` | 模型层最终查表；覆盖直接调 model 的代码 | 兜底，防止绕过 manager |
| ★★★★☆ | `StartupABManager#getGroup(String)` | 启动阶段独立 AB | 冷启动/启动任务 key |
| ★★★☆☆ | `com.netease.cloudmusic.utils.e0#g()` | 恒 true，打开调试覆盖 map `l` 与 `ABTestDebugActivity` 写入口 | 想复用官方调试页 |
| ★★★☆☆ | `ABTestManagerImpl#updateExperiment4Dev(...)` / `b#A0(...)` | 直接注入调试覆盖 | 不想改查询逻辑 |
| ★★☆☆☆ | `b#u0(...)` / `b#d0(...)` | 在服务端响应合并阶段注入 | 避免刷新覆盖 |
| ★★☆☆☆ | `ABTestDebugActivity#d(Context)` | 调起官方调试页 | root/Frida 调试 |

### 7.2 推荐方案 A：只 hook 分组（最小副作用）

**目标**：只影响白名单 key 的分组，其他字段（`clientConfig`、`status`）保持服务端原值。

```javascript
// Frida 伪代码
Java.perform(function () {
  var ABTestConfig = Java.use('com.netease.cloudmusic.abtest2.ABTestConfig');
  var ALLOW = {
    'tabMerge': 't',
    'home-headframe': 't1',
    'comment-click': 't2',
    'SearchRN5android': 't',
    'aosCrossFade': 't1'
    // ... 只放经过验证的体验类 key
  };

  var hookGroup = function (impl) {
    impl.implementation = function () {
      var name = this.abtestname.value;
      if (Object.prototype.hasOwnProperty.call(ALLOW, name)) {
        return ALLOW[name];
      }
      return this.getGroupKeys();
    };
  };

  hookGroup(ABTestConfig.getGroupKeys);
  // 关键：LibraABTestConfig 重写了 getGroupKeys，必须单独 hook
  hookGroup(Java.use('com.netease.cloudmusic.abtest2.LibraABTestConfig').getGroupKeys);
});
```

优点：不构造对象、不改 `clientConfig`，对 `checkBelongGroupC/T`、`checkBelongToWhichGroup` 全覆盖。
缺点：如果业务直接读 `config.abtestgroup` 字段（而不是 `getGroupKeys()`），不会生效；需要再 hook `checkExperiment`。

### 7.3 推荐方案 B：hook 中心查询（覆盖最广）

```javascript
Java.perform(function () {
  var Mgr = Java.use('com.netease.cloudmusic.abtest2.ABTestManagerImpl');
  var Cfg = Java.use('com.netease.cloudmusic.abtest2.ABTestConfig');
  var HashMap = Java.use('java.util.HashMap');
  var ALLOW = { 'tabMerge': 't', 'comment-click': 't2' };

  function makeCfg(name, group) {
    var c = Cfg.$new();
    c.abtestname.value = name;
    c.abtestgroup.value = group;
    c.status.value = 1;
    c.clientConfig.value = HashMap.$new();
    return c;
  }

  Mgr.checkExperiment.overload('java.lang.String', 'boolean').implementation = function (key, log) {
    if (Object.prototype.hasOwnProperty.call(ALLOW, key)) {
      return makeCfg(key, ALLOW[key]);
    }
    return this.checkExperiment(key, log);
  };

  // 兜底：直接走 model 的调用
  var Model = Java.use('com.netease.cloudmusic.abtest2.b');
  Model.x.overload('java.lang.String', 'boolean').implementation = function (key, log) {
    if (Object.prototype.hasOwnProperty.call(ALLOW, key)) {
      return makeCfg(key, ALLOW[key]);
    }
    return this.x(key, log);
  };
});
```

注意：

- `checkExperiment(String)` 内部调用 `checkExperiment(String, boolean)`，所以 hook 2 参重载即可覆盖 1 参。
- 返回的 `ABTestConfig` 必须是**新的**对象，避免污染原 map；`clientConfig` 至少给空 `HashMap`，否则 `checkClientConfig` 会返回 null。
- 如果业务需要 `clientConfig` 参数，方案 B 返回空 map 会让 `checkExperimentWithParam` 全部走默认值；此时改用方案 C 或保留原 config 的 `clientConfig`。

### 7.4 方案 C：只改参数值

```javascript
Java.perform(function () {
  var Mgr = Java.use('com.netease.cloudmusic.abtest2.ABTestManagerImpl');
  Mgr.genericCheckExperimentWithParam.implementation = function (exp, param, def) {
    // 只对白名单实验 + 白名单参数生效
    if (exp === 'some_exp' && param === 'some_param') {
      return Java.use('java.lang.String').$new('forced');
    }
    return this.genericCheckExperimentWithParam(exp, param, def);
  };
});
```

`genericCheckExperimentWithParam` 是 private，但 Frida 可以 hook；Xposed 也可以。它的重载只有 `(String,String,Object)` 一个。

### 7.5 方案 D：启动 AB（独立系统）

```javascript
Java.perform(function () {
  var SAB = Java.use('com.netease.cloudmusic.module.startup.StartupABManager');
  var ALLOW = {
    'wait-task-0805-v2': 't',
    'cold-start-26H2-aos': 't1',
    'ad_cold_boot_close_ip_direct_link': 't'
  };
  SAB.getGroup.implementation = function (key) {
    if (Object.prototype.hasOwnProperty.call(ALLOW, key)) return ALLOW[key];
    return this.getGroup(key);
  };
});
```

注意：`StartupABManager` 在 `Application` 早期就会调用，普通 `frida -f` 的 `Java.perform` 可能赶不上；要稳定生效需 **Zygisk/LSPosed 模块**，或直接改 `cacheDir/startup.config`（root）。

### 7.6 方案 E：复用官方调试覆盖（需要 `e0.g()`）

```javascript
Java.perform(function () {
  var E0 = Java.use('com.netease.cloudmusic.utils.e0');
  E0.g.implementation = function () { return true; };

  // 之后可调用 ABTestDebugActivity.d(context)
  var Activity = Java.use('com.netease.cloudmusic.abtest2.ABTestDebugActivity');
  var current = Java.use('android.app.ActivityThread').currentApplication().getApplicationContext();
  Activity.d(current);
});
```

风险：`e0.g()` 是全局 debug 门禁，打开后可能启用其它调试分支、日志、断言，甚至影响 `checkParam`（debug 包参数缺失会抛异常）。不建议长期使用。

### 7.7 方案 F：跨进程广播（需实测）

`b$b` 注册了以下 action（前缀是包名 `com.netease.cloudmusic`）：

```text
ACTION_LIVE_EXPERIMENTS_CONFIG_UPDATE
ACTION_LIVE_EXPERIMENTS_CONFIG_CHECKED
ACTION_LIVE_EXPERIMENTS_CONFIG_CLEAR
ACTION_LIVE_EXPERIMENTS_KEY_ADD
ACTION_UPDATE_CONFIG_4_DEV
ACTION_LIVE_EXPERIMENTS_MAIN_PROCESS_START
ACTION_LIVE_EXPERIMENTS_OVERRIDE_CONFIG_CHECKED
```

其中 `ACTION_LIVE_EXPERIMENTS_CONFIG_UPDATE` 的 JSON 格式与本地缓存一致：

```bash
adb shell am broadcast \
  -a com.netease.cloudmusic.ACTION_LIVE_EXPERIMENTS_CONFIG_UPDATE \
  --es EXTRA_STRING_AB_EXPERIMENTS_CONFIG '[{"abtestname":"tabMerge","abtestgroup":"t","status":1,"clientConfig":{}}]' \
  --ei EXTRA_INT_AB_EXPERIMENTS_CONFIG_PROCESS 999
```

- `onReceive` 会先比较 `EXTRA_INT_AB_EXPERIMENTS_CONFIG_PROCESS` 与本进程 id，相同则忽略；传 `999` 可绕过。
- 该路径只改内存 `e`，**不持久化**；下次刷新/重启丢失。
- 接收器通过 `eb/a.d` -> `gb/b.Q` -> `fb/a` 注册；`usePermission=false` 时最终走 2 参 `registerReceiver`。在 Android 13/targetSdk 33 上，2 参注册自定义 action 可能抛 `IllegalArgumentException`（被 `eb/a` 捕获后静默失败）。**因此这条路径必须先在真机验证 `am broadcast` 是否能命中；不要把它当主方案。**

`ACTION_UPDATE_CONFIG_4_DEV` 更直接（Parcelable extra `config`），但 `am broadcast` 不能传 Parcelable，需要辅助 App/Frida 构造。

---

## 8. 白名单与“禁止强开”边界

### 8.1 白名单原则

1. **精确 key 匹配**，禁止前缀/正则放行（例如不要 `key.startsWith("home")`）。
2. 只允许“UI/性能/启动顺序”类 key；任何涉及账号、权益、内容权限、支付、广告收入的 key 默认拒绝。
3. 运行时二次校验：

```javascript
function isAllowed(key) {
  if (!ALLOW_EXACT.has(key)) return false;
  if (/vip|svip|pay|purchas|privilege|member|album|quality|trial|rights|copyright|order|price|subscribe|oversea|region|country|youth|teen|minor|privacy|login|cookie|auth|token|risk|security|encrypt|adfree|freeflow|64share|listenTogetherPurchase/i.test(key)) {
    return false;
  }
  return true;
}
```

4. 只影响 `getGroupKeys()` 或 `checkExperiment` 的返回值，不修改 `status`、`clientConfig`（除非明确需要）。
5. 不做“全局默认 t”（把所有未命中 key 都变成 T），否则会把大量实验/开关一起打开，极易触发崩溃和风控。

### 8.2 建议首批白名单（低风险体验 key）

```text
tabMerge
home-headframe
comment-click
reply-top
comment-loadingAndroid
SearchRN5android
best-search
Hot-search-list
local_search_local_sort_1020
aosCrossFade
aos-player-datasource-7
play-ring
newAudioFocusDialog
notificationReceiverAsyncRegister
AndroidNotificationOnlyForeground
messagecenter_notfication_rn_a
StorageAdjustment0321
FH-DiskScannerTree
FH-DiskCleanForScan2
local-music-file-state-fix
dailyRecommend-refresh
playlist_recommendation
```

这些 key 的共同点：只影响展示/加载/排序/性能，不直接改变账号权益或内容权限。

### 8.3 禁止强开（黑名单）

#### A. 付费 / VIP / 版权 / 音质 / 订单

| key | 原因 |
|---|---|
| `LimitTimeFreeTrialPrivilege` / `FH-LimitTimeFreeTrialPrivilege` | 限时免费试听权益，直接涉及付费权益 |
| `playerhquality` | 高品质播放，可能绕过音质权益 |
| `hy-audio-quality-upgrade` | 音质升级，涉及会员权益 |
| `purchasedAlbumGapLessOpt` | 已购专辑无缝播放，涉及已购内容 |
| `native-album-purchase-layer` | 专辑购买层 |
| `album-purchase-h5-container` | 专辑购买 H5 容器 |
| `album-purchase-RN` | 专辑购买 RN |
| `Podcast_RelatedRec_Pay` | 播客付费推荐 |
| `jsonParseOptPlaylistPrivilege` | 歌单权限解析 |
| `jsonParseOptVipauthAppMemberlogoMypageQuery` | VIP 鉴权/会员标识 |
| `VIP_accountcard_01` / `VIP_vipcenter_202504` / `Vip_mark_20221117` | VIP 中心/标识 |
| `homepage-VIPbar` / `Vip-RecentPlay` / `hy-vipcardrenew` / `hy-vip0520` | VIP 入口/续费 |
| `hy_song_VipLogo` / `fx-coverviptag` / `hy_VipIcon_*` | VIP 角标/图标 |
| `hy-android-viplayer` / `hy-android-viplayer-v2` | VIP 播放器 |
| `androidpaydelay` / `FH-androidpaydelay` / `music-pay-anr-opt-0611` | 支付链路 |
| `orderPolling-a` / `FH-orderPolling-a` | 订单轮询 |
| `hy-AlipayNewSDK2509` | 支付 SDK |
| `hy-listenTogetherPurchaseFunnelOptimize` | 一起听购买漏斗 |
| `hy-freetscashierbox-list-android` | 免费/付费收银台 |
| `ad_disable_ad_free_play_task` / `ad_disable_ad_free_play_task_retry_opt_android` | 免广告/免广告播放，商业权益边界 |
| `FH-kv-*` / `kv-*` | KV/Cookie 存储，可能影响登录态/风控 |

#### B. 青少年模式 / 合规

| key | 原因 |
|---|---|
| `jsonParseOptYouthTimeLockConfig` | 青少年时间锁；强开可能绕过防沉迷合规 |
| 任何 `youth/teen/minor/guardian` 语义 key | 同上 |

#### C. 登录 / Cookie / 隐私 / 鉴权

| key | 原因 |
|---|---|
| `FH-OneClickLogin` / `OneClickLogin` | 一键登录 |
| `newuserlogin2` | 新用户登录 |
| `hp-newUser-anonymousLogin` / `hp-recallUser-anonymousLogin` | 匿名登录 |
| `enableLoginRecreate` / `adjustLoginRecreateMainActivityLaunch` | 登录重建 |
| `quickLoginAnrOpt` / `quickLoginSwitchAccount0108` | 登录优化/切号 |
| `login-token-jsk` / `login-verify-jsk` | 登录 token/校验 |
| `androidTokenBackFillCookieV1` / `enableCookieCorrection` / `cookieStartupOptimizeV2` | Cookie 回填/修正 |
| `enableCookieStoreLockOptNew` / `enableCookieStoreGetAllCookiesLockOpt` | Cookie 存储锁 |
| `FH-noCookieInForm` / `noCookieInForm` | 请求表单 Cookie |
| `qd-reset-cookie-open` | Cookie 重置 |
| `nim-login-error-opt` / `FH-nim-login-error-opt` | IM 登录错误处理 |

#### D. 安全 / 风控 / 加密

| key | 原因 |
|---|---|
| `Xcrash` / `XCrash2` / `XCrash4` | 崩溃采集 |
| `androidSysDebugEncryptUpload` | 调试信息加密上传 |
| `Encryption-refer` | 加密引用 |
| `shieldEnable` | 安全防护开关 |
| `androidhttps` / `aosApiHttps` / `aosCDNHttps` | HTTPS 策略 |
| `androidnapm` / `androidbi` | 监控/埋点 |
| `ab-tombstone-collect-data` | Tombstone 采集 |
| 任何 `risk/security/encrypt/token/auth` 语义 key | 同上 |

#### E. 稳定性高风险（不建议强开）

| key | 原因 |
|---|---|
| `boot-browser-process` / `boot-push-process` | 改变进程启动，可能 ANR/崩溃 |
| `startup_task_play_process` | 播放进程任务顺序 |
| `wait-task-0805-v2` / `cold-start-26H2-aos` | 启动任务等待/排序，强改可能卡启动 |
| `ad-cold-boot-main-page-ad-order-change-*` | 冷启动广告排序，涉及商业化 |
| `rewarded-video-ads` | 激励视频，涉及广告收入/付费权益 |

---

## 9. 风险分析

### 9.1 服务端校验与上报

- 客户端会把命中的实验写进日志/上报：
  - `b.x` 命中且 `isEnable()` 时调用 `x0()` 并广播 `ACTION_LIVE_EXPERIMENTS_CONFIG_CHECKED`；
  - `getLogStr()` / `getLibraLogStr()` 暴露实验串；
  - `logServerExperiments(String)` -> `b.Y`；
  - `updateLibraCheckedConfig(exp, group)` -> `b.Z`；
  - `ABInfoReporter.reportPipelineInfo` 仅在 `dw0/j.b` 为 true 时执行（debug）。
- 服务端可以用上报的 group 做分析或一致性校验。强制分组可能导致“客户端行为与服务端记录不一致”，但**没有看到客户端对分组做签名/完整性校验**。
- Libra 的 `log` 字段由服务端下发，客户端只负责透传；强改 `abtestgroup` 不会改 `log`，可能造成日志与实际分组不一致。

### 9.2 风控与商业风险

- 广告类 key 涉及广告曝光/排序/激励视频；强开可能触发广告反作弊或收入异常。
- 付费/VIP key 强开不会真的获得权益（服务端仍校验会员/版权/地区），但会造成：
  - UI 显示 VIP/已购，实际播放/下载失败；
  - 支付链路异常（订单轮询、收银台）；
  - 账号异常/风控标记。
- 免流/免广告类 key 涉及运营商/广告合同，强开属于商业边界。

### 9.3 登录与播放副作用

- 登录/Cookie key 可能改变 token 回填、Cookie 存储、匿名登录；强开可能导致登录态丢失、请求 401、风控验证。
- 播放器 key 可能改变数据源、缓存、解码、音频焦点；强开可能导致：
  - 播放失败/频繁重试；
  - 缓存损坏；
  - 后台播放/通知异常；
  - 音质请求被服务端拒绝（403/试听）。
- 启动 key 可能改变任务顺序/进程启动；强开可能导致冷启动 ANR、白屏、崩溃。

### 9.4 ABTest 与付费权益的边界

- ABTest 是**客户端实验/配置开关**，不是权益凭证。VIP、版权、地区限制、付费专辑、试听时长等最终由服务端接口校验。
- 因此：
  - 强制 VIP UI key：只会“看起来像 VIP”，不会解锁内容；
  - 强制音质 key：可能请求更高音质，但服务端会按会员/版权返回降级或拒绝；
  - 强制购买/订单 key：不会产生真实权益，可能触发支付异常。
- 结论：**所有付费/版权/地区/青少年/登录鉴权 key 一律列入禁止强开**；只对 UI/性能/排序类 key 做白名单。

### 9.5 持久化与刷新覆盖

- 服务端每次刷新会用 `u0()` **整体替换** `e`，所以直接改内存 `e` 或缓存 JSON 都会在下次刷新时被覆盖。
- 调试覆盖 `l` 只有在 `e0.g()==true` 时才参与查询；正式包不可用。
- 跨进程广播只改内存，不持久化。
- 若要长期稳定，必须 hook 查询入口（每次查询都返回目标值），而不是只改一次缓存。

### 9.6 兼容性风险

- `b`、`p`、`q` 是混淆名，版本升级后可能变化；`ABTestManagerImpl`、`ABTestConfig`、`IABTestManager` 名称稳定，优先 hook 这些。
- `getGroupKeys()` 可能被内联；ART 去优化通常仍可 hook，但如果发现不生效，改用 `checkExperiment`/`b.x`。
- `StartupABManager` 在启动早期调用，普通 Frida 附加可能错过；需要 Zygisk/LSPosed 或改 `startup.config`。

---

## 10. 证据附录

### 10.1 关键类与方法索引

| 类 | 方法 | file_id / method_idx |
|---|---|---|
| `ABTestManagerImpl` | `checkExperiment(String)` | 5 / 7681 |
| `ABTestManagerImpl` | `checkExperiment(String,Z)` | 5 / 7682 |
| `ABTestManagerImpl` | `checkBelongToWhichGroupWithoutLog` | 5 / 7678 |
| `ABTestManagerImpl` | `checkBelongGroupC(String,Z)` | 5 / 7671 |
| `ABTestManagerImpl` | `checkBelongGroupT(String,Z)` | 5 / 7675 |
| `ABTestManagerImpl` | `checkClientConfig(String)` | 5 / 7680 |
| `ABTestManagerImpl` | `genericCheckExperimentWithParam` | 5 / 7689 |
| `ABTestManagerImpl` | `init(Context,I)` | 5 / 7700 |
| `ABTestManagerImpl` | `refresh(J,m)` | 5 / 7703 |
| `ABTestManagerImpl` | `refreshLocalCache` | 5 / 7707 |
| `ABTestManagerImpl` | `updateExperiment4Dev` | 5 / 7713 |
| `ABTestManagerImpl` | `getAllConfig` | 5 / 7690 |
| `ABTestConfig` | `getGroupKeys` | 5 / 7621 |
| `ABTestConfig` | `isEnable` | 5 / 7622 |
| `ABTestConfig` | `isHit` | 5 / 7623 |
| `b` (ABModel) | `x(String,Z)` | 5 / 7891 |
| `b` (ABModel) | `u0(JSONArray,m)` | 5 / 7886 |
| `b` (ABModel) | `A0(String,ABTestConfig)` | 5 / 7818 |
| `b` (ABModel) | `S(Context,I)` | 5 / 7837 |
| `b` (ABModel) | `z0()` | 5 / 7896 |
| `b` (ABModel) | `Q(String,String)` | 5 / 7835 |
| `b` (ABModel) | `i0(String,String)` | 5 / 7862 |
| `b` (ABModel) | `W()` | 5 / 7841 |
| `ABInitTask` | `init()` | 15 / 20619 |
| `EmptyABTestManager` | `checkExperiment(String)` | 15 / 20820 |
| `StartupABManager` | `getGroup(String)` | 10 / 21932 |
| `StartupABManager` | `loadConfig()` | 10 / 21974 |
| `StartupABManager` | `configFile()` | 10 / 21925 |
| `e0` | `g()` | 16 / 25303 |
| `ABTestDebugActivity` | `d(Context)` | 5 / 7658 |
| `ABTestDebugActivity` | `onCreate` | 5 / 7660 |
| `PushService$5` | `run()` | 15 / 26168 |
| `PushService$7` | `run()` | 15 / 26172 |

### 10.2 关键字符串（strings 表）

```text
AB_TEST_CONFIG_CACHE_FILE_NAME
AB_TEST_CONFIG_MMKV_FILE_NAME
AB_TEST_CHECKED_CONFIG_MMKV
EXTRA_STRING_AB_EXPERIMENTS_CONFIG
EXTRA_STRING_AB_EXPERIMENTS_CONFIG_DEBUG
EXTRA_STRING_AB_EXPERIMENTS_CONFIG_CHECKED_CONFIG
EXTRA_STRING_AB_EXPERIMENTS_CONFIG_CHECKED_TYPE
EXTRA_STRING_AB_EXPERIMENTS_KEY
EXTRA_STRING_AB_EXPERIMENTS_SWITCH_DEBUG
EXTRA_INT_AB_EXPERIMENTS_CONFIG_PROCESS
ACTION_LIVE_EXPERIMENTS_CONFIG_UPDATE
ACTION_LIVE_EXPERIMENTS_CONFIG_CHECKED
ACTION_LIVE_EXPERIMENTS_CONFIG_CLEAR
ACTION_LIVE_EXPERIMENTS_KEY_ADD
ACTION_UPDATE_CONFIG_4_DEV
ACTION_LIVE_EXPERIMENTS_MAIN_PROCESS_START
ACTION_LIVE_EXPERIMENTS_OVERRIDE_CONFIG_CHECKED
abtest_prefer_file
abTestCallMonitorSwitch
abtest_start_up_check
k_abtest_interval_flag
k_abtest_re_pull_time
startup.config
abtest#dynamicABDefines
abtest#newDuration
abtest#enableFixClearUserLog
wait-task-0805-v2
cold-start-26H2-aos
" can not be found in ABTestKeysUtil , you must add it to register first !"
```

### 10.3 关键 smali 片段

`ABTestManagerImpl.checkExperiment(String,Z)`：

```smali
iget-object v0, v1, Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;->libraModel:Lcom/netease/cloudmusic/abtest2/b;
if-eqz v0, :label_0009
invoke-virtual {v0, v2, v3}, Lcom/netease/cloudmusic/abtest2/b;->x(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
move-result-object v0
goto :label_000a
:label_0009
const/4 v0, 0x0
:label_000a
if-nez v0, :label_0012
iget-object v0, v1, Lcom/netease/cloudmusic/abtest2/ABTestManagerImpl;->mModel:Lcom/netease/cloudmusic/abtest2/b;
invoke-virtual {v0, v2, v3}, Lcom/netease/cloudmusic/abtest2/b;->x(Ljava/lang/String;Z)Lcom/netease/cloudmusic/abtest2/ABTestConfig;
move-result-object v0
:label_0012
return-object v0
```

`b.x` 的优先级：

```smali
invoke-static {}, Lcom/netease/cloudmusic/utils/e0;->g()Z
move-result v2
if-eqz v2, :label_005f
iget-object v2, v3, Lcom/netease/cloudmusic/abtest2/b;->l:Ljava/util/concurrent/ConcurrentHashMap;
if-eqz v2, :label_005f
iget-object v0, v3, Lcom/netease/cloudmusic/abtest2/b;->l:Ljava/util/concurrent/ConcurrentHashMap;
invoke-virtual {v0, v4}, Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
check-cast v0, Lcom/netease/cloudmusic/abtest2/ABTestConfig;
:label_005f
if-nez v0, :label_0069
iget-object v0, v3, Lcom/netease/cloudmusic/abtest2/b;->e:Ljava/util/concurrent/ConcurrentHashMap;
invoke-virtual {v0, v4}, Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
check-cast v0, Lcom/netease/cloudmusic/abtest2/ABTestConfig;
:label_0069
```

`ABInitTask.init()` 的注册与初始化：

```smali
invoke-virtual {v0, v2, v1}, Lcom/netease/cloudmusic/abtest2/c;->b(ZZ)V
...
invoke-static {v0}, Lcom/netease/cloudmusic/abtest2/f;->c([Lcom/netease/cloudmusic/abtest2/l;)V
...
invoke-static {v2}, Lcom/netease/cloudmusic/abtest2/e;->a(Z)Lcom/netease/cloudmusic/abtest2/IABTestManager;
```

`PushService$5.run()`：

```smali
const-class v0, Lcom/netease/cloudmusic/abtest2/IABTestManager;
invoke-static {v0}, Lcom/netease/cloudmusic/common/ServiceFacade;->get(...)
move-result-object v0
check-cast v0, Lcom/netease/cloudmusic/abtest2/IABTestManager;
const-wide/16 v2, 0x0
invoke-interface {v0, v2, v3, v1}, Lcom/netease/cloudmusic/abtest2/IABTestManager;->refresh(JLcom/netease/cloudmusic/abtest2/m;)V
# 重排：debug 0xdbba0 (15min) / release 0xa4cb80 (3h)
```

`ABTestDebugActivity$c.onClick` 的写入口：

```smali
invoke-interface {v0, v2, v1}, Lcom/netease/cloudmusic/abtest2/IABTestManager;->updateExperiment4Dev(Ljava/lang/String;Lcom/netease/cloudmusic/abtest2/ABTestConfig;)V
```

`b.A0` 的 debug 门禁：

```smali
invoke-static {}, Lcom/netease/cloudmusic/utils/e0;->g()Z
move-result v1
if-eqz v1, :label_000c
if-nez v2, :label_0009
goto :label_000c
:label_0009
invoke-direct {v0, v2}, Lcom/netease/cloudmusic/abtest2/b;->n(Lcom/netease/cloudmusic/abtest2/ABTestConfig;)V
:label_000c
return-void
```

`e0.g()` 的 debuggable 判断：

```smali
iget v4, v4, Landroid/content/pm/ApplicationInfo;->flags:I
and-int/lit8 v4, v4, 0x2
if-eqz v4, :label_0056
...
```

### 10.4 `abtest2` 全部类清单（57 个）

| # | 类（`Lcom/netease/cloudmusic/abtest2/...`） | 角色 |
|---|---|---|
| 1 | `ABTestConfig` | 实验配置模型（Parcelable） |
| 2 | `ABTestConfig$a` | `CREATOR` |
| 3 | `LibraABTestConfig` | Libra 配置模型（继承 ABTestConfig） |
| 4 | `LibraABTestConfig$a` | `CREATOR` |
| 5 | `IABTestManager` | 对外接口 |
| 6 | `ABTestManagerImpl` | 单例实现 |
| 7 | `ABTestManagerImpl$a` | 合成构造器标记 |
| 8 | `ABTestManagerImpl$b` | 单例持有者（`a()` 返回实例） |
| 9 | `b` | ABModel 抽象基类（存储/查找/刷新/广播） |
| 10 | `b$a` | SharedPreferences 变更监听 |
| 11 | `b$b` | 跨进程广播接收器 |
| 12 | `b$c` | 日期变更监听 |
| 13 | `b$d` | 网络刷新 Runnable |
| 14 | `b$d$a` | Retrofit 回调 |
| 15 | `b$d$a$a` | 响应解析 Runnable |
| 16 | `b$e` | MMKV 读写策略（配置缓存） |
| 17 | `b$f` | MMKV 读写策略（checked 缓存） |
| 18 | `p` | LibraABModel（继承 b） |
| 19 | `p$a` | Libra 内部辅助 |
| 20 | `q` | 旧 ABModel（继承 b） |
| 21 | `a` | 网络参数/自定义配置拼装 |
| 22 | `a$a` | 内部辅助 |
| 23 | `a$b` | 内部辅助 |
| 24 | `a$c` | Kotlin Lambda |
| 25 | `c` | 运行期开关单例（调试/存储介质） |
| 26 | `d` | 动态 AB 定义注册表（`abtest#dynamicABDefines`） |
| 27 | `e` | 静态工厂 `a(Z)` |
| 28 | `f` | key 注册表聚合器 |
| 29 | `g` | 调试页 ViewModel |
| 30 | `h` | ABTestKeysUtil 调用监控 |
| 31 | `h$a` | 监控内部辅助 |
| 32 | `h$b` | 监控主体 |
| 33 | `h$b$a` | 广播接收器 |
| 34 | `h$b$b` | Kotlin Lambda |
| 35 | `h$b$c` | 监控内部辅助 |
| 36 | `i` | 调试悬浮窗/点击处理 |
| 37 | `j` | 实验回调接口 |
| 38 | `k` | Retrofit API 接口 |
| 39 | `l` | key 注册表接口 |
| 40 | `m` | 刷新回调接口 |
| 41 | `n` | 刷新完成回调接口 |
| 42 | `o` | RPC AB key 管理器接口 |
| 43 | `o$a` | RPC 回调 |
| 44 | `r` | 5 个布尔开关 |
| 45 | `s` | RN AB key 管理器 |
| 46 | `s$a` | RN 回调 |
| 47 | `t` | ABTest1/2/3/4 更新监听 |
| 48 | `ABTestDebugActivity` | 调试 Activity |
| 49 | `ABTestDebugActivity$a` | 调试页内部辅助（排序/比较） |
| 50 | `ABTestDebugActivity$b` | 搜索框 TextWatcher |
| 51 | `ABTestDebugActivity$c` | 编辑分组点击回调 |
| 52 | `ABTestDebugActivity$d` | RecyclerView Adapter |
| 53 | `ABTestDebugActivity$ABTestViewHolder` | RecyclerView ViewHolder |
| 54 | `ABTestDebugActivity$ABTestViewHolder$a` | ViewHolder 内部回调 |
| 55 | `ABTestDebugActivity$ABTestViewHolder$b` | ViewHolder 内部回调 |
| 56 | `ABTestDebugActivity$ABTestViewHolder$c` | ViewHolder 内部回调 |
| 57 | `ABTestDebugActivity$ABTestViewHolder$d` | ViewHolder 内部回调 |

### 10.5 Manifest 证据

```xml
<activity
    android:theme="@style/sh"
    android:label="ABTest信息配置"
    android:name="com.netease.cloudmusic.abtest2.ABTestDebugActivity"
    android:screenOrientation="locked"
    android:configChanges="screenSize|orientation|keyboardHidden"
    android:windowSoftInputMode="adjustResize|stateHidden"/>
```

```xml
<provider
    android:name="org.xjy.android.treasure.provider.TreasureProvider"
    android:exported="false"
    android:multiprocess="false"
    android:authorities="com.netease.cloudmusic.treasurepreferences"/>
```

```xml
<application ... android:debuggable="false" ...>
```

```xml
<uses-sdk android:minSdkVersion="23" android:targetSdkVersion="33"/>
```

---

## 11. 最终建议（落地顺序）

1. **先做只读验证**：在真机上用 Frida hook `ABTestManagerImpl.checkExperiment`，打印白名单 key 的原始 group/status/clientConfig，确认服务端实际分组。
2. **再上方案 A（`getGroupKeys`）**：只改分组，不动其它字段；先选 3~5 个纯 UI key（`tabMerge`、`comment-click`、`home-headframe`）。
3. **需要参数时再上方案 C**：只对白名单实验的特定参数返回目标值。
4. **不要**用全局默认 `t`、不要碰付费/青少年/登录/Cookie/安全 key。
5. **不要**依赖 `ABTestDebugActivity` 或一次性的缓存修改；它们会被 `e0.g()` 门禁或服务端刷新覆盖。
6. 若必须覆盖启动阶段 key，单独用 LSPosed/Zygisk hook `StartupABManager.getGroup`，并严格限制 key 列表。

---

*报告完。所有结论均来自 `E:\download\base(2).apk` 的只读 DEX/Manifest 分析；未修改任何源码或 APK。*
