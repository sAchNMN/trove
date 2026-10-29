# 设备归档

一个离线 Android 文件整理 App。照片和视频留在手机里，按媒体元数据中的拍摄设备建立真实文件夹，不经电脑往返传输。

## 安装与使用

- Android 9 或更高版本，不需要 root。
- 安装包：`dist/DeviceSorter-0.1.0-test.apk`。这是本地调试签名的测试版本，尚未上架 Google Play，也未经过独立安全审计。
- 点来源旁的加号，用系统目录选择器授权一个或多个本地目录。
- 选择独立的目标目录，例如来源选 `DCIM/Camera`，目标选 `Pictures/设备归档`。来源和目标不能相同或互相包含。
- 点“扫描设备”，勾选要处理的设备，可查看对应文件和目标路径。
- 首次先点“小批量试跑”：每台设备最多复制 3 张照片和 3 段视频，保留原件。
- 确认照片可打开、视频可播放后，重新扫描，再选择“复制”或“移动”。
- 顶部时钟按钮可查看历史操作记录、导出完整 JSONL。

试跑之后再正式整理时，之前的试跑副本不会自动删除；同名文件会加序号，不会覆盖。操作日志不是备份，也不提供自动撤销。

## 文件安全

- 默认复制。未知设备、无法读取元数据的文件、非媒体文件不移动。
- 使用系统 Storage Access Framework，只访问明确授权的本机内部存储/SD 卡目录，不支持云盘。
- 不申请联网权限，不含广告、遥测、账号、服务器，也不申请“所有文件访问”权限。
- 不改写原文件内容、EXIF 或视频编码。设备型号是原始字段，不把例如 `V2415` 猜成某个商品名称。
- 先写隐藏的 `.devicesorter-*.partial` 文件。计算复制流 SHA-256，再复读目标校验；通过后才恢复正常文件名。
- 移动还会复读源文件校验，并检查扫描前后大小、修改时间；写入并同步校验日志之后才请求删除源文件。
- 复制失败、目标校验失败、空间不足或正常取消不会删除源文件。删除被拒绝时保留校验后的目标副本。
- 操作中断不会自动恢复删除。再次启动先查看记录和目标目录，再决定如何处理重复副本或隐藏临时文件。
- 移动成功后，源文件会真正删除，不经过回收站。重要素材应另有备份；不要在整理期间用其他 App 编辑这些文件。

这不是零拷贝移动：每个文件先在手机内复制和校验，再删除。需要至少容纳当前文件副本的空闲空间；复制模式则需要容纳所有副本。大视频会花时间，但无需通过 USB 往返。文件系统修改时间可能变化，文件内部拍摄时间不变。

## 已知边界

- 图片使用 `metadata-extractor 2.22.0`，支持其可读取的 JPEG、HEIF/HEIC、TIFF/RAW 等格式的 EXIF 设备字段。不是所有格式或厂商私有元数据都能读取。
- 视频首版读取 QuickTime 风格的制造商/型号字段。已覆盖带 Apple QuickTime 型号的 MOV；MP4 需要包含此读取器支持的字段才能识别。
- `com.android.model` 等 Android 厂商字段目前未适配；即使文件有其他工具能读出的型号，首版也可能列为未识别。不会根据文件名、分辨率或编码器推测设备。
- 不支持自动处理隐藏在私有 App 数据区、系统禁止授权的目录或未下载的云文件。
- 不把 Live Photo、RAW+JPEG、XMP sidecar 当作一个原子组；各媒体文件独立识别，非媒体附件保留原处。
- vivo 的后台限制仍需实机验证。大批量任务建议保持 App 前台和手机电量充足；系统杀进程时查看持久化日志，不自动继续。
- 不是已有 Google Play 软件的破解版本，也不包含 Termux 或 ExifTool。源代码和依赖来源可查，但不等于通过安全认证。

## 构建

依赖：JDK 17/21、Android SDK Platform 34、Gradle 8.13。

```powershell
$env:ANDROID_HOME = '你的 Android SDK 目录'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

SDK 路径也可以由本机 `local.properties` 的 `sdk.dir` 提供，不要提交个人路径。

APK 默认位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle 包装器使用官方分发地址，依赖从 Google Maven / Maven Central 获取；不禁用 TLS 或证书校验。

模拟器测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

测试 APK 使用 Android 平台的可选测试库，仅作用于测试包，不加入生产 APK。合成媒体夹具位于 `app/src/androidTest/assets`；重新生成需要 Pillow 和 FFmpeg，运行 `tools/make_test_media.py`，可用 `FFMPEG` 环境变量指定编码器路径。生成脚本不会读取私人媒体。

## 结构

- `MainActivity`：目录授权、设备筛选、确认及日志导出。
- `SortService`：前台扫描和批量任务。
- `Storage`：本地文档提供器操作。
- `Metadata`：调用现成元数据库读取设备字段。
- `Transfer`：独立、可测试的复制/校验/删除顺序。
- `Session`：扫描快照和逐文件同步日志。
- `Naming`：型号归一、目录名与重名保护。

## 依赖与资料

- [metadata-extractor 官方仓库](https://github.com/drewnoakes/metadata-extractor)，Apache-2.0；传递依赖 `xmpcore` 具有自己的许可证。
- [QuickTime 型号字段源码](https://github.com/drewnoakes/metadata-extractor/blob/2.22.0/Source/com/drew/metadata/mov/metadata/QuickTimeMetadataDirectory.java)。
- [Android 目录授权](https://developer.android.com/training/data-storage/shared/documents-files)。
- [Android DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)。

APK 内附第三方许可证。请保留这些声明。
