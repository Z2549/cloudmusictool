# 发版检查清单

> 当前版本 1.1.0（versionCode 3）。1.0.0 已发布；1.1.0 的代码与真机验收记录见
> [`phase2/1.1.0-VERIFY-A.md`](phase2/1.1.0-VERIFY-A.md)。

## 1. 真机验证（必须先做）

设备：`25102RKBEC`（Android 17 / arm64 / KernelSU / LSPosed v2.2.1 / 网易云 9.6.08）
详细记录见 [DEVICE_VERIFICATION.md](DEVICE_VERIFICATION.md)

- [x] 安装 debug APK，LSPosed 启用并勾选网易云（staticScope 自动生效）
- [x] 状态卡显示「已激活」
- [x] logcat tag `CloudMusicTool` 有 `registered` / `hit`（0 处 not found）
- [x] AD-01 开屏、AD-03 更新弹窗（hit 实测）
- [ ] AD-02 运营弹窗（已注册，等服务端下发弹窗时目视确认）
- [x] AD-05 评论广告（hit 实测）、AD-08 上报/外跳（hit 实测）
- [ ] AD-04 首页 Banner、AD-06 播放页广告、AD-07 音频贴片（已注册；视觉无广告，无自然 hit 场景）
- [x] UI-01 首页 block 多选（开启/关闭双向回归通过，见 DEVICE_VERIFICATION.md）
- [x] UI-03 底栏（保留清单实测 4→2 生效；选项已按真实 tab 修正）
- [x] UI-02 顶部频道（1.1.0 已实现真实删除：`dr.h#T0` 数据源过滤；真机「只保留推荐 → 1 个 tab、左右滑不进隐藏频道、旋转/深色不崩」）
- [x] UI-05 启动延迟、UI-07 自动播放（hit 实测）
- [ ] UI-06 引导气泡、UI-09 挂件（已注册，等自然触发）
- [x] PV-01~05 逐项开启（二轮已验：无闪退、登录正常、播放正常、PV-03/05 有 hit；已恢复默认关闭）
- [x] 关闭功能后重启，确认行为恢复（AD-03 开关、UI-01 多选均已双向验证）
- [x] 记录失效的 Hook（本轮 0 处 `skip: class/method not found`）

## 2. 版本号

- [x] 1.0.0 → 1.1.0：`app/build.gradle` versionCode 2 → 3，versionName 1.0.0 → 1.1.0
- [x] 同步 `CHANGELOG.md` 的版本号与日期
- [x] 更新 README 的版本与功能表

## 3. 构建与签名

