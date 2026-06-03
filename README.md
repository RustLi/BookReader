# BookReader 书阅

一款简洁的 Android 本地电子书阅读器:支持导入 **EPUB / MOBI** 本地图书、分章阅读、目录跳转、阅读进度记忆,以及**语音自动朗读**。

## 功能特性

- **本地导入**:书架右上「+」直接通过系统文件选择器(SAF)导入 `.epub` / `.mobi`,自动解析书名、作者、封面并入库。
- **书架**:封面网格展示,书名筛选,长按删除。
- **阅读**:WebView 渲染章节,点击翻页 / 滑动切章,底部进度条可拖动定位,目录(NCX)一键跳章。
- **进度记忆**:退出 / 切章自动保存章节与章内位置,重进恢复到上次阅读处。
- **语音朗读**:基于系统 TextToSpeech,按句连读、当前句高亮跟随、章末自动翻页。
- **阅读设置**:字号、行距、背景主题(米黄 / 纯白 / 护眼 / 夜间)、屏幕亮度,持久化并即时生效。
- **书签 / 笔记**:任意位置加书签;选中正文文字加笔记;均支持列表查看、点击跳转、长按删除。
- **阅读中首页**:展示"继续阅读"大卡片与"最近读过"列表,一键回到上次进度。

> 解析全部为**自研、零三方电子书依赖**:EPUB 用内置 `XmlPullParser` + `java.util.zip` 解析;MOBI 自实现 PalmDOC(LZ77)解压与 PDB/EXTH 解析。

## 技术栈

- 语言:**Java**;架构:单 Activity + Fragment + ViewModel/LiveData(Jetpack),结构从简。
- `minSdk 24` / `compileSdk 34` / `targetSdk 34`,**Java 17**。
- AGP `8.5.2` + Gradle `8.9`。
- 依赖:AndroidX AppCompat、Material `1.12`、ConstraintLayout、Navigation `2.7.7`、
  Lifecycle `2.8.4`、Room `2.6.1`、Glide `4.16`。

## 工程结构

```
app/src/main/java/com/lwl/bookreader/
├─ MainActivity.java            底部导航宿主(阅读中 / 书架)
├─ data/                        数据层
│  ├─ Book / Bookmark / Note    Room 实体
│  ├─ *Dao / AppDatabase        DAO 与数据库(v2,含 v1→v2 迁移)
│  ├─ BookRepository            书籍仓库
│  ├─ BookImporter             导入:复制文件 → 解析 → 入库
│  ├─ epub/                     EPUB 解析(EpubParser / EpubExtractor / EpubBook / EpubMeta)
│  └─ mobi/                     MOBI 解析(MobiParser,PalmDOC 解压)
├─ ui/
│  ├─ shelf/                    书架(Fragment / ViewModel / Adapter)
│  ├─ reading/                  阅读中首页
│  └─ reader/                   阅读页(ReaderActivity / TtsManager / ReaderPrefs / 适配器…)
└─ util/                        SingleLiveEvent / TimeFormat
```

## 构建与运行

### 前置条件

- Android SDK(已安装 `android-34`、`build-tools 34`)。
- **JDK 17**(项目要求,见下方说明)。
- `local.properties` 指定 SDK 路径(首次需自行创建):

  ```properties
  sdk.dir=/path/to/Android/sdk
  ```

### 命令行构建

构建必须使用 **JDK 17**(AGP 8.5 不支持更高版本 JDK):

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

安装到设备:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 用 Android Studio 打开则使用其内置 JBR 17,无需额外设置。

### 依赖仓库

`settings.gradle` 已配置阿里云镜像 + Google/Maven Central,首次构建会联网拉取依赖。

## 使用说明

1. 打开 App → **书架** → 右上「+」→ 选择 `.epub` / `.mobi`。
2. 点击封面进入阅读;点击屏幕**中间**显隐工具栏,**左 / 右**区域上一章 / 下一章。
3. 顶栏:▶ 朗读、🔖 加书签;底栏:目录 / 笔记 / 书签 / 设置。
4. 朗读需设备具备系统 TTS 引擎(首次可能弹出引擎授权;Android 11+ 已在清单声明 `TTS_SERVICE` 可见性)。

## 说明 / 限制

- MOBI 仅支持 MOBI6 / PalmDOC 压缩;HUFF/CDIC、KF8/AZW3 会提示"暂不支持"。
- 翻页粒度为"章"(spine 单位)+ 章内滚动,未做整页分栏式分页。

## License

见 [LICENSE](LICENSE)。
