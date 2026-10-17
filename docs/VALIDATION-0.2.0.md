# 0.2.0 验证记录

日期：2026-10-07。应用标识 cn.riyousuode.app，versionCode 2，Android 8.0 及以上。

## 结果

- JVM：22 项通过（原有规则 13 项、新增 9 项）。涵盖删除无历史/已归档/关联目标任务、净积分回退与负余额、已兑现凭证和商店兑换保留、重复删除幂等、超过 Int 范围的积分调整；金额精度与无效输入、记账增改删、跨月筛选及分类统计、积分隔离；v1 升级与 v2 备份校验。
- Android 11 / API 30 专用模拟器：8 项全部通过，包含真实任务删除确认、记录/目标清除与提醒取消，记账新增/编辑/删除及月份切换，Room 持久化、事务异常回滚、v1/v2 恢复和损坏备份拒绝，以及原任务与奖惩流程。输出见 instrumentation-results-0.2.0.txt。
- 320dp 宽、约 533dp 高、1.5 倍系统字体：五页截图、任务删除、记账增改删共 3 项通过。确认正文可滚动，按钮及五项导航可用。输出见 large-font-results-0.2.0.txt；截图在 dist/screenshots/0.2.0/large-font-final。
- 实际签名 0.1.0 → 0.2.0 覆盖安装：未卸载，旧任务 UpgradeCheck 保留，新记账导航可见。见 upgrade-results-0.2.0.txt。随后仅在专用模拟器卸载 release、安装 debug 运行测试，最后重新安装发行 APK 做冷启动检查。
- 发行 APK 签名验证通过，与 0.1.0 使用相同证书；SHA-256 指纹：16fe625cf0158809c59537fb8ddd686019bee30558ec4b4f7a2f318f6f16bc4f。见 signature-verification-0.2.0.txt。
- Release、Debug、测试 APK 构建成功。Lint 0 错误、1 个已有警告：Compose runtime 自带 Lint 与 AGP 8.2.2 分析 API 不兼容，部分 Compose 静态规则跳过。见 build-results-0.2.0.txt。

## 数据与实现

删除和积分调整使用同一 Room 事务；原流水保留，未兑现效果取消，已兑现效果保留并标注来源已删除。提醒在事务成功后重新同步，重启或广播也按当前任务状态检查。删除后的任务不再生成记录。

记账使用独立 MoneyRecord 集合和整数分，提供新增、修改、删除、月份明细、月汇总、分类汇总接口。v1 快照读取后迁移至 v2，记账为空；导出始终使用 v2。恢复沿用 10 MB 限制、结构与字段验证、覆盖前快照，并明确提醒覆盖任务与记账。

本次未增加第三方依赖，沿用已检查的工具链及离线依赖。APK 不包含网络权限。源码包不包含签名私钥、SDK、构建缓存或个人数据库；独立重建发行版需要自己的签名，不能覆盖本签名安装。

## 验证边界

未在实体手机、Android 13+ 通知权限弹窗或各厂商省电策略上测试。非精确提醒仍可能由系统延迟。Gradle UTP 的本机 gRPC 入口不可用，设备测试经 adb 直接调用同一 AndroidJUnitRunner，可使用 scripts/test-device.ps1 复现。

截图均使用测试数据，首次安装为空。普通尺寸截图位于 dist/screenshots/0.2.0/normal-final，安装及源码文件校验值见 dist/SHA256SUMS.txt。