```bat
cd /d D:\cloudmusictool
gradlew.bat clean
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

- [ ] release 使用 `keystore.properties` 指向的正式 keystore（不是 debug 证书）
- [ ] `apksigner verify --print-certs` 指纹与预期一致
- [ ] APK 内 `META-INF/xposed/java_init.list` 只有一个入口
- [ ] APK 内 `module.prop` 的 min/targetApiVersion=102
- [ ] APK 内 `scope.list` 只有 `com.netease.cloudmusic`
- [ ] `classes.dex` 中 `CloudMusicToolModule`、`XposedProvider`、`MaterialSwitchPreference` 未被 R8 改名
- [ ] `debuggable=false`、`allowBackup=false`

## 4. 发布材料

- [ ] README 功能表与默认值准确
- [ ] CHANGELOG 完整
- [ ] LICENSE 存在
- [ ] 应用图标（音乐符号）已替换
- [ ] 截图：状态卡、功能页、首页定制页、关于页
- [ ] 免责声明与隐私说明

## 5. 仓库与发布

- [x] git 仓库已初始化（当前已有提交）
- [ ] 源码仓库 tag：`v<versionName>`
- [ ] LSPosed 官方仓库 tag：`<versionCode>-<versionName>`（例如 `3-1.1.0`）
- [ ] 包名保持 `io.github.yigenhuobah.cloudmusictool`（自动审批）
- [ ] 上传 release APK 与 mapping.txt
- [ ] 发布说明包含：目标版本 9.6.08、已知限制、隐私默认关闭、26 个新挂载点、新功能五步验证状态

## 6. 发布后

- [ ] 监控 issue：版本升级导致的 `skip: class/method not found`
- [ ] 用 `tools/check-mounts.sh` 对新版本 APK 做挂载点体检
- [ ] 按需补充 DexKit anchors

---

## 7. 1.0.0 发布执行记录（2026-10-19）

- [x] versionCode 1 → 2，versionName 0.1.0 → 1.0.0
- [x] CHANGELOG 1.0.0 条目（变更/已知限制/默认值）
- [x] README 版本说明与 UI-02/UI-04 已知限制同步
- [x] clean build debug + release，release 4.7MB 已签名（CN=CloudMusicTool，SHA-256 eea85c…9025）
- [x] 元数据核验：包名/versionCode=2/versionName=1.0.0/minSdk 29/label 云音乐工具箱
- [x] META-INF/xposed：单入口 CloudMusicToolModule、min/target=102、scope 仅 com.netease.cloudmusic
- [x] R8 后 CloudMusicToolModule / XposedProvider / MaterialSwitchPreference / MultiSelectPreference / HookKit 均保留原名
- [x] **release 包真机验证**：卸载 debug 后安装 release 1.0.0（LSPosed 需重新启用：pkill daemon → 修正 modules_state → setsid 拉起 daemon → 软重启），
  冷启动 14 项 enabled、AD-01/03/08、UI-05/07 命中、0 error
- [ ] git init / 提交 / tag v1.0.0
- [ ] LSPosed 官方仓库 tag `2-1.0.0` + 上传 release APK 与 mapping.txt
- [ ] 发布说明（含 UI-02/UI-04 暂不支持、目标版本 9.6.08、隐私默认关闭）

---

## 8. LSPosed 官方仓库提交（1.0.0，2026-10-19）

已完成：

- [x] 提交 issue：https://github.com/Xposed-Modules-Repo/submission/issues/2223（`[submission] io.github.yigenhuobah.cloudmusictool`）
- [x] 机器人已建仓库：https://github.com/Xposed-Modules-Repo/io.github.yigenhuobah.cloudmusictool

**待人工完成（需浏览器，API 权限不足）**：

1. 接受协作者邀请：https://github.com/Xposed-Modules-Repo/io.github.yigenhuobah.cloudmusictool/invitations
2. 在网页把仓库 Description 设为「云音乐工具箱」，Homepage 设为 https://github.com/yigenhuobah/cloudmusictool
3. 推送元数据文件（内容已备份在 `dist/xmr-repo/`）：
   - `SUMMARY`：一行模块简介
   - `README.md`：模块说明页
4. 创建 Release：
   - 标题 `1.0.0`
   - tag `2-1.0.0`（versionCode-versionName）
   - 上传 `dist/cloudmusictool-1.0.0.apk`
   - 正文填 CHANGELOG 1.0.0 内容
5. 等约 5 分钟，模块会自动出现在 LSPosed「模块仓库」与 modules.lsposed.org

注意：只改 Release 里的 APK 附件不会触发机器人更新，必须同时修改 Release 内容。

### 官方仓库已完成（2026-10-19）

- [x] 接受协作者邀请（push 权限已生效）
- [x] 推送元数据：`SUMMARY` + `README.md` → main 分支
- [x] Release 已创建：tag `2-1.0.0`，标题 `1.0.0`，附件 `cloudmusictool-1.0.0.apk`
  https://github.com/Xposed-Modules-Repo/io.github.yigenhuobah.cloudmusictool/releases
- [ ] **Description 需网页设置**（API 对 maintain 角色返回 404，机器人文档亦说明须浏览器操作）：
  打开 https://github.com/Xposed-Modules-Repo/io.github.yigenhuobah.cloudmusictool
  点右侧设置图标，Description 填「云音乐工具箱」，Website 填 https://github.com/yigenhuobah/cloudmusictool
  → 不填 Description 模块不会出现在 LSPosed 仓库列表
