# 麦动 KTV — 项目开发约定

> 本文件供后续开发（含 AI 助手会话）遵循，优先级高于默认习惯。

## 一、代码同步与发布策略（2026-10-01 更新）

**代码主仓库：Gitea `http://ck.ac19.cn:6699/admin/maidong-ktv`**

| 远端 | 地址 | 用途 |
|---|---|---|
| `origin` | `http://ck.ac19.cn:6699/admin/maidong-ktv.git` | ✅ 代码主仓库；**已配置双推送**，`git push origin master` 同时推 Gitea + Gitee |
| `gitee` | `https://gitee.com/weishilong/maidong-ktv.git` | 🌐 **开源镜像（同步源码）**；自 2026-10-01 起与 Gitea 同步全部源码与 tag |
| `upstream` | `https://gitea.kasugano.cn/zijunchiang/maidong-ktv.git` | 原始仓库（只读参考，不推送） |

### 四条硬性规则

1. **推送失败只重试 3 次**：推送失败时最多重试 **3 次**（间隔约 60s）。3 次仍失败就**停止重试**，
   改动保留为本地 commit，**留到下次一起同步**（不要无限重试）。恢复同步时直接 `git push origin master`
   把积压的 commit 一并推上即可。注意：`origin` 配了两个 pushurl（Gitea 在前、Gitee 在后），
   单侧失败会使整条命令返回非 0，需按输出区分是哪一侧失败（Gitea 失败重试；Gitee 失败检查令牌/网络）。
2. **Gitee 是开源镜像，源码与 Gitea 保持一致**（2026-10-01 起）：Gitee 仓库
   `weishilong/maidong-ktv` 为**公开**镜像，承载完整源码与 tag，作为对外开源入口。
   历史策略「Gitee 只发 Release、禁止推源码」自本日起废止。Release 仍按规则 4 由人工指令触发。
3. **版本号递增规则（2026-09-26 更新）**：`versionName` 形如 `<major>.<minor>.<patch>`，
   **patch 取值 0~10、minor 取值 1~10**，每次修改先用掉一个 patch：
   - 常规递增：`1.5.0` → `1.5.1` → … → `1.5.10`；
   - patch 到 10 后再改 → **进位到 minor、patch 归 0**：`1.4.10` → `1.5.0`；
   - minor 到 10 后再进位 → **major +1、minor 归 1、patch 归 0**：`1.10.1` → `2.1.0`。
   `versionCode` 始终同步 +1（单调递增，供系统与更新检查判断新旧）。
   不要在同一个版本号下反复改动交付。
4. **发布是手动操作**：**未收到明确的「发布新版本」指令时，默认只在本地打包测试**——
   产物按内部测试约定命名 `maidong-ktv-<版本>-<改动说明>-双ABI-release.apk`，
   **不创建 Release、不上传附件、不推送 tag、不在 Gitea/Gitee 建 Release**。
   只有收到发布指令后，才按「发布新版本」小节在 Gitea（以及需要的 Gitee）建 Release。
   > ⚠️ **不要混淆"交付"与"发布"**：向 Gitea/Gitee `git push origin master` 同步代码（规则 1）、
   > 本地构建/签名出 `*-release.apk`（规则 4 前半段）**都不算发布**，不需要、也不得顺带
   > 建 Release / 打 tag / 传附件。发布动作只有三个：建 Release、打 tag、上传附件；
   > 三者的触发条件只有一个 —— **收到明确的人工发布指令**。若不确定是否已发布，
   > 用 `GET /repos/{owner}/{repo}/releases` 与 `/tags` 核对，不要凭"打过包"推断。

- 凭据：
  - **Gitea**（代码主仓库）：访问令牌亦写入仓库级远端 URL，直接推送。
  - **Gitee**（开源镜像）：账号 `weishilong`，私人令牌（`projects` 权限）写在本机远端 URL
    与 Gitee API 调用中，**不得写入仓库内任何被提交的文件**。
  - ⚠️ **令牌只出现在 `.git/config` 的远端 URL 中，不进入任何提交**；
    改动 remote 后请用 `git remote -v` 复核，避免把令牌带进文档或脚本。
  - ⚠️ **Gitee 仓库必须是 public**：Gitee 的规则是"空仓库不能设为公开"，
    首次推送源码后才能 `PATCH /api/v5/repos/{owner}/{repo}` 把 `private` 改 `false`。
  - ⚠️ 本机（Windows）曾因 `http_proxy`/git-credential-manager 导致 git 网络操作挂起，
    仓库级已配置 `credential.helper ""` 与 `http.proxy ""`（直连），**不要删掉这两项**。

### 推送方式

```bash
cd <仓库根>
git push origin master              # 同时推 Gitea + Gitee（origin 配了双 pushurl）
git push origin --tags              # 标签同样双推
```

> `origin` 已用 `git remote set-url --add --push` 配置两个推送目标（顺序：Gitea → Gitee）。
> 单独操作某一个镜像时：`git push gitee master`（只推 Gitee）/ `git push origin master`（两边都推）。
> 新增远端后可用 `git remote -v` 复核推送目标（push 行应有两行）。

### 运行时端点（已全部迁移到 Gitea）

App 内部两处端点已指向 Gitea（随本版 APK 生效）：

- `AppUpdateManager.kt`：`ck.ac19.cn:6699/api/v1/repos/admin/maidong-ktv/releases/latest`（App 内检查更新）
- `KtvJsBridge.kt`：`ck.ac19.cn:6699/admin/maidong-ktv/raw/branch/master/app/src/main/assets/mobile/ktv_api.js`（JS 热更新）

