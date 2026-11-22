# 智慧屏短剧 · 华为智慧屏大屏版

面向 **华为智慧屏 HarmonyOS 4.3.0.252** 定制的红果短剧 TV 大屏客户端。安装后用遥控器即可刷剧，无需安卓模拟器，也无需 root。

---

## 一、这是什么 / 为什么这样做

你原本想以 `yangbin1322/hongguo-player` 桌面版为基础移植，但该仓库**只发布安装包、不含源代码**，桌面版本质是 WebView2 外壳 + Web 前端 + 本机解密代理，无法直接移植到智慧屏。

真正适合智慧屏的是社区已有的 **Android TV 原生版**：

| 项目 | 说明 | 本定制版是否采用 |
| --- | --- | --- |
| `yangbin1322/hongguo-player` | 桌面版，WebView2 外壳，**无源码** | ✗ 无法移植 |
| `zfdang/hongguo-open-player` | Electron + Vue 桌面版，有源码 | ✗ 仍是桌面端 |
| **`N3urda/hongguoTV`** | **Kotlin 原生 + Media3 的 Android TV 客户端**，1.5k Star，持续更新，直接发 TV APK | ✓ **作为内核** |

本工程 = `N3urda/hongguoTV` 原生 TV 内核 + 针对华为智慧屏的安装兼容改造。播放、搜索、排行榜、收藏、续播、弹幕、遥控器操作、CENC 流解密等能力全部保留。

---

## 二、针对智慧屏做了哪些改造

改动集中在 `app/build.gradle.kts` 与少量入口代码，目的只有一个：**让 APK 能在 HarmonyOS 4.3 智慧屏上装得上、跑得稳**。

| 改造项 | 原始值 | 定制值 | 解决的问题 |
| --- | --- | --- | --- |
| ABI 分包 | `splits{abi}` 生成 arm64/arm32/universal 三个包 | **移除分包，单一 universal APK** | 下错架构包导致"解析失败/应用未安装" |
| targetSdk | 36 | **30** | 兼容层对新系统行为支持不全导致安装/运行异常 |
| 应用签名 | v2/v3 | **v1 + v2 同时开启** | 老安装器不认 v3，判为无证书而解析失败 |
| applicationId | `com.hongguotv.nativeapp` | `com.hongguotv.huawei.screen` | 与已装官方版签名冲突导致"应用未安装" |
| 应用名称 | hongguoTV | **智慧屏短剧** | 大屏品牌化 |
| 应用内自动更新 | 拉官方签名包 | **指向本仓库 GitHub Release**（v0.17.0 起） | 换签名后拉官方包必然失败；改为只认本仓库同签名包 |

> 未改动：播放内核、解密算法、遥控器按键、TV banner/图标、界面布局。原项目已有 `LEANBACK_LAUNCHER` 启动器与 `android.software.leanback` 声明，天然适配大屏。

---

## 二·二、v0.16.14 首页播放优化（版本迭代）

在智慧屏实测稳定后，参照桌面版红果播放器的"沉浸式首页"模式，为随心看首页补齐了**播放控制**能力。核心刷剧手感（左右换剧、确定暂停/继续、自动连播、进度记忆）保持不变，新增能力通过**菜单键**呼出，做到不打扰原有操作。

| 新增能力 | 遥控器操作 | 说明 |
| --- | --- | --- |
| 进度条 + 时间 | 菜单键呼出控制条 | 显示 `当前 / 总时长`（如 `00:05 / 01:54`）与进度条，播放中实时刷新 |
| 倍速切换 | 控制条内选中"倍速"按确定 | 在 0.75× / 1× / 1.25× / 1.5× / 1.75× / 2× / 3× 间循环，即时生效并写入偏好 |
| 清晰度切换 | 控制条内选中"清晰度"按确定 | 在 480P / 720P / 1080P 间循环，保留当前集与播放位置重新取流，写入偏好 |
| 直接选集 | 控制条内选中"选集"按确定 | 进入当前剧集详情页并把焦点落到选集区，无需先"播放全集"再找选集 |

**遥控器路径**：首页画面按 **菜单键** → 控制条出现、焦点落在"倍速" → 左右键在「倍速/清晰度/选集」间移动 → 确定键执行 → 再按菜单键或返回键回到画面。

**实现要点**（改动均为增量，向后兼容）：
- `FeedPlayback.kt`：`TrialPlayback` 新增带默认实现的 `seekTo()` / `setSpeed()`，既有测试替身不受影响；`Media3TrialPlayback` 实现即时倍速。
- `HomeFeedScreen.kt`：新增自包含控制条（时间、进度、3 个按钮），菜单键切换显隐；清晰度切换保留播放位置重载当前集。
- `MainActivity.kt`：偏好读写接到 `library`（与设置页/全屏播放器共享），选集走 `openDetail(focusEpisodes=true)`。
- 版本号：`0.16.13-huawei-screen` → **`0.16.14-huawei-screen`**（versionCode 34 → 35）。

