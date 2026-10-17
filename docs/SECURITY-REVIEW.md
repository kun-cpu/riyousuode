# 来源与构建审查 · 2026-10-07

结论：官方工具和已检查的构建配置可以用于本项目；没有发现与应用用途无关的安装钩子或运行时网络行为。覆盖范围有限，不能视作完整安全认证。

## 参考项目

- `https://github.com/DweepParekh/HabitTrackerApp` 的 main 分支（查询时提交 `e95654499ffeee44896f90aa9222e146518f7555`）ZIP 在系统临时目录 `riyousuode-review` 解包，未运行原应用。
- 检查了根及 app 的 Gradle 脚本、Manifest、HabitRepository、HabitViewModel 和全树危险模式。构建仅使用 Google、Maven Central、Gradle Plugin Portal；没有自定义 shell 下载/执行任务。
- 原 Manifest 不请求网络或敏感权限；源码扫描的 URL 命中为文档、XML 命名空间和 Gradle 标准链接。
- 原应用逐步更新积分并只保留最后打卡日期，故不复用原结算算法。
- 保留原 MIT 文件。本项目源代码未引入其二进制包或签名密钥。

## 官方工具校验

Android 包的 SHA-1 与 `https://dl.google.com/android/repository/repository2-1.xml` 的官方元数据比较通过；Gradle SHA-256 与官方 `.sha256` 文件比较通过。

| 文件 | 官方校验值 |
|---|---|
| platform-34-ext7_r03.zip | `1f2e9478d6a7601425ceaa553311dc43191f103d` |
| build-tools_r34-windows.zip | `62cfde1b6fcc3ad12a4d2ba1b537e752768bfd47` |
| platform-tools_r37.0.1-win.zip | `e03e78b1d80b396f1c3358e31251cb31740e1110` |
| gradle-8.2-bin.zip (SHA-256) | `38f66cd6eef217b4c35855bb11ea4e9fbc53594ccccb5fb82dfd317ef8c2c5a3` |
| emulator-windows_x64-16428233.zip | `488ed747e82de7e9bb5247becd1ac043c7e5e85d` |
| x86_64-30_r11.zip | `5e4de3946d46f88856c35efcc4d797b381456347` |

模拟器来自 Google 官方源；系统镜像的校验值来自 `https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml`。仅在本任务专用的 Android 模拟器中安装测试包。

依赖版本固定于 Gradle 配置；OSV 对 Maven `org.jetbrains.kotlin:kotlin-stdlib:1.9.22` 的查询返回空结果，这不是完整传递依赖审计。

Windows Defender 自定义扫描已尝试，但系统返回「尝试扫描你的设备时遇到错误」，不能宣称通过病毒扫描。继续使用的依据是官方工具来源与哈希匹配、参考源码静态审查，以及没有运行参考项目安装脚本。

## 应用权限

本项目 Manifest 仅声明通知与开机广播；没有 INTERNET、通讯录、定位、短信、文件全盘访问权限。备份通过系统文件选择器读取用户指定文件。个人签名在本机新建，并排除版本控制。

## 0.3.0 增量审查（2026-10-07）

未增加依赖或下载插件，沿用本地工具链。背景由本应用绘制或由用户通过系统选择器授权读取，不申请整个相册的读取权限；图片复制到私有目录并限制输入、尺寸及编码大小。Android 8 的 JPEG 方向仅解析有边界检查的 EXIF orientation 字段，避免使用旧平台完整 EXIF 解析器。ZIP 恢复检查路径、重复条目、数量、解压大小、结构、图片内容和 SHA-256 内容文件名；全部验证后提交数据库。源码包不含签名私钥和个人数据库。
