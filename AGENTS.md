# 麦动 KTV — 项目开发约定

> 本文件供后续开发（含 AI 助手会话）遵循，优先级高于默认习惯。

## 一、代码同步策略（重要，2026-09-11 起生效）

**自动推送 Gitee：已禁用。**

- ✅ 允许：本地 `git add` / `git commit`（保留本地提交历史，便于回滚与对比）
- ❌ 禁止：**自动执行 `git push`**（包括"完成改动后顺手推送"这类默认动作）
- ⏸ 仅在用户**明确指示**（例如"推送到 Gitee"/"同步代码"）时，才执行推送

### 手动启用/推送方式

已安装 `pre-push` 钩子做拦截（防止误推送）。启用推送二选一：

```bash
# 方式 1：创建启用标记（推荐，推送后建议删除标记恢复拦截）
touch .git/ALLOW_GITEE_PUSH
git push origin master
rm -f .git/ALLOW_GITEE_PUSH

# 方式 2：临时绕过钩子
git push --no-verify origin master
```

### 远端信息

- 仓库：https://gitee.com/zijunchiang/maidong-ktv （公开）
- 分支：`master`
- 发布：Gitee Release（tag 形如 `v1.2.5`，需附 `.apk` 附件，供 App 内检查更新）

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
- **发布**：Gitee 仓库必须保持 **public**，否则 App 内检查更新/下载会 403