---

## 二·三、v0.17.0 大版本迭代

| 改动 | 说明 |
| --- | --- |
| **首页即随心看** | 打开应用直接进入沉浸式推荐刷剧流（推荐/分类切换、左右换剧、自动连播、进度记忆），与电脑端红果一致；原货架式首页不再作为默认入口 |
| **观看历史升级为一级入口** | 左侧导航直接可见"观看历史"；沉浸式首页每 5 秒记录一次进度，切剧/暂停/退出时强制保存，看过的剧按更新时间倒序出现在历史中，支持继续观看与本机记录管理 |
| **弹幕贴近屏幕顶端** | 顶部留白由 65dp 缩至 8dp，弹幕从画面最上方区域开始滚动；右上角播放时钟保留在弹幕区下方 |
| **检查更新（自有源）** | 设置 › 关于与更新 › 检查更新，读取本仓库 Release 的 `update.json`；下载后沿用 SHA256、包名、版本、v1+v2 签名四重校验，防止被替换成官方签名包 |
| **鸿蒙 6 扫码登录兜底** | 二维码请求对齐桌面版指纹参数；若鸿蒙 6 红果客户端提示"暂不支持该二维码"，弹窗直接提供"手机号登录"入口（手机号+验证码流程完整可用） |

版本号：`0.16.14-huawei-screen` → **`0.17.0-huawei-screen`**（versionCode 35 → 36）。

> 更新发布约定：Release 需上传 `update.json`（`schema=1`、`packageName=com.hongguotv.huawei.screen`、tag 形如 `v0.17.1-huawei-screen`）与同名 ASCII 文件名 APK，且 APK 必须为本仓库同一签名证书签发。

---

## 三、编译（生成可安装的 APK）

### 3.1 环境要求

- **JDK 17**（必须，Gradle 8.14.3 + AGP 8.11 要求）
- **Android SDK**：`platforms;android-36` + `build-tools;36.0.0`
- 可访问 Maven/Google 仓库（首次编译需下载 Gradle 与依赖；国内建议配置代理）

> 快速装环境：安装 Android Studio，它会自动装 JDK 17 和 SDK；再用 SDK Manager 勾选 Android 16 (API 36) 平台与对应 Build Tools。

### 3.2 一键构建

直接用 Windows 命令行（cmd）运行：

```
build.bat
```

脚本会自动完成：检查环境 → 生成签名证书（仅一次）→ 写签名配置 → 编译 → 把 APK 输出到 `outputs/智慧屏短剧-huawei-screen.apk`。

### 3.3 手动构建

```bat
rem 1) 生成签名证书（仅一次）
keytool -genkeypair -v -keystore signing\huawei-screen.jks -alias huawei-screen -keyalg RSA -keysize 2048 -validity 10000 -storepass changeit -keypass changeit -dname "CN=Huawei Screen DIY, O=Personal, C=CN"

rem 2) 写 local.properties（指向你的 SDK，注意反斜杠转义）
echo sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk > local.properties

rem 3) 编译
gradlew.bat :app:assembleRelease --no-daemon
```

产物：`app/build/platform/outputs/apk/release/app-release.apk`

### 3.4 关于签名（重要）

- 签名证书 `signing/huawei-screen.jks` 请**妥善备份**。以后覆盖安装新版必须用同一证书，否则会报"应用未安装（签名不一致）"。
- `.gitignore` 已排除所有签名材料，**不要**把 jks 和 keystore.properties 传到公开仓库。

---

## 四、安装到华为智慧屏

见同目录 [安装说明.md](安装说明.md)。三种官方推荐方式：**U 盘安装**、**华为分享**、**智慧生活 App**。

---

## 五、遥控器操作

- 方向键移动焦点，确定键打开/播放
- 播放中：确定键 暂停/继续并呼出菜单；菜单键 呼出菜单
- 上下键 切上/下一集，左右键 快退/快进
- 返回键逐层返回

---

## 六、免责声明

本工程是基于 `N3urda/hongguoTV`（GPL-3.0）的个人定制版，仅供学习研究与个人使用，与红果短剧官方无任何隶属或授权关系。所有音视频内容来自公开网络服务，本工程不提供、不存储、不分发任何媒体文件。第三方接口可能随时变更导致播放失败。使用者应遵守当地法律法规与平台服务条款，支持正版内容。

上游许可证：[LICENSE](LICENSE) · [NOTICE.md](NOTICE.md) · [上游说明](README.upstream.md)
