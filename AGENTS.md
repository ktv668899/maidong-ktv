# 麦动 KTV — 项目开发约定

> 本文件供后续开发（含 AI 助手会话）遵循，优先级高于默认习惯。

## 一、代码同步策略（2026-09-11 更新）

**代码主仓库已迁移至 Gitea：https://gitea.kasugano.cn/zijunchiang/maidong-ktv**

| 远端 | 地址 | 状态 |
|---|---|---|
| `origin` | `https://gitea.kasugano.cn/zijunchiang/maidong-ktv.git` | ✅ 主仓库，**同步目标** |
| `gitee` | `https://gitee.com/zijunchiang/maidong-ktv.git` | ⛔ 已停用（推送被钩子拦截） |

- ✅ 预期动作：完成改动 → 本地 `git commit` → `git push origin master`（同步到 Gitea）
- ⛔ 禁止：推送到 Gitee（`pre-push` 钩子会拦截，避免误推送）
- 凭据：Gitea 访问令牌 `dsh-sync`（可在 Gitea「设置 → 应用 → 访问令牌」随时吊销），
  存放于 `~/.git-credentials-gitea`（权限 600），由本仓库级 `credential.helper` 引用；
  账号密码不写入仓库配置。

### 推送方式

```bash
cd /home/zijun/dsh/maidongktv/maidong-ktv
git push origin master              # origin = Gitea（主仓库）
git push --no-verify gitee master   # 仅在确需回推 Gitee 时使用
```

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

- `app/build.gradle`：`versionName "1.2.5"` / `versionCode 5`
- App 内显示统一用纯版本号（不加 `(versionCode)` 括号）

## 三、关键实现约束（避免踩坑）

- **歌曲源**：`app/src/main/assets/mobile/ktv_api.js` 的 `CONFIG.HOSTS`（多源自动排序，失败源沉底）
- **JS 热更新**：改动 `ktv_api.js` 必须同步提升 `KTV_BRIDGE_API: N` 标记（JS 与 `KtvJsBridge.kt` 两处校验），否则设备仍用缓存旧脚本
- **曲库**：只走官方 Muse 同步（`MuseDbSync`），不再依赖代码仓库数据库文件
- **曲库同步时效**：默认 **30 天**（`DatabaseBootstrapper.SYNC_TTL_DAYS`）。未到期启动**不联网**、直接用本地曲库；到期后才查询服务器版本，有更新才下载。时效起点记录在 `db_sync_at.txt`，由 `DatabaseBootstrapper.markSynced()` 写入（下载成功或校验为最新时）。设置页「曲库 → 更新」为手动强制检查（绕过时效，仅在服务器版本更新时下载）
- **麦克风**：`MicActivator` 常驻占用输入通道（TV/投影需占用后才有演唱声音输出），外接设备热插拔会自动重建
- **远程日志**：GlogCenter HTTP 接口 `POST http://log.kasugano.cn/glc/v1/log/add`（单条 JSON，字段
  `system`/`date`/`text`/`servername`/`serverip`/`loglevel`/`traceid`/`user`；`date` 必须为
  `yyyy-MM-dd HH:mm:ss.SSS`，否则按时间检索异常）。客户端为 `MiliLog`（有界队列 + 后台线程 + 失败重试），
  调用点 `MiliLog.info/notice/warn/error` 保持不变；设备 IP/标识通过 `MiliLog.updateDeviceContext()` 注入，
  网络变化时自动刷新
- **仓库可见性**：Gitea 仓库保持 **public**（若后续把 App 更新/热更新端点迁到 Gitea，非 public 会导致 403）
