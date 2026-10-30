# 喵在身边 · CatCompanion V0.1

Kotlin 原生安卓悬浮宠物 **源代码项目**（不是已编译 APK）。兼容 Android 8.0+；建议在 Android 12–16 的真机测试。无需网络权限、无需后端或 AI 密钥。

## 当前功能

- ✅ `WindowManager.TYPE_APPLICATION_OVERLAY` 透明悬浮猫咪；可在支持悬浮窗的第三方 APP 上显示
- ✅ 两种显示模式：全局陪伴 / 仅桌面（仅桌面通过使用情况访问权限检测 Launcher）
- ✅ 示例手绘猫咪：左右散步、坐下、睡觉、伸展、挥爪、眨眼、摇尾巴；随机切换
- ✅ 点击小猫出现爱心并挥爪；拖拽改变位置；设置大小；通知栏停止按钮
- ✅ Android 相册导入 JPG / PNG，缩小后仅储存于应用私有目录
- ✅ 导入的照片作为悬浮图片产生轻微摆动，保留透明 PNG 的透明区域
- ✅ Android 前台服务通知；用户可明确开启或关闭；无广告、无网络上传
- ⏳ **未实现**：AI 自动抠图、AI 多动作生成、将 AI 生成的帧导入播放器；仅保留 `AiAnimationGateway` 扩展接口

## 构建项目

需要 Android Studio、JDK 17+、Android SDK 35，以及可用的 Gradle 8.13。项目使用 AGP 8.13.0 + Kotlin 2.3.0。

1. 解压 ZIP，Android Studio → **Open** → 选择 `CatCompanionV01` 文件夹。
2. 等待 IDE 同步并安装缺少的 Android SDK / Gradle 依赖。
3. 选择连接的安卓手机或模拟器，点击 **Run ▶**。
4. 或在 Android Studio 的 Build → Build APK(s) 导出 APK，默认在 `app/build/outputs/apk/debug/app-debug.apk`。

### Gradle 包装器提示

此 ZIP 包含 `gradle/wrapper/gradle-wrapper.properties`，但环境未提供可分发的官方 `gradle-wrapper.jar` 和启动脚本。**因此需先生成 Gradle Wrapper，才能使用标准 `./gradlew` 命令**。如果 Android Studio 无法直接同步，请先在安装了 Gradle 8.13 的电脑上，在项目根目录运行：

```bash
gradle wrapper --gradle-version 8.13 --distribution-type bin
```

生成后将 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 保留在项目中，重新打开项目。`gradle wrapper` 首次运行也可能需要先让 IDE 通过内置 Gradle 同步工程。

## 手机上操作

1. 打开 APP，选择 **全局陪伴** 或 **仅桌面**。
2. 允许“显示在其他应用上层”；如选择仅桌面，再允许“使用情况访问”。
3. 点击 **开启猫咪悬浮宠物**，返回主屏幕。
4. 点击小猫触发爱心，长按拖动小猫；再次进入 APP 可修改大小、切换显示模式、关闭。
5. 相册导入透明背景 PNG 效果更好。普通 JPEG 的背景不会自动被移除。

## 兼容性与局限

- 部分安卓厂商限制常驻服务、悬浮窗口或使用情况事件；锁屏、系统设置、权限提示等界面可能不显示悬浮猫咪。无法保证所有机型显示一致。
- 仅桌面模式基于 `UsageStatsManager` 最近前台 Activity 的事件，实际判断会有约 1 秒延迟；有的第三方桌面启动器可能存在判断不准的情况。
- 无需替换原壁纸，不调用无障碍服务；但全局模式悬浮的触控区域会覆盖底下对应位置，建议猫咪缩小或移开。
- 通知权限在 Android 13+ 可选；如果拒绝通知，系统仍可能展示前台服务运行管理提示。
- 从主界面启动前台服务，避免 Android 15 对**从后台启动**前台服务的更严格限制。
- `FOREGROUND_SERVICE_SPECIAL_USE` 用于持续用户启用的悬浮宠物，若日后上架 Play Store，需要另外审查最新前台服务政策及申报要求。
- 保留应用运行状态只是 UI 使用，未实现开机自启（设计上避免未经用户许可自动启动）。

## 主要源码

```
app/src/main/java/com/example/catcompanion/
  MainActivity.kt             设置界面、照片上传、权限引导
  FloatingPetService.kt       悬浮窗、前台服务、触摸、动画调度
  PetView.kt                  画猫与动作 / 图片模式
  ForegroundAppDetector.kt    仅桌面模式识别
  PermissionChecks.kt         权限检测
  PetSettings.kt              设置存储
  AiAnimationGateway.kt       AI 动画扩展接口（尚未对接）
```

## 后续 AI 接入建议

最小可行链路：拍照/选图 → 图像实例分割 → 生成透明 PNG → 固定 5–8 种动作的透明 PNG 帧（每个动作 8–16 帧）→ 缓存帧包 → 本地动画引擎切换。API 密钥应置于服务端，不应打包在 APK 中。后台处理须经用户明确同意并展示费用/隐私告知。当前项目**不含 AI 模型与 API 实现**。

## 测试清单

- [ ] Android 12 / 13 / 14 / 15 / 16 各测试一次悬浮窗授权及启动
- [ ] 全局模式：主屏幕、浏览器、微信等第三方 APP 显示与拖动
- [ ] 仅桌面：按 Home 显示，切换浏览器隐藏，返回主屏幕重新显示
- [ ] 点击挥爪/爱心、自动走路、拖拽边界、横竖屏切换
- [ ] 透明 PNG 与普通 JPEG 上传，确认无网络连接
- [ ] 通知栏关闭、应用内关闭、屏幕熄灭后恢复

**验证状态：源代码结构、权限声明和模块之间的调用已经做静态检查；当前执行环境没有 Android SDK 和 Gradle 工具，未能实际编译或在真机运行。**
