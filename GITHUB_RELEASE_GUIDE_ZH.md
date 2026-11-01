# GitHub Beta APK 发布指南

## 1. Android Studio 构建

1. 将工程解压到你的 **APFS 开发磁盘**（不推荐直接在 exFAT 上解压或编译）。
2. 在 Android Studio 选择 **File → Open**，打开工程根目录。
3. Gradle JDK 使用 **21**；同步完成后先执行 **Build → Make Project**，确认无编译错误。
4. 分别在 vivo 和 OPPO Pad Mini 真机测试：请求刷新率 → 在实际第三方 App 里读取显示模式 → 释放请求 → 确认恢复。

## 2. 导出正式签名的 APK

1. 选择 **Build → Generate Signed App Bundle / APK → APK**。
2. 首次发布创建新的 keystore（密钥库），以后更新必须使用同一密钥库和别名；保存好密码。
3. 选择 **release** 构建变体并完成签名，等待 APK 构建完成。
4. 不要把 debug APK 当正式发布文件。不要上传 `.jks`、`.keystore`、密码、`local.properties`、私密日志。
5. 若已分发了签名不同的 APK，Android 可能无法覆盖安装，需先处理安装包签名一致性。

项目 `applicationId` 保持 `com.vanker.vrefresh`，本版 `versionCode=13`、`versionName=0.6.6-beta.1`。

## 3. GitHub Release

1. 创建仓库（建议 `Android-Refresh-Rate-Tool`）。将本工程源码提交到仓库，保留 `README.md` 与 `THIRD_PARTY_NOTICES.md`。
2. 创建 tag：`v0.6.6-beta.1`，勾选 **Set as a pre-release**。
3. 上传经签名并通过真机测试的 APK，文件名可用 `AndroidRefreshRateTool-v0.6.6-beta.1.apk`。
4. 更新记录放 **Releases** 或 `CHANGELOG.md`；**README.md 保持稳定，不要每版重写**。
5. 标明实际验证通过的品牌/机型与功能；不要宣称所有品牌已实现强制高刷。

## 公开测试提醒

- `QUERY_ALL_PACKAGES` 用于本地枚举安装包，以尝试覆盖全部应用；若将来提交 Google Play，需按平台政策评估该权限。
- 当前不上传诊断数据，用户选择分享报告时才会交给目标应用。
- 高刷可能增加功耗、发热或影响显示调光；无法恢复时可关闭其他高刷工具、重新启动设备，并回到系统显示设置检查。
- 程序显示屏幕 Hz，并非游戏 FPS；无论请求是否被接受，都应验证实际模式。
