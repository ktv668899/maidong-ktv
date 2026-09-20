# 麦动 KTV — 项目开发约定

> 本文件供后续开发（含 AI 助手会话）遵循，优先级高于默认习惯。

## 一、代码同步与发布策略（2026-09-14 更新）

**代码主仓库：Gitea `https://gitea.kasugano.cn/zijunchiang/maidong-ktv`**

| 远端 | 地址 | 用途 |
|---|---|---|
| `origin` | `https://gitea.kasugano.cn/zijunchiang/maidong-ktv.git` | ✅ 代码主仓库，**唯一的代码同步目标** |
| `gitee` | `https://gitee.com/zijunchiang/maidong-ktv.git` | 📦 **不推代码**，只用于发布 Release；仓库内只保留 README 文档 + Releases 附件 |

### 四条硬性规则

1. **推送失败只重试 3 次**：Gitea 不可用（网络不通／服务器故障／返回非 200）时，最多重试 **3 次**
   （间隔约 60s）。3 次仍失败就**停止重试**，改动保留为本地 commit，**留到下次一起同步**
   （不要无限重试、不要为了"推上去"而改推 Gitee）。恢复同步时直接 `git push origin master`
   把积压的 commit 一并推上即可。
2. **Gitee 不再同步代码，只发布版本**：Gitee 仓库保持「仅 README 文档 + Releases 附件」状态，
   **禁止推送源码**（`pre-push` 钩子会拦截 `gitee.com`）。Gitee 上只做一件事：创建 Release 并上传 APK 附件。
3. **每次更新版本号 +1**：完成一次修改就把版本号递增（`versionName` 与 `versionCode` 同步递增），
   例如 `1.3.0` → `1.3.1` → `1.3.2`。不要在同一个版本号下反复改动交付。
4. **发布是手动操作**：**未收到明确的「发布新版本」指令时，默认只在本地打包测试**——
   产物按内部测试约定命名 `maidong-ktv-<版本>-<改动说明>-双ABI-release.apk`，
   **不创建 Release、不上传附件、不推送 tag、不在 Gitee 建 Release**。
   只有收到发布指令后，才按「发布新版本」小节在 Gitea（以及需要的 Gitee）建 Release。
   > ⚠️ **不要混淆"交付"与"发布"**：向 Gitea `git push origin master` 同步代码（规则 1）、
   > 本地构建/签名出 `*-release.apk`（规则 4 前半段）**都不算发布**，不需要、也不得顺带
   > 建 Release / 打 tag / 传附件。发布动作只有三个：建 Release、打 tag、上传附件；
   > 三者的触发条件只有一个 —— **收到明确的人工发布指令**。若不确定是否已发布，
   > 用 `GET /repos/{owner}/{repo}/releases` 与 `/tags` 核对，不要凭"打过包"推断。

- 凭据：Gitea 访问令牌 `dsh-sync`（可在 Gitea「设置 → 应用 → 访问令牌」随时吊销），
  存放于 `~/.git-credentials-gitea`（权限 600），由本仓库级 `credential.helper` 引用；
  账号密码不写入仓库配置。

### 推送方式

```bash
cd /home/zijun/dsh/maidongktv/maidong-ktv
git push origin master              # origin = Gitea（唯一的代码同步目标）
```

> Gitee 不参与代码同步；确需回推文档时用 `git push --no-verify gitee master`，
> 但按规则 2 正常情况下不应执行。

### 运行时端点（已全部迁移到 Gitea）

App 内部两处端点已指向 Gitea（随本版 APK 生效）：

- `AppUpdateManager.kt`：`gitea.kasugano.cn/api/v1/repos/zijunchiang/maidong-ktv/releases/latest`（App 内检查更新）
- `KtvJsBridge.kt`：`gitea.kasugano.cn/zijunchiang/maidong-ktv/raw/branch/master/app/src/main/assets/mobile/ktv_api.js`（JS 热更新）

