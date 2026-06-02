## 待完成

(无)

## 已完成

任务:工程初始化与底部导航骨架  〔本期核心 · 优先级 P0〕  ✅ 已完成

### 目标
搭起 Android 工程骨架,跑通底部 5 个 Tab 导航。

### 实现内容
* Gradle 工程 + `app` 模块,Java 语言,minSdk 24 / targetSdk 34 / compileSdk 34
  (本机最高仅安装 android-34,故用 34 而非原计划 35)。
* AGP 8.5.2 + Gradle 8.9(本机已缓存);仓库走阿里云镜像 + google/mavenCentral。
* 启用 ViewBinding;引入 Jetpack Navigation(navigation-fragment / navigation-ui 2.7.7)。
* `MainActivity` + `BottomNavigationView` + NavHostFragment,NavigationUI 绑定。
* 底部 5 Tab:阅读中 / 书架 / 云端 / 会员 / 账号,各对应一个占位 Fragment
  (ReadingFragment / ShelfFragment / CloudFragment / VipFragment / AccountFragment),
  共用 fragment_placeholder 布局,起始页为书架。
* 资源:5 个 Tab 矢量图标、选中态颜色选择器、Material3 主题、应用图标、字符串/颜色。

### 验收结果
* `./gradlew :app:assembleDebug` 构建成功,产出 app-debug.apk(约 5.8M)。
* 真机验证(小米 M2102K1AC,Android,1440x3200):安装启动正常,无崩溃。
  逐个点击 5 个 Tab,内容区文字分别正确切换为 阅读中/书架/云端/会员/账号,
  选中态高亮随点击移动;旋转(配置变更)后页面保持稳定。
  验证截图见 .claude/verify/。

### 重要环境说明(供后续任务沿用)
* 本机默认 JDK 为 26,AGP 8.5 不兼容;命令行构建需指定 JDK 17:
  `export JAVA_HOME=/Users/luojuncheng/Library/Java/JavaVirtualMachines/corretto-17.0.11/Contents/Home`
  (Android Studio 用内置 JBR 17,无需额外设置)。
* `local.properties` 指向 SDK:/Users/luojuncheng/Library/Android/sdk。
