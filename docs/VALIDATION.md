# 验证记录 · 2026-10-07

## 构建与规则测试

实测命令：本机 Gradle 8.2 执行 `testDebugUnitTest assembleRelease lintDebug assembleDebug assembleDebugAndroidTest`。最终构建成功，完整输出见 `build-results.txt`。

13 项 JVM 测试全部通过，0 失败、0 错误：

- 多日未打开仍等待确认，不自动扣分；重复生成不增加同日记录。
- 重复完成不重复加分；撤销恢复原余额。
- 失败后补记按差额修正账本，撤回未执行惩罚。
- 已执行惩罚更正后保留真实历史及人工处理标记。
- 每周任务连续统计跳过休息日和豁免，失败中断。
- 目标到期前不能判失败，期限内待确认记录须先结算。
- 兑换不能透支；重复请求不会重复扣款；退款只能执行一次。
- 编辑不改写历史积分，改变重复规则不补造过去的欠账。
- 归档未来任务不产生任务记录；一次性任务只生成一次。
- 手动目标及已兑现奖励的更正。
- 备份往返、损坏 JSON、缺字段、未知版本和重复记录校验。
- 当日失败拒绝，以及最大积分正负切换的边界。

## 安卓设备测试

使用独立 Android 11 / API 30 x86_64 模拟器，360dp 宽屏幕；没有连接或更改用户手机。

已通过 5 项 Android instrumentation 测试：

1. 新建任务、打卡获得积分、创建奖励、兑换扣分。
2. 过去任务确认失败，余额变负并生成待执行惩罚。
3. 读取四个页面并生成真实运行截图。
4. Room 数据重新打开后保留；损坏备份不覆盖；有效备份恢复。
5. 提醒广播显示本地通知；完成一次性任务后取消预约。

测试输出见 `instrumentation-results.txt`。Gradle 的 UTP 设备测试入口在本机无法启动 gRPC 监听服务，使用相同 AndroidJUnitRunner 经 adb 直接运行；可用 `scripts/test-device.ps1 -Serial emulator-5556` 复现。

另外在 1.5 倍系统字体下运行四页面截图流程通过（`large-font-results.txt`），人工检查首页、奖惩页无文字重叠，内容可滚动。截图位于 `dist/screenshots/`；截图中的任务为测试示例，安装包首次打开没有预填数据。

## 静态检查与边界

- Android Lint：0 错误、1 警告。警告来自 Compose runtime 自带的 Lint 插件与 AGP 8.2.2 分析 API 不兼容，部分 Compose 专用静态规则被跳过；这不等同于完全静态检查覆盖。
- 已修正 Android 8.0 不支持浅色系统导航按钮的资源版本问题。
- APK 已通过 Android `apksigner verify`，使用本机 RSA 3072 位个人签名、APK v2 签名方案。
- 没有 INTERNET 权限；Android 自动云备份及系统设备迁移已禁用，使用应用内手动备份。
- 尚未在实体安卓手机、Android 13+ 通知权限弹窗及各厂商后台省电策略上验证。模拟器通过不保证每个机型的提醒时效；系统可延迟非精确提醒。
- 第一版数据库使用原子 JSON 快照，备份/数据上限为 10 MB，适合个人日常记录。

签名验证输出见 `signature-verification.txt`。发行包 SHA-256 见 `dist/SHA256SUMS.txt`。

## 最终发行包验证

最终签名 APK 在专用模拟器中全新安装成功，冷启动成功；通过真实界面新建任务后再次覆盖安装同一签名 APK，任务仍然存在。结果见 `release-smoke-results.txt`。首次启动截图为 `dist/screenshots/00-first-launch.png`。
