<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ktv_logo.png" width="140" alt="麦动 KTV Logo">
</p>

<h1 align="center">麦动 KTV</h1>

<p align="center">面向 Android TV 与横屏点歌设备的本地 KTV 应用</p>

<p align="center">遥控器操作 · 歌曲检索 · 点播队列 · 下载管理 · 稳定播放</p>

## 📌 项目说明

> **内容框架来源**：[https://gitee.com/yangyachao-X/maidong-ktv](https://gitee.com/yangyachao-X/maidong-ktv)
>
> 本仓库基于上游「麦动 KTV」的界面与功能框架进行改造，并使用 **DSH（DeepSeek Harness）** 重写内核与数据源：重新实现曲库同步、歌曲源接入、设备认证、风控应对、远程日志与广告屏蔽等底层逻辑。上游框架的版权归原作者所有，本项目仅作学习与技术交流。

## ⚠️ 严正声明

本软件是**完全免费且公开源码**的个人学习与技术交流项目。

- **【禁止任何形式的营利性使用】**：本软件（包括源码和编译后的 APK）严禁任何商家或个人进行打包售卖、硬件预装、收费技术支持或商业运营。
- **【侵权必究】**：如果您是付费购买的本软件，说明您已被骗，请立即向平台举报商家并退款。
- 完整授权条款请参见项目根目录下的 [LICENSE](./LICENSE) 文件。

## 核心功能

- TV 遥控器全局导航，支持方向键、确认键、返回键和长按操作。
- 统一焦点反馈，按钮、列表、卡片、开关和弹窗均提供清晰的选中状态。
- 首页提供排行榜、歌名、歌星、常唱、收藏、分类和本地歌曲入口。
- 支持全网与本地歌曲检索、语种筛选、分页浏览和首字母键盘输入。
- 歌星页面支持头像标签分类和歌手歌曲浏览。
- 支持歌曲点播、批量点歌、置顶、收藏、取消收藏和文件管理。
- 已点、已唱和下载列表实时同步歌曲状态。
- 已下载歌曲直接加入队列；未下载歌曲完成下载后自动加入，不打断当前播放。
- 已点列表存在歌曲时自动开始播放，并在切歌后同步队列与播放状态。
- 手机扫码点歌（局域网 HTTP 服务 + 二维码）。

## 播放功能

- 使用 IJK 播放器处理本地与下载歌曲（双 ABI：arm64-v8a / armeabi-v7a）。
- 支持小窗播放与全屏播放，切换页面和执行点歌操作时保持视频连续播放。
- 支持播放、暂停、切歌、重唱、进度显示和拖动。
- 支持原唱与伴唱切换，并保持当前播放或暂停状态。
- 支持歌曲片头信息、播放提示和暂停常驻提示。
- 支持视频比例、自动全屏和音画同步设置。

## 内核与数据源（DSH 重写部分）

- **曲库**：统一走**官方 Muse 同步**（`mconn.cherryonline.cn` `mls-api/v1/sqlite/sync`，RSA 登录 + 增量文件校验），不再依赖 Gitee 承载数据库；Gitee 仓库仅存代码。
- **歌曲源**：接入 `w.w345.my`（`i.php` 取 token → `do.php` 解析播放地址），固定设备标识并复刻签名算法；广告/错号资源（如返回 `wb66.cn/.../719.ts` 或文件名与 musicno 不一致）会被拦截并重试。
- **风控应对**：源对陌生出口 IP 存在试用窗口（约 10 分钟，403"检测到异常行为"），App 检测到 403 后弹窗提示更换公网 IP；同 token 退避重试，不放大请求量。
- **远程日志**：可选的 syslog 客户端（RFC 3164，TCP+TLS，`zb.kasugano.cn:514`），启动上报设备/网络信息，点歌实时记录「编号 / 标题 / 请求源 / 状态」。
- **JS 热更新**：歌曲接口逻辑在 `app/src/main/assets/mobile/ktv_api.js`，安装后从本仓库自动拉取兼容版本（须保留 `KTV_BRIDGE_API: 7` 标记）。
- **应用更新**：以 Gitee Release 标签作为版本号（如 `1.2.5`），Release 需附带一个 `.apk` 附件；仅当标签高于当前 `BuildConfig.VERSION_NAME` 时提示下载。

## 🔧 编译教程

### 1. 环境要求

| 项目 | 要求 |
|---|---|
| JDK | OpenJDK **17** |
| Android SDK | `compileSdk 35`、`minSdk 21`、`targetSdk 25` |
| Gradle | 使用仓库自带 wrapper（**8.13**），无需单独安装 |
| 构建系统 | Linux / macOS / Windows（命令行） |

### 2. 准备 Android SDK

安装 `platforms;android-35`、`build-tools`（≥ 34）与平台工具，并接受许可：

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
yes | sdkmanager --licenses > /dev/null
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### 3. 配置签名密钥

`app/build.gradle` 的 release 签名默认读取仓库外相对路径的密钥：

```groovy
storeFile file("../../ktv_keystore.jks")
storePassword "123456"
keyAlias "ktv"
keyPassword "123456"
```

即密钥位于仓库根目录的上一级（`<项目根>/../ktv_keystore.jks`）。若没有该密钥，可用 keytool 生成并放到对应位置（或直接改 `app/build.gradle` 中的路径与口令）：

```bash
keytool -genkeypair -v \
  -keystore ../ktv_keystore.jks \
  -alias ktv -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 123456 -keypass 123456 \
  -dname "CN=Maidong KTV, OU=Dev, O=Maidong, L=Beijing, ST=Beijing, C=CN"
```

> 若只想快速跑通、不关心签名，可把 `app/build.gradle` 的 `signingConfigs.release` 临时替换为 `signingConfigs.debug`。

### 4. 执行编译

```bash
# 仓库根目录
cd maidong-ktv

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # 按本机实际 JDK 路径调整
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew assembleRelease
```

首次运行会自动下载 Gradle 8.13 与依赖，耗时取决于网络。构建成功后在：

```
app/build/outputs/apk/release/app-release.apk
```

产物为已签名的 release APK，包含 `armeabi-v7a` 与 `arm64-v8a` 双 ABI。

### 5. 安装到设备

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

首次启动会自动初始化曲库（官方 Muse 同步，需联网）；播放歌曲时由 `ktv_api.js` 实时解析 `w.w345.my` 播放地址并下载到本地。

### 6. 常见问题

- **曲库初始化失败**：检查设备网络能否访问 `mconn.cherryonline.cn`；已在设置页提供「重置数据库」。
- **点歌报 403 / 无播放地址**：多为歌曲源对当前公网 IP 的试用窗口到期，重启光猫/路由器更换公网 IP 后恢复。
- **热更新脚本不生效**：确认远端 `ktv_api.js` 保留 `KTV_BRIDGE_API: 7` 标记，否则内置版本优先。
- **想改签名/ABI**：编辑 `app/build.gradle` 的 `signingConfigs` 与 `ndk.abiFilters` 后重新编译。

## 项目结构

```
maidong-ktv/
├── app/
│   ├── src/main/java/com/local/ktv/   # Kotlin 源码
│   │   ├── MainActivity.kt            # 主界面 / 播放 / 下载编排
│   │   ├── MuseDbSync.kt              # 官方曲库同步
│   │   ├── MuseDatabase.kt            # 曲库访问
│   │   ├── KtvJsBridge.kt             # JS 桥(HTTP/RSA/热更新)
│   │   ├── MiliLog.kt                 # 远程 syslog 日志
│   │   └── SongOkDownloadManager.kt   # 下载管理
│   ├── src/main/assets/mobile/ktv_api.js   # 歌曲源逻辑(热更新)
│   └── src/main/res/                  # 资源 / 证书
├── tools/                             # 辅助脚本
├── build.gradle / settings.gradle
└── gradlew
```

## 免责声明

本项目仅用于个人学习与技术交流。请遵守所在地区法律法规，尊重上游作者版权与歌曲版权；请勿将本软件用于任何商业或侵权用途。
