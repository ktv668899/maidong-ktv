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

### 待迁移（尚未完成，需单独确认后再改）

App 内部仍指向 **Gitee** 的两个运行时端点，迁移到 Gitea 需改代码并重新发版：

- `AppUpdateManager.kt`：`gitee.com/api/v5/repos/.../releases/latest`（App 内检查更新）
- `KtvJsBridge.kt`：`gitee.com/zijunchiang/maidong-ktv/raw/master/.../ktv_api.js`（JS 热更新）

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
- **曲库**：只走官方 Muse 同步（`MuseDbSync`），不再依赖 Gitee 数据库文件
- **麦克风**：`MicActivator` 常驻占用输入通道（TV/投影需占用后才有演唱声音输出），外接设备热插拔会自动重建
- **仓库可见性**：Gitea 仓库保持 **public**（若后续把 App 更新/热更新端点迁到 Gitea，非 public 会导致 403）