> 注意：Gitea 的 raw 规范路径是 `/raw/branch/<分支>/<文件>`；`/raw/<分支>/<文件>` 会 303 跳转。
> Gitea 的 raw 与 release 下载对**无 User-Agent** 的请求同样放行（App 裸 socket 可直连）。

### 发布新版本（Gitea Release）

```bash
TOKEN=$(sed -E 's|https://zijunchiang:([^@]+)@.*|\1|' ~/.git-credentials-gitea)

# 1) 创建 Release（tag 需高于当前 versionName 才会提示更新）
curl -X POST -u "zijunchiang:$TOKEN" -H "Content-Type: application/json" \
  -d '{"tag_name":"v1.2.6","name":"麦动KTV V1.2.6","body":"更新说明","target_commitish":"master"}' \
  https://gitea.kasugano.cn/api/v1/repos/zijunchiang/maidong-ktv/releases

# 2) 上传 APK 附件（用返回的 release id；附件名必须 ASCII）
curl -X POST -u "zijunchiang:$TOKEN" \
  -F "attachment=@麦动KTV-V1.2.6-release.apk" \
  "https://gitea.kasugano.cn/api/v1/repos/zijunchiang/maidong-ktv/releases/<id>/assets?name=maidong-ktv-V1.2.6-release.apk"
```

> ⚠️ **附件文件名必须为 ASCII**：Gitea 1.27 的规范下载路径
> `/releases/download/<tag>/<文件名>` 对非 ASCII 文件名返回 404（`/attachments/<uuid>` 直链虽可用，
> 但 `releases/latest` API 返回的是规范路径）。因此 Gitea 上的附件用
> `maidong-ktv-V1.2.x-release.apk` 这类 ASCII 名；本地留存的发布包仍可命名
> `麦动KTV-V1.2.x-release.apk`。
>
> 上传 22MB APK 约需 70s，注意命令超时设置。

## 二、构建与产物

### 构建（release，双 ABI）

```bash
cd /home/zijun/dsh/maidongktv/maidong-ktv
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/zijun/Android/Sdk
export ANDROID_SDK_ROOT=/home/zijun/Android/Sdk
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（已由 `app/build.gradle` 的 signingConfig 签名）

### 签名与校验（单独签名/改名时）

```bash
BT=/home/zijun/Android/Sdk/build-tools/36.0.0
$BT/apksigner sign --ks /home/zijun/dsh/maidongktv/ktv_keystore.jks \
  --ks-key-alias ktv --ks-pass pass:123456 --key-pass pass:123456 \
  --out 目标名.apk app/build/outputs/apk/release/app-release.apk
