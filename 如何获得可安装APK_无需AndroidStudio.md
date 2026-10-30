# 不安装 Android Studio：使用 GitHub Actions 生成可安装的 APK

此项目只包含源代码，**没有现成 APK**。如果不想自己安装 Android 开发环境，可以使用 GitHub 免费提供的云端运行器执行编译。

1. 登录 GitHub，创建一个新仓库（不必勾选添加 README）。
2. 在电脑上解压本压缩包，打开 GitHub 仓库，点击 **Add file → Upload files**，把解压后 `CatCompanionV01` 文件夹里面的所有内容（包含 `.github` 文件夹）一起上传到仓库根目录；点击 **Commit changes**。
3. 进入仓库的 **Actions** 标签，打开 **Build installable Android APK** 工作流。如果推送到 main/master 已自动触发，可直接打开该次运行；否则点击 **Run workflow**。
4. 等工作流状态为绿色成功后，进入该次运行页面底部的 **Artifacts**，下载 **CatCompanion-V0.1-Android-APK**，解压后里面就是 **app-debug.apk**。
5. 把 APK 发到你的安卓手机，打开文件并根据系统提示允许从该来源安装，然后在 App 内开启悬浮窗权限。

## 注意

- `app-debug.apk` 是 Android 自动签名的**调试版安装包**，可手动安装和测试，不是应用商店发布版。
- 首次构建的外部依赖会由 GitHub Actions 自动下载，无需自行安装 Java、Gradle 或 Android SDK。
- 本项目最低支持 Android 8.0（API 26）。部分系统需要额外在设置中允许后台活动或悬浮窗。
- 当前仅完成悬浮宠物基础功能及照片导入；**没有**真正的 AI 动画生成功能。
- **还没有通过 GitHub Actions 实际构建或用安卓真机测试**。如果编译出错，打开失败的工作流页面可查看日志，便于继续定位修复。
- GitHub 自身可能调整界面、免费额度和安全限制；私有仓库的 Actions 运行需遵守账户相关配额。

## 对照路径

- 自动打包配置：`.github/workflows/build-apk.yml`
- 构建命令：`gradle --no-daemon --stacktrace :app:assembleDebug`
- 云端生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`