> 注意：Gitea 的 raw 规范路径是 `/raw/branch/<分支>/<文件>`；`/raw/<分支>/<文件>` 会 303 跳转。
> Gitea 的 raw 与 release 下载对**无 User-Agent** 的请求同样放行（App 裸 socket 可直连）。

### 发布新版本（Gitea Release）

```bash
# 令牌直接从远端 URL 提取（本机把凭证写在远端 URL 里，不用凭据文件）
TOKEN=$(git remote get-url origin | sed -nE 's|https?://[^:]+:([^@]+)@ck\.ac19\.cn.*|\1|p')

# 1) 创建 Release（tag 需高于当前 versionName 才会提示更新）
curl -X POST -u "admin:$TOKEN" -H "Content-Type: application/json" \
  -d '{"tag_name":"v1.5.7","name":"麦动KTV V1.5.7","body":"更新说明","target_commitish":"master"}' \
  http://ck.ac19.cn:6699/api/v1/repos/admin/maidong-ktv/releases

# 2) 上传 APK 附件（用返回的 release id；附件名必须 ASCII）
curl -X POST -u "admin:$TOKEN" \
  -F "attachment=@maidong-ktv-release.apk" \
  "http://ck.ac19.cn:6699/api/v1/repos/admin/maidong-ktv/releases/<id>/assets?name=maidong-ktv-release.apk"
```

> ⚠️ **附件文件名必须为 ASCII**：Gitea 1.27 的规范下载路径
> `/releases/download/<tag>/<文件名>` 对非 ASCII 文件名返回 404（`/attachments/<uuid>` 直链虽可用，
> 但 `releases/latest` API 返回的是规范路径）。因此 Gitea 上的附件用
> `maidong-ktv-V1.2.x-release.apk` 这类 ASCII 名；本地留存的发布包仍可命名
> `麦动KTV-V1.2.x-release.apk`。
>
> 上传 22MB APK 约需 70s，注意命令超时设置。

### 发布新版本（Gitee Release）

```bash
GT=$(git remote get-url gitee | sed -nE 's|https://[^:]+:([^@]+)@gitee\.com.*|\1|p')

# 1) 创建 Release（Gitee 会自动创建同名 tag）
curl -X POST -H "Authorization: token $GT" -H "Content-Type: application/json" \
  -d '{"tag_name":"v1.5.7","name":"麦动KTV V1.5.7","body":"更新说明","target_commitish":"master"}' \
  https://gitee.com/api/v5/repos/weishilong/maidong-ktv/releases

# 2) 上传 APK 附件（表单字段名是 file，不是 attachment；用返回的 release id）
curl -X POST -H "Authorization: token $GT" \
  -F "file=@maidong-ktv-release.apk" \
  "https://gitee.com/api/v5/repos/weishilong/maidong-ktv/releases/<id>/attach_files"
```

> Gitee 附件名**可用中文**（ASCII 限制只针对 Gitea 1.27），上传 22MB 约 4s。
> 发布后务必校验下载：`curl -sL <browser_download_url> | md5sum` 应与本地发布包一致
> （Gitea 侧同样要校验 `/releases/download/<tag>/<文件>` 规范路径返回 200）。

## 二、构建与产物

### 构建（release，双 ABI）

```bash
cd /home/zijun/dsh_data/maidongktv/maidong-ktv
. /home/zijun/dsh_data/maidongktv/env.sh    # 导入 JAVA_HOME / ANDROID_HOME / PATH
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（已由 `app/build.gradle` 的 signingConfig 签名）

### 本机工具链（2026-09-26 数据迁移后重建）

数据迁移后的本机是**纯净环境且当前用户无 sudo**，因此工具链全部装在用户态，
不要再用系统路径（`/usr/lib/jvm/...` 不存在）。统一入口：

```bash
. /home/zijun/dsh_data/maidongktv/env.sh    # 已加入 ~/.bashrc，新开 shell 自动生效
```

| 组件 | 路径 | 来源 |
|---|---|---|
| JDK 17 | `/home/zijun/tools/jdk-17`（Temurin 17.0.20.1） | Adoptium tarball 解包 |
| git | `/home/zijun/tools/bin/git`（包装脚本 → `/home/zijun/tools/gitroot/usr/bin/git`） | `apt-get download git` + `dpkg -x` |
| Android SDK | `/home/zijun/Android/Sdk`（与 `local.properties` 的 `sdk.dir` 一致） | `cmdline-tools` + `sdkmanager` |
| 已装 SDK 包 | `platforms;android-35`、`build-tools;34.0.0`、`build-tools;36.0.0`、`platform-tools` | sdkmanager |
| 签名密钥 | `/home/zijun/dsh_data/maidongktv/ktv_keystore.jks`（仓库外，勿入库） | 数据迁移 |

- `git` 包装脚本是必需的：deb 内 git 的编译期 `exec-path` 写死为 `/usr/lib/git-core`
  （无 root 无法创建），包装脚本显式设置 `GIT_EXEC_PATH`/`GIT_TEMPLATE_DIR` 后才找得到
  `git-remote-https` 等子命令。
- 迁移会把仓库文件权限改成 744（git 记为 1288 个纯 mode change、0 行内容变化）。
  遇到大量 `mode change 100644 => 100755` 时，按索引恢复权限即可，不要当成内容改动：
  `git ls-files -s -z | ... chmod`（或临时 `git config core.fileMode false`）。

### 签名与校验（单独签名/改名时）

```bash
BT=/home/zijun/Android/Sdk/build-tools/36.0.0
$BT/apksigner sign --ks /home/zijun/dsh_data/maidongktv/ktv_keystore.jks \
  --ks-key-alias ktv --ks-pass pass:123456 --key-pass pass:123456 \
  --out 目标名.apk app/build/outputs/apk/release/app-release.apk