$BT/apksigner verify 目标名.apk
```

- 密钥：`/home/zijun/dsh/maidongktv/ktv_keystore.jks`（仓库外，勿入库）
- APK 命名约定：`麦动KTV-V<版本号>-release.apk`（对外发布）/ `maidong-ktv-<版本>-<改动说明>-双ABI-release.apk`（内部测试）

### 版本号

- `app/build.gradle`：`versionName "1.3.5"` / `versionCode 11`
- App 内显示统一用纯版本号（不加 `(versionCode)` 括号）

## 三、关键实现约束（避免踩坑）

- **歌曲源**：`app/src/main/assets/mobile/ktv_api.js` 的 `CONFIG.HOSTS`（多源自动排序，失败源沉底）
- **JS 热更新**：改动 `ktv_api.js` 必须同步提升 `KTV_BRIDGE_API: N` 标记（JS 与 `KtvJsBridge.kt` 两处校验），否则设备仍用缓存旧脚本
- **曲库**：只走官方 Muse 同步（`MuseDbSync`），不再依赖代码仓库数据库文件
- **曲库同步时效**：默认 **30 天**（`DatabaseBootstrapper.SYNC_TTL_DAYS`）。未到期启动**不联网**、直接用本地曲库；到期后才查询服务器版本，有更新才下载。时效起点记录在 `db_sync_at.txt`，由 `DatabaseBootstrapper.markSynced()` 写入（下载成功或校验为最新时）。设置页「曲库 → 更新」为手动强制检查（绕过时效，仅在服务器版本更新时下载）
- **麦克风**：`MicActivator` 常驻占用输入通道（TV/投影需占用后才有演唱声音输出），外接设备热插拔会自动重建
- **设备 MAC**：统一由 `DeviceMac` 解析**当前在用网卡**的硬件地址（12 位小写十六进制）。优先
  `wlan*`/`eth*`/`en*`，忽略虚拟/蜂窝/VPN 接口；Android 6 起 `WifiInfo.getMacAddress()` 恒为
  `02:00:00:00:00:00`，因此直接读 `NetworkInterface.getHardwareAddress()`；读出 `02:00:00:00:00:00`
  或全 0 视为无效。读取失败回退 `DeviceMac.FALLBACK_MAC`（历史抓包值），保证身份永不为空。
  结果按进程缓存，网络切换时由 `remoteNetworkMonitor` 调用 `DeviceMac.refresh()` 重解析。
  消费方：`KtvJsBridge.DEVICE_MAC`（JS 端 `mac`）、whoami 心跳的 `_mac`/`androidid`/`_cid`
  （抓包时三者同值，保持一致）
- **播放信息实时刷新**：`refreshNowPlayingLabels()` 是播放界面左上角「当前播放 / 下一首」、首页播放条、
  旧版播放区与顶部状态栏的**唯一**刷新入口，只在文本变化时写 View；已挂到 `lyricTicker`（500ms）上，
  因此队列变化（本地点歌 / 扫码点歌 / 自动切歌）最迟 500ms 内必然同步。新增播放信息展示时请复用它，
  不要另起刷新路径
- **远程日志**：接入 GlogCenter 集中式日志（HTTP 接口，单条 JSON）。**域名属敏感信息，不得写入任何文档**
  （端点常量见代码 `MiliLog.ENDPOINT`，需要时查代码，勿在 README/AGENTS 中记录域名）。字段：
  `system`/`date`/`text`/`servername`/`serverip`/`clientip`/`loglevel`/`traceid`/`user`；`date` 必须为
  `yyyy-MM-dd HH:mm:ss.SSS`，否则按时间检索异常。其中 `serverip` = **主机 IP**（公网出口 IP，
  由 `MiliLog.PUBLIC_IP_URL` 查询，结果缓存 5 分钟、失败 1 分钟后重试，查询在独立后台线程，
  绝不在主线程发起），`clientip` = **客户端 IP**（设备当前网卡获取到的局域网 IP，由
  `MiliLog.updateDeviceContext(ip = ...)` 注入，网络变化时自动刷新并顺带复核公网 IP）。
  客户端为 `MiliLog`（有界队列 + 后台线程 + 失败重试），调用点 `MiliLog.info/notice/warn/error`
  保持不变。启动上报（`reportDeviceBootInfo()`）只采集这 10 项：包名、版本号、安装时间、品牌、型号、
  设备名、Android 版本、CPU 架构、内核版本、屏幕分辨率
- **焦点填充与「纯色遮罩层」**：`TvFocusStyler.install()` 会把背景整体换成**不透明**红→橙渐变，
  作为遥控器焦点/按下的反馈（`prepareTvFocusableTree(window.decorView)` 与对话框的
  `installTree(decorView)` 会遍历整棵树施加）。按钮/Tab/卡片/list 行需要这个反馈；但
  **大面积展示型表面绝不能加** —— 否则触摸一次（`state_pressed`）就用纯色盖住整屏内容。
  已排除的表面：播放画面宿主（`home_player_container` / `player_container`）、视频层
  （`persistentVideo`）、播放触摸层（`playerTouchOverlay`）、全屏播放容器（`R.id.container`）、
  启动加载遮罩（`databaseLoadingOverlay`）、手机点歌二维码浮层、全屏音量面板。
  **新增此类铺满/覆盖播放画面的 View 时，必须调用 `TvFocusStyler.suppressFill(view)`**
- **音量默认值**：`KtvStore.DEFAULT_MUSIC_VOLUME`（当前 85%）是唯一来源，
  `KtvStore` 默认值与 `optInt` 兜底、`MainActivity.musicVolume` 初值、调音弹窗「默认」按钮
  都引用它；远程页面 `assets/mobile/{index.html,app.js}` 的默认值需手工跟改
- **音量控制（只控 App 输出，不动系统音量）**：`musicVolume`(0-100) 是 **App 内部输出音量**，
  统一在 `applyPlaybackMode()` 内按线性系数施加到播放器（`KtvVideoView.setPlaybackVolume` /
  `selectAudioChannel`）与副音轨播放器（`vocalPlayer` / `embeddedVocalEngine`）。
  **严禁调用 `AudioManager.setStreamVolume` / `adjustStreamVolume` 修改系统音量**。
  三条入口必须全部收敛到 `applyPlaybackMode()`：**「调音」弹窗**（`showTuningDialog()`，播放界面与
  主界面右上角共用同一个弹窗）的音量横向控制杆、声音设置-歌曲最大音量、远程控制-音乐音量
  （`remoteAudio()` 的 `musicVolume`）。曲线是纯衰减（100% 即 unity gain），不做系统级增益；
  设备最终响度仍取决于系统音量，App 不再替用户改动它
- **音频均衡器**：内核 `KtvEqualizer`（参考开源项目 OpenEQ，`Turbofan3360/OpenEQ`，GPL-3.0 的
  `EQUtils.kt`）——直接用系统 `android.media.audiofx.Equalizer`，**按设备真实频段数与增益范围自适应**
  （频段是动态生成的，不要写死 5 段）。默认把 EQ 挂到**实际播放会话**：
  `player.audioSessionId()` / `vocalPlayer.audioSessionId` / `embeddedVocalEngine.audioSessionId()`
  （IJK 的原生 `IjkMediaPlayer_getAudioSessionId` 返回真实 AudioTrack 会话）。会话会随歌曲变化，
  因此由 500ms 的 `lyricTicker` → `syncEqualizerSessions()` 周期对齐，消失的会话延迟 3s 释放。
  `globalMix`（会话 0）是设备不支持按会话挂载时的显式开关，会影响本机所有声音，默认关闭。
  均衡器已并入**「调音」弹窗**（`showTuningDialog()`，自上而下：音量横向杆 → 均衡器开关 →
  预设 → EQ 竖向杆），主界面右上角与播放界面「调音」是同一个弹窗；另有声音设置入口与
  远程点歌页面「均衡器」卡片（`PATCH /api/v1/player/equalizer`，状态随
  `GET /api/v1/state` 的 `equalizer` 字段下发）。预设：App 内为「自定义/流行/摇滚/舞曲」
  （`appPresetNames()`，手动改频段即转「自定义」并自动保存），远程页面额外保留「标准」
  供「恢复标准」使用。设置持久化在 `KtvStore.eq*`；
  **刻度固定 30 档**（`eqSliderSteps`），步进由设备增益范围均分，保证 0 dB 一定可选中。
  EQ 竖向杆是自绘控件 `VerticalEqBar`（SeekBar 只支持横向），已 `suppressFill` 并自绘焦点框；
  它在上下键到达量程端点时会放行按键，否则遥控器会困在滑杆上出不去
- **公放随机清单**：`RandomPlayQueue`（纯内存单例）——随机续播不再「每次现随机挑一首」，
  而是一次性生成打乱顺序的临时清单，一轮内不重复；播完一首 `markFinished` +1；
  `next()` 每次都重新比对曲库，**自动吸纳新增歌曲**、剔除已删除歌曲；一轮播完自动开新一轮。
  **不显示**（界面不展示）、**不关联已点清单**（`orderQueue` 完全独立，随机歌曲不入队）、
  **App 退出即清理**（`onDestroy` 里 `isFinishing` 时 `clear()`，不落盘）
- **仓库可见性**：Gitea 仓库保持 **public**（若后续把 App 更新/热更新端点迁到 Gitea，非 public 会导致 403）
