# 隐私与权限说明 / Privacy & Permissions

本项目当前源码没有声明 `INTERNET` 权限，不包含广告或分析 SDK，也不会自动上传诊断数据。选择“分享兼容性报告”时，报告将交给用户选择的其他 App。

- **修改系统设置**：仅用于公开的刷新率偏好写入与恢复（厂商策略可能拒绝）。
- **悬浮窗 / 前台服务 / 通知**：用户手动启用可拖动的屏幕 Hz 提示，关闭时停止服务。
- **查看安装的应用**：全局高刷实验模式需要本地枚举应用包名；这些名称不会自动上传。
- **厂商私有接口**：仅在用户发起操作时尝试调用；可能因权限/固件差异失败。

This app currently declares no `INTERNET` permission and includes no analytics or advertising SDK. Device diagnostics are only shared when the user explicitly invokes the system share sheet. The overlay, installed-app visibility and vendor-control functions operate locally, subject to Android and manufacturer policy.
