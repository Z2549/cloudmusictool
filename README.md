# 云音乐工具箱 (CloudMusicTool)

网易云音乐 Android 客户端的 LSPosed 体验优化模块：去广告、精简界面、收敛弹窗、隐私加固。

> 当前版本 **1.0.0**（2026-10-19），已完成 25102RKBEC 真机验证（Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08）。
> 与网易官方无任何关联，仅供学习研究和个人设备使用。

## 兼容性

| 项 | 值 |
|---|---|
| 目标应用 | `com.netease.cloudmusic` **9.6.08** (versionCode 9006008) |
| 模块包名 | `io.github.yigenhuobah.cloudmusictool` |
| Xposed API | libxposed API 102（`minApiVersion=102` / `targetApiVersion=102`） |
| 系统要求 | Android 10+（minSdk 29） |
| 框架 | 支持 API 102 的 LSPosed（实测环境 LSPosed v2.2.1 + KernelSU/Zygisk） |
| 架构 | 纯 Java 17 + XML + Material 3，无 Kotlin/Compose |

## 安装

1. 安装 `cloudmusictool-<version>.apk`。
2. 在 LSPosed 管理器中启用「云音乐工具箱」，作用域勾选「网易云音乐」。
3. 打开模块 App，确认状态卡显示「已激活」（首次可能需要启动一次网易云）。
4. 按需开启功能，修改后重启网易云音乐生效。

## 功能

### 广告（默认开启）

| ID | 功能 | 说明 |
|---|---|---|
| AD-01 | 开屏广告跳过 | 判定/数据源/落地页三层拦截 + 摇一摇 + 免广实验分支 |
| AD-02 | 运营弹窗屏蔽 | 启动时的运营/营销弹窗，保留一键登录 |
| AD-03 | 更新弹窗屏蔽 | 版本检查、强更弹窗、应用内下载入口 |
| AD-04 | 首页信息流广告 | `Banner.isAdBanner` 闸门 |
| AD-05 | 评论区广告 | 评论条目广告字段、评论推广条、带货标签 |
| AD-06 | 播放页广告 | 黑胶广告层、送礼/活动贴纸、曝光兜底 |
| AD-07 | 音频贴片/播客广告 | 音频贴片、播客视频广告 |
| AD-08 | 广告上报/外跳 | 广告统计上报、静默拉起外部 App、曝光预请求 |

### 界面（默认开启，UI-08 实验性）

| ID | 功能 | 说明 |
|---|---|---|
| UI-01 | 首页模块净化 | 按 blockCode 隐藏发现页卡片；同步过滤首页 MMKV 缓存 |
| UI-02 | 顶部频道管理 | **暂未启用**（数据链路已定位，但渲染层无法完全屏蔽空频道） |
| UI-03 | 底部导航精简 | 隐藏漫游/动态，按保留清单过滤底栏 |
| UI-04 | 侧栏抽屉精简 | **暂未启用**（当前版本无可靠挂载点，避免误伤主界面） |
| UI-05 | 跳过启动延迟 | 去掉红屏人为等待 |
| UI-06 | 引导与气泡屏蔽 | 推送/评分/VIP/直播/底栏气泡、青少年弹窗 |
| UI-07 | 禁止自动播放 | 冷启动、信息流、Banner、蓝牙、4G MLog 自动播放 |
| UI-08 | 强制深色模式 | 实验性，默认关闭 |
| UI-09 | 关闭头像挂件 | 个人主页/评论区头像装饰 |

### 隐私（默认关闭）

| ID | 功能 | 说明 |
|---|---|---|
| PV-01 | OAID 上报拦截 | OAID 上传与启动任务 |
| PV-02 | 日志写盘任务屏蔽 | `NeedLogToFileTask` / `NBLogTask` |
| PV-03 | 启动任务黑名单 | 按 taskName/类简名跳过非必要启动任务 |
| PV-04 | APM 上报抑制 | 抑制 `logApm` / `forceApmUpload`（不拦初始化） |
| PV-05 | 统计数据观测 | 只记录统计 key，不拦截业务调用 |

## 已知限制

- **只适配 9.6.08**。其他版本默认跳过；高级配置 `allow_unsupported_version` 可尝试 best-effort，但不保证。
- 广告由服务端下发，模块只能阻止展示/上报，不能阻止接口请求。
- 宿主存在 Tinker 风格热补丁，补丁可能替换被 hook 的实现。
- UI-02 顶部频道管理：9.6.08 的频道数据链路已打通，但顶部 tab 是预创建视图 + 增量 addTab 结构，
  渲染层无法从外部可靠删除，空频道仍会留下可点击槽位，故暂标记为 blocked。
- UI-04 抽屉精简因缺乏可靠挂载点，当前标记为 blocked。
- PV-05 只做观测，不会屏蔽统计上报。
- 不触碰安全/风控/设备 ID，不绕过付费、VIP、DRM、版权区域限制。

## 构建

环境：JDK 17、Android SDK Platform 36、Gradle wrapper 8.13、AGP 8.12.2。

```bash
# debug
gradlew.bat :app:assembleDebug

# release（需要根目录 keystore.properties 与 .jks）
gradlew.bat :app:assembleRelease
```

产物：
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/cloudmusictool-<version>.apk`

依赖：
- `compileOnly io.github.libxposed:api:102.0.0` / `annotation:1.0.0`
- `implementation io.github.libxposed:service:102.0.0`
- `implementation org.luckypray:dexkit:2.3.0`（仅硬编码失败时兜底）
- AppCompat / Material / androidx.preference

## 签名

`keystore.properties` 与 `*.jks` 已在 `.gitignore` 中，不入库。
缺失时 release 会回退 debug 签名（仅用于本地测试，不可用于正式发布）。

## 隐私

模块不联网、不收集、不上传任何账号或使用数据。
设置界面只通过 LSPosed 的 remote preferences 与本机目标进程通信。
统计观测（PV-05）只写 logcat，不落盘、不外传。

## 免责声明

本项目为开源学习项目，所有代码仅供学习研究、逆向工程实践和个人设备体验改善。
严禁用于商业用途、付费破解或绕过版权保护。使用风险由使用者自行承担。
与网易云音乐官方无任何隶属或背书关系。

## 参考

- libxposed API 102 规范（Maven Central `io.github.libxposed:api:102.0.0`）
- DexKit（`org.luckypray:dexkit`）
- UI 结构参考同作者的 QuietBox 模块