$BT/apksigner verify 目标名.apk
```

- 密钥：`/home/zijun/dsh_data/maidongktv/ktv_keystore.jks`（仓库外，勿入库）
- APK 命名约定：`麦动KTV-V<版本号>-release.apk`（对外发布）/ `maidong-ktv-<版本>-<改动说明>-双ABI-release.apk`（内部测试）

### 版本号

- `app/build.gradle`：`versionName "1.5.1"` / `versionCode 33`（18~21 被本机临时诊断包占用过）
- App 内显示统一用纯版本号（不加 `(versionCode)` 括号）
- ⚠️ **发布构建已开启 NewApi 闸门**（`lint { checkReleaseBuilds true; fatal += ['NewApi'] }`）：
  任何「调用了高于 `minSdk 21` 且未做版本守卫的 API」都会让 `assembleRelease` 直接失败。
  被阻断时按报错位置补版本判断或改用兼容写法，**不要**把 `checkReleaseBuilds` 改回 `false`
  来绕过 —— 这道闸门专为「低版本设备一打开就闪退」而设（见第三节同名条目）

## 三、关键实现约束（避免踩坑）

- **⚠️ 低版本设备闪退（API 守卫）**：`minSdk 21`，但**实际客户里有 Android 5.1.1 的机器**
  （极米 H1S / MStar 6A928 / GMUI2.0）。以下三类写法会让 app 在 API 22 上**一打开就闪退**，
  必须避免：
  1. **把高版本 API 的类写在 `Activity`/`Fragment`/`object` 的字段初始化器里** ——
     字段初始化在**构造期**执行，早于 `onCreate` 里的 `SDK_INT` 判断，因此守卫形同虚设。
     v1.4.0 的闪退根因就是 `private val micDeviceCallback = object : android.media.AudioDeviceCallback() {...}`
     （`AudioDeviceCallback` 是 API 23）→ 构造 `MainActivity` 时 `NoClassDefFoundError`。
     正确写法：字段声明为可空、在 `SDK_INT >= 23` 分支里**按需创建**（见 `ensureMicDeviceCallback()`）。
  2. **Java 8 的集合默认方法**：`Map.getOrDefault` / `Map.putIfAbsent` / `ConcurrentHashMap.newKeySet`
     / `computeIfAbsent` / `Comparator.comparing` / `List.sort` 等都是 **API 24+**，
     API 21~23 上抛 `NoSuchMethodError`。替代：`getOrPut`、
     `Collections.newSetFromMap(ConcurrentHashMap())`、`ConcurrentHashMap.putIfAbsent`（API 1）、
     Kotlin `compareBy`。
  3. 其它漏网的：`URLConnection.getContentLengthLong()`（API 24，用 `Compat.kt` 的
     `contentLengthCompat()`）、`ByteArrayOutputStream.toString(Charset)`（API 33）。
  - 自查命令（必须为 0 条）：`./gradlew :app:lintRelease` 后
    在 `app/build/reports/lint-results-release.txt` 里搜 `NewApi`。
  - 根因扫描捷径：`grep -n "private val .* = object : android\." app/src/main/java/com/local/ktv/*.kt`
    —— 字段初始化器里出现 `android.*` 的高版本类就是要闪退。
- **⚠️ 存储根目录必须运行时解析（不要再写死 `/storage/emulated/0`）**：
  `AppPaths` 曾把根目录写死成 `/storage/emulated/0/MaidongKTV`，在**极米 H1S（GMUI2.0 / Android 5.1.1）**
  上该路径不可写 → `ensureDirectories()` 的 `check(it.exists() || it.mkdirs())` 抛异常 →
  **App 一打开就闪退**（这台机器历来所有版本都打不开的真正原因，2026-09-26 定位）。
  现在的约定：
  1. `AppPaths.init(context)` 在 `KtvApplication.attachBaseContext` **最前面**调用，按
     「`Environment.getExternalStorageDirectory()` → 历史硬编码路径 → `getExternalFilesDir()`
     → `filesDir`」顺序挑**第一个真正可建且可写**的目录（幂等）；
  2. 所有用到根路径的地方必须走 `AppPaths.*` 的 **getter**，**绝不能用 `const val` 把路径编译期内联**
     （`MuseDatabase.VIDEO_ROOT`、`MuseDatabase.defaultDbFile()` 曾经就是 `const val` 内联的，
     改存储根后必须一起改成运行时取值）；
  3. 目录创建**不许抛异常**：`AppPaths.ensureDirectories()` 返回 `Boolean`，失败只记日志，
     `MainActivity` 拿到 false 也只是告警继续，绝不能让存储问题变成闪退；
  4. 用户可见的目录提示文案也要用 `AppPaths.*Dir.absolutePath` 拼，不要写死 `/sdcard/...`；
  5. 启动时会往远程日志打一行 `STORAGE root=... by=... envState=... probe=[...]`，
     换盒子/换 ROM 时先看这行就能确认存储根落在哪。
  - 自查：`grep -rn "/storage/emulated\|/sdcard/MaidongKTV" app/src/main/java --include='*.kt'`
    除 `AppPaths` 的候选清单外不应再有硬编码。
- **⚠️ 老系统（API < 24）的 TLS 根证书**：`*.cherryonline.cn`（曲库同步 `mconn` / 点歌登录 `mws`）
  （自建 Gitea `http://ck.ac19.cn:6699` 不走 TLS，不涉及此问题。）
  `res/xml/network_security_config.xml` 里的三个内置根证书**只在 Android 7.0(API 24)+ 生效**，
  Android 5.1.1 上被完全忽略、系统信任库又没有 ISRG 新根 →
  `SSLHandshakeException: Trust anchor for certification path not found` →
  曲库登录失败 →**「不能下载曲库」**（这就是极米 H1S 上第二个卡点的根因，2026-09-26 定位）。
  约定：
  1. `TlsCompat.install(context)`（在 `KtvApplication.attachBaseContext`）在 API<24 上把
     「系统默认信任 ∪ 内置根证书」的复合 `X509TrustManager` 装到全局
     `HttpsURLConnection.setDefaultSSLSocketFactory`，一次覆盖 App 内所有 `HttpsURLConnection`
     （曲库同步、JS 热更新下载、更新检查、头像下载）。**主机名校验保持系统默认，绝不放行任意证书**；
  2. WebView 的 TLS 信任**无法**用 SSLContext 覆盖，而 JS（`ktv_api.js`）会直连
     `https://mws.cherryonline.cn`。因此在 `KtvJsBridge` 的 `onReceivedSslError` 里**绝不盲目
     `proceed()`**：先用 `TlsCompat.isHostTrustedByOurStore(host)` 对同一主机独立做一次 TLS 校验，
     通过才放行，否则 `cancel()`；
  3. 以后替换/新增内置根证书时，`TlsCompat.BUNDLED_CERTS` 与 `network_security_config.xml`
     两处要同步改（一个管 API<24、一个管 API>=24）；
  4. 曲库同步每一步失败都会 `MiliLog.warn("MUSE-DB ..." / "DB-SYNC ...")` 上报。
     老设备上「不能下载曲库」先看远程日志这几行 —— 以前只写 logcat，界面只剩
     「未找到可用曲库」兜底文案，排查极其困难。
- **⚠️ 高频回调不能直接刷 UI（曲库下载 = 430MB）**：`MuseDbSync.downloadFile` 每读一个
  256KB 分块回调一次进度，430MB 就是 **约 1700 次**。若每次回调都 `main.post { 改文本 +
  改进度 + bringToFront() }`，主线程 Looper 队列会被压满 —— 实测表现为**界面卡死、进度停滞**，
  而且整机 CPU 被布局任务吃掉后**下载本身也变慢**（极米 H1S 上就是这个现象）。
  约定：
  1. 进度回调必须**按最终显示文案去重**（`MuseDbSync` 侧按百分比去重、
     `showDatabaseDownloadProgress` 侧按文本去重），刷新次数上限 ~100 次；
  2. `showDatabaseLoading()` 的更新路径**只做真正变化的事**：文本不同才 `setText`、
     进度不同才 `progress=`、**不要每次 `bringToFront()`**（整棵树 `requestLayout`）、
     不要每次重设 `setOnClickListener`；
  3. 解压（约 1GB）与 md5 校验（430MB）阶段**也要报进度**（回调带明细文案，
     如「正在解压曲库 512MB」），否则界面会长时间停在某个百分比、看着像卡死；
  4. 下载速率会按每 10% 上报一次 `MUSE-DB progress 30% 129MB/430MB 1.85MB/s`，
     判断"是网络慢还是磁盘慢"直接看这几行；
  5. 新增任何"周期性/高频"回调（进度、心跳、轮询）时都要按同样标准评估主线程开销。
- **⚠️ 裸 Socket 通道（`KtvJsBridge.rawRequest`）的三个坑**：点歌取址走的正是这条通道
  （为了贴近原版风控要求的"干净请求"），它**不受** `HttpsURLConnection` 默认工厂与
  `network_security_config` 的任何影响：
  1. **HTTPS 必须用 `TlsCompat.sslSocketFactory()`**，不能用 `SSLSocketFactory.getDefault()`——
     后者是系统信任库，老设备（及 Android 7.0）没有 ISRG 新根，握手直接失败；
  2. **连接必须显式给超时**：`Socket(host, port)` 的连接阶段**不受** `soTimeout` 约束，
     遇路由黑洞会按系统 TCP 超时（约 2 分钟）干等；多源叠加会耗光 JS 侧
     `JS_REQUEST_TIMEOUT_MS`(180s) 预算，表现为**「无法生成下载地址」且日志里什么都没有**。
     正确写法：`Socket().connect(InetSocketAddress(host, port), connectTimeout)`；
  3. **失败必须上报**：裸通道失败打 `RAW-ERR`、HttpURLConnection 回退失败打 `NET-ERR` 到远程日志。
- **JS 侧日志必须能进远程日志**：`ktv_api.js` 的 `_log()` 走 `console.log`（桥以 `debug:true` 实例化），
  默认只进 WebView 控制台、现场无法取证。`KtvJsBridge.wrapHtml()` 已注入 console 桥把
  `console.log` 转发到 `android.log` → `MiliLog.info("JS ...")`。
  点歌失败时先搜远程日志里的 **`JS `** 与 **`点歌失败`** 两行：前者给
  「Try source / Token OK/FAIL / do.php code=」的逐步过程，后者给 JS 汇总的失败原因。
- **歌曲源**：`app/src/main/assets/mobile/ktv_api.js` 的 `CONFIG.HOSTS`（多源自动排序，失败源沉底）
- **⚠️ 取址有两条实现，改协议必须同时改**：JS 主路径（`ktv_api.js`）+ 原生兜底
  （`SongUrlResolver.kt`，供 **WebView 不可用**的老盒子使用 —— 实测极米 H1S/GMUI2.0 上
  WebView 导不出 `window.KtvApi`，桥永远 not ready，只靠 JS 时表现为
  **「无法生成下载地址」且远程日志里一条 `JS ...` 都没有**）。
  - `SongUrlResolver` 镜像了 `ktv_api.js` 的 `APP_ID/APP_KEY/SDK_KEY/HOSTS` 与
    **桥注入的** `VER=2.0/VN=4.1.3.03281430`（注意不是 JS 文件里的原始值）、
    以及 `isDemoUrl` 校验（wb66/demo/纯 IP/r2.dev/ts 编号与 musicno 不一致）。
    源站配置若变更，JS 能热更新而它必须发版 —— 所以它**只做兜底**：
    `SongApiClient.getSongDownloadUrl` 在「桥未就绪 / JS 未取到地址 / 主线程调用」
    三个回退点先试原生、再回退 DB 的 `cloud_url`。
  - 它必须走 `KtvJsBridge.rawCleanGet()`（与 JS 完全相同的裸 Socket 干净请求），
    否则会被源站风控拒绝。
  - 自查远程日志：成功是 `NATIVE-SONG 取址成功 编号=... 源=...`，失败是
    `NATIVE-SONG 全部源失败 编号=... 详情=token-fail|no-url|demo-or-mismatch`；
    桥侧另有 `JS-BRIDGE ...`（ready / KtvApi 未导出 / WebView 初始化失败 / 15s 未就绪）。
- **JS 热更新**：改动 `ktv_api.js` 必须同步提升 `KTV_BRIDGE_API: N` 标记（JS 与 `KtvJsBridge.kt` 两处校验），否则设备仍用缓存旧脚本
- **⚠️ 广告/错号地址只有一道闸门：`AdVideoFilter.isTrustedSongUrl(url, musicno)`**
  （2026-09-26 事故：新设备点歌 7303235 拿到 `gz.ac16.vip` 的**广告视频并被当成歌曲下载播放**）。
  约定：
  1. **任何来源**的播放地址都必须过这道闸门 —— JS 取址（`ktv_api.js` 的 `isDemoUrl`）、
     原生兜底（`SongUrlResolver.isValidSongUrl`）、DB 回退（`SongApiClient.fetchFromDb`），
     以及**最终兜底** `SongOkDownloadManager.buildDownloadUrl` 的成功分支。
     只加在某一层必然漏：这次就是 JS 层的规则不认广告域名、且 Kotlin 的成功分支**完全没做校验**；
  2. 判定三条：非 http(s)；命中广告特征（路径 `/ad_files/`、`my_ad_video`、**域名 `*.ac16.vip`**）；
     **URL 里的 `.ts` 文件名必须等于点歌编号**（真源恒为 `/<musicno>.ts`，已实测
     `txog.ktvsky.com` / `download.origjoy.com` 两个 CDN 域）。旧规则只比对"纯数字文件名"，
     `my_ad_video.ts` 这类非数字名字直接漏过；
  3. **内容校验挡不住广告**：广告视频能通过 `SongFileValidator`（≥6MB / ≥60s / TS 包完整），
     所以"取址即校验"是唯一有效防线。一旦判定地址不可信，必须**同时删除已落盘的歌曲文件**
     （`purgeCachedSongFile`），否则 `isDownloaded()` 会一直复用那个广告文件，广告反复播；
  4. 历史遗留由 `SongOkDownloadManager.purgeInjectedAdDuplicatesOnce()` 在升级后清一次
     （marker：`filesDir/ad_dup_purge_v1`），**只删「多首不同歌曲内容完全相同」的文件**：
     广告对每首歌都下发同一个视频，所以它的特征是"多首歌共用同一段视频"；
     **绝不能图省事直接清空整个歌曲目录**（1.4.10 这么干过，见下条）；
  5. 回归测试 `app/src/test/java/com/local/ktv/AdVideoFilterTest.java` 用日志里的**真实 URL 形态**
     锁住行为（含两个真源 CDN 域），改这块逻辑必须先让它通过。
  - ⚠️ **文件名比对只在编号可信时启用**：`buildDownloadUrl` 里只有"从 `song.filename` 推出"的
    点歌编号才传给 `isTrustedSongUrl`；`song.id` 可能是数据库主键，拿它比对会把**正常地址误杀**
    （表现为真源被判"文件名不符"→ 永远下不到歌）。
- **⚠️ 本地歌曲缓存 = 公放曲库，绝不能整目录清空**（2026-09-26 新设备回归）：
  `MuseDatabase.songDirectories()` **只有 `cloudSongsDir` 一个目录**，
  `SongOkDownloadManager.getLocalFile()` 也落在那里 —— 所以"清理歌曲缓存"就等于**删掉公放曲库**。
  1.4.10 在启动时做过一次整目录清理，结果：库里没歌 → `playRandomLocalSong()` 选不出候选
  → `currentSong` 保持 null → 按「切歌」时 `advancePlayback` 落进
  「取消随机续播、进入空闲态」分支 → **按钮按了完全没反应**（用户报「公放时切歌失效」）。
  约定：**任何"清理缓存"的改动都必须先确认它会删掉哪些目录**；要清理就只删**能证明有问题**的
  文件（见上条），**永远不要动用户已下载的正常歌曲**。
- **⚠️ 主线程禁止网络 I/O**：`SongApiClient.getSongDownloadUrl` 的主线程分支只允许走
  本地 DB 兜底（`fetchFromDb`）。1.4.7 曾在该分支先调 `resolveNatively()` ——
  原生兜底最多 7 个源 × 8 秒超时，一旦有主线程调用者就会**卡死 UI 到 ANR**。
  取址（JS 桥 / 原生兜底）一律只允许在后台线程发起。
- **⚠️ 裸 Socket 的 HTTPS 必须带主机名**：用 `TlsCompat.sslSocketFactory()` 时必须
  `createSocket(plainSocket, host, port, true)`（**先连接、再按主机名包装**），
  不能用无参 `createSocket()` —— 后者拿到的 `SSLSocket` **不带主机名**，
  会被 SNI 与证书主机名校验影响（共享 CDN / 反代多证书时直接握手失败）。
  这样既保住显式连接超时，又补上 SNI。
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
  - **写日志的五条约定**（2026-09-26 依据现场日志复盘补充）：
    1. **持久状态只记一次**，不要每次事件都记。反例：桥不可用曾按「每次点歌一条 WARN」输出，
       一台机器一天几百条、把日志刷满且毫无新增信息。现在由 `KtvJsBridge.markUnavailable()`
       在**状态首次变化**时记一条。
    2. **关键行必须带全上下文**：`STORAGE`/`BOOT` 这类启动行要放在 `reportDeviceBootInfo()` 的
       后台线程里、`refreshLogDeviceContext()` 与 `MiliLog.awaitServerIp()` **之后**，
       否则 `serverip`/`clientip`/`user`/`traceid` 全是 `undefined`（旧日志按设备检索不到）。
    3. **不要在日志行里做重活**：`AppPaths.storageFacts()`/`diagnose()` 会真实建目录、写探针、
       读 `/proc/mounts`，**绝不能放在主线程**（启动时曾如此）。
    4. **日志要能自我定位**：失败行写清"哪一步/哪个源/耗时/原因"，例如
       `NATIVE-SONG 全部源失败 编号=... 详情=token-fail|no-url|demo-or-mismatch 用时=...ms`；
       桥侧由 `JS-BRIDGE ...` 说明 WebView 到底卡在哪一步（初始化失败/KtvApi 未导出/20s 超时）。
    5. **状态样本 + 事件**缺一不可：事件（点歌/失败）说明"发生了什么"，但排查"随时间变化"的
       问题（空间变少、曲库丢失、长期离线）需要状态样本 —— 现由 6 小时一条 `HEALTH ...`
       （版本/在线/可用空间/曲库大小/根目录）提供。
  - 一段典型现场日志应为：`BOOT` → `STORAGE` → (点歌)`点歌编号=... 请求源=... 状态=成功 urlHost=...`
    → `PLAY ... src=local` → 每 6h `HEALTH ...`；异常时才有 `RAW-ERR`/`NET-ERR`/`NATIVE-SONG ...失败`。
- **网络状态与断网恢复**：`KtvNetworkMonitor`（object，走 `ConnectivityManager` 回调、不轮询）
  维护全局 `online`；`MainActivity.onNetworkStateChanged()` 负责：
  - 断网 → 播放页右上角 `fullScreenNetworkBadge` 显示「网络未连接」（在全屏控制层 header 内、
    标题右侧，不遮挡右上角二维码）；
  - 恢复 → `refreshNetworkState()`：重解析网卡 MAC + 日志上下文、**重建歌曲源会话**、
    复位 `Eac19WindowGuard`、重启本地点歌服务并刷新二维码、自动重试断网期间失败的下载（最多 8 首）。
  换网/换 IP 时 `remoteNetworkMonitor` 走同一个 `refreshNetworkState()`（静默，不弹提示）。
  - ⚠️ **启动 ≠ 网络恢复**：`KtvNetworkMonitor.start()` 的回调带 **`initial`** 参数，
    `initial=true` 表示"注册回调时系统回报的初始状态"。**绝不能**把它当成"断网后恢复"——
    启动流程（`onCreate`）自己已经做过 `startRemoteServer()`/二维码/设备上下文，
    再走一遍恢复逻辑会：白清刚建立的歌曲源会话、语义错误地 `noteUrlOk()`（掩盖真正的
    窗口到期提示）、把状态栏错写成"网络已恢复"（启动瞬间就显示，用户可见）。
    初始状态**只更新断网角标**，恢复动作只在真正的 offline→online 跃迁时执行
    （2026-09-26 由现场日志 `NET-UP` 出现在 `BOOT` 之前而发现）。
  ⚠️ **「断网重连后再也下载不了、日志却显示歌曲源获取成功」的根因**：
  `SongApiClient.clearTokenCache()` 原本是**空实现**，JS 侧 `window.api.tokens` 与
  `_apiInitPromise` 会被一直复用；而歌曲源按「出口 IP + device」识别、试用窗口也记在出口 IP 上，
  断网/重连（尤其换出口 IP）后旧 token 已失效 —— 取址这一步看起来成功，真正下载却 403/失败。
  现在 `clearTokenCache()` 会真正下发 `KtvJsBridge.resetApiSession()`
  （置空 `window.api` 与 `_apiInitPromise`，下次取址重新构造 API 实例、回到默认源顺序并重新登录）。
  下载层的网络类失败由 `SongOkDownloadManager` 结合 `KtvNetworkMonitor.online` 与异常类型/HTTP 0
  判定并记入 `networkFailed`，网络恢复后由 `retryNetworkFailed()` 自动重试（手动取消的不入集合）。
  ⚠️ 顺带修掉一个隐患：离线时 `localIp()` 为空，原 `remoteNetworkMonitor` 会每 5s 误判「IP 变化」
  而反复重启本地点歌服务（并连带触发刷新）；现已加空值短路，离线只等网络回调。
- **播放界面右上角二维码**：跟随全屏控制层一起出现/隐藏 —— 在
  `setFullScreenChromeVisible()` 里同步 `fullScreenQrOverlay` 的可见性，`updateFullScreenQrOverlay()`
  新建时按 `fullScreenChromeVisible` 初始化。**不要**再按「手机是否在点歌」自动隐藏
  （原 `qrAutoHideMonitor` 已删除，扫码弹窗里的对应提示文案也一并删除）
- **远程页面滑杆(触控)**：`<input type="range">` **绝不能被 `<label>` 包裹** —— label 会把
  内部任意位置的点击转发给 range，表现为"点轨道/点文字就跳值"。行容器用 `<div class="audio-row">`
  / `<div class="eq-band">`；CSS 选择器也随之用类选择器而不是标签选择器。
  另外滑杆元素本身高度设 32px（触控目标够大），视觉细轨道由
  `::-webkit-slider-runnable-track` / `::-moz-range-track` 绘制，滑块 22px + 5px 边框。
  `attachThumbOnlyDrag()` 仍作为第二道防线（命中滑块才放行原生拖动，容差按 32px 滑块算）
- **远程搜索性能**：曲库 DB 有 **67 万首 / 约 1GB**（`muse.db`），搜索是 I/O 密集操作。
  实测（同一台机器直连该 DB，`rec_score` 复合索引齐全）：
  - 中文 `name_trim LIKE '%kw%'`：热态 **2~280ms**（走 `idx_rec_score` 顺序扫描 + 早停，无临时排序）
  - 拼音 `name_cap/name_full GLOB 'kw*'`：热态 **0.2~150ms**（前缀范围扫描 + 临时 B-tree 排序；
    `A*` 这类高频前缀会扫很多行），冷态（首次读 1GB 文件）可达 **1~15s**
  - 歌手检索（`EXISTS` + singers LIKE）：热态 **34~70ms**
  结论与对策：**瓶颈在冷态 I/O 与重复查询**，因此
  1) 设备端加 `remoteSearchCache`（LRU 24 条 = 最近 4 个查询 × 6 页，TTL 60s，曲库刷新时清空），
     同一「类型+关键词+页码」直接复用 JSON，翻页与重复搜索不再查库；
  2) 手机端：搜索请求用 `AbortController` 取消旧请求、同关键词不重复请求、
     首页返回后**预热第 2 页**（把设备缓存捂热）、搜索期间暂停 1s 的状态轮询避免抢 I/O。
  ⚠️ 不要试图用「一次多取几页」来省请求：实测 `LIMIT 201` 的代价远高于 `LIMIT 21`
  （`name_full GLOB` 301 行 6.2s vs 21 行 0.7s，冷态），会把体验做得更差
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
  入口只有两处，且都必须收敛到 `applyPlaybackMode()`：**「调音」弹窗**（`showTuningDialog()`，
  播放界面与主界面右上角共用同一个弹窗）的音量横向控制杆、远程控制-音乐音量
  （`remoteAudio()` 的 `musicVolume`）。**声音设置弹窗里不再有音量滑杆与均衡器入口**
  （避免同一功能两套入口），它只保留「切到点播歌曲时音量/原伴唱」这类策略选择。曲线是纯衰减（100% 即 unity gain），不做系统级增益；
  设备最终响度仍取决于系统音量，App 不再替用户改动它
- **音频均衡器**：内核 `KtvEqualizer`（参考开源项目 OpenEQ，`Turbofan3360/OpenEQ`，GPL-3.0 的
  `EQUtils.kt`）——直接用系统 `android.media.audiofx.Equalizer`，**按设备真实频段数与增益范围自适应**
  （频段是动态生成的，不要写死 5 段）。默认把 EQ 挂到**实际播放会话**：
  `player.audioSessionId()` / `vocalPlayer.audioSessionId` / `embeddedVocalEngine.audioSessionId()`
  （IJK 的原生 `IjkMediaPlayer_getAudioSessionId` 返回真实 AudioTrack 会话）。会话会随歌曲变化，
  因此由 500ms 的 `lyricTicker` → `syncEqualizerSessions()` 周期对齐，消失的会话延迟 3s 释放。
  `globalMix`（会话 0）是设备不支持按会话挂载时的显式开关，会影响本机所有声音，默认关闭。
  均衡器已并入**「调音」弹窗**（`showTuningDialog()`，自上而下：音量横向杆 → 均衡器开关 →
  预设 → EQ 频段横向杆，每个频段一行：频率 | 滑杆 | 增益），主界面右上角与播放界面「调音」
  是同一个弹窗；另有远程点歌页面「均衡器」卡片（`PATCH /api/v1/player/equalizer`，状态随
  `GET /api/v1/state` 的 `equalizer` 字段下发）。预设：App 内为「自定义/流行/摇滚/舞曲」
  （`appPresetNames()`，手动改频段即转「自定义」并自动保存），远程页面额外保留「标准」
  供「恢复标准」使用。设置持久化在 `KtvStore.eq*`；
  **刻度固定 30 档**（`eqSliderSteps`），步进由设备增益范围均分，保证 0 dB 一定可选中。
  音量与频段共用自绘控件 `EqSlider`（横向），已 `suppressFill` 并自绘焦点/调节描边。
  ⚠️ **它的交互是刻意定制的，不要换回 SeekBar**：
  - **遥控**：焦点在控件上时方向键**不**改数值（先让焦点在控件间移动）；按**确认键进入调节态**
    （描边变亮变粗）后方向键才调值，**再按确认键退出**调节态；调节态下 BACK 只退出调节态、
    不关弹窗；失焦自动退出。这是"需按确认键才可调节控件点、同理按确认键退出控制"的实现。
  - **触控**：必须按住滑块（控件点）才拖动，点轨道空白不跳值（`hitsThumb` 命中判断，
    容差 `thumbTouchSlop`）。远程点歌页面同规则，见 `attachThumbOnlyDrag()`（app.js，对
    `input[type=range]` 做指针命中判断，未命中则 preventDefault）
- **公放随机清单**：`RandomPlayQueue`（纯内存单例）——随机续播不再「每次现随机挑一首」，
  而是一次性生成打乱顺序的临时清单，一轮内不重复；播完一首 `markFinished` +1；
  `next()` 每次都重新比对曲库，**自动吸纳新增歌曲**、剔除已删除歌曲；一轮播完自动开新一轮。
  **不显示**（界面不展示）、**不关联已点清单**（`orderQueue` 完全独立，随机歌曲不入队）、
  **App 退出即清理**（`onDestroy` 里 `isFinishing` 时 `clear()`，不落盘）
- **⚠️ 切歌/续播的状态机（`MainActivity.advancePlayback`）**：`orderQueue` 是"已点清单"，
  正在播的那首**仍在队列里**，切歌时才移除。四条出口，改动前先对齐语义：
  1. **显式切歌**且队列还有歌 → 播下一首；
  2. **自然播完** + `autoNext` + 队列已空 → 进公放（随机本地歌曲）；
  3. **显式切歌**且队列已空（= 在点歌清单**最后一首**上按切歌）→ **进公放**。
     这是 2026-09-26 用户明确要求的行为：公放清单与点歌清单互不影响
     （随机歌曲不入 `orderQueue`；用户一点歌，挂起的随机请求就会被
     `orderQueue.isNotEmpty()` 守卫取消）；
  4. 手动模式（`autoNext=false`）自然播完 → 停在空闲态等待点歌（保持旧逻辑）。
  ⚠️ **凡是要清空 `currentSong`/播放状态的出口，都必须同时停播放器**
  （`player.stopPlayback()` + `currentMediaPlayer = null`，参考「清空已点」处理）。
  原因：`onCompletion` 第一行是
  `if (generation != playbackGeneration || currentSong?.equals(song) != true) return`，
  所以只要出现"**播放器还在跑、`currentSong` 已被置空**"，那首歌播完时回调被直接吞掉：
  不再推进、状态却是"无歌可播"，之后按切歌/播放都没有反应 ——
  2026-09-26「点歌最后一首按切歌 → 播完卡死」就是这个僵尸态。
  唯一例外是"马上要 `play()` 下一首"的路径（可以不停，避免切歌瞬间静音）。
- **⚠️ 「切歌 → 公放」在老盒子上卡死（2026-09-26，极米 H1S）**：新设备正常、老设备卡死，
  排查出**三个叠加原因**，都已修掉，改动这块时必须同时守住：
  1. **不能共用 `io` 线程池**：`io` 只有 4 个线程，且"点歌即下即播"（`streamDownloadAndPlay`）
     这类几十秒的长任务也在里面。老设备网速慢，并发点几首歌就把池占满，切歌任务一直排队。
     → 切歌/随机续播改用 `randomPlayExecutor`（专用单线程守护线程）。
  2. **不能现扫目录找候选**：候选来自 `localSongInventory()` + `library.scanLocal()`，
     代价是"每个文件在头尾各读约 4.3MB 做 TS 校验" + "遍历 `/storage/ 各挂载点`
     （老 ROM 的失效挂载点会让 `File.listFiles()` **永久阻塞**）"。
     → 改为启动时后台**预热候选缓存**（`primeRandomCandidates()`：独立守护线程 + 
     `scanWithTimeout` 超时保护），下载完成即时补进缓存，切歌直接读缓存（零 I/O）。
  3. **主线程不能做深度文件校验**：`Song.hasLocalFile()` 内部是 `SongFileValidator.inspect`
     （头尾各读 4.3MB）。它曾被用在 `play()`、播放错误回调、`switchToLocalPlayback`，
     以及**逐行渲染**的 `songRowState()` 里 —— 慢存储上每次都是秒级，直接 ANR。
     → 播放/渲染路径统一改用**廉价检查** `MainActivity.hasPlayableLocalFile()`
     （只看存在与大小），深度校验只留在后台（`localSongInventory()` / `isDownloaded()`）。
- **⚠️ Kotlin 块注释会嵌套，注释里不能出现 `/*`**：写 `` `/storage/*` `` 时那个 `/*` 会开启一个
  **嵌套注释**，导致 KDoc 永远不闭合，后面的代码全被吞进注释里（报错是
  "Unclosed comment" + 一堆 "Missing '}'"，很难一眼看出）。注释里要写路径通配就写
  `/storage/ 各挂载点` 这种不含 `/*` 的说法。
- **弹窗分辨率自适应**：TV/投影/盒子的分辨率与 density 差异很大（1280x720、1920x1080，
  density 1.0~2.5），固定 dp 尺寸在矮屏上会被屏幕裁切。统一用 `fitDialogToScreen(dialog, scroll,
  content, preferredWidthDp, …)`：宽度取 `min(preferredWidthDp, 屏幕宽*0.94)`，高度把内容所在的
  ScrollView 限制为 `屏幕高*0.92 - 弹窗标题/按钮框架(chromeDp)` —— **不要再用 `WRAP_CONTENT` 作为
  弹窗高度**。内容高度由「屏幕预算 - 固定区高度」反推（调音弹窗的 EQ 频段区就是这样自适应），
  预算不足时弹窗内滚动而不是被裁掉。已应用：调音弹窗、扫码点歌弹窗、声音设置弹窗。
  新增/改动弹窗时请复用该 helper，别再写死 `setLayout(w, WRAP_CONTENT)`。
  ⚠️ **文字行必须 wrap_content**：`label()` 自带 6dp 上下内边距，固定矮行（曾用 18/28/42dp）
  会把文字裁掉 —— 这是"文字显示不全"的根因。做法是「文字行 wrap_content + 只有无文字区域
  才用计算高度」：调音弹窗里唯一可伸缩的是 EQ 频段区，采用**两遍 fit**（先 `panel.measure()`
  实测内容高度，超出预算就把频段区减掉，再调 `fitDialogToScreen`），这样收缩的永远是频段区
  而不是文字；频段区下限 dp(96)，再放不下才滚动。大字体(系统字体缩放)同理自动兜住
- **仓库可见性**：Gitea 仓库保持 **public**（若后续把 App 更新/热更新端点迁到 Gitea，非 public 会导致 403）
