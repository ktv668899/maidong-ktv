package com.local.ktv

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnPreparedListener
import android.media.MediaPlayer.TrackInfo
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.local.ktv.KtvStore.Companion.stableId
import com.local.ktv.Song.Companion.local
import com.local.ktv.SongApiClient.getSongDownloadUrl
import com.local.ktv.SongApiClient.init
import com.local.ktv.SongOkDownloadManager.DownloadCallback
import com.local.ktv.SongOkDownloadManager.download
import com.local.ktv.SongOkDownloadManager.getLocalFile
import com.local.ktv.SongOkDownloadManager.isDownloaded
import com.local.ktv.player.PlayerControllerView
import com.local.ktv.player.PlayerControllerView.OnControlListener
import com.local.ktv.player.KtvPlaybackEngine
import com.local.ktv.player.KtvVideoView
import com.local.ktv.player.VocalSwitchHelper
import com.local.ktv.player.VocalSwitchHelper.switchVocal
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.ToIntFunction
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.Exception
import kotlin.Float
import kotlin.FloatArray
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Throws
import kotlin.also
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.check
import kotlin.floatArrayOf
import kotlin.intArrayOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.plus
import kotlin.toString

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val keyboardSearchRunnable = Runnable { performSearch() }
    private val io: ExecutorService = Executors.newFixedThreadPool(4)
    private val stateIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val library = SongLibrary()
    private lateinit var stateDatabase: KtvStateDatabase
    private lateinit var playbackEngine: KtvPlaybackEngine

    /**
     * 获取 KTV 状态存储对象(供 Fragment 访问已点/已唱/收藏/歌单数据)。
     * 
     * @return 全局 KtvStore 实例
     */
    val store: KtvStore = KtvStore()
    private val remoteServer by lazy { LocalRemoteServer(this) }
    @Volatile private var remoteStateVersion = 1L
    private val remoteSongCache = Collections.synchronizedMap(LinkedHashMap<String, Song>())
    private var mobileQrOverlay: View? = null
    private var fullScreenQrOverlay: View? = null
    private var updateCheckStarted = false
    private var compactUi = false
    private val remoteNetworkMonitor = object : Runnable {
        override fun run() {
            val ip = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
            if (ip != remoteServer.address()) {
                remoteServer.stop()
                startRemoteServer()
                updateMobileQrOverlay()
                updateFullScreenQrOverlay()
                // 网络/IP 变化后同步日志上报的 serverip。
                refreshLogDeviceContext()
            }
            main.postDelayed(this, 5000)
        }
    }

    /** 全屏右上角扫码点歌二维码显隐控制: 手机连接点歌(页面可见时每秒轮询 state)
     *  期间自动隐藏, 断开(无请求超过约 4 秒)后恢复显示。 */
    private val qrAutoHideMonitor = object : Runnable {
        private var hiddenByClient = false
        override fun run() {
            val overlay = fullScreenQrOverlay
            if (overlay != null && isFullScreen) {
                val active = remoteServer.hasRecentActivity(4000)
                if (active && !hiddenByClient) {
                    hiddenByClient = true
                    overlay.visibility = View.GONE
                } else if (!active && hiddenByClient) {
                    hiddenByClient = false
                    overlay.visibility = View.VISIBLE
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    /** 周期向 hbeat.ktvsky.com 上报设备身份,维持歌曲源对"合法设备"的判定。 */
    private val ktvHeartbeatRunnable = object : Runnable {
        override fun run() {
            sendDeviceHeartbeat(boot = false)
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }
    private val visibleSongs: MutableList<Song> = ArrayList<Song>()
    private val visibleSingers: MutableList<Array<String?>?> = ArrayList<Array<String?>?>()
    private val visiblePlaylists: MutableList<Array<String?>> = ArrayList()
    private val orderQueue: MutableList<Song>? = store.orderQueue
    private val sangHistory = store.sangHistory

    /**
     * 获取当前下载任务列表(供 DownloadListFragment 展示下载队列)。
     * 
     * @return 全局下载任务列表
     */
    val downloads: MutableList<DownloadTask> = ArrayList<DownloadTask>()

    private var content: LinearLayout? = null
    private var nav: LinearLayout? = null
    private var header: TextView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var databaseLoadingOverlay: FrameLayout? = null
    private var databaseLoadingText: TextView? = null
    private var databaseLoadingProgress: ProgressBar? = null
    private var databaseRetryButton: TextView? = null
    @Volatile private var databaseBootstrapRunning = false
    private var player: KtvVideoView? = null

    /** 播放器控制层(叠加在 player 之上)  */
    private var playerController: PlayerControllerView? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var vocalPlayer: MediaPlayer? = null
    private var embeddedVocalEngine: KtvPlaybackEngine? = null
    private var vocalPlayerPrepared = false
    private var pendingVocalPosition = 0
    private var embeddedDualTrackMode = false
    private var embeddedMainTrackOriginal = true

    /**
     * 获取当前正在播放的歌曲(供已点列表标记播放状态)。
     * 
     * @return 当前播放的歌曲,未播放时返回 null
     */
    var currentSong: Song? = null
        private set
    private var currentVocalPath = ""
    private var lyricCurrent: TextView? = null
    private var lyricNext: TextView? = null
    private val lyricLines: MutableList<LyricLine?> = ArrayList<LyricLine?>()
    private val lyricTicker: Runnable = object : Runnable {
        override fun run() {
            updateFullScreenProgress()
            updateLyricView()
            main.postDelayed(this, 500)
        }
    }
    private var search: EditText? = null
    private var songList: ListView? = null
    private var modernSongAdapter: SongListAdapter? = null
    private var queueList: ListView? = null
    private var songAdapter: ArrayAdapter<String?>? = null
    private var queueAdapter: ArrayAdapter<String?>? = null
    private var audioManager: AudioManager? = null
    private var recorder: MediaRecorder? = null
    /** 电视/投影等设备需 App 占用麦克风输入后才会启用麦克风声音输出(供演唱)。 */
    private val micActivator = MicActivator()
    private var currentRecording: File? = null
    private var currentPage = "首页"

    private data class FocusBookmark(
        val resourceName: String?,
        val marker: String?,
        val text: String?,
        val groupText: String?,
    )

    private data class FocusReturnPoint(
        val restorePage: () -> Unit,
        val focus: FocusBookmark?,
        val directFocus: View?,
    )

    private val focusReturnStack = ArrayDeque<FocusReturnPoint>()
    private var restoringFocusRoute = false
    private var focusRestoreSerial = 0

    /** 导航按钮列表,用于维护选中态视觉  */
    private val navButtons: MutableList<TextView> = ArrayList<TextView>()

    /** 当前选中的导航索引(-1 表示未选中,如初始主页)  */
    private var currentNavIndex = -1

    // ===== 分页浏览状态(用于数据库驱动的曲库浏览) =====
    /** 当前浏览模式的页码(从0开始)  */
    private var browsePage = 0

    /** 当前浏览模式的总页数  */
    private var browseTotalPages = 0

    /** 原版一屏展示量：歌曲 2x5、歌手 4x2、排行榜 1x6。 */
    private fun browsePageSize(): Int = when (browseMode) {
        "rank" -> 6
        "singer_list" -> 8
        else -> MuseDatabase.PAGE_SIZE
    }

    /** 当前浏览类型:"hot"/"language"/"wordcount"/"singer"/"search"  */
    private var browseMode = "hot"

    /** 当前浏览的筛选参数(如语种名、歌手ID、搜索关键词等)  */
    private var browseParam: String? = ""

    /** 当前浏览的总记录数  */
    private var browseTotalCount = 0
    private var browseRequestVersion = 0
    private data class BrowseCacheKey(
        val mode: String,
        val param: String,
        val language: String,
        val searchQuery: String,
        val page: Int,
        val pageSize: Int,
    )
    private data class BrowsePageResult(val songs: List<Song>, val total: Int)
    private val browsePageCache = object : LinkedHashMap<BrowseCacheKey, BrowsePageResult>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseCacheKey, BrowsePageResult>?): Boolean =
            size > 48
    }
    private val browseCountCache = object : LinkedHashMap<String, Int>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 24
    }
    private val browseRequestsInFlight = HashSet<BrowseCacheKey>()
    private val browseDisplayRequests = HashMap<BrowseCacheKey, Int>()
    private var searchScope = "song"
    private var activeSettingsEntries: List<SettingsEntry> = emptyList()
    private var activeSettingsAdapter: SettingsListAdapter? = null
    private var activeSearchQuery = ""
    private var searchLanguage = "全部"
    private var searchContextId = ""
    private var suppressSearchWatcher = false

    /** 用户在大曲库打开前进入了曲库页面，打开完成后需要重放该次查询。 */
    private var catalogRefreshPending = false

    /** 显示分页信息的 TextView  */
    private var pageInfo: TextView? = null
    private var loopOne = false
    private var autoNext = true
    private var songRetryCount = 0 // 播放错误重试计数
    private var playbackPreparing = false
    private var playWhenPrepared = true
    private var playbackGeneration = 0L
    private val pendingQueueSongIds = LinkedHashSet<String>()
    private var suppressCompletionUntil = 0L
    private val autoFullscreenRunnable = Runnable {
        val eligible = autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen
        Log.d(TAG, "autoFullscreen fired eligible=$eligible seconds=$autoFullscreenSeconds " +
            "song=${currentSong?.let(::stableId)} playing=$playWhenPrepared full=$isFullScreen")
        if (eligible) {
            showFullScreenPlayer()
        }
    }
    private var homeBannerIndex = 0
    private val homeBanners = intArrayOf(
        R.drawable.home_banner_0,
        R.drawable.home_banner_1,
        R.drawable.home_banner_2,
        R.drawable.home_banner_3,
        R.drawable.home_banner_4,
    )
    private val homeBannerRunnable = object : Runnable {
        override fun run() {
            homeBannerIndex = (homeBannerIndex + 1) % homeBanners.size
            homePosterView?.setBackgroundResource(homeBanners[homeBannerIndex])
            textPosterPage?.text = "${homeBannerIndex + 1}/${homeBanners.size}"
            main.postDelayed(this, 5000L)
        }
    }
    private var originalVocal = true
    private var vocalChannelMode = "自动"
    private var musicVolume = 70
    private var micVolume = 70
    private var tone = 0
    private var atmosphere = "标准"
    private var singMode = "普通演唱"
    private var recordEnabled = true
    private var scoreEnabled = true
    private var marqueeEnabled = true
    private var marqueeText: String? = "欢迎使用麦动"
    private var playWhileDownloading = false
    private var clearDownloadsOnBoot = false
    private var autoFullscreenSeconds = 30
    private var showUsbSongs = true
    private var autoDeleteSongs = true
    private var reserveStorageGb = 1.0
    private var floatingButtonEnabled = true
    private var songTitleSubtitleEnabled = true
    private var orderedSongOriginal = true
    private var orderedVolumeMode = 0
    private var orderedVocalMode = 0
    private var voiceEngineEnabled = true
    private var voiceEngineVolume = 70
    private var lightMode: String? = "自动"
    private var audioDelayMs = 0
    private var screenBrightness = 80
    private var screenMode = "适应屏幕"
    private var doubleScreenEnabled = false
    private var tableBroadcastEnabled = true
    private var tableBroadcastSeconds = 8
    private var tableBroadcastText: String? = "欢迎光临"

    private class LyricLine(val timeMs: Int, val text: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().getDecorView().setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        )
        // 使用新的横屏TV布局
        setContentView(R.layout.activity_main)
        applyResponsiveLayout()
        if (!hasStoragePermission()) {
            requestStorage()
            return
        }
        AppPaths.ensureDirectories()
        store.load()
        loadStateFromStore()
        stateDatabase = KtvStateDatabase(applicationContext)
        if (clearDownloadsOnBoot) clearDownloadedFilesOnBoot()
        val restoredState = runCatching {
            stateDatabase.restoreOrSeed(orderQueue!!, sangHistory)
        }.getOrElse { KtvStateDatabase.RestoredState(null, "idle") }
        currentSong = restoredState.currentSong
        playWhenPrepared = restoredState.playbackState != "paused"
        runCatching {
            stateDatabase.restoreDownloads().forEach { snapshot ->
                if (snapshot.status == "completed") return@forEach
                snapshot.localPath?.let { snapshot.song.path = it }
                downloads += DownloadTask(snapshot.song).apply {
                    progress = snapshot.progress
                    state = when (snapshot.status) {
                        "queued", "downloading" -> "下载中断，可重试"
                        "completed" -> "完成"
                        "failed" -> "失败：${snapshot.error.orEmpty()}"
                        else -> snapshot.status
                    }
                }
                if (snapshot.status == "queued" || snapshot.status == "downloading") {
                    stateDatabase.updateDownload(
                        snapshot.song,
                        "failed",
                        snapshot.progress,
                        snapshot.localPath,
                        "应用重启，等待重试",
                    )
                }
            }
        }
        applyScreenBrightness()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
        // 初始化 API 客户端 (歌曲下载链接)
        init("abe235a87118f6de", "080027deed4f")
        playbackEngine = KtvPlaybackEngine(applicationContext)
        // 远程日志系统(GlogCenter): 启动即上报设备/网络信息。
        MiliLog.init(applicationContext)
        refreshLogDeviceContext()
        reportDeviceBootInfo()
        // 歌曲源窗口/风控到期守卫: 源对陌生出口 IP 可能在短暂窗口后返回 403,
        // 换公网 IP 即恢复。到期时弹一次提示引导用户重启光猫/路由器(重拨换 IP)。
        Eac19WindowGuard.onBlocked = {
            main.post {
                if (isFinishing || isDestroyed) return@post
                AlertDialog.Builder(this)
                    .setTitle("歌曲源临时受限")
                    .setMessage(
                        "歌曲源在当前网络(公网 IP)下暂时无法获取新歌播放地址(风控/试用窗口)。\n\n" +
                            "解决办法：重启光猫/路由器重新拨号更换公网 IP，恢复后即可继续点歌。\n\n" +
                            "已缓存的歌曲不受影响，可正常播放。",
                    )
                    .setPositiveButton("知道了", null)
                    .create()
                    .showForTv(DialogInterface.BUTTON_POSITIVE)
            }
        }
        // 初始化新布局的 UI 组件
        initTvLayout()
        val startupSong = restoredState.currentSong ?: orderQueue!!.firstOrNull()
        startupSong?.let { restoredSong ->
            play(restoredSong)
            updateBottomBar(restoredSong, true)
        } ?: run {
            // 启动时没有恢复的播放与已点歌曲: 曲库就绪后自动随机播放本地已下载歌曲
            // (与"点歌列表播放完毕自动随机本地歌曲"同一逻辑), 避免开机后空白等待。
            startupRandomPending = true
        }
        // Keep initialization on the loading state until the external catalog is ready.
        SongApiClient.init(this)  // 主线程初始化 JS Bridge
        initializeDatabase()
        startRemoteServer()
        updateMobileQrOverlay()
        main.postDelayed(remoteNetworkMonitor, 5000)
        main.postDelayed(qrAutoHideMonitor, 1000)
        // 设备心跳:开机立即上报一次(boot=1),之后每 3600s 保活,维持歌曲源合法设备判定。
        sendDeviceHeartbeat(boot = true)
        main.postDelayed(ktvHeartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        main.post(lyricTicker)
        main.postDelayed({ checkAppUpdate(false) }, 2500L)
        // 电视/投影等设备: 请求麦克风权限并保持输入占用, 否则麦克风声音不会输出到扬声器。
        main.postDelayed({ requestMicActivation() }, 4000L)
    }

    /** 申请并激活麦克风输入(演唱用)。授权后由 [MicActivator] 常驻占用输入通道。 */
    private fun requestMicActivation() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
                return
            }
        }
        micActivator.start()
        registerMicInputWatcher()
        Log.i(TAG, "麦克风输入已激活(演唱输出通道占用)")
    }

    /**
     * 监听输入设备变化: 外接麦克风(USB 声卡/3.5mm 麦)插入时系统会把默认输入路由切到外接,
     * 原 AudioRecord 随之失效。检测到变化后重建占用, 让演唱输出通道跟随当前麦克风。
     */
    private fun registerMicInputWatcher() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            manager.registerAudioDeviceCallback(micDeviceCallback, null)
        } else {
            runCatching {
                val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
                registerReceiver(micHotplugReceiver, filter)
            }
        }
    }

    private fun unregisterMicInputWatcher() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            manager.unregisterAudioDeviceCallback(micDeviceCallback)
        } else {
            runCatching { unregisterReceiver(micHotplugReceiver) }
        }
    }

    private val micDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            handleMicInputDevicesChanged(addedDevices.any { isMicInputDevice(it) })
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            handleMicInputDevicesChanged(removedDevices.any { isMicInputDevice(it) })
        }
    }

    private val micHotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleMicInputDevicesChanged(true)
        }
    }

    /** 是否为可用的麦克风输入设备(内置/耳机/USB 等)。 */
    private fun isMicInputDevice(device: android.media.AudioDeviceInfo): Boolean {
        if (!device.isSource) return false
        return when (device.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_HDMI,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
            -> true
            else -> false
        }
    }

    /** 输入设备增删后: 重新打开麦克风占用, 跟随系统当前路由(优先外接)。 */
    private fun handleMicInputDevicesChanged(_changed: Boolean) {
        // 延迟少许等系统完成路由切换, 再重建 AudioRecord。
        main.postDelayed({ micActivator.restart() }, 300L)
    }

    private fun initializeDatabase() {
        if (databaseBootstrapRunning) return
        databaseBootstrapRunning = true
        showDatabaseLoading(true, "正在初始化曲库...", null)
        io.execute {
            var bootstrapError: Throwable? = null
            var usedOldDatabase = false

            // 先尝试打开本地曲库；仅当本地不可用或已过 30 天时效时才联网同步。
            var ok = library.muse.open()
            val syncDue = DatabaseBootstrapper.isSyncDue()
            if (!ok || syncDue) {
                val reason = if (!ok) "本地曲库不可用" else "已到 ${DatabaseBootstrapper.SYNC_TTL_DAYS} 天同步时效"
                Log.i(TAG, "触发曲库服务器同步：$reason")
                val remoteVersion = DatabaseBootstrapper.fetchRemoteVersion()
                if (remoteVersion != null) {
                    val localVersion = DatabaseBootstrapper.getLocalDbVersion()
                    if (!ok || remoteVersion != localVersion) {
                        library.muse.close()
                        val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
                        bootstrapError = result.exceptionOrNull()
                        ok = result.isSuccess && library.muse.open()
                        if (!ok) {
                            // A failed online update must not make an existing local catalog unusable.
                            usedOldDatabase = library.muse.open()
                            ok = usedOldDatabase
                        }
                    } else {
                        // 版本一致：本次已完成有效校验，重置时效起点。
                        DatabaseBootstrapper.markSynced()
                    }
                } else {
                    Log.w(TAG, "曲库同步到期，但获取远端版本失败，本次继续使用本地曲库")
                }
            } else {
                Log.i(TAG, "曲库未到期(${DatabaseBootstrapper.syncStatusText()})，跳过服务器同步")
            }

            val count = if (ok) library.muse.songCount() else 0
            if (ok) warmRemoteBrowseCache(count)
            main.post {
                databaseBootstrapRunning = false
                if (ok) {
                    refreshLibrary {
                        showDatabaseLoading(false, "", null)
                        showHomePage()
                        if (usedOldDatabase && bootstrapError != null) toast("曲库更新失败，已使用本地曲库")
                        if (catalogRefreshPending) refreshCurrentCatalogPage()
                        // 启动自动随机播放: 仅当启动时无恢复播放/空队列且允许顺序播放时触发一次。
                        if (startupRandomPending) {
                            startupRandomPending = false
                            if (autoNext && orderQueue!!.isEmpty() && currentSong == null) {
                                main.postDelayed({ playRandomLocalSong(startup = true) }, 600L)
                            }
                        }
                    }
                } else {
                    val reason = bootstrapError?.message.orEmpty().ifBlank { "未找到可用曲库" }
                    showDatabaseLoadingFailure("曲库初始化失败：$reason")
                }
            }
        }
    }

    private fun showDatabaseDownloadProgress(value: Int) {
        val phase = when {
            value < 80 -> "正在下载并解压曲库"
            value < 90 -> "正在完成解压"
            value < 100 -> "正在校验数据库"
            else -> "完成"
        }
        main.post { showDatabaseLoading(true, "$phase ${value}%", value) }
    }

    private fun warmRemoteBrowseCache(count: Int) {
        val pageSize = MuseDatabase.PAGE_SIZE
        val songs = library.muse.hotSongs(0, pageSize * 2)
        synchronized(browseCountCache) { browseCountCache["hot\u0001\u0001全部\u0001"] = count }
        songs.chunked(pageSize).forEachIndexed { page, pageSongs ->
            val key = BrowseCacheKey("hot", "", "全部", "", page, pageSize)
            synchronized(browsePageCache) { browsePageCache[key] = BrowsePageResult(pageSongs, count) }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        main.removeCallbacks(lyricTicker)
        main.removeCallbacks(homeBannerRunnable)
        main.removeCallbacks(autoFullscreenRunnable)
        main.removeCallbacks(remoteNetworkMonitor)
        main.removeCallbacks(qrAutoHideMonitor)
        stopRecording(false)
        unregisterMicInputWatcher()
        micActivator.stop()
        releaseVocalPlayer()
        if (::playbackEngine.isInitialized) playbackEngine.release()
        remoteServer.stop()
        library.muse.close()
        stateIo.shutdown()
        runCatching { stateIo.awaitTermination(2, TimeUnit.SECONDS) }
        if (::stateDatabase.isInitialized) stateDatabase.close()
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (updateCheckStarted) return
        updateCheckStarted = true
        if (manual) toast("正在检查更新...")
        AppUpdateManager.check(BuildConfig.VERSION_NAME) { result ->
            main.post {
                updateCheckStarted = false
                result.onSuccess { release ->
                    if (release == null) {
                        if (manual) toast("当前已是最新版本 ${BuildConfig.VERSION_NAME}")
                    } else {
                        showAppUpdateDialog(release)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Update check failed", error)
                    if (manual) toast(error.message ?: "检查更新失败")
                }
            }
        }
    }

    private fun showAppUpdateDialog(release: AppUpdateManager.Release) {
        val notes = release.notes.trim().ifBlank { "发现新版本，建议立即更新。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${release.version}")
            .setMessage("${release.title}\n\n$notes")
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载并安装") { _, _ -> downloadAppUpdate(release) }
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun downloadAppUpdate(release: AppUpdateManager.Release) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(12))
        }
        val value = label("正在下载 ${release.apkName}…", 16, Color.WHITE).apply { gravity = Gravity.CENTER }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(value, LinearLayout.LayoutParams(-1, dp(44)))
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(24)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("应用更新")
            .setView(box)
            .setNegativeButton("后台下载", null)
            .create()
        dialog.showForTv()
        AppUpdateManager.download(this, release, { progress ->
            main.post {
                bar.progress = progress
                value.text = "正在下载 ${release.apkName}… $progress%"
            }
        }) { result ->
            main.post {
                dialog.dismiss()
                result.onSuccess { apk ->
                    AlertDialog.Builder(this)
                        .setTitle("下载完成")
                        .setMessage("版本 ${release.version} 已下载，是否现在安装？")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("安装") { _, _ -> AppUpdateManager.install(this, apk) }
                        .create().showForTv(DialogInterface.BUTTON_POSITIVE)
                }.onFailure { error ->
                    AlertDialog.Builder(this).setTitle("更新失败")
                        .setMessage(error.message ?: "下载安装包失败")
                        .setPositiveButton("确定", null).show()
                }
            }
        }
    }

    /**
     * XML 保留电视端的大触控/遥控焦点尺寸；窄屏横屏设备在运行时切换紧凑尺寸。
     * 平板及电视继续使用原始布局，手机不会再因固定 370dp 左栏和顶部按钮而溢出。
     */
    private fun applyResponsiveLayout() {
        val config = resources.configuration
        compactUi = config.screenWidthDp < 1000 || config.screenHeightDp < 600
        if (!compactUi) return
        val widthDp = config.screenWidthDp.coerceAtLeast(480)
        val barHeight = dp(52)
        findViewById<ViewGroup?>(R.id.top_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(8), 0)
        }
        findViewById<ViewGroup?>(R.id.bottom_bar)?.apply {
            layoutParams = layoutParams.apply { height = barHeight }
            setPadding(dp(10), 0, dp(10), 0)
        }
        findViewById<View?>(R.id.text_logo)?.layoutParams =
            findViewById<View>(R.id.text_logo).layoutParams.apply { width = dp(34); height = dp(34) }
        findViewById<TextView?>(R.id.top_brand_name)?.apply { textSize = 16f }
        if (widthDp < 760) {
            findViewById<View?>(R.id.top_brand_name)?.visibility = View.GONE
            findViewById<View?>(R.id.btn_top_search)?.visibility = View.GONE
        }
        listOf(
            R.id.btn_top_search, R.id.text_top_order_label, R.id.btn_top_vocal,
            R.id.btn_top_next, R.id.btn_top_pause, R.id.btn_top_replay, R.id.btn_top_tone,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply {
                textSize = 11f
                setPadding(dp(5), paddingTop, dp(6), paddingBottom)
            }
        }
        findViewById<FrameLayout?>(R.id.content_frame)?.let { frame ->
            (frame.layoutParams as? FrameLayout.LayoutParams)?.apply {
                topMargin = barHeight
                bottomMargin = barHeight
                frame.layoutParams = this
            }
        }
        findViewById<LinearLayout?>(R.id.main_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.home_layout)?.setPadding(dp(12), dp(10), dp(12), dp(8))
        findViewById<LinearLayout?>(R.id.left_column)?.let { left ->
            val desiredWidthDp = (widthDp * 0.34f).toInt().coerceIn(230, 340)
            left.layoutParams = left.layoutParams.apply { width = dp(desiredWidthDp) }
            findViewById<View?>(R.id.player_container)?.layoutParams =
                findViewById<View>(R.id.player_container).layoutParams.apply {
                    height = dp((desiredWidthDp * 9f / 16f).toInt())
                }
        }
        listOf(
            R.id.home_card_regular, R.id.home_card_favorite, R.id.home_card_category,
            R.id.home_card_rank, R.id.home_card_song, R.id.home_card_singer, R.id.home_card_local,
        ).forEach { id ->
            findViewById<TextView?>(id)?.apply { textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        }
    }

    private fun chromeBarHeight(): Int = dp(if (compactUi) 52 else 64)

    private fun buildShell() {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val shell = LinearLayout(this)
        shell.setOrientation(LinearLayout.HORIZONTAL)
        shell.setPadding(dp(16), dp(14), dp(16), dp(12))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))

        val navScroll = ScrollView(this)
        navScroll.setFillViewport(true)
        navScroll.setBackgroundColor(Color.rgb(12, 18, 25))
        shell.addView(navScroll, LinearLayout.LayoutParams(dp(168), -1))

        nav = LinearLayout(this)
        nav!!.setOrientation(LinearLayout.VERTICAL)
        nav!!.setPadding(dp(10), dp(10), dp(10), dp(10))
        navScroll.addView(nav, FrameLayout.LayoutParams(-1, -2))

        val brand = label("麦动", 26, GOLD)
        brand.setGravity(Gravity.CENTER)
        nav!!.addView(brand, LinearLayout.LayoutParams(-1, dp(64)))
        // 导航项(图标 + 文字),选中态由 selectNav() 维护
        navButtons.clear()
        addNav("🏠 主页", View.OnClickListener { v: View? -> showHome() })
        addNav("🎵 点歌", View.OnClickListener { v: View? -> showSongs("全部") })
        addNav("🎤 歌星", View.OnClickListener { v: View? -> showSingers() })
        addNav("🏆 排行榜", View.OnClickListener { v: View? -> showRank() })
        addNav("🌐 语种", View.OnClickListener { v: View? -> showLanguages() })
        addNav("🔢 字数", View.OnClickListener { v: View? -> showWordCounts() })
        addNav("⭐ 收藏", View.OnClickListener { v: View? -> showFavorites() })
        addNav("📋 歌单", View.OnClickListener { v: View? -> showPlaylists() })
        addNav("📝 已点", View.OnClickListener { v: View? -> showQueue() })
        addNav("🎵 已唱", View.OnClickListener { v: View? -> showSang() })
        addNav("⬇️ 下载", View.OnClickListener { v: View? -> showDownloads() })
        addNav("💾 U盘", View.OnClickListener { v: View? -> showUdisk() })
        addNav("🎬 影片", View.OnClickListener { v: View? -> showLocalMovies() })
        addNav("🎉 派对", View.OnClickListener { v: View? -> showDisco() })
        addNav("📡 网络", View.OnClickListener { v: View? -> showNetworkDialog() })
        addNav("🖥️ 屏幕", View.OnClickListener { v: View? -> showScreenDialog() })
        addNav("📦 应用", View.OnClickListener { v: View? -> showAppCenter() })
        addNav("🔧 维护", View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addNav("📻 广播", View.OnClickListener { v: View? -> showTableBroadcast() })
        addNav("⚙️ 设置", View.OnClickListener { v: View? -> showSettings() })

        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(14), 0, 0, 0)
        shell.addView(body, LinearLayout.LayoutParams(0, -1, 1f))

        val top = LinearLayout(this)
        top.setGravity(Gravity.CENTER_VERTICAL)
        top.setPadding(0, 0, 0, dp(6))
        header = label("首页", 24, TEXT_WHITE)
        top.addView(header, LinearLayout.LayoutParams(0, dp(54), 1f))
        status = label("准备就绪", 16, TEXT_DIM)
        status!!.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        top.addView(status, LinearLayout.LayoutParams(dp(430), dp(54)))
        body.addView(top)
        // 顶栏底部分割线(原版风格)
        val topDivider = View(this)
        topDivider.setBackgroundColor(DIVIDER)
        body.addView(topDivider, LinearLayout.LayoutParams(-1, dp(1)))

        content = LinearLayout(this)
        content!!.setOrientation(LinearLayout.HORIZONTAL)
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        progress = ProgressBar(this)
        progress!!.setVisibility(View.GONE)
        val pp = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM or Gravity.RIGHT)
        pp.rightMargin = dp(24)
        pp.bottomMargin = dp(14)
        root.addView(progress, pp)

        // 将构建好的 shell 内容挂载到 main_activity 布局的 R.id.container 容器中
        // 注意:现在使用 MainFragment 加载原版布局,buildShell 的程序化 UI 不再直接添加到 container
        // 保留 buildShell 中的播放器、下载等核心组件初始化逻辑,但 UI 由 Fragment 管理
        val container = findViewById<FrameLayout?>(R.id.container)
        if (container != null) {
            // 不再添加程序化导航 UI,避免与 MainFragment 冲突
            // shell 中的播放器和下载组件仍通过 root 引用保持工作
        } else {
            setContentView(root)
        }
    }

    /**
     * 更新导航栏选中态视觉
     * @param index 要选中的导航项索引(0-based),-1 表示取消选中
     */
    private fun selectNav(index: Int) {
        currentNavIndex = index
        for (i in navButtons.indices) {
            val btn = navButtons.get(i)
            if (i == index) {
                // 选中态:半透明红背景 + 红色文字 + 左侧红色色条效果(通过 padding 模拟)
                btn.setBackgroundColor(NAV_SELECTED)
                btn.setTextColor(ACCENT_RED)
                btn.setPadding(dp(16), dp(6), dp(8), dp(6))
            } else {
                // 未选中:透明背景 + 白色文字
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(TEXT_WHITE)
                btn.setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        }
    }

    private fun showHome() {
        if (content == null) return  // buildShell 未启用

        setPage("首页")
        selectNav(0)
        if (content == null) return
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(0, -1, 1.25f))
        left.addView(sectionTitle("快速入口"))
        val grid = GridLayout(this)
        grid.setColumnCount(3)
        left.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        addTile(
            grid,
            "新歌推荐",
            "网络/本地新增歌曲",
            View.OnClickListener { v: View? -> showSongs("全部") })
        addTile(grid, "热门排行", "按点播热度排序", View.OnClickListener { v: View? -> showRank() })
        addTile(grid, "歌星点歌", "按歌手查找", View.OnClickListener { v: View? -> showSingers() })
        addTile(
            grid,
            "语种点歌",
            "国语/粤语/外语",
            View.OnClickListener { v: View? -> showLanguages() })
        addTile(
            grid,
            "字数点歌",
            "按歌名字数查找",
            View.OnClickListener { v: View? -> showWordCounts() })
        addTile(
            grid,
            "拼音搜索",
            "支持简码/歌名/歌手",
            View.OnClickListener { v: View? -> showSearch() })
        addTile(
            grid,
            "我的收藏",
            "常唱歌曲快速点播",
            View.OnClickListener { v: View? -> showFavorites() })
        addTile(
            grid,
            "我的歌单",
            "本地自建歌单",
            View.OnClickListener { v: View? -> showPlaylists() })
        addTile(grid, "U盘导入", "扫描导入目录", View.OnClickListener { v: View? -> showUdisk() })
        addTile(
            grid,
            "下载管理",
            "网络曲库下载",
            View.OnClickListener { v: View? -> showDownloads() })

        addTile(
            grid,
            "音量音调",
            "原伴唱 / 音量 / 升降调",
            View.OnClickListener { v: View? -> showAudioControlDialog() })
        addTile(
            grid,
            "气氛模式",
            atmosphere,
            View.OnClickListener { v: View? -> showAtmosphereDialog() })
        addTile(
            grid,
            "演唱评分",
            if (scoreEnabled) scoreSummary() else "评分已关闭",
            View.OnClickListener { v: View? -> showScoreDialog() })
        addTile(
            grid,
            "本地录音",
            if (recorder == null) "录制演唱音频" else "正在录音",
            View.OnClickListener { v: View? -> showRecordings() })
        addTile(
            grid,
            "手机点歌",
            remoteUrl(),
            View.OnClickListener { v: View? -> showMobileRemoteDialog() })
        addTile(
            grid,
            "跑马灯",
            if (marqueeEnabled) marqueeText else "跑马灯已关闭",
            View.OnClickListener { v: View? -> showMarqueeSettings() })
        addTile(grid, "灯光迪斯科", lightMode, View.OnClickListener { v: View? -> showDisco() })
        addTile(
            grid,
            "本地影片",
            "用户 MV / 影片播放",
            View.OnClickListener { v: View? -> showLocalMovies() })
        addTile(
            grid,
            "网络设置",
            remoteUrl(),
            View.OnClickListener { v: View? -> showNetworkDialog() })
        addTile(
            grid,
            "屏幕设置",
            screenMode,
            View.OnClickListener { v: View? -> showScreenDialog() })
        addTile(
            grid,
            "应用工具",
            "本地工具 / 维护",
            View.OnClickListener { v: View? -> showAppCenter() })
        addTile(
            grid,
            "曲库维护",
            "清理 / 重建 / 导出",
            View.OnClickListener { v: View? -> showLibraryMaintenance() })
        addTile(
            grid,
            "包房广播",
            if (tableBroadcastEnabled) tableBroadcastText else "广播已关闭",
            View.OnClickListener { v: View? -> showTableBroadcast() })

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 0.8f))
        right.addView(sectionTitle("播放预览"))
        // 用 FrameLayout 包裹共享播放 Surface,以便叠加控制层
        val playerContainer = FrameLayout(this)
        player = KtvVideoView(this).also {
            it.bind(playbackEngine)
            playbackEngine.attach(it)
        }
        player!!.setBackgroundColor(Color.BLACK)
        playerContainer.addView(player, FrameLayout.LayoutParams(-1, -1))
        // 初始化播放器控制层并叠加到视频上方
        playerController = PlayerControllerView(this)
        initPlayerControllerCallbacks()
        playerContainer.addView(playerController, FrameLayout.LayoutParams(-1, -1))
        right.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        // 同步当前歌曲信息到控制层(若已在播放)
        if (currentSong != null) {
            playerController!!.updateSongInfo(currentSong)
            playerController!!.updatePlayState(playWhenPrepared)
            playerController!!.setVocalMode(originalVocal)
        }
        lyricCurrent = label("暂无歌词", 22, GOLD)
        lyricCurrent!!.setGravity(Gravity.CENTER)
        lyricNext = label("", 18, Color.LTGRAY)
        lyricNext!!.setGravity(Gravity.CENTER)
        right.addView(lyricCurrent, LinearLayout.LayoutParams(-1, dp(42)))
        right.addView(lyricNext, LinearLayout.LayoutParams(-1, dp(34)))
        val controls = LinearLayout(this)
        controls.setGravity(Gravity.CENTER_VERTICAL)
        controls.addView(
            button("暂停/继续", View.OnClickListener { v: View? -> togglePlay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button("重唱", View.OnClickListener { v: View? -> replay() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button("切歌", View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(button("单曲循环", View.OnClickListener { v: View? ->
            loopOne = !loopOne
            toast(if (loopOne) "单曲循环" else "顺序播放")
        }), LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addView(
            button(
                vocalActionText(),
                View.OnClickListener { v: View? -> toggleOriginalVocal() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                "音控",
                View.OnClickListener { v: View? -> showAudioControlDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                if (recorder == null) "录音" else "停止录音",
                View.OnClickListener { v: View? -> toggleRecording() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        controls.addView(
            button(
                "状态",
                View.OnClickListener { v: View? -> showPlayerStatusDialog() }),
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        right.addView(controls)
        right.addView(sectionTitle("已点 " + orderQueue!!.size))
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue.get(pos)
            )
        })
        right.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
    }

    private fun showSearch() {
        showSongs("全部")
    }

    fun showSongs(category: String?) {
        setPage("点歌")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("分类"))
        // 分类按钮:优先使用数据库语种,回退到本地分类
        val cats: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            cats.add("全部")
            cats.addAll(library.muse.languages())
            cats.add("本地")
            cats.add("网络")
        } else {
            cats.addAll(library.categories())
        }
        for (item in cats) {
            left.addView(button(item, View.OnClickListener { v: View? ->
                val cat = (v as TextView).getText().toString()
                if (library.muse.isAvailable() && ("本地" != cat) && ("网络" != cat)) {
                    browseMode = "language"
                    browseParam = cat
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterSongs(cat, "")
                }
            }))
        }
        left.addView(button("网络同步", View.OnClickListener { v: View? -> fetchCatalog() }))

        val center = panel()
        content!!.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        val searchRow = LinearLayout(this)
        searchRow.setGravity(Gravity.CENTER_VERTICAL)
        search = EditText(this)
        search!!.setSingleLine(true)
        search!!.setHint("输入歌名 / 歌手 / 拼音简码")
        search!!.setTextColor(Color.WHITE)
        search!!.setHintTextColor(Color.GRAY)
        search!!.setInputType(InputType.TYPE_CLASS_TEXT)
        search!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            filterSongs(category, search!!.getText().toString())
            true
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchRow.addView(
            button("键盘", View.OnClickListener { v: View? -> showKeyboardDialog() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        searchRow.addView(
            button(
                "搜索",
                View.OnClickListener { v: View? ->
                    filterSongs(
                        category,
                        search!!.getText().toString()
                    )
                }), LinearLayout.LayoutParams(dp(110), dp(54))
        )
        center.addView(searchRow)
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        center.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        center.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        // 默认加载热门歌曲第一页
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            filterSongs(category, "")
        }
    }

    private fun showSingers() {
        setPage("歌星")
        content!!.removeAllViews()
        val groupPanel = panel()
        content!!.addView(groupPanel, LinearLayout.LayoutParams(dp(190), -1))
        groupPanel.addView(sectionTitle("歌手分类"))

        val singerPanel = panel()
        content!!.addView(singerPanel, LinearLayout.LayoutParams(dp(340), -1))
        singerPanel.addView(sectionTitle("歌星列表"))
        val singers = listView()
        val adapter = tvAdapter()
        singers.setAdapter(adapter)
        singerPanel.addView(singers, LinearLayout.LayoutParams(-1, 0, 1f))

        val songs = panel()
        content!!.addView(songs, LinearLayout.LayoutParams(0, -1, 1f))
        songs.addView(sectionTitle("歌星歌曲"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        pageInfo = label("", 13, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(100), dp(44))
        )
        songs.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        songs.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        singers.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            val singer = adapter.getItem(pos)
            if (library.muse.isAvailable()) {
                browseMode = "singer"
                browseParam = singer
                browsePage = 0
                loadBrowsePage()
            } else {
                visibleSongs.clear()
                for (song in library.allSongs()) {
                    if (isVisibleSong(song) && singerName(song) == singer) visibleSongs.add(song)
                }
                renderSongs()
            }
        })
        // 歌手分类:优先使用数据库区域
        if (library.muse.isAvailable()) {
            val groups =
                arrayOf<String?>("全部歌手", "大陆", "港台", "外国", "中国", "男", "女", "组合")
            for (group in groups) {
                groupPanel.addView(
                    button(
                        group,
                        View.OnClickListener { v: View? ->
                            populateSingerListFromDb(
                                adapter,
                                (v as TextView).getText().toString()
                            )
                        })
                )
            }
            populateSingerListFromDb(adapter, "全部歌手")
        } else {
            val groups = arrayOf<String?>(
                "全部歌手",
                "热门歌手",
                "大陆/内地",
                "港台",
                "日韩",
                "欧美",
                "组合/乐队",
                "其他"
            )
            for (group in groups) {
                groupPanel.addView(
                    button(
                        group,
                        View.OnClickListener { v: View? ->
                            populateSingerList(
                                adapter,
                                (v as TextView).getText().toString()
                            )
                        })
                )
            }
            populateSingerList(adapter, "全部歌手")
        }
    }

    /**
     * 从数据库分页加载歌手列表。
     * 
     * @param adapter 歌手列表适配器
     * @param group   分类组名(全部歌手/大陆/港台/外国/中国/男/女/组合)
     */
    private fun populateSingerListFromDb(adapter: ArrayAdapter<String?>, group: String?) {
        if (!library.muse.isAvailable()) return
        adapter.clear()
        busy(true, "正在加载歌手...")
        io.execute(Runnable {
            var area: String? = null
            var type: String? = null
            if ("大陆" == group) area = "大陆"
            else if ("港台" == group) area = "港台"
            else if ("外国" == group) area = "外国"
            else if ("中国" == group) area = "中国"
            else if ("男" == group) type = "男"
            else if ("女" == group) type = "女"
            else if ("组合" == group) type = "组合"
            val result: MutableList<Array<String?>> = library.muse.singers(area, type, 0, 200)
            main.post(Runnable {
                for (s in result) adapter.add(s[1]) // s[1] 是歌手名
                busy(false, "加载了 " + result.size + " 位歌手")
            })
        })
    }

    private fun showLanguages() {
        setPage("语种")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("语种"))
        val langs: MutableList<String?> = ArrayList<String?>()
        if (library.muse.isAvailable()) {
            langs.add("全部")
            langs.addAll(library.muse.languages())
        } else {
            langs.addAll(library.languages())
        }
        for (language in langs) {
            left.addView(button(language, View.OnClickListener { v: View? ->
                val lang = (v as TextView).getText().toString()
                if (library.muse.isAvailable()) {
                    browseMode = "language"
                    browseParam = lang
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterLanguage(lang)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("语种点歌"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "language"
            browseParam = "全部"
            browsePage = 0
            loadBrowsePage()
        } else {
            filterLanguage("全部")
        }
    }

    private fun showWordCounts() {
        setPage("字数")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(230), -1))
        left.addView(sectionTitle("歌名字数"))
        val groups = arrayOf<String?>("全部", "一字", "二字", "三字", "四字", "五字", "六字以上")
        for (group in groups) {
            left.addView(button(group, View.OnClickListener { v: View? ->
                val g = (v as TextView).getText().toString()
                if (library.muse.isAvailable()) {
                    val wc = if ("一字" == g) 1 else if ("二字" == g) 2 else if ("三字" == g)
                        3
                    else
                        if ("四字" == g) 4 else if ("五字" == g) 5 else if ("六字以上" == g) 6 else 0
                    browseMode = "wordcount"
                    browseParam = wc.toString()
                    browsePage = 0
                    loadBrowsePage()
                } else {
                    filterWordCount(g)
                }
            }))
        }

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("字数点歌"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        right.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "wordcount"
            browseParam = "0"
            browsePage = 0
            loadBrowsePage()
        } else {
            filterWordCount("全部")
        }
    }

    private fun showRank() {
        setPage("排行")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("热门排行"))
        // 分页控制栏
        val pageBar = LinearLayout(this)
        pageBar.setGravity(Gravity.CENTER_VERTICAL)
        pageBar.addView(
            button("上一页", View.OnClickListener { v: View? -> prevPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        pageInfo = label("", 14, GOLD)
        pageInfo!!.setGravity(Gravity.CENTER)
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, dp(44), 1f))
        pageBar.addView(
            button("下一页", View.OnClickListener { v: View? -> nextPage() }),
            LinearLayout.LayoutParams(dp(110), dp(44))
        )
        panel.addView(pageBar)
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        if (library.muse.isAvailable()) {
            browseMode = "hot"
            browseParam = ""
            browsePage = 0
            loadBrowsePage()
        } else {
            visibleSongs.clear()
            for (song in library.allSongs()) {
                if (isVisibleSong(song)) visibleSongs.add(song)
            }
            Collections.sort<Song?>(
                visibleSongs,
                Comparator { a: Song?, b: Song? -> Integer.compare(b!!.playCount, a!!.playCount) })
            renderSongs()
        }
    }

    private fun showQueue() {
        setPage("已点")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        val actions = LinearLayout(this)
        actions.addView(
            button(
                "播放第一首",
                View.OnClickListener { v: View? ->
                    if (!orderQueue!!.isEmpty()) play(
                        orderQueue.get(
                            0
                        )
                    )
                }), LinearLayout.LayoutParams(dp(150), dp(54))
        )
        actions.addView(
            button("切歌", View.OnClickListener { v: View? -> playNext() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button(
                "置顶",
                View.OnClickListener { v: View? -> moveSelectedQueueToTop() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(
            button("删除", View.OnClickListener { v: View? -> removeSelectedQueue() }),
            LinearLayout.LayoutParams(dp(110), dp(54))
        )
        actions.addView(button("清空", View.OnClickListener { v: View? ->
            orderQueue!!.clear()
            renderQueue()
            saveState()
        }), LinearLayout.LayoutParams(dp(110), dp(54)))
        panel.addView(actions)
        queueList = listView()
        queueAdapter = tvAdapter()
        queueList!!.setAdapter(queueAdapter)
        queueList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            play(
                orderQueue!!.get(pos)
            )
        })
        panel.addView(queueList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderQueue()
    }

    private fun showSang() {
        setPage("已唱")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("已唱记录"))
        val list = listView()
        val adapter = tvAdapter()
        for (song in sangHistory) adapter.add(song.displayTitle())
        list.setAdapter(adapter)
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showDownloads() {
        setPage("下载")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle("下载操作"))
        left.addView(button("同步网络曲库", View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button("设置网络源", View.OnClickListener { v: View? -> showCatalogDialog() }))
        left.addView(
            button(
                "重扫本地歌曲",
                View.OnClickListener { v: View? -> refreshLibrary(null) })
        )
        left.addView(
            button(
                "取消全部下载",
                View.OnClickListener { v: View? -> cancelAllDownloads() })
        )
        left.addView(button("清理已完成", View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if ("完成" == downloads.get(i).state) downloads.removeAt(i)
            }
            showDownloads()
        }))
        left.addView(button("清理失败任务", View.OnClickListener { v: View? ->
            for (i in downloads.indices.reversed()) {
                if (downloads.get(i).state.startsWith("失败") || "已取消" == downloads.get(i).state) downloads.removeAt(
                    i
                )
            }
            showDownloads()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("下载队列"))
        val list = listView()
        val adapter = tvAdapter()
        for (task in downloads) adapter.add(task.display())
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < downloads.size) {
                downloads.get(pos).cancelled = true
                downloads.get(pos).state = "正在取消"
                showDownloads()
            }
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showUdisk() {
        setPage("U盘")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("本地导入"))
        panel.addView(
            label(
                "请把歌曲放入 /sdcard/MaidongKTV/import、/sdcard/MaidongKTV/songs、U盘/KTV 或 U盘/songs，支持 mp4/mkv/avi/mp3/flac/wav。",
                20,
                Color.LTGRAY
            )
        )
        panel.addView(button("扫描导入目录", View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showSongs("本地") })
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        filterSongs("本地", "")
    }

    fun showSettings() {
        currentTabIndex = 8
        setupTabs()
        showSettingsPage()
    }

    /**
     * 退出确认弹窗(对齐原app风格)
     */
    private fun AlertDialog.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
    ): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnShowListener {
            listView?.apply {
                setSelector(R.drawable.bg_tv_dialog_choice_focus)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
            }
            window?.decorView?.let(TvFocusStyler::installTree)
            listOf(
                DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEUTRAL,
            ).mapNotNull(::getButton).forEach(TvFocusStyler::installAction)
            val preferred = getButton(preferredButton)
                ?: getButton(DialogInterface.BUTTON_NEGATIVE)
                ?: getButton(DialogInterface.BUTTON_POSITIVE)
            preferred?.let {
                if (it.requestFocus() || it.requestFocusFromTouch()) {
                    it.refreshDrawableState()
                    it.jumpDrawablesToCurrentState()
                    it.invalidate()
                }
            }
            afterShow?.invoke()
        }
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
        }
        show()
        return this
    }

    private fun AlertDialog.Builder.showForTv(
        preferredButton: Int = DialogInterface.BUTTON_NEGATIVE,
        afterShow: (() -> Unit)? = null,
    ): AlertDialog = create().showForTv(preferredButton, afterShow)

    private fun AlertDialog.restoreSourceFocusOnDismiss(): AlertDialog {
        val sourcePage = currentPage
        val sourceView = this@MainActivity.window.decorView.findFocus()
        val sourceFocus = captureFocusBookmark(sourceView)
        setOnDismissListener {
            if (currentPage == sourcePage) restoreFocusBookmark(sourceFocus, sourceView)
        }
        return this
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出确认")
            .setMessage("确定要退出麦动吗?")
            .setPositiveButton(
                "确定退出",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> finish() })
            .setNegativeButton("取消", null)
            .create()
            .showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    /** 顶栏/键盘相关 占位字段  */
    private val key_abc: TextView? = null
    private val key_123: TextView? = null
    private val key_hand: TextView? = null

    /** 设置弹窗(对齐原app: 左侧菜单 + 右侧内容)  */
    private fun showSettingsDialog() {
        val root = getLayoutInflater().inflate(R.layout.dialog_settings, null)
        val btnClose = root.findViewById<TextView>(R.id.btn_settings_close)
        val menu = root.findViewById<ListView>(R.id.settings_menu)
        val content = root.findViewById<LinearLayout>(R.id.settings_content)

        val menuItems = arrayOf<String?>(
            "🎤  演唱模式", "🔊  音频控制", "🌐  声道设置", "🎚  音调控制",
            "📺  画面设置", "📃  跑马灯", "📶  网络设置", "ℹ  关于"
        )
        val adapter: ArrayAdapter<String?> =
            object : ArrayAdapter<String?>(this, R.layout.settings_menu_item, menuItems) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val v = super.getView(pos, cv, parent) as TextView
                    v.setTextSize(15f)
                    v.setMinHeight(dp(44))
                    v.setGravity(Gravity.CENTER_VERTICAL)
                    v.setPadding(dp(16), dp(10), dp(16), dp(10))
                    v.setTextColor(Color.rgb(225, 225, 245))
                    return v
                }
            }
        menu.setAdapter(adapter)
        // 点击左侧菜单渲染右侧内容
        menu.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
            renderSettingsContent(content, position)
        })
        // 默认显示第一个
        renderSettingsContent(content, 0)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        if (dialog.getWindow() != null) {
            dialog.getWindow()!!.setBackgroundDrawableResource(android.R.color.transparent)
        }
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        dialog.restoreSourceFocusOnDismiss()
        dialog.setOnShowListener {
            menu.post {
                if (menu.childCount > 0) menu.getChildAt(0)?.requestFocus()
            }
        }
        dialog.show()
    }

    /** 渲染设置弹窗右侧内容  */
    private fun renderSettingsContent(content: LinearLayout, index: Int) {
        content.removeAllViews()
        when (index) {
            0 -> {
                // 演唱模式
                addSettingsTitle(content, "演唱模式")
                addSettingsOptions(
                    content,
                    "演唱模式",
                    arrayOf<String>(
                        "普通演唱", "练唱模式", "评分演唱", "静音练唱"
                    ),
                    arrayOf<String?>(null, null, "评分演唱", "静音练唱"),
                    singMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setSingMode((`val` as kotlin.String?)!!)
                    })
            }

            1 -> {
                // 音频控制(链接到调音弹窗)
                addSettingsTitle(content, "音频控制")
                addSettingsItem(
                    content,
                    "麦克风音量",
                    micVolume.toString() + "%",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "音乐音量",
                    musicVolume.toString() + "%",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "音调",
                    (if (tone > 0) "+" else "") + tone + " 调",
                    View.OnClickListener { v: View? -> showAudioControlDialog() })
                addSettingsItem(
                    content,
                    "原唱模式",
                    if (originalVocal) "开" else "关",
                    View.OnClickListener { v: View? -> toggleOriginalVocal() })
            }

            2 -> {
                // 声道设置
                addSettingsTitle(content, "声道模式")
                addSettingsOptions(
                    content,
                    "声道",
                    arrayOf<String>(
                        "自动", "左伴右原", "右伴左原", "全声道"
                    ),
                    arrayOf<String>("自动", "左伴右原", "右伴左原", "全声道"),
                    vocalChannelMode,
                    OnValueClickListener { v: View?, `val`: Any? ->
                        setVocalChannelMode((`val` as kotlin.String?)!!)
                    })
            }

            3 -> {
                // 音调控制
                addSettingsTitle(content, "音调控制")
                addSettingsItem(content, "音调 -", "降调", View.OnClickListener { v: View? ->
                    tone = clamp(tone - 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
                addSettingsItem(content, "音调 +", "升调", View.OnClickListener { v: View? ->
                    tone = clamp(tone + 1, -5, 5)
                    player?.setTone(tone)
                    saveState()
                })
            }

            4 -> {
                // 画面设置
                addSettingsTitle(content, "画面设置")
                addSettingsItem(content, "画面比例", "16:9", null)
                addSettingsItem(content, "画面质量", "高", null)
                addSettingsItem(content, "全屏模式", "是", null)
            }

            5 -> {
                // 跑马灯
                addSettingsTitle(content, "跑马灯")
                addSettingsItem(content, "跑马灯状态", "关", null)
                addSettingsItem(content, "跑马灯内容", "麦动 欢迎光临", null)
                addSettingsItem(content, "滚动速度", "正常", null)
            }

            6 -> {
                // 网络设置
                addSettingsTitle(content, "网络设置")
                addSettingsItem(content, "当前网络", "WIFI 已连接", null)
                addSettingsItem(content, "CDN 主地址", "pub.cdn.cherryonline.cn", null)
                addSettingsItem(content, "CDN 备地址", "pub.mcdn.cherryonline.cn", null)
            }

            7 -> {
                // 关于
                addSettingsTitle(content, "关于")
                addSettingsItem(content, "应用名称", "麦动", null)
                addSettingsItem(content, "版本号", BuildConfig.VERSION_NAME, null)
                addSettingsItem(
                    content,
                    "曲库",
                    if (library.muse.isAvailable()) "已加载 (" + library.muse.songCount() + " 首)" else "未加载",
                    null
                )
            }
        }
    }

    /** 设置项标题  */
    private fun addSettingsTitle(parent: LinearLayout, title: String?) {
        val tv = TextView(this)
        tv.setText(title)
        tv.setTextSize(17f)
        tv.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
        tv.setTextSize(17f)
        tv.setPadding(dp(8), dp(8), dp(8), dp(12))
        tv.setTypeface(null, Typeface.BOLD)
        parent.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** 设置项 - 单行(label + value, 点击有 action)  */
    private fun addSettingsItem(
        parent: LinearLayout,
        label: String?,
        value: String?,
        onClick: View.OnClickListener?
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(label)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val r = TextView(this)
        r.setText(value)
        r.setTextSize(15f)
        r.setTextColor(Color.rgb(0xFF, 0xD5, 0x6B))
        row.addView(
            r,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (onClick != null) {
            row.setClickable(true)
            row.setOnClickListener(onClick)
        }
    }

    /** 选项值点击回调 (传 value)  */
    private fun interface OnValueClickListener {
        fun onClick(v: View?, value: Any?)
    }

    /** 设置项 - 选项组(单选)  */
    private fun addSettingsOptions(
        parent: LinearLayout,
        title: String?,
        options: Array<out String?>,
        values: Array<out Any?>,
        current: Any?,
        onClick: OnValueClickListener
    ) {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.VERTICAL)
        row.setBackgroundResource(R.drawable.bg_settings_item)
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        parent.addView(row, lp)
        val l = TextView(this)
        l.setText(title)
        l.setTextSize(15f)
        l.setTextColor(Color.rgb(225, 225, 245))
        row.addView(
            l,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 选项按钮横向布局
        val btnRow = LinearLayout(this)
        btnRow.setOrientation(LinearLayout.HORIZONTAL)
        btnRow.setPadding(0, dp(8), 0, 0)
        row.addView(
            btnRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        for (i in options.indices) {
            val opt = options[i]
            val `val` = values[i]
            val btn = TextView(this)
            btn.setText(opt)
            val isOn = `val` != null && `val` == current
            btn.setBackgroundResource(if (isOn) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
            btn.setTextColor(if (isOn) Color.WHITE else Color.rgb(204, 204, 204))
            btn.setTextSize(13f)
            btn.setGravity(Gravity.CENTER)
            btn.setPadding(dp(10), dp(8), dp(10), dp(8))
            val bLp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            bLp.leftMargin = if (i == 0) 0 else dp(4)
            bLp.rightMargin = if (i == options.size - 1) 0 else dp(4)
            btnRow.addView(btn, bLp)
            val fv = `val`
            val fi = i
            btn.setOnClickListener(View.OnClickListener { v: View? ->
                if (fv != null) onClick.onClick(v, fv)
                // 更新按钮状态
                for (j in 0 until btnRow.getChildCount()) {
                    val sib = btnRow.getChildAt(j) as TextView
                    val isOn2 = j == fi
                    sib.setBackgroundResource(if (isOn2) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
                    sib.setTextColor(if (isOn2) Color.WHITE else Color.rgb(204, 204, 204))
                }
            })
        }
    }

    private fun toggleOriginalVocal() {
        setOriginalVocalMode(!originalVocal)
    }

    /** 按按钮表达的目标模式切换，提示框和实际声道使用同一个目标值。 */
    private fun setOriginalVocalMode(targetOriginal: Boolean) {
        val shouldPlay = playWhenPrepared
        originalVocal = targetOriginal
        applyPlaybackMode()
        saveState()
        showPlaybackModeNotice(if (targetOriginal) "原唱" else "伴唱")
        // 同步状态到控制层
        if (playerController != null) {
            playerController!!.setVocalMode(originalVocal)
        }
        // 更新 TV 布局底部控制栏
        if (textVocalMode != null) {
            textVocalMode.setText(vocalActionText())
        }
        updateBottomBar(currentSong, shouldPlay)
    }

    /** 控制按钮显示下一次动作；播放框提示显示本次切换后实际生效的模式。 */
    private fun vocalActionText(): String = if (originalVocal) "伴唱" else "原唱"

    private fun vocalActionIcon(): Int = if (originalVocal) {
        R.drawable.ott_ic_ctrl_music_accomp
    } else {
        R.drawable.ott_ic_ctrl_music_origin
    }

    /**
     * 初始化播放器控制层的回调。
     * 
     * 
     * 将 [PlayerControllerView] 的按钮事件路由到 MainActivity 的现有播放逻辑:
     * 
     *  * `onPlayPause` → [.togglePlay]
     *  * `onPrev` / `onNext` → [.playPrevSong] / [.playNext]
     *  * `onVocalSwitch` → 使用 [VocalSwitchHelper] 切换原唱/伴唱声道
     *  * `onVolume` → [.showAudioControlDialog]
     *  * `onFullScreen` → [.showFullScreenPlayer]
     * 
     */
    private fun initPlayerControllerCallbacks() {
        if (playerController == null) return
        playerController!!.setOnControlListener(object : OnControlListener {
            override fun onPlayPause() {
                togglePlay()
            }

            override fun onPrev() {
                playPrevSong()
            }

            override fun onNext() {
                playNext()
            }

            override fun onVocalSwitch(original: Boolean) {
                setOriginalVocalMode(original)
            }

            override fun onVolume() {
                showAudioControlDialog()
            }

            override fun onFullScreen() {
                showFullScreenPlayer()
            }
        })
    }

    /**
     * 进入全屏播放界面。
     * 
     * 
     * 加载 [FullScreenPlayerFragment] 到 `R.id.container` 容器,
     * 替换当前 MainFragment,并加入回退栈以便返回。
     */
    fun showFullScreenPlayer() {
        if (player == null) player = videoBackground
        if (isFullScreen || player == null || fullScreenContainer == null) return
        main.removeCallbacks(autoFullscreenRunnable)
        playerReturnSurface = player
        playerReturnHost = activePlayerHost
        playerTouchOverlay?.visibility = View.GONE

        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setBackgroundColor(Color.TRANSPARENT)
        fullScreenContainer!!.setOnClickListener {
            setFullScreenChromeVisible(!fullScreenChromeVisible)
        }

        val header = LinearLayout(this)
        header.setGravity(Gravity.CENTER_VERTICAL)
        header.setPadding(dp(12), dp(4), dp(12), dp(4))
        header.setBackgroundColor(Color.TRANSPARENT)
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ktv_logo)
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER)
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val song = label(currentAndNextText(), 18, Color.WHITE)
        fullScreenSongInfo = song
        header.addView(song, LinearLayout.LayoutParams(0, dp(48), 1f))
        val back = label("返回", 15, Color.WHITE)
        back.setGravity(Gravity.CENTER)
        back.setBackgroundResource(R.drawable.bg_btn_glass)
        back.setOnClickListener(View.OnClickListener { v: View? -> exitFullScreenPlayer() })
        back.visibility = View.GONE
        header.addView(back, LinearLayout.LayoutParams(dp(76), dp(36)))
        val headerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP
        )
        fullScreenContainer!!.addView(header, headerParams)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.clipChildren = false
        controls.clipToPadding = false
        controls.setPadding(dp(8), dp(4), dp(8), dp(4))
        controls.setBackgroundColor(Color.TRANSPARENT)
        val controlItems = ArrayList<View>(7)

        fun addControl(iconRes: Int, text: String, action: () -> Unit): Pair<TextView, ImageView> {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.TRANSPARENT)
                id = View.generateViewId()
                clipChildren = false
                clipToPadding = false
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.76f
            }
            val caption = label(text, 12, Color.rgb(184, 184, 184)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(28), dp(30)))
            item.addView(caption, LinearLayout.LayoutParams(-1, dp(24)))
            item.setOnClickListener {
                action()
                if (isFullScreen) setFullScreenChromeVisible(true)
            }
            installPressFeedback(item)
            controlItems.add(item)
            val params = LinearLayout.LayoutParams(dp(78), dp(68))
            params.setMargins(dp(2), 0, dp(2), 0)
            controls.addView(item, params)
            return caption to icon
        }

        addControl(R.drawable.ic_return_order, "返回点歌") { exitFullScreenPlayer() }
        addControl(R.drawable.ott_ic_ctrl_orderlist, "已点") {
            exitFullScreenPlayer()
            showOrderListPage()
        }
        addControl(
            vocalActionIcon(),
            vocalActionText()
        ) {
            toggleOriginalVocal()
        }.also { (text, icon) -> fullScreenVocalText = text; fullScreenVocalIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_switch_song, "切歌") { playNext() }
        addControl(
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
            if (playWhenPrepared) "暂停" else "播放"
        ) { togglePlay() }.also { (text, icon) -> fullScreenPauseText = text; fullScreenPauseIcon = icon }
        addControl(R.drawable.ott_ic_ctrl_replay, "重唱") { replay() }
        addControl(R.drawable.ott_ic_ctrl_volume, "调音") { showFullScreenVolumeDialog() }
        fullScreenToneControl = controlItems.lastOrNull()

        controlItems.forEachIndexed { index, item ->
            item.nextFocusLeftId = controlItems.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusRightId = controlItems.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusUpId = item.id
            item.nextFocusDownId = item.id
        }

        val controlsParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(74), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        controlsParams.topMargin = dp(48)
        fullScreenContainer!!.addView(controls, controlsParams)
        fullScreenControls = controls

        val progressRow = LinearLayout(this)
        progressRow.orientation = LinearLayout.HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL
        progressRow.setPadding(dp(18), dp(4), dp(18), dp(4))
        progressRow.setBackgroundColor(Color.argb(155, 0, 0, 0))

        fullScreenSeekBar = SeekBar(this).apply {
            max = 1
            isFocusable = true
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fullScreenTime?.text = formatPlaybackTime(progress) + " / " +
                            formatPlaybackTime(seekBar?.max ?: 0)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    fullScreenSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val position = seekBar?.progress ?: 0
                    runCatching {
                        player?.seekTo(position)
                        prepareExternalVocal(currentSong, position)
                    }
                    fullScreenSeeking = false
                    updateLyricView()
                    persistRuntimeState()
                }
            })
        }
        progressRow.addView(fullScreenSeekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        fullScreenTime = label("00:00 / 00:00", 14, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }
        progressRow.addView(fullScreenTime, LinearLayout.LayoutParams(dp(132), dp(48)))
        fullScreenContainer!!.addView(
            progressRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM
            )
        )
        fullScreenProgressRow = progressRow

        isFullScreen = true
        fullScreenContainer!!.setVisibility(View.VISIBLE)
        expandPlayerToFullScreen()
        updateFullScreenQrOverlay()
        setFullScreenChromeVisible(true)
        updateFullScreenProgress()
    }

    private fun exitFullScreenPlayer() {
        if (!isFullScreen || player == null || fullScreenContainer == null) return
        fullScreenContainer!!.removeAllViews()
        fullScreenContainer!!.setOnClickListener(null)
        fullScreenContainer!!.setVisibility(View.GONE)
        isFullScreen = false
        playerReturnHost?.let {
            activePlayerHost = it
            applyPlayerBounds(it)
        }
        playerReturnSurface = null
        playerReturnHost = null
        fullScreenPauseText = null
        fullScreenVocalText = null
        fullScreenPauseIcon = null
        fullScreenVocalIcon = null
        fullScreenSongInfo = null
        fullScreenSeekBar = null
        fullScreenTime = null
        fullScreenSeeking = false
        fullScreenControls = null
        fullScreenProgressRow = null
        fullScreenVolumePanel = null
        fullScreenVolumeIndicator = null
        fullScreenVolumeMinusButton = null
        fullScreenVolumePlusButton = null
        fullScreenVolumeMuteButton = null
        fullScreenToneControl = null
        fullScreenQrOverlay = null
        fullScreenChromeVisible = false
        main.removeCallbacks(hideFullScreenChromeRunnable)
        main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
        scheduleAutoFullscreen()
    }

    private fun setFullScreenChromeVisible(visible: Boolean) {
        if (!isFullScreen && !visible) return
        fullScreenChromeVisible = visible
        fullScreenControls?.visibility = if (visible) View.VISIBLE else View.GONE
        fullScreenProgressRow?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) fullScreenVolumePanel?.visibility = View.GONE
        main.removeCallbacks(hideFullScreenChromeRunnable)
        if (visible && isFullScreen) {
            val controls = fullScreenControls
            if (controls != null && !controls.hasFocus() && fullScreenVolumePanel?.hasFocus() != true) {
                controls.getChildAt(0)?.requestFocus()
            }
            main.postDelayed(hideFullScreenChromeRunnable, 5000L)
        }
    }

    private fun updateFullScreenProgress() {
        if (!isFullScreen || player == null || fullScreenSeekBar == null) return
        runCatching {
            val duration = max(0, player!!.duration)
            val position = max(0, player!!.currentPosition)
            if (duration > 0) fullScreenSeekBar!!.max = duration
            if (!fullScreenSeeking) fullScreenSeekBar!!.progress = min(position, max(1, duration))
            fullScreenTime?.text = formatPlaybackTime(position) + " / " + formatPlaybackTime(duration)
        }
    }

    private fun formatPlaybackTime(milliseconds: Int): String {
        val totalSeconds = max(0, milliseconds) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return (if (minutes < 10) "0" else "") + minutes + ":" +
            (if (seconds < 10) "0" else "") + seconds
    }

    /**
     * 供 [FullScreenPlayerFragment] 调用打开音量控制面板。
     */
    fun showAudioControlFromFragment() {
        showAudioControlDialog()
    }

    private fun showFullScreenVolumeDialog() {
        if (!isFullScreen || fullScreenContainer == null) return
        val existing = fullScreenVolumePanel
        if (existing != null) {
            existing.visibility = if (existing.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (existing.visibility == View.VISIBLE) {
                fullScreenVolumeMinusButton?.requestFocus()
            } else {
                fullScreenToneControl?.requestFocus()
            }
            return
        }

        if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
        }
        val indicator = CircularVolumeView(this).apply {
            setVolume(musicVolume)
            visibility = View.GONE
            alpha = 1f
        }
        panel.addView(indicator, LinearLayout.LayoutParams(dp(154), dp(154)).apply {
            bottomMargin = dp(22)
        })
        panel.addView(label("调音", 26, Color.WHITE).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(420), dp(54)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun volumeButton(text: String): TextView = TextView(this).apply {
            this.text = text
            id = View.generateViewId()
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.ott_bg_volume_ctrl_action_button)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            includeFontPadding = false
            setPadding(dp(14), 0, dp(14), 0)
        }
        val minus = volumeButton("音量 －")
        val plus = volumeButton("音量 ＋")
        val mute = volumeButton("静音").apply {
            compoundDrawablePadding = dp(8)
        }
        fun renderMuteButton() {
            mute.text = if (musicVolume == 0) "放音" else "静音"
            val drawable = getDrawable(
                if (musicVolume == 0) R.drawable.ic_ctrl_sound_enable
                else R.drawable.ic_ctrl_sound_disable
            )
            drawable?.setBounds(0, 0, dp(23), dp(23))
            mute.setCompoundDrawables(null, null, drawable, null)
        }
        fun showVolumeResult() {
            indicator.animate().cancel()
            indicator.setVolume(musicVolume)
            indicator.alpha = 1f
            indicator.visibility = View.VISIBLE
            renderMuteButton()
            applySystemMusicVolume()
            applyPlaybackMode()
            saveState()
            main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
            main.postDelayed(hideFullScreenVolumeIndicatorRunnable, 2200L)
            setFullScreenChromeVisible(true)
        }
        minus.setOnClickListener {
            musicVolume = (musicVolume - 5).coerceAtLeast(0)
            if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
            showVolumeResult()
        }
        plus.setOnClickListener {
            musicVolume = (musicVolume + 5).coerceAtMost(100)
            if (musicVolume > 0) fullScreenPreviousVolume = musicVolume
            showVolumeResult()
        }
        mute.setOnClickListener {
            if (musicVolume > 0) {
                fullScreenPreviousVolume = musicVolume
                musicVolume = 0
            } else {
                musicVolume = fullScreenPreviousVolume.coerceAtLeast(5)
            }
            showVolumeResult()
        }
        listOf(minus, plus, mute).forEachIndexed { index, button ->
            button.nextFocusLeftId = listOf(minus, plus, mute).getOrNull(index - 1)?.id ?: button.id
            button.nextFocusRightId = listOf(minus, plus, mute).getOrNull(index + 1)?.id ?: button.id
            button.nextFocusUpId = fullScreenToneControl?.id ?: button.id
            button.nextFocusDownId = button.id
            buttons.addView(button, LinearLayout.LayoutParams(dp(136), dp(58)).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            })
        }
        panel.addView(buttons, LinearLayout.LayoutParams(-2, dp(70)).apply { topMargin = dp(12) })
        renderMuteButton()

        fullScreenContainer!!.addView(
            panel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                bottomMargin = dp(86)
            },
        )
        fullScreenVolumePanel = panel
        fullScreenVolumeIndicator = indicator
        fullScreenVolumeMinusButton = minus
        fullScreenVolumePlusButton = plus
        fullScreenVolumeMuteButton = mute
        minus.requestFocus()
    }

    /**
     * 切换到上一首歌曲(供控制层 onPrev 回调使用)。
     */
    fun playPrevSong() {
        if (orderQueue!!.isEmpty() || currentSong == null) return
        val index = orderQueue.indexOf(currentSong!!)
        if (index <= 0) {
            // 当前歌曲不在队列或已是首曲,从已唱历史取上一首
            if (!sangHistory.isEmpty()) {
                val prev = sangHistory.get(0)
                orderQueue.add(0, prev)
                sangHistory.removeAt(0)
                saveState()
                play(prev)
            }
            return
        }
        val prev = orderQueue.get(index - 1)
        // 将当前歌曲放回队首
        orderQueue.removeAt(index)
        orderQueue.add(index, prev)
        play(prev)
    }

    private fun setSingMode(mode: String) {
        singMode = mode
        if ("练唱模式" == mode) {
            recordEnabled = true
            scoreEnabled = false
        } else if ("评分演唱" == mode) {
            recordEnabled = true
            scoreEnabled = true
        } else if ("静音练唱" == mode) {
            recordEnabled = true
            scoreEnabled = false
            atmosphere = "静音练唱"
        }
        applyPlaybackMode()
        saveState()
        toast("演唱模式：" + singMode)
    }

    private fun setVocalChannelMode(mode: String) {
        vocalChannelMode = mode
        applyPlaybackMode()
        saveState()
        toast("声道模式：" + vocalChannelMode)
    }

    /**
     * 显示调音/音频控制弹窗(对应"弹窗-调音.jpg")
     * 使用 dialog_audio_control.xml 实现
     */
    private fun showAudioControlDialog() {
        if (showAlignedTuningDialog()) return
        val root = getLayoutInflater().inflate(R.layout.dialog_audio_control, null)
        val btnClose = root.findViewById<TextView>(R.id.btn_audio_close)
        val btnStereo = root.findViewById<TextView>(R.id.btn_tone_stereo)
        val btnAccomp = root.findViewById<TextView>(R.id.btn_tone_accomp)
        val btnOrigin = root.findViewById<TextView>(R.id.btn_tone_origin)
        val btnMute = root.findViewById<TextView>(R.id.btn_tone_mute)
        val seekMicVol = root.findViewById<SeekBar>(R.id.seek_mic_volume)
        val seekMicEffect = root.findViewById<SeekBar>(R.id.seek_mic_effect)
        val seekMusicVol = root.findViewById<SeekBar>(R.id.seek_music_volume)
        val tvMicVol = root.findViewById<TextView>(R.id.text_mic_volume_value)
        val tvMicEffect = root.findViewById<TextView>(R.id.text_mic_effect_value)
        val tvMusicVol = root.findViewById<TextView>(R.id.text_music_volume_value)
        val btnDefault = root.findViewById<TextView>(R.id.btn_audio_default)
        val btnSave = root.findViewById<TextView>(R.id.btn_audio_save)

        // 初始值
        seekMicVol.setProgress(micVolume)
        seekMicEffect.setProgress(50)
        seekMusicVol.setProgress(musicVolume)
        tvMicVol.setText(micVolume.toString())
        tvMicEffect.setText("50")
        tvMusicVol.setText(musicVolume.toString())

        // 4 个切换按钮:伴唱 / 原唱 / 立体声 / 静音
        updateToggleState(btnAccomp, !originalVocal)
        updateToggleState(btnOrigin, originalVocal)
        updateToggleState(btnStereo, "自动" == vocalChannelMode)
        updateToggleState(btnMute, false)
        btnAccomp.setOnClickListener(View.OnClickListener { v: View? ->
            if (originalVocal) toggleOriginalVocal()
            updateToggleState(btnAccomp, true)
            updateToggleState(btnOrigin, false)
        })
        btnOrigin.setOnClickListener(View.OnClickListener { v: View? ->
            if (!originalVocal) toggleOriginalVocal()
            updateToggleState(btnAccomp, false)
            updateToggleState(btnOrigin, true)
        })
        btnStereo.setOnClickListener(View.OnClickListener { v: View? ->
            setVocalChannelMode("自动")
            updateToggleState(btnStereo, true)
        })
        btnMute.setOnClickListener(View.OnClickListener { v: View? ->
            if (musicVolume > 0) {
                muteBeforeVol = musicVolume
                musicVolume = 0
            } else {
                musicVolume = muteBeforeVol
            }
            applySystemMusicVolume()
            applyPlaybackMode()
            seekMusicVol.setProgress(musicVolume)
            tvMusicVol.setText(musicVolume.toString())
            updateToggleState(btnMute, musicVolume == 0)
            saveState()
        })

        // 3 个滑块
        seekMicVol.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                micVolume = p
                tvMicVol.setText(p.toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                saveState()
            }
        })
        seekMicEffect.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                tvMicEffect.setText(p.toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekMusicVol.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                musicVolume = p
                tvMusicVol.setText(p.toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                applySystemMusicVolume()
                applyPlaybackMode()
                saveState()
            }
        })

        // 默认 / 保存
        btnDefault.setOnClickListener(View.OnClickListener { v: View? ->
            seekMicVol.setProgress(60)
            seekMicEffect.setProgress(50)
            seekMusicVol.setProgress(80)
            micVolume = 60
            musicVolume = 80
            applySystemMusicVolume()
            applyPlaybackMode()
            saveState()
            tvMicVol.setText("60")
            tvMicEffect.setText("50")
            tvMusicVol.setText("80")
            updateToggleState(btnMute, false)
        })

        // 创建并显示弹窗
        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        // 透明背景显示圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow()!!.setBackgroundDrawableResource(android.R.color.transparent)
        }
        btnClose.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        btnSave.setOnClickListener(View.OnClickListener { v: View? ->
            saveState()
            dialog.dismiss()
        })
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()
    }

    private fun showAlignedTuningDialog(): Boolean {
        val root = getLayoutInflater().inflate(R.layout.dialog_tuning, null)
        val toneValue = root.findViewById<TextView>(R.id.text_tone_counter)
        val micValue = root.findViewById<TextView>(R.id.text_mic_counter)
        toneValue.setText(tone.toString())
        micValue.setText(micVolume.toString())

        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<View?>(R.id.btn_tuning_close)
            .setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
        root.findViewById<View?>(R.id.btn_tone_down)
            .setOnClickListener(View.OnClickListener { v: View? ->
                tone = max(-5, tone - 1)
                toneValue.setText(tone.toString())
                player?.setTone(tone)
                applyPlaybackMode()
                saveState()
            })
        root.findViewById<View?>(R.id.btn_tone_up)
            .setOnClickListener(View.OnClickListener { v: View? ->
                tone = min(5, tone + 1)
                toneValue.setText(tone.toString())
                player?.setTone(tone)
                applyPlaybackMode()
                saveState()
            })
        root.findViewById<View?>(R.id.btn_mic_down)
            .setOnClickListener(View.OnClickListener { v: View? ->
                toast("麦克风音量由功放/混音器硬件调节")
            })
        root.findViewById<View?>(R.id.btn_mic_up)
            .setOnClickListener(View.OnClickListener { v: View? ->
                toast("麦克风音量由功放/混音器硬件调节")
            })
        bindAtmospherePreset(root, R.id.btn_preset_pop, "流行")
        bindAtmospherePreset(root, R.id.btn_preset_lyric, "抒情")
        bindAtmospherePreset(root, R.id.btn_preset_rock, "摇滚")
        bindAtmospherePreset(root, R.id.btn_preset_singer, "唱将")

        dialog.showForTv(afterShow = {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                attributes = attributes.apply { dimAmount = 0.72f }
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            root.findViewById<View>(R.id.btn_tone_down).requestFocus()
        })
        return true
    }

    private fun bindAtmospherePreset(root: View, id: Int, preset: String) {
        root.findViewById<View?>(id).setOnClickListener(View.OnClickListener { v: View? ->
            atmosphere = preset
            saveState()
            toast("音效: " + preset)
        })
    }

    /** 4 个切换按钮的状态切换  */
    private var muteBeforeVol = 80
    private fun updateToggleState(btn: TextView?, on: Boolean) {
        if (btn == null) return
        btn.setBackgroundResource(if (on) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_toggle_off)
        btn.setTextColor(if (on) Color.WHITE else Color.rgb(204, 204, 204))
    }

    private fun controlRow(
        left: String?,
        right: String?,
        leftAction: Runnable,
        rightAction: Runnable
    ): LinearLayout {
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.addView(
            button(left, View.OnClickListener { v: View? -> leftAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        row.addView(
            button(right, View.OnClickListener { v: View? -> rightAction.run() }),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        return row
    }

    private fun showAtmosphereDialog() {
        val items = arrayOf<String>("标准", "热烈", "柔和", "派对", "静音练唱")
        AlertDialog.Builder(this)
            .setTitle("气氛模式")
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    atmosphere = items[which]
                    saveState()
                    toast("气氛模式：" + atmosphere)
                })
            .create().showForTv()
    }

    private fun showPlayerStatusDialog() {
        val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
        val message = ("当前歌曲：" + (if (now == null) "无" else now.displayTitle())
                + "\n已点：" + orderQueue.size
                + "\n已唱：" + sangHistory.size
                + "\n模式：" + (if (loopOne) "单曲循环" else (if (autoNext) "顺序播放" else "手动切歌"))
                + "\n声道：" + (if (originalVocal) "原唱" else "伴唱")
                + "\n声道模式：" + vocalChannelMode
                + "\n演唱模式：" + singMode
                + "\n音乐音量：" + musicVolume
                + "\n麦克风音量：" + micVolume
                + "\n音调：" + tone
                + "\n气氛：" + atmosphere
                + "\n评分：" + scoreSummary()
                + "\n录音：" + (if (recorder == null) "未录音" else "录音中")
                + "\n灯光：" + lightMode
                + "\n跑马灯：" + (if (marqueeEnabled) marqueeText else "关闭")
                + "\n音画同步：" + audioDelayMs + "ms"
                + "\n包房广播：" + (if (tableBroadcastEnabled) tableBroadcastText else "关闭"))
        AlertDialog.Builder(this).setTitle("播放状态").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showStorageDialog() {
        val root = library.rootDir
        val importRoot = library.importDir
        val message = ("歌曲目录：" + root.getAbsolutePath()
                + "\n可用空间：" + formatSize(root.getFreeSpace())
                + "\n导入目录：" + importRoot.getAbsolutePath()
                + "\n录音目录：" + recordDir().getAbsolutePath()
                + "\n曲库总数：" + library.allSongs().size
                + "\n下载任务：" + downloads.size)
        AlertDialog.Builder(this).setTitle("存储信息").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showNetworkDialog() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val message =
            ("网络状态：" + (if (info != null && info.isConnected()) "已连接" else "未连接")
                    + "\n网络类型：" + (if (info == null) "未知" else info.getTypeName())
                    + "\n曲库源：" + prefs()!!.getString(KEY_CATALOG_URL, "未设置")
                    + "\n本地曲库：" + library.allSongs().size + " 首")
        AlertDialog.Builder(this)
            .setTitle("网络状态")
            .setMessage(message)
            .setPositiveButton(
                "同步曲库",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> fetchCatalog() })
            .setNegativeButton("关闭", null)
            .create().showForTv()
    }


    private fun showAppCenter() {
        setPage("应用")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(320), -1))
        left.addView(sectionTitle("应用工具"))
        left.addView(button("网络设置", View.OnClickListener { v: View? -> showNetworkDialog() }))
        left.addView(button("屏幕设置", View.OnClickListener { v: View? -> showScreenDialog() }))
        left.addView(
            button(
                "曲库维护",
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )
        left.addView(button("存储信息", View.OnClickListener { v: View? -> showStorageDialog() }))
        left.addView(button("配置备份", View.OnClickListener { v: View? -> showBackupTools() }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("本地工具"))
        val tools = arrayOf<String?>(
            "点歌台",
            "语种点歌",
            "字数点歌",
            "歌星分类",
            "下载管理",
            "U盘加歌",
            "用户影片",
            "灯光迪斯科",
            "手机点歌",
            "录音评分"
        )
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        right.addView(grid)
        for (tool in tools) {
            addTile(
                grid,
                tool,
                "打开",
                View.OnClickListener { v: View? ->
                    openTool(
                        (v as TextView).getText().toString().split("\n".toRegex())
                            .dropLastWhile { it.isEmpty() }.toTypedArray()[0])
                })
        }
        right.addView(sectionTitle("本地 APK / 升级包"))
        val list = listView()
        val adapter = tvAdapter()
        val apks = localApks()
        for (apk in apks) adapter.add(apk.getName() + "    " + formatSize(apk.length()))
        if (adapter.isEmpty()) adapter.add("暂无 APK，请放入 /sdcard/MaidongKTV/apps 或 U盘/apps")
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: Long ->
            if (pos >= 0 && pos < apks.size) installApk(apks.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showTableBroadcast() {
        setPage("广播")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("包房广播"))
        left.addView(
            button(
                if (tableBroadcastEnabled) "广播功能：开" else "广播功能：关",
                View.OnClickListener { v: View? ->
                    tableBroadcastEnabled = !tableBroadcastEnabled
                    saveState()
                    showTableBroadcast()
                })
        )
        left.addView(
            button(
                "发送当前广播",
                View.OnClickListener { v: View? -> sendTableBroadcast(tableBroadcastText) })
        )
        left.addView(controlRow("时长 -", "时长 +", Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds - 1, 2, 60)
            saveState()
            showTableBroadcast()
        }, Runnable {
            tableBroadcastSeconds = clamp(tableBroadcastSeconds + 1, 2, 60)
            saveState()
            showTableBroadcast()
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("广播内容"))
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(tableBroadcastText)
        input.setHint("输入包房广播内容")
        right.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        right.addView(button("保存并发送", View.OnClickListener { v: View? ->
            tableBroadcastText = input.getText().toString().trim { it <= ' ' }
            if (tableBroadcastText!!.isEmpty()) tableBroadcastText = "欢迎光临"
            saveState()
            sendTableBroadcast(tableBroadcastText)
            showTableBroadcast()
        }), LinearLayout.LayoutParams(dp(180), dp(58)))
        right.addView(label("显示时长：" + tableBroadcastSeconds + " 秒", 20, Color.WHITE))
        right.addView(sectionTitle("广播历史"))
        val list = listView()
        val adapter = tvAdapter()
        if (store.broadcastHistory.isEmpty()) adapter.add("暂无广播记录")
        else adapter.addAll(store.broadcastHistory)
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showBackupTools() {
        setPage("备份")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("配置备份"))
        left.addView(button("备份当前配置", View.OnClickListener { v: View? -> backupConfig() }))
        left.addView(button("从备份恢复", View.OnClickListener { v: View? -> restoreConfig() }))
        left.addView(
            button(
                "打开曲库维护",
                View.OnClickListener { v: View? -> showLibraryMaintenance() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("备份信息"))
        val backupDir = backupDir()
        right.addView(label("备份目录：" + backupDir.getAbsolutePath(), 20, Color.LTGRAY))
        val list = listView()
        val adapter = tvAdapter()
        val files = backupDir.listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add("暂无备份")
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showLibraryMaintenance() {
        setPage("维护")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(340), -1))
        left.addView(sectionTitle("曲库维护"))
        left.addView(button("重扫本地曲库", View.OnClickListener { v: View? ->
            refreshLibrary(
                Runnable { showLibraryMaintenance() })
        }))
        left.addView(button("同步网络曲库", View.OnClickListener { v: View? -> fetchCatalog() }))
        left.addView(button("清除网络缓存", View.OnClickListener { v: View? ->
            val deleted = library.clearRemoteCache()
            refreshLibrary(Runnable {
                toast(if (deleted) "网络曲库缓存已清除" else "没有可清除的网络缓存")
                showLibraryMaintenance()
            })
        }))
        left.addView(button("清空下载任务", View.OnClickListener { v: View? ->
            downloads.clear()
            showLibraryMaintenance()
        }))
        left.addView(button("清理失效已点", View.OnClickListener { v: View? ->
            removeMissingQueueItems()
            showLibraryMaintenance()
        }))
        left.addView(button("恢复隐藏歌曲", View.OnClickListener { v: View? ->
            store.hiddenSongIds.clear()
            saveState()
            refreshLibrary(Runnable {
                toast("已恢复隐藏歌曲")
                showLibraryMaintenance()
            })
        }))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("曲库概览"))
        right.addView(label("总曲库：" + library.allSongs().size + " 首", 22, Color.WHITE))
        right.addView(label("已点：" + orderQueue!!.size + " 首", 20, Color.LTGRAY))
        right.addView(label("已唱：" + sangHistory.size + " 首", 20, Color.LTGRAY))
        right.addView(label("下载任务：" + downloads.size, 20, Color.LTGRAY))
        right.addView(label("隐藏歌曲：" + store.hiddenSongIds.size + " 首", 20, Color.LTGRAY))
        right.addView(label("本地目录：" + library.rootDir.getAbsolutePath(), 18, Color.LTGRAY))
        right.addView(label("导入目录：" + library.importDir.getAbsolutePath(), 18, Color.LTGRAY))
    }

    private fun showMobileRemoteDialog() {
        // 自愈:服务没在跑就先尝试启动(可能因网络切换/瞬时问题停止过)。
        if (!remoteServer.isRunning()) startRemoteServer()
        val url = remoteUrl()
        if (url.isBlank()) {
            val ip = localIp()
            val reason = remoteServer.error() ?: "请连接 Wi-Fi 或有线局域网后重试"
            val detected = when {
                ip.isEmpty() -> "未检测到局域网 IP"
                ip == "127.0.0.1" || ip.startsWith("169.254") -> "检测到不可用 IP：$ip"
                else -> "检测到 IP：$ip"
            }
            AlertDialog.Builder(this)
                .setTitle("手机点歌")
                .setMessage("$reason\n$detected")
                .setNegativeButton("关闭", null)
                .setPositiveButton("重新检测") { _, _ -> restartRemoteServer() }
                .create().showForTv(DialogInterface.BUTTON_POSITIVE)
            return
        }
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setGravity(Gravity.CENTER_HORIZONTAL)
        box.setPadding(dp(24), dp(18), dp(24), dp(12))
        val qr = ImageView(this)
        qr.setBackgroundColor(Color.WHITE)
        qr.setImageBitmap(qrBitmap(url, dp(260)))
        box.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
        val tip = label("手机连接同一局域网后扫码点歌", 18, Color.WHITE)
        tip.gravity = Gravity.CENTER
        box.addView(tip, LinearLayout.LayoutParams(dp(380), dp(54)))
        // 显示当前服务的 ip:端口 地址(去掉路径,便于对照路由器/局域网排查)。
        val serverHost = remoteServer.address().orEmpty()
        val serverPort = remoteServer.port()
        if (serverHost.isNotEmpty() && serverPort > 0) {
            val addressLine = label("$serverHost:$serverPort", 16, GOLD)
            addressLine.gravity = Gravity.CENTER
            box.addView(addressLine, LinearLayout.LayoutParams(dp(380), dp(34)))
        }
        // 提示:扫码连上后播放界面右上角二维码会自动隐藏。
        val hint = label("扫码点歌后自动隐藏播放界面二维码", 14, Color.rgb(193, 193, 193))
        hint.gravity = Gravity.CENTER
        box.addView(hint, LinearLayout.LayoutParams(dp(420), dp(30)))
        AlertDialog.Builder(this)
            .setTitle("扫码点歌")
            .setView(box)
            .setPositiveButton("确定", null)
            .create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun restartRemoteServer() {
        remoteServer.stop()
        startRemoteServer()
        updateMobileQrOverlay()
        updateFullScreenQrOverlay()
        main.postDelayed({ showMobileRemoteDialog() }, 180)
    }

    private fun updateMobileQrOverlay() {
        val root = findViewById<FrameLayout?>(R.id.layout_root) ?: return
        mobileQrOverlay?.let { root.removeView(it) }
        mobileQrOverlay = null
        if (prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) != true) return
        val url = remoteUrl()
        if (url.isBlank()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(4))
            setBackgroundColor(Color.argb(205, 255, 255, 255))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, dp(112)))
                contentDescription = "手机点歌二维码"
            }, LinearLayout.LayoutParams(dp(116), dp(116)))
            addView(TextView(this@MainActivity).apply {
                text = "手机点歌"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
            }, LinearLayout.LayoutParams(-1, dp(24)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(dp(128), dp(152), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(20)
            bottomMargin = dp(76)
        }
        root.addView(box, params)
        mobileQrOverlay = box
    }

    /** 全屏播放时固定显示在右上角的小型手机点歌二维码。 */
    private fun updateFullScreenQrOverlay() {
        val container = fullScreenContainer ?: return
        fullScreenQrOverlay?.let { container.removeView(it) }
        fullScreenQrOverlay = null
        if (!isFullScreen) return
        val url = remoteUrl()
        if (url.isBlank()) return
        val qrSize = dp(if (compactUi) 68 else 84)
        val totalWidth = dp(if (compactUi) 78 else 96)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(3))
            setBackgroundColor(Color.argb(218, 255, 255, 255))
            elevation = dp(12).toFloat()
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrBitmap(url, qrSize))
                contentDescription = "扫码手机点歌"
            }, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(TextView(this@MainActivity).apply {
                text = "扫码点歌"
                textSize = if (compactUi) 9f else 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(59, 18, 92))
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, dp(if (compactUi) 16 else 18)))
            setOnClickListener { showMobileRemoteDialog() }
        }
        val params = FrameLayout.LayoutParams(totalWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = dp(8)
            rightMargin = dp(10)
        }
        container.addView(box, params)
        box.bringToFront()
        fullScreenQrOverlay = box
    }

    private fun showMobileRemoteSettings() {
        val enabled = prefs()?.getBoolean(KEY_SHOW_MOBILE_QR, false) == true
        AlertDialog.Builder(this)
            .setTitle("手机点歌")
            .setSingleChoiceItems(arrayOf("仅在菜单中显示二维码", "播放页右下角显示二维码"), if (enabled) 1 else 0) { dialog, which ->
                prefs()?.edit()?.putBoolean(KEY_SHOW_MOBILE_QR, which == 1)?.apply()
                updateMobileQrOverlay()
                dialog.dismiss()
            }
            .setNeutralButton("查看二维码") { _, _ -> showMobileRemoteDialog() }
            .setNegativeButton("关闭", null)
            .create().showForTv()
    }

    private fun startRemoteServer() {
        val address = localIp().takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        remoteServer.start(address, object : LocalRemoteServer.Callback {
            override fun handle(
                method: String,
                path: String,
                query: Map<String, String>,
                body: String
            ): LocalRemoteServer.ApiResponse = remoteApi(method, path, query, body)

            override fun statusJson(): String {
                try {
                    val json = JSONObject()
                    val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
                    json.put("ok", true)
                    json.put("current", if (now == null) "" else now.displayTitle())
                    json.put("queueSize", orderQueue.size)
                    json.put("historySize", sangHistory.size)
                    json.put("playing", player?.isPlaying == true)
                    json.put("score", scoreSummary())
                    json.put("vocal", if (originalVocal) "原唱" else "伴唱")
                    json.put("vocalChannelMode", vocalChannelMode)
                    json.put("singMode", singMode)
                    json.put("remoteUrl", remoteUrl())
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun catalogJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun queueJson(): String {
                try {
                    val queue = JSONArray()
                    for (song in orderQueue!!) queue.put(songJson(song))
                    val history = JSONArray()
                    for (song in sangHistory) history.put(songJson(song))
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("queue", queue)
                    json.put("history", history)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun rankJson(): String {
                try {
                    val ranked: MutableList<Song?> = ArrayList<Song?>()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song)) ranked.add(song)
                    }
                    Collections.sort<Song?>(
                        ranked,
                        Comparator { a: Song?, b: Song? ->
                            Integer.compare(
                                b!!.playCount,
                                a!!.playCount
                            )
                        })
                    val array = JSONArray()
                    var i = 0
                    while (i < ranked.size && i < 80) {
                        array.put(songJson(ranked.get(i)!!))
                        i++
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun filterJson(type: String?, value: String?): String {
                try {
                    val t = if (type == null) "" else type.trim { it <= ' ' }
                    val v = if (value == null) "" else value.trim { it <= ' ' }
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val compare = if ("language" == t) song.language else song.category
                        if (v.isEmpty() || v == compare) array.put(songJson(song))
                        if (array.length() >= 120) break
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun favoritesJson(): String {
                try {
                    val array = JSONArray()
                    for (song in library.allSongs()) {
                        if (isVisibleSong(song) && store.isFavorite(song)) array.put(songJson(song))
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun search(query: String?): String {
                try {
                    val array = JSONArray()
                    val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                    var count = 0
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text =
                            (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
                        if (q.isEmpty() || text.contains(q)) {
                            array.put(songJson(song))
                            if (++count >= 80) break
                        }
                    }
                    val json = JSONObject()
                    json.put("ok", true)
                    json.put("songs", array)
                    return json.toString()
                } catch (e: Exception) {
                    return errorJson(e)
                }
            }

            override fun order(query: String?): String {
                val q = if (query == null) "" else query.lowercase().trim { it <= ' ' }
                if (q.isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                for (song in library.allSongs()) {
                    if (store.hiddenSongIds.contains(stableId(song))) continue
                    val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                    if (text.contains(q)) {
                        main.post(Runnable { handleSong(song) })
                        return "{\"ok\":true,\"message\":\"ordered\"}"
                    }
                }
                return "{\"ok\":false,\"error\":\"song not found\"}"
            }

            override fun command(action: String?, params: Map<String, String>): String {
                val a = if (action == null) "" else action
                if ("next" == a) main.post(Runnable { playNext() })
                else if ("pause" == a) main.post(Runnable { togglePlay() })
                else if ("replay" == a) main.post(Runnable { replay() })
                else if ("vocal" == a) {
                    val mode = params.get("mode")
                    main.post(Runnable {
                        originalVocal = "original" == mode || "原唱" == mode
                        applyPlaybackMode()
                        saveState()
                        status!!.setText(if (originalVocal) "远程切换原唱" else "远程切换伴唱")
                    })
                } else if ("singmode" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setSingMode(mode.trim { it <= ' ' }) })
                } else if ("channel" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable { setVocalChannelMode(mode.trim { it <= ' ' }) })
                } else if ("light" == a) {
                    val mode = params.get("mode")
                    if (mode == null || mode.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing mode\"}"
                    main.post(Runnable {
                        lightMode = mode
                        saveState()
                        status!!.setText("远程灯光：" + mode)
                    })
                } else if ("marquee" == a) {
                    val text = params.get("text")
                    main.post(Runnable {
                        if (text != null && !text.trim { it <= ' ' }.isEmpty()) marqueeText =
                            text.trim { it <= ' ' }
                        marqueeEnabled = true
                        saveState()
                        status!!.setText("远程跑马灯：" + marqueeText)
                    })
                } else if ("broadcast" == a) {
                    val text = params.get("text")
                    if (text == null || text.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing text\"}"
                    main.post(Runnable { sendTableBroadcast(text.trim { it <= ' ' }) })
                } else if ("status" == a) {
                    return statusJson()
                } else if ("cancel_downloads" == a) {
                    main.post(Runnable { cancelAllDownloads() })
                } else if ("clear_queue" == a) {
                    main.post(Runnable {
                        orderQueue!!.clear()
                        saveState()
                        renderQueue()
                        status!!.setText("远程清空已点")
                    })
                } else if ("remove_queue" == a) {
                    val index = params.get("index")
                    try {
                        val pos = index!!.toInt()
                        main.post(Runnable {
                            if (pos >= 0 && pos < orderQueue!!.size) {
                                val removed = orderQueue.removeAt(pos)
                                saveState()
                                renderQueue()
                                status!!.setText("远程删除已点：" + removed.title)
                            }
                        })
                    } catch (e: Exception) {
                        return "{\"ok\":false,\"error\":\"bad index\"}"
                    }
                } else if ("favorite" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        if (!isVisibleSong(song)) continue
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable {
                                store.toggleFavorite(song)
                                saveState()
                                status!!.setText("远程收藏：" + song.title)
                            })
                            return "{\"ok\":true,\"message\":\"favorite toggled\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else if ("hide" == a) {
                    val q = params.get("q")
                    if (q == null || q.trim { it <= ' ' }
                            .isEmpty()) return "{\"ok\":false,\"error\":\"missing q\"}"
                    for (song in library.allSongs()) {
                        val text = (song.title + " " + song.singer + " " + song.pinyin).lowercase()
                        if (text.contains(q.lowercase())) {
                            main.post(Runnable { removeSong(song) })
                            return "{\"ok\":true,\"message\":\"hidden\"}"
                        }
                    }
                    return "{\"ok\":false,\"error\":\"song not found\"}"
                } else return "{\"ok\":false,\"error\":\"unknown command\"}"
                return "{\"ok\":true}"
            }
        })
    }

    /** 复刻原版「时光KTV」的 whoami 心跳,维持设备在歌曲源后台的"合法/活跃"状态。 */
    private fun sendDeviceHeartbeat(boot: Boolean) {
        Thread({
            try {
                val now = System.currentTimeMillis()
                val sb = StringBuilder("http://hbeat.ktvsky.com/whoami?_src=600007&_src_at=600001")
                sb.append("&_m=").append(java.net.URLEncoder.encode(Build.MODEL, "UTF-8"))
                sb.append("&_mac=").append(LEGACY_DEVICE_ID)
                sb.append("&_vc=4&_vn=4.1.3.03281430")
                sb.append("&_pkn=").append(LEGACY_PACKAGE)
                sb.append("&_player=0&_boot=").append(if (boot) 1 else 0)
                sb.append("&_ts=").append(now)
                sb.append("&_api=").append(Build.VERSION.SDK_INT)
                sb.append("&ktv_id=").append(LEGACY_KTV_ID)
                sb.append("&androidid=").append(LEGACY_DEVICE_ID)
                sb.append("&_manuf=").append(java.net.URLEncoder.encode(Build.MANUFACTURER, "UTF-8"))
                sb.append("&_cid=A_").append(LEGACY_DEVICE_ID)
                sb.append("&d_name=").append(java.net.URLEncoder.encode(Build.PRODUCT, "UTF-8"))
                sb.append("&d_device=").append(java.net.URLEncoder.encode(Build.DEVICE, "UTF-8"))
                sb.append("&d_board=").append(java.net.URLEncoder.encode(Build.BOARD, "UTF-8"))
                sb.append("&d_hardware=").append(java.net.URLEncoder.encode(Build.HARDWARE, "UTF-8"))
                sb.append("&d_fingerprint=").append(java.net.URLEncoder.encode(Build.FINGERPRINT, "UTF-8"))
                sb.append("&d_incremental=").append(java.net.URLEncoder.encode(Build.VERSION.INCREMENTAL, "UTF-8"))
                sb.append("&d_release=").append(java.net.URLEncoder.encode(Build.VERSION.RELEASE, "UTF-8"))
                sb.append("&d_utc=").append(now).append("&d_user=dpi")
                val conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(ByteArray(0)) }
                    Log.i(TAG, "whoami heartbeat HTTP ${conn.responseCode}")
                } finally {
                    conn.disconnect()
                }
            } catch (ignored: Exception) {
            }
        }, "ktv-heartbeat").start()
    }

    /** 远程日志: 把设备 IP 与设备标识同步给日志客户端(GlogCenter 的 serverip/user/traceid)。 */
    private fun refreshLogDeviceContext() {
        MiliLog.updateDeviceContext(ip = localIp(), deviceId = LEGACY_DEVICE_ID)
    }

    /** 远程日志: App 启动时上报设备信息与网络信息(异步, 尽力而为)。 */
    private fun reportDeviceBootInfo() {
        Thread({
            try {
                val sb = StringBuilder()
                sb.append("BOOT pkg=").append(packageName)
                sb.append(" ver=").append(BuildConfig.VERSION_NAME)
                sb.append(" device=").append(Build.BRAND).append(' ').append(Build.MODEL)
                sb.append(" android=").append(Build.VERSION.RELEASE).append("(api").append(Build.VERSION.SDK_INT).append(')')
                sb.append(" abi=").append(Build.SUPPORTED_ABIS?.joinToString(","))
                // 歌曲源设备身份(与抓包请求一致)
                sb.append(" mac=").append(LEGACY_DEVICE_ID).append(" ktv=").append(LEGACY_KTV_ID)
                sb.append(" ip=").append(localIp())
                // 网关 / DNS / WiFi 基础信息
                try {
                    val wm = getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                    val info = wm.connectionInfo
                    if (info != null) {
                        val ssid = info.ssid?.replace("\"", "")
                        sb.append(" wifi=").append(ssid)
                        sb.append(" rssi=").append(info.rssi)
                    }
                } catch (ignored: Exception) {
                }
                try {
                    val dm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val network = dm.activeNetwork
                    if (network != null) {
                        sb.append(" net=").append(dm.getNetworkCapabilities(network)?.toString().orEmpty())
                    }
                } catch (ignored: Exception) {
                }
                sb.append(" bootTs=").append(System.currentTimeMillis())
                MiliLog.notice(sb.toString())
            } catch (ignored: Exception) {
            }
        }, "ktv-boot-log").start()
    }

    private fun remoteApi(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String
    ): LocalRemoteServer.ApiResponse {
        return try {
            when {
                method == "GET" && path == "/api/v1/state" -> remoteOk(remoteStateJson())
                method == "GET" && path == "/api/v1/songs" -> remoteOk(remoteSearchJson(query))
                method == "GET" && path == "/api/v1/hot" -> remoteOk(remoteHotJson(query))
                method == "GET" && path == "/api/v1/queue" -> remoteOk(remoteQueueJson())
                method == "POST" && path == "/api/v1/queue" -> remoteOrder(JSONObject(body))
                method == "POST" && path == "/api/v1/player/actions" -> remotePlayerAction(JSONObject(body))
                method == "PATCH" && path == "/api/v1/player/audio" -> remoteAudio(JSONObject(body))
                path.startsWith("/api/v1/queue/") && method in arrayOf("PATCH", "DELETE") -> {
                    remoteQueueMutation(method, URLDecoder.decode(path.substringAfterLast('/'), "UTF-8"))
                }
                else -> remoteError(404, "NOT_FOUND", "接口不存在")
            }
        } catch (e: Exception) {
            remoteError(400, "BAD_REQUEST", e.message ?: "请求参数错误")
        }
    }

    private fun remoteStateJson(): JSONObject {
        val now = currentSong ?: orderQueue?.firstOrNull()
        val next = orderQueue?.firstOrNull { now == null || stableId(it) != stableId(now) }
        return JSONObject().apply {
            put("current", now?.let { songJson(it) } ?: JSONObject.NULL)
            put("next", next?.let { songJson(it) } ?: JSONObject.NULL)
            put("queueSize", orderQueue?.size ?: 0)
            put("playing", player?.isPlaying == true)
            put("vocalMode", if (originalVocal) "original" else "accompany")
            put("musicVolume", musicVolume)
            put("micVolume", micVolume)
        }
    }

    private fun remoteSearchJson(query: Map<String, String>): JSONObject {
        val keyword = query["q"].orEmpty().trim()
        val type = query["type"].orEmpty().lowercase(Locale.ROOT).let { if (it == "song") "song" else "singer" }
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (page - 1) * pageSize
        val songs = if (library.muse.isAvailable() && keyword.isNotBlank()) {
            if (type == "singer") library.muse.searchSongsBySingerKeyword(keyword, offset, pageSize + 1)
            else library.muse.searchSongs(keyword, offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter { song ->
                isVisibleSong(song) && keyword.isNotBlank() && if (type == "singer") {
                    song.singer.orEmpty().contains(keyword, true)
                } else {
                    (song.title + " " + song.pinyin).contains(keyword, true)
                }
            }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val pageSongs = songs.take(pageSize)
        val array = JSONArray()
        pageSongs.forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("type", type).put("hasMore", hasMore)
    }

    private fun remoteHotJson(query: Map<String, String>): JSONObject {
        val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageSize = (query["pageSize"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val offset = (page - 1) * pageSize
        val songs = if (library.muse.isAvailable()) {
            library.muse.hotSongs(offset, pageSize + 1)
        } else {
            library.allSongs().asSequence().filter(::isVisibleSong)
                .sortedByDescending { it.playCount }.drop(offset).take(pageSize + 1).toMutableList()
        }
        val hasMore = songs.size > pageSize
        val array = JSONArray()
        songs.take(pageSize).forEach { song ->
            remoteSongCache[stableId(song)] = song
            array.put(songJson(song))
        }
        while (remoteSongCache.size > 500) remoteSongCache.remove(remoteSongCache.keys.first())
        return JSONObject().put("songs", array).put("page", page).put("pageSize", pageSize)
            .put("hasPrevious", page > 1).put("hasMore", hasMore)
    }

    private fun remoteQueueJson(): JSONObject {
        val array = JSONArray()
        orderQueue?.toList()?.forEach { array.put(songJson(it)) }
        return JSONObject().put("queue", array)
    }

    private fun remoteOrder(json: JSONObject): LocalRemoteServer.ApiResponse {
        val id = json.optString("songId").trim()
        if (id.isEmpty()) return remoteError(400, "MISSING_SONG_ID", "缺少歌曲 ID")
        val song = remoteSongCache[id] ?: library.allSongs().firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "SONG_NOT_FOUND", "歌曲不存在或搜索结果已过期")
        if (orderQueue?.any { stableId(it) == id } == true) {
            return remoteError(409, "ALREADY_QUEUED", "歌曲已在已点列表中")
        }
        main.post {
            addToQueue(song)
            remoteStateVersion++
            saveState()
        }
        return remoteOk(JSONObject().put("message", "点歌成功"), 201)
    }

    private fun remoteQueueMutation(method: String, id: String): LocalRemoteServer.ApiResponse {
        val song = orderQueue?.firstOrNull { stableId(it) == id }
            ?: return remoteError(404, "QUEUE_ITEM_NOT_FOUND", "已点歌曲不存在")
        if (stableId(currentSong ?: song) == id && currentSong != null) {
            return remoteError(409, "CURRENT_SONG", "正在播放的歌曲不能修改")
        }
        main.post {
            if (method == "PATCH") moveQueuedSongToNext(song) else deleteQueuedSong(song)
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", if (method == "PATCH") "已置顶" else "已删除"))
    }

    private fun remotePlayerAction(json: JSONObject): LocalRemoteServer.ApiResponse {
        val action = json.optString("action")
        if (action !in arrayOf("pause", "resume", "next", "replay")) {
            return remoteError(400, "BAD_ACTION", "不支持的播放操作")
        }
        main.post {
            when (action) {
                "pause" -> if (player?.isPlaying == true) togglePlay()
                "resume" -> if (player?.isPlaying != true) togglePlay()
                "next" -> playNext()
                "replay" -> replay()
            }
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", "操作已执行"))
    }

    private fun remoteAudio(json: JSONObject): LocalRemoteServer.ApiResponse {
        if (!json.has("vocalMode") && !json.has("musicVolume") && !json.has("micVolume")) {
            return remoteError(400, "MISSING_AUDIO_VALUE", "缺少音频参数")
        }
        val vocal = json.optString("vocalMode", "")
        if (vocal.isNotEmpty() && vocal !in arrayOf("original", "accompany")) {
            return remoteError(400, "BAD_VOCAL_MODE", "原伴唱参数无效")
        }
        val music = if (json.has("musicVolume")) json.optInt("musicVolume", -1) else null
        val mic = if (json.has("micVolume")) json.optInt("micVolume", -1) else null
        if ((music != null && music !in 0..100) || (mic != null && mic !in 0..100)) {
            return remoteError(400, "BAD_VOLUME", "音量必须在 0 到 100 之间")
        }
        main.post {
            if (vocal.isNotEmpty()) originalVocal = vocal == "original"
            music?.let { musicVolume = it }
            mic?.let { micVolume = it }
            applyPlaybackMode()
            playerController?.setVocalMode(originalVocal)
            saveState()
            remoteStateVersion++
        }
        return remoteOk(JSONObject().put("message", "音频设置已更新"))
    }

    private fun remoteOk(data: JSONObject, statusCode: Int = 200): LocalRemoteServer.ApiResponse {
        data.put("ok", true).put("stateVersion", remoteStateVersion)
        return LocalRemoteServer.ApiResponse(statusCode, data.toString())
    }

    private fun remoteError(statusCode: Int, code: String, message: String): LocalRemoteServer.ApiResponse =
        LocalRemoteServer.ApiResponse(statusCode, JSONObject().put("ok", false).put("code", code)
            .put("error", message).put("stateVersion", remoteStateVersion).toString())

    private fun errorJson(e: Exception): String {
        return "{\"ok\":false,\"error\":\"" + e.message.toString().replace("\"", "'") + "\"}"
    }

    @Throws(Exception::class)
    private fun songJson(song: Song): JSONObject {
        val item = JSONObject()
        item.put("id", stableId(song))
        item.put("title", song.title)
        item.put("singer", song.singer)
        item.put("category", song.category)
        item.put("language", song.language)
        item.put("remote", song.remote)
        item.put("favorite", store.isFavorite(song))
        item.put("playCount", song.playCount)
        item.put("hasOriginal", hasUrl(song.originalUrl) || hasUrl(song.originalPath))
        item.put("hasAccompany", hasUrl(song.accompanyUrl) || hasUrl(song.accompanyPath))
        item.put("hasLyric", hasUrl(song.lyricUrl) || hasUrl(song.lyricPath))
        return item
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) {
            val fallback = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            fallback.eraseColor(Color.WHITE)
            return fallback
        }
    }

    private fun remoteUrl(): String {
        if (!remoteServer.isRunning() || remoteServer.port() <= 0) return ""
        val overrideHost = Settings.Global.getString(contentResolver, "maidong_remote_host")
            ?.takeIf { LocalRemoteServer.isPrivateIpv4(it) }
        val host = overrideHost ?: remoteServer.address() ?: return ""
        return "http://$host:${remoteServer.port()}/mobile"
    }

    /**
     * 选择"扫码点歌"应公布的局域网 IP。手机(如 S10)常有蜂窝(rmnet/10.x)、
     * 抓包代理(VPN tun)等多接口,若随机选到这些会被手机扫描后无法访问,
     * 导致扫码点歌连不上。这里按可靠性排序:
     *  1) 首选 up、非虚拟接口上的站点本地 IPv4(优先 wlan/eth 网卡);
     *  2) 退而求其次选任意非虚拟接口的私有 IPv4;
     *  3) 再选任意私有 IPv4(兼容个别厂商网卡命名);
     *  4) 都找不到返回空串(不返回 127.0.0.1,避免生成只能本机访问的无效二维码)。
     */
    private fun localIp(): String {
        var firstPrivate = ""
        var firstNonVirtual = ""
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = network.name.orEmpty().lowercase(Locale.ROOT)
                val virtual = network.isVirtual || name.startsWith("tun") || name.startsWith("ppp") ||
                    name.startsWith("wg") || name.startsWith("rmnet") || name.startsWith("radio") ||
                    name.startsWith("ccmni") || name.startsWith("pdp") || name.contains("vpn")
                val up = network.isUp && !network.isLoopback
                val addresses = network.getInetAddresses()
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.indexOf(':') >= 0 || !LocalRemoteServer.isPrivateIpv4(host)) continue
                    if (firstPrivate.isEmpty()) firstPrivate = host
                    if (up && !virtual && firstNonVirtual.isEmpty()) firstNonVirtual = host
                    if (up && !virtual && (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en"))) {
                        return host
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return firstNonVirtual.ifEmpty { firstPrivate }
    }

    private fun showScoreDialog() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.addView(label("当前：" + (if (scoreEnabled) "已开启" else "已关闭"), 20, Color.WHITE))
        box.addView(label("最近：" + scoreSummary(), 20, Color.LTGRAY))
        box.addView(sectionTitle("评分记录"))
        val list = listView()
        val adapter = tvAdapter()
        if (store.scoreHistory.isEmpty()) adapter.add("暂无评分记录")
        else adapter.addAll(store.scoreHistory)
        list.setAdapter(adapter)
        box.addView(list, LinearLayout.LayoutParams(-1, dp(260)))
        AlertDialog.Builder(this)
            .setTitle("演唱评分")
            .setView(box)
            .setPositiveButton(
                if (scoreEnabled) "关闭评分" else "开启评分",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    scoreEnabled = !scoreEnabled
                    saveState()
                })
            .setNegativeButton("确定", null)
            .create().showForTv()
    }

    private fun showRecordings() {
        setPage("录音")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(280), -1))
        left.addView(sectionTitle("录音操作"))
        left.addView(
            button(
                if (recorder == null) "开始录音" else "停止录音",
                View.OnClickListener { v: View? -> toggleRecording() })
        )
        left.addView(button("刷新列表", View.OnClickListener { v: View? -> showRecordings() }))
        left.addView(
            button(
                if (recordEnabled) "录音功能：开" else "录音功能：关",
                View.OnClickListener { v: View? ->
                    recordEnabled = !recordEnabled
                    if (!recordEnabled) stopRecording(true)
                    saveState()
                    showRecordings()
                })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("录音文件"))
        val list = listView()
        val adapter = tvAdapter()
        val files = recordDir().listFiles()
        if (files != null) {
            val values: MutableList<File> = ArrayList<File>()
            for (file in files) if (file.isFile()) values.add(file)
            Collections.sort<File?>(
                values,
                Comparator { a: File?, b: File? ->
                    b!!.lastModified().compareTo(a!!.lastModified())
                })
            for (file in values) adapter.add(file.getName() + "    " + formatSize(file.length()))
        }
        if (adapter.isEmpty()) adapter.add("暂无录音")
        list.setAdapter(adapter)
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showMarqueeSettings() {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(marqueeText)
        input.setHint("跑马灯文字")
        box.addView(label("当前：" + (if (marqueeEnabled) "已开启" else "已关闭"), 20, Color.WHITE))
        box.addView(input, LinearLayout.LayoutParams(-1, dp(58)))
        AlertDialog.Builder(this)
            .setTitle("跑马灯设置")
            .setView(box)
            .setPositiveButton(
                "保存",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeText = input.getText().toString().trim { it <= ' ' }
                    if (marqueeText!!.isEmpty()) marqueeText = "欢迎使用麦动"
                    saveState()
                    toast("跑马灯已保存")
                })
            .setNegativeButton(
                if (marqueeEnabled) "关闭" else "开启",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    marqueeEnabled = !marqueeEnabled
                    saveState()
                })
            .create().showForTv()
    }

    private fun showDisco() {
        setPage("迪斯科")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("灯光 / 迪斯科"))
        val modes =
            arrayOf<String?>("自动", "开房灯", "关房灯", "暂停灯", "静音灯", "热舞", "柔和", "全关")
        val grid = GridLayout(this)
        grid.setColumnCount(4)
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        for (mode in modes) {
            addTile(
                grid,
                mode,
                if (mode == lightMode) "当前模式" else "切换灯效",
                View.OnClickListener { v: View? ->
                    lightMode = (v as TextView).getText().toString().split("\n".toRegex())
                        .dropLastWhile { it.isEmpty() }.toTypedArray()[0]
                    if ("热舞" == lightMode) atmosphere = "派对"
                    if ("柔和" == lightMode) atmosphere = "柔和"
                    saveState()
                    showDisco()
                })
        }
        panel.addView(
            label(
                "本地版记录灯效状态；接入实际灯控盒时可把这些模式映射到串口、HTTP 或红外命令。",
                18,
                Color.LTGRAY
            )
        )
    }

    private fun showLocalMovies() {
        setPage("本地影片")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("影片操作"))
        left.addView(button("扫描影片目录", View.OnClickListener { v: View? -> showLocalMovies() }))
        left.addView(label("目录：/sdcard/MaidongKTV/movies 或 U盘/movies", 18, Color.LTGRAY))

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("用户 MV / 本地影片"))
        val list = listView()
        val adapter = tvAdapter()
        val movies = localMovies()
        for (file in movies) adapter.add(file.getName() + "    " + formatSize(file.length()))
        if (adapter.isEmpty()) adapter.add("暂无影片")
        list.setAdapter(adapter)
        list.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            if (pos >= 0 && pos < movies.size) playMovie(movies.get(pos))
        })
        right.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun networkSummary(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val info = if (cm == null) null else cm.getActiveNetworkInfo()
        val wifi = getApplicationContext().getSystemService(WIFI_SERVICE) as WifiManager?
        val wifiState =
            if (wifi == null) "未知" else (if (wifi.isWifiEnabled()) "已开启" else "已关闭")
        return ("连接：" + (if (info != null && info.isConnected()) "已连接" else "未连接")
                + "\n类型：" + (if (info == null) "未知" else info.getTypeName())
                + "\nWiFi：" + wifiState
                + "\nIP：" + localIp())
    }

    private fun testCatalogSource() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, "正在测试曲库源...")
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                main.post(Runnable { busy(false, "曲库源可用：" + array.length() + " 首") })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, "曲库源不可用：" + e.message) })
            }
        })
    }

    private fun openTool(tool: String?) {
        if ("点歌台" == tool) showSongs("全部")
        else if ("语种点歌" == tool) showLanguages()
        else if ("字数点歌" == tool) showWordCounts()
        else if ("歌星分类" == tool) showSingers()
        else if ("下载管理" == tool) showDownloads()
        else if ("U盘加歌" == tool) showUdisk()
        else if ("用户影片" == tool) showLocalMovies()
        else if ("灯光迪斯科" == tool) showDisco()
        else if ("手机点歌" == tool) showMobileRemoteDialog()
        else if ("录音评分" == tool) showScoreDialog()
    }

    private fun sendTableBroadcast(text: String?) {
        if (!tableBroadcastEnabled) {
            toast("包房广播已关闭")
            return
        }
        var value: String? = if (text == null) "" else text.trim { it <= ' ' }
        if (value!!.isEmpty()) value = tableBroadcastText
        if (value == null || value.trim { it <= ' ' }.isEmpty()) value = "欢迎光临"
        tableBroadcastText = value
        marqueeEnabled = true
        marqueeText = value
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        store.broadcastHistory.add(0, stamp + "  " + value)
        while (store.broadcastHistory.size > 100) store.broadcastHistory.removeAt(store.broadcastHistory.size - 1)
        saveState()
        status!!.setText("包房广播：" + value + "（" + tableBroadcastSeconds + "秒）")
        toast("广播已发送")
    }

    private fun applyScreenBrightness() {
        val attrs = getWindow().getAttributes()
        attrs.screenBrightness = screenBrightness / 100f
        getWindow().setAttributes(attrs)
    }

    private fun removeMissingQueueItems() {
        val before = orderQueue!!.size
        for (i in orderQueue.indices.reversed()) {
            val song = orderQueue.get(i)
            val localPath = song.path
            if (!song.remote && (localPath.isNullOrEmpty() || !File(localPath).exists())) {
                orderQueue.removeAt(i)
            }
        }
        saveState()
        toast("已清理：" + (before - orderQueue.size) + " 首")
    }

    private fun toggleRecording() {
        if (!recordEnabled) {
            toast("录音功能已关闭")
            return
        }
        if (recorder == null) startRecording()
        else stopRecording(true)
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf<String>(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        // MediaRecorder 需要独占麦克风: 先暂停 MicActivator 占用。
        micActivator.pause()
        try {
            val dir = recordDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val now = if (orderQueue!!.isEmpty()) null else orderQueue.get(0)
            val name =
                (if (now == null) "ktv" else safeFileName(now.title + "-" + now.singer)) + "-" + stamp + ".m4a"
            currentRecording = File(dir, name)
            recorder = MediaRecorder()
            recorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder!!.setOutputFile(currentRecording!!.getAbsolutePath())
            recorder!!.prepare()
            recorder!!.start()
            toast("开始录音")
            status!!.setText("正在录音：" + currentRecording!!.getName())
            if ("首页" == currentPage) showHome()
        } catch (e: Exception) {
            // 录音启动失败: 释放 recorder 并恢复麦克风输入占用。
            if (recorder != null) stopRecording(false) else micActivator.resume()
            toast("录音启动失败：" + e.message)
        }
    }

    private fun stopRecording(notify: Boolean) {
        if (recorder == null) return
        try {
            recorder!!.stop()
        } catch (ignored: Exception) {
        }
        try {
            recorder!!.release()
        } catch (ignored: Exception) {
        }
        recorder = null
        // 录音结束 → 恢复麦克风输入占用(演唱声音输出通道)。
        micActivator.resume()
        if (notify && currentRecording != null) toast("录音已保存：" + currentRecording!!.getName())
        currentRecording = null
        if ("录音" == currentPage) showRecordings()
        else if ("首页" == currentPage) showHome()
    }

    private fun recordScore(song: Song?) {
        if (!scoreEnabled || song == null) return
        val value = 60 + abs((stableId(song) + System.currentTimeMillis() / 60000L).hashCode() % 40)
        store.lastScore = value
        store.lastScoreSong = song.displayTitle()
        store.scoreHistory.add(0, value.toString() + " 分    " + song.displayTitle())
        while (store.scoreHistory.size > 100) store.scoreHistory.removeAt(store.scoreHistory.size - 1)
    }

    private fun scoreSummary(): String {
        if (store.lastScore <= 0 || store.lastScoreSong == null || store.lastScoreSong.isEmpty()) return "暂无评分"
        return store.lastScore.toString() + " 分  " + store.lastScoreSong
    }

    private fun recordDir(): File {
        return AppPaths.recordsDir
    }

    private fun localMovies(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanMovieDir(
            AppPaths.moviesDir,
            out
        )
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanMovieDir(File(root, "movies"), out)
                scanMovieDir(File(root, "Movies"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                a!!.getName().compareTo(b!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun localApks(): MutableList<File> {
        val out: MutableList<File> = ArrayList<File>()
        scanApkDir(AppPaths.appsDir, out)
        val storage = File("/storage")
        val roots = storage.listFiles()
        if (roots != null) {
            for (root in roots) {
                val name = root.getName()
                if ("emulated" == name || "self" == name) continue
                scanApkDir(File(root, "apps"), out)
                scanApkDir(File(root, "apk"), out)
            }
        }
        Collections.sort<File?>(
            out,
            Comparator { a: File?, b: File? ->
                b!!.getName().compareTo(a!!.getName(), ignoreCase = true)
            })
        return out
    }

    private fun scanApkDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanApkDir(file, out)
            else if (file.getName().lowercase().endsWith(".apk")) out.add(file)
        }
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法打开安装器：" + e.message)
        }
    }

    private fun scanMovieDir(dir: File, out: MutableList<File>) {
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            if (file.isDirectory()) scanMovieDir(file, out)
            else if (isMovie(file.getName())) out.add(file)
        }
    }

    private fun isMovie(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".flv")
    }

    private fun playMovie(file: File) {
        if (player == null) showHome()
        try {
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
                player!!.start()
                status!!.setText("正在播放影片：" + file.getName())
            })
            player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
                status!!.setText("影片播放完成：" + file.getName())
                if (autoNext && !orderQueue!!.isEmpty()) play(orderQueue.get(0))
            })
            player!!.setVideoURI(Uri.fromFile(file))
        } catch (e: Exception) {
            toast("影片播放失败：" + e.message)
        }
    }

    private fun safeFileName(value: String?): String {
        return if (value == null) "ktv" else value.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }

    private fun backupDir(): File {
        return AppPaths.backupDir
    }

    private fun backupConfig() {
        try {
            val dir = backupDir()
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            copyFile(store.stateFile(), File(dir, "state-" + stamp + ".json"))
            if (library.catalogFile().exists()) copyFile(
                library.catalogFile(),
                File(dir, "catalog-" + stamp + ".json")
            )
            toast("配置已备份")
            showBackupTools()
        } catch (e: Exception) {
            toast("备份失败：" + e.message)
        }
    }

    private fun restoreConfig() {
        val dir = backupDir()
        val files = dir.listFiles()
        if (files == null) {
            toast("没有备份文件")
            return
        }
        var newestState: File? = null
        var newestCatalog: File? = null
        for (file in files) {
            if (file.getName().startsWith("state-") && file.getName().endsWith(".json")
                && (newestState == null || file.lastModified() > newestState.lastModified())
            ) newestState = file
            if (file.getName().startsWith("catalog-") && file.getName().endsWith(".json")
                && (newestCatalog == null || file.lastModified() > newestCatalog.lastModified())
            ) newestCatalog = file
        }
        val state = newestState
        val catalog = newestCatalog
        if (state == null && catalog == null) {
            toast("没有可恢复的备份")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage("将恢复最近的 state/catalog 备份，应用内状态会重新加载。")
            .setPositiveButton(
                "恢复",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    try {
                        if (state != null) copyFile(state, store.stateFile())
                        if (catalog != null) copyFile(catalog, library.catalogFile())
                        store.load()
                        loadStateFromStore()
                        applyScreenBrightness()
                        refreshLibrary(Runnable {
                            toast("恢复完成")
                            showHome()
                        })
                    } catch (e: Exception) {
                        toast("恢复失败：" + e.message)
                    }
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    @Throws(Exception::class)
    private fun copyFile(source: File?, target: File) {
        if (source == null || !source.exists()) return
        val parent = target.getParentFile()
        if (parent != null && !parent.exists()) parent.mkdirs()
        BufferedInputStream(FileInputStream(source)).use { `in` ->
            FileOutputStream(target, false).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while ((`in`.read(buffer).also { read = it }) > 0) out.write(buffer, 0, read)
            }
        }
    }

    private fun applySystemMusicVolume() {
        if (audioManager == null) return
        val max = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = max(0, min(max, (musicVolume * max) / 100))
        audioManager!!.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return max(min, min(max, value))
    }

    private fun formatSize(bytes: kotlin.Long): String {
        if (bytes > 1024L * 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f GB",
            bytes / 1024f / 1024f / 1024f
        )
        if (bytes > 1024L * 1024L) return String.format(
            Locale.ROOT,
            "%.1f MB",
            bytes / 1024f / 1024f
        )
        return bytes.toString() + " B"
    }

    private fun populateSingerList(adapter: ArrayAdapter<String?>, group: String?) {
        adapter.clear()
        val names: MutableList<String?> = ArrayList<String?>()
        for (song in library.allSongs()) {
            if (!isVisibleSong(song) || !singerMatchesGroup(song, group)) continue
            val name = singerName(song)
            if (!names.contains(name)) names.add(name)
        }
        Collections.sort<String?>(
            names,
            Comparator { obj: String?, str: String? -> obj!!.compareTo(str!!, ignoreCase = true) })
        if ("热门歌手" == group) {
            Collections.sort<String?>(
                names,
                Comparator { a: String?, b: String? ->
                    Integer.compare(
                        singerPlayCount(b),
                        singerPlayCount(a)
                    )
                })
        }
        adapter.addAll(names)
        adapter.notifyDataSetChanged()
        visibleSongs.clear()
        renderSongs()
        header!!.setText("歌星  " + group + "  " + names.size)
    }

    private fun singerPlayCount(singer: String?): Int {
        var count = 0
        for (song in library.allSongs()) {
            if (isVisibleSong(song) && singerName(song) == singer) count += song.playCount
        }
        return count
    }

    private fun singerMatchesGroup(song: Song, group: String?): Boolean {
        if ("全部歌手" == group) return true
        val text = (((if (song.singer == null) "" else song.singer) + " "
                + (if (song.category == null) "" else song.category) + " "
                + (if (song.language == null) "" else song.language))).lowercase()
        if ("热门歌手" == group) return song.playCount > 0 || !song.remote
        if ("大陆/内地" == group) return text.contains("大陆") || text.contains("内地") || text.contains(
            "国语"
        )
        if ("港台" == group) return text.contains("香港") || text.contains("台湾") || text.contains(
            "港台"
        ) || text.contains("粤语") || text.contains("闽南")
        if ("日韩" == group) return text.contains("日本") || text.contains("韩国") || text.contains(
            "日韩"
        ) || text.contains("日语") || text.contains("韩语")
        if ("欧美" == group) return text.contains("欧美") || text.contains("英语") || text.contains(
            "英文"
        ) || text.contains("外语")
        if ("组合/乐队" == group) return text.contains("组合") || text.contains("乐队") || text.contains(
            "band"
        ) || text.contains("group")
        return !singerMatchesGroup(song, "大陆/内地") && !singerMatchesGroup(
            song,
            "港台"
        ) && !singerMatchesGroup(song, "日韩") && !singerMatchesGroup(
            song,
            "欧美"
        ) && !singerMatchesGroup(song, "组合/乐队")
    }

    private fun filterLanguage(language: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val value = song.language?.takeIf(String::isNotEmpty) ?: "其他"
            if (isVisibleSong(song) && ("全部" == language || value == language)) visibleSongs.add(
                song
            )
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
    }

    private fun filterWordCount(group: String?) {
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val count = songWordCount(song.title)
            if (isVisibleSong(song) && ("全部" == group || wordGroupMatches(
                    group,
                    count
                ))
            ) visibleSongs.add(song)
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
    }

    private fun wordGroupMatches(group: String?, count: Int): Boolean {
        if ("一字" == group) return count == 1
        if ("二字" == group) return count == 2
        if ("三字" == group) return count == 3
        if ("四字" == group) return count == 4
        if ("五字" == group) return count == 5
        return "六字以上" == group && count >= 6
    }

    private fun songWordCount(title: String?): Int {
        var count = 0
        val value = if (title == null) "" else title
        for (i in 0 until value.length) {
            val ch = value.get(i)
            if (Character.isLetterOrDigit(ch) || (ch >= '\u4e00' && ch <= '\u9fa5')) count++
        }
        return count
    }

    private fun singerName(song: Song): String {
        return song.singer?.takeIf(String::isNotEmpty) ?: "未知歌手"
    }

    private fun isVisibleSong(song: Song): Boolean {
        return !store.hiddenSongIds.contains(stableId(song))
    }

    private fun filterSongs(category: String?, query: String?) {
        val q = if (query == null) "" else query.trim { it <= ' ' }
        // 优先使用数据库搜索(64 万+歌曲)
        if (library.muse.isAvailable() && !q.isEmpty()) {
            browseMode = "search"
            browseParam = q
            browsePage = 0
            loadBrowsePage()
            return
        }
        // 数据库不可用或空查询时,回退到本地文件扫描
        val ql = q.lowercase()
        visibleSongs.clear()
        for (song in library.allSongs()) {
            val categoryOk = "全部" == category
                    || (song.category != null && song.category == category)
                    || ("本地" == category && !song.remote)
                    || ("网络" == category && song.remote)
            val text =
                (song.title + " " + song.singer + " " + song.category + " " + song.language + " " + song.pinyin).lowercase()
            if (isVisibleSong(song) && categoryOk && (ql.isEmpty() || text.contains(ql))) visibleSongs.add(
                song
            )
        }
        Collections.sort<Song?>(
            visibleSongs,
            Comparator.comparing<Song?, String?>(Function { song: Song? -> song!!.title!!.lowercase() })
        )
        renderSongs()
        if (pageInfo != null) pageInfo!!.setText("本地 " + visibleSongs.size + " 首")
    }

    /**
     * 加载当前分页浏览模式的歌曲列表。
     * 根据 browseMode 和 browseParam 从数据库查询对应页的数据。
     */
    /**
     * 安全解析整数。
     * 
     * @param value        字符串
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private fun parseIntSafe(value: String?, defaultValue: Int): Int {
        if (value == null || value.isEmpty()) return defaultValue
        try {
            return value.trim { it <= ' ' }.toInt()
        } catch (e: Exception) {
            return defaultValue
        }
    }

    /**
     * 公共点歌方法:将歌曲加入已点队列并开始播放。
     * 
     * 
     * 供 [SongSearchFragment] 等外部组件调用,实现点歌功能。
     * 
     * @param song 要点播的歌曲
     */
    fun orderSong(song: Song) {
        handleSong(song)
    }

    val muse: MuseDatabase
        /**
         * 获取 Muse 曲库数据库访问层(供 Fragment 查询歌曲/歌手/排行)。
         * 
         * @return 全局 MuseDatabase 实例
         */
        get() = library.muse

    val playbackPosition: Int
        get() {
            try {
                return player?.currentPosition ?: 0
            } catch (ignored: Exception) {
                return 0
            }
        }

    fun resumeMainPlaybackAt(positionMs: Int) {
        try {
            if (player != null && currentSong != null) {
                player!!.seekTo(max(0, positionMs))
                player!!.start()
                startVocalIfReady()
                updateBottomBar(currentSong, true)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * 播放指定歌曲(供已点列表点击播放)。
     * 
     * @param song 要播放的歌曲
     */
    fun playSong(song: Song?) {
        play(song)
    }

    /**
     * 切换到下一首歌曲(供已点列表切歌操作)。
     */
    fun playNextSong() {
        playNext()
    }

    private fun handleSong(song: Song) {
        addToQueue(song)
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
    }

    private fun download(song: Song) {
        val mainUrl = if (song.downloadUrl == null || song.downloadUrl!!.trim { it <= ' ' }
                .isEmpty()) song.videoUrl else song.downloadUrl
        if (mainUrl == null || mainUrl.trim { it <= ' ' }.isEmpty()) {
            toast("该条目没有下载地址")
            return
        }
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, "正在下载：" + song.title)
        io.execute(Runnable {
            val target = library.targetFor(song)
            try {
                downloadToFile(mainUrl, target, task, 0, 75)
                val originalFile = sidecarFile(target, ".original", song.originalUrl)
                val accompanyFile = sidecarFile(target, ".accompany", song.accompanyUrl)
                val lyricFile = sidecarFile(target, "", song.lyricUrl)
                if (hasUrl(song.originalUrl)) downloadToFile(
                    song.originalUrl,
                    originalFile,
                    task,
                    75,
                    85
                )
                if (hasUrl(song.accompanyUrl)) downloadToFile(
                    song.accompanyUrl,
                    accompanyFile,
                    task,
                    85,
                    95
                )
                if (hasUrl(song.lyricUrl)) downloadToFile(song.lyricUrl, lyricFile, task, 95, 99)
                if (task.cancelled) throw InterruptedException("cancelled")
                task.state = "完成"
                task.progress = 100
                val local = local(target.getAbsolutePath(), target.getName())
                local.originalPath =
                    if (originalFile.exists()) originalFile.getAbsolutePath() else ""
                local.accompanyPath =
                    if (accompanyFile.exists()) accompanyFile.getAbsolutePath() else ""
                local.lyricPath = if (lyricFile.exists()) lyricFile.getAbsolutePath() else ""
                main.post(Runnable {
                    orderQueue!!.add(local)
                    saveState()
                    refreshLibrary(null)
                    busy(false, "下载完成并已加入已点：" + local.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: InterruptedException) {
                task.state = "已取消"
                target.delete()
                cleanupSidecars(target)
                main.post(Runnable {
                    busy(false, "下载已取消：" + song.title)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            } catch (e: Exception) {
                task.state = "失败：" + e.message
                main.post(Runnable {
                    busy(false, "下载失败：" + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    @Throws(Exception::class)
    private fun downloadToFile(
        url: String?,
        target: File?,
        task: DownloadTask,
        base: Int,
        span: Int
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(30000)
        val code = conn.getResponseCode()
        check(!(code < 200 || code >= 300)) { "HTTP " + code }
        val total = conn.getContentLength()
        var done = 0
        task.state = "下载中"
        try {
            BufferedInputStream(conn.getInputStream()).use { `in` ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) > 0) {
                        if (task.cancelled) throw InterruptedException("cancelled")
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) task.progress = min(99, base + (done * span) / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hasUrl(value: String?): Boolean {
        return value != null && !value.trim { it <= ' ' }.isEmpty()
    }

    private fun sidecarFile(media: File, suffix: String?, url: String?): File {
        var ext = ".mp3"
        if (url != null) {
            val dot = url.lastIndexOf('.')
            val q = url.indexOf('?', dot)
            if (dot >= 0) ext = url.substring(dot, if (q > dot) q else url.length)
            if (ext.length > 8 || ext.contains("/")) ext = ".mp3"
        }
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        return File(base + suffix + ext)
    }

    private fun cleanupSidecars(media: File) {
        val dir = media.getParentFile()
        if (dir == null) return
        val path = media.getAbsolutePath()
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val files = dir.listFiles()
        if (files == null) return
        for (file in files) {
            val value = file.getAbsolutePath()
            if (value.startsWith(base + ".original") || value.startsWith(base + ".accompany") || value.startsWith(
                    base + ".lrc"
                )
            ) {
                file.delete()
            }
        }
    }

    private fun cancelAllDownloads() {
        for (task in downloads) {
            if ("完成" != task.state) {
                task.cancelled = true
                task.state = "正在取消"
            }
        }
        showDownloads()
    }

    private fun play(song: Song?) {
        if (player == null) player = videoBackground
        if (song == null) return
        try {
            // 远程日志: 点歌使用的源(本地文件 / 在线源)。
            MiliLog.info(
                "PLAY id=${extractTid(song)} title=${song.title} src=" +
                    (if (song.hasLocalFile()) "local" else "online"),
            )
            // 取消仍在后台扫描的“已点播完后随机本地歌曲”请求，避免抢占新点歌曲。
            randomLocalRequestSerial++
            playbackGeneration++
            songRetryCount = 0 // 重置重试计数
            playWhenPrepared = true
            hidePlaybackNoticeNow()
            releaseVocalPlayer()
            currentSong = song
            loadLyrics(song)
            persistRuntimeState()

            if (playerController != null) {
                playerController!!.updateSongInfo(song)
                playerController!!.setVocalMode(originalVocal)
            }

            updateBottomBar(song, true)
            scheduleAutoFullscreen()

            if (song.hasLocalFile()) {
                playLocalFile(song)
            } else {
                playbackPreparing = true
                // 统一使用支持断点续传和下载地址重试的下载管理器。
                if (isDownloading(song)) {
                    showDownloadProgressFor(song)
                    return
                }
                toast("加载中: " + song.title + "...")
                showDownloadProgressFor(song)
                startDownloadAndPlay(song)
            }
        } catch (e: Exception) {
            toast("播放失败：" + e.message)
        }
    }

    private fun playLocalFile(song: Song, startPositionMs: Int = 0) {
        try {
            playbackPreparing = true
            val localPath = song.path
            if (localPath.isNullOrEmpty()) {
                playbackPreparing = false
                startDownloadAndPlay(song)
                return
            }
            val f = File(localPath)
            val inspection = SongFileValidator.inspect(f, SongFileValidator.requiresTransportStream(f))
            if (!inspection.valid) {
                Log.w(TAG, "Invalid local file: ${song.path}, ${inspection.reason}")
                if (f.exists()) runCatching { f.delete() }
                song.path = null
                toast("本地文件丢失，重新下载...")
                startDownloadAndPlay(song)
                return
            }
            Log.i(
                TAG,
                "playLocalFile song=${stableId(song)} generation=$playbackGeneration " +
                    "startPositionMs=$startPositionMs path=${f.absolutePath} size=${f.length()}",
            )
            // 自动清理以文件时间作为最近使用时间；每次实际播放都刷新，避免常唱歌曲被误删。
            runCatching { f.setLastModified(System.currentTimeMillis()) }
            player!!.stopPlayback()
            setupPlayerListeners(song, startPositionMs, playbackGeneration)
            player!!.setVideoURI(Uri.fromFile(f))
            if (playWhenPrepared) player!!.start()
        } catch (e: Exception) {
            playbackPreparing = false
            Log.e(TAG, "playLocalFile error: " + e.message, e)
            toast("播放失败")
        }
    }

    /**
     * 完整下载后播放。VideoView不支持流式TS，必须下载完再播。
     * 使用256KB大缓冲区加速下载，实时显示进度。
     */
    private fun streamDownloadAndPlay(song: Song) {
        io.execute(Runnable {
            var tmpFile: File? = null
            try {
                var url = song.downloadUrl
                if (url == null || url.isEmpty()) {
                    url = getSongDownloadUrl(extractTid(song))
                    if (url != null) song.downloadUrl = url
                }
                if (url == null || url.isEmpty()) {
                    MiliLog.error(
                        "点歌编号=${song?.let(::extractTid)} 标题=${song?.title} 请求源=w.w345.my 状态=失败 详情=无法获取播放地址",
                    )
                    main.post(Runnable {
                        hideDownloadProgress()
                        toast("无法获取播放地址")
                    })
                    return@Runnable
                }

                val finalFile = getLocalFile(song)
                finalFile.getParentFile().mkdirs()
                tmpFile = File(finalFile.getParent(), finalFile.getName() + ".tmp")

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setConnectTimeout(10000)
                conn.setReadTimeout(30000)
                conn.setRequestProperty("User-Agent", "ThunderSDK/4.1.3")
                conn.connect()

                val totalSize = conn.getContentLength().toLong()
                val `in`: InputStream = BufferedInputStream(conn.getInputStream(), 262144)
                val out = FileOutputStream(tmpFile)

                val buf = ByteArray(262144) // 256KB buffer for speed
                var downloaded: kotlin.Long = 0
                var lastPct = 0
                var lastTime = System.currentTimeMillis()
                var lastBytes: kotlin.Long = 0

                while (true) {
                    val read = `in`.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    downloaded += read.toLong()

                    // 每秒更新一次进度和速度
                    val now = System.currentTimeMillis()
                    if (now - lastTime > 1000) {
                        val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                        val speed = downloaded - lastBytes
                        lastPct = pct
                        lastTime = now
                        lastBytes = downloaded
                        val p = pct
                        val speedStr = formatSize(speed) + "/s"
                        main.post(Runnable {
                            updateDownloadProgress(song, p)
                            if (textDownloadSpeed != null) textDownloadSpeed!!.setText(speedStr)
                        })
                    }
                }
                out.close()
                `in`.close()
                conn.disconnect()

                // 移到正式位置
                tmpFile.renameTo(finalFile)
                song.path = finalFile.getAbsolutePath()

                main.post(Runnable {
                    hideDownloadProgress()
                    if (currentSong != null && currentSong!!.equals(song)) {
                        playLocalFile(song)
                    }
                    toast(song.title + " 就绪")
                })
            } catch (e: Exception) {
                Log.e(TAG, "download error: " + e.message)
                if (tmpFile != null) tmpFile.delete()
                MiliLog.error("DL-ERR id=${song?.let(::extractTid)} title=${song?.title} ${e.message}")
                main.post(Runnable {
                    hideDownloadProgress()
                    toast("下载失败: " + e.message)
                })
            }
        })
    }

    /**
     * 下载歌曲并在完成后自动播放 (旧版，备用)。
     */
    private fun startDownloadAndPlay(song: Song) {
        downloadSong(song, object : DownloadCallback {
            override fun onDownloadStart(s: Song) {
                main.post(Runnable {
                    updateDownloadProgress(s, 0)
                })
            }

            override fun onDownloadProgress(s: Song, progress: Int) {
                main.post(Runnable { updateDownloadProgress(s, progress) })
            }

            override fun onDownloadReadyToPlay(s: Song) {}
            override fun onDownloadComplete(s: Song, localPath: String) {
                main.post { hideDownloadProgress() }
            }

            override fun onDownloadFailed(s: Song, error: String) {
                MiliLog.error("DL-FAIL id=${extractTid(s)} title=${s.title} $error")
                main.post(Runnable {
                    hideDownloadProgress()
                    toast("下载失败: " + error)
                })
            }
        })
    }

    private fun showDownloadProgressFor(song: Song) {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        if (textDownloadStatus != null) textDownloadStatus!!.setText("下载中: " + song.title)
        if (textDownloadSpeed != null) textDownloadSpeed!!.setText("")
        if (downloadProgressBar != null) downloadProgressBar!!.setProgress(0)
    }

    private fun updateDownloadProgress(song: Song?, progress: Int) {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.VISIBLE)
        if (textDownloadStatus != null) textDownloadStatus!!.setText("下载: " + (if (song != null) song.title else "") + " " + progress + "%")
        if (textDownloadSpeed != null) textDownloadSpeed!!.setText(progress.toString() + "%")
        if (downloadProgressBar != null) downloadProgressBar!!.setProgress(progress)
    }

    private fun hideDownloadProgress() {
        if (downloadProgressContainer != null) downloadProgressContainer!!.setVisibility(View.GONE)
    }

    private fun setupPlayerListeners(song: Song?, startPositionMs: Int = 0, generation: Long = playbackGeneration) {
        player!!.setOnPreparedListener(OnPreparedListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnPreparedListener
            Log.i(TAG, "onPrepared song=${song?.let(::stableId)} generation=$generation play=$playWhenPrepared")
            playbackPreparing = false
            currentMediaPlayer = mp
            applyPlaybackMode()
            if (startPositionMs > 0) player!!.seekTo(startPositionMs)
            prepareExternalVocal(song, startPositionMs)
            if (playWhenPrepared) {
                player!!.start()
                startVocalIfReady()
            }
            updateBottomBar(song, playWhenPrepared)
            if (startPositionMs <= 0) showSongIntro(song)
            persistRuntimeState()
        })
        player!!.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer? ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnCompletionListener
            if (System.currentTimeMillis() < suppressCompletionUntil) return@OnCompletionListener
            // 模拟器/部分设备会连续触发多次 onCompletion, 加 800ms 去抖, 防止跳歌或同一首被重复处理。
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastAutoAdvanceAtMs < 800L) return@OnCompletionListener
            lastAutoAdvanceAtMs = nowMs
            main.post(Runnable { playNext(true) })
        })
        player!!.setOnErrorListener(MediaPlayer.OnErrorListener { mp: MediaPlayer?, what: Int, extra: Int ->
            if (generation != playbackGeneration || currentSong?.equals(song) != true) return@OnErrorListener true
            playbackPreparing = false
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra)
            // 远程日志: 播放网络/解码错误实时上报。
            MiliLog.error(
                "PLAY-ERR id=${song?.let(::extractTid)} title=${song?.title} what=$what extra=$extra" +
                    (if (song?.hasLocalFile() == true) " local" else " stream"),
            )
            if (songRetryCount < 2 && song != null && song.hasLocalFile()) {
                songRetryCount++
                val retry = songRetryCount
                main.postDelayed(Runnable {
                    toast("播放重试 " + retry + "/2")
                    playLocalFile(song)
                }, 700L * retry)
            } else {
                songRetryCount = 0
                main.post(Runnable { this.playNext() })
            }
            true
        })
    }

    private fun switchToLocalPlayback(song: Song?) {
        if (currentSong == null || !currentSong!!.equals(song)) return
        if (!song!!.hasLocalFile()) return
        var pos = 0
        try {
            pos = player!!.currentPosition
        } catch (ignored: Exception) {
        }
        player!!.stopPlayback()
        playLocalFile(song, pos)
    }

    /**
     * 更新底部控制栏显示
     */
    private fun updateBottomBar(song: Song?, playing: Boolean) {
        if (playing && playbackModeNotice?.text?.contains("暂停") == true) hidePlaybackNoticeNow()
        if (playerSongTitle != null) playerSongTitle!!.setText(if (song != null) song.title else "未播放")
        if (playerSongSinger != null) playerSongSinger!!.setText(if (song != null) (if (song.singer != null) song.singer else "") else "")
        if (textVocalMode != null) textVocalMode.setText(vocalActionText())
        if (textPlayPause != null) textPlayPause.setText(if (playing) "暂停" else "播放")
        if (btnTopPause != null) btnTopPause!!.setText(if (playing) "暂停" else "播放")
        if (btnTopVocal != null) btnTopVocal!!.setText(vocalActionText())
        setTopControlIcon(btnTopPause, if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        setTopControlIcon(
            btnTopVocal,
            vocalActionIcon(),
        )
        fullScreenPauseText?.text = if (playing) "暂停" else "播放"
        fullScreenPauseIcon?.setImageResource(if (playing) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play)
        fullScreenVocalText?.text = vocalActionText()
        fullScreenVocalIcon?.setImageResource(vocalActionIcon())
        playerController?.updatePlayState(playing)
        playerController?.setVocalMode(originalVocal)
        syncPlayerInfo()
        updateOrderBadge()
    }

    /**
     * 从 CDN 下载歌曲文件。
     * 使用 global_confs 表中的 cdn_path 作为基础 URL。
     * 
     * @param song 要下载的歌曲
     */
    private fun downloadFromCdn(song: Song) {
        if (song.filename == null || song.filename!!.isEmpty()) {
            toast("无法下载:缺少文件名")
            return
        }
        val cdnBase = "https://pub.mcdn.cherryonline.cn/"
        val url = cdnBase + "cloud-song/" + song.filename
        val task = DownloadTask(song)
        downloads.add(task)
        busy(true, "正在从云端下载:" + song.title)
        io.execute(Runnable {
            val target =
                File(MuseDatabase.VIDEO_ROOT + "/" + MuseDatabase.CLOUD_SONG_DIR, song.filename)
            try {
                File(target.getParent()).mkdirs()
                downloadToFile(url, target, task, 0, 100)
                task.state = "完成"
                task.progress = 100
                song.path = target.getAbsolutePath()
                main.post(Runnable {
                    busy(false, "下载完成")
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
            } catch (e: Exception) {
                task.state = "失败:" + e.message
                main.post(Runnable {
                    busy(false, "下载失败:" + e.message)
                    if (currentTabIndex == 6) loadDownloadedList()
                })
            }
        })
    }

    /**
     * 使用 SongOkDownloadManager 下载歌曲到本地。
     */
    private fun downloadSong(song: Song, extraCallback: DownloadCallback? = null, storageChecked: Boolean = false) {
        if (SongOkDownloadManager.isDownloading(song)) {
            if (extraCallback != null) extraCallback.onDownloadStart(song)
            return
        }
        if (!storageChecked && autoDeleteSongs && isBelowReservedStorage()) {
            io.execute {
                purgeColdDownloadedFiles()
                main.post { downloadSong(song, extraCallback, storageChecked = true) }
            }
            return
        }
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.updateDownload(song, "queued", 0) }
        }
        download(song, object : DownloadCallback {
            override fun onDownloadStart(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadStart(song)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "downloading", 0)
                }
                main.post(Runnable {
                    val task = downloads.firstOrNull { stableId(it.song) == stableId(song) }
                        ?: DownloadTask(song).also { downloads.add(it) }
                    task.state = "下载中"
                    task.progress = 0
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, 0)
                })
            }

            override fun onDownloadProgress(song: Song, progress: Int) {
                if (extraCallback != null) extraCallback.onDownloadProgress(song, progress)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "downloading", progress)
                }
                main.post(Runnable {
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.progress = progress
                            break
                        }
                    }
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) updateDownloadProgress(song, progress)
                })
            }

            override fun onDownloadReadyToPlay(song: Song) {
                if (extraCallback != null) extraCallback.onDownloadReadyToPlay(song)
                // 后台预下载不能抢占当前播放；当前点播在完整文件落盘后切换。
            }

            override fun onDownloadComplete(song: Song, localPath: String) {
                if (extraCallback != null) extraCallback.onDownloadComplete(song, localPath)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.removeDownload(song)
                }
                main.post(Runnable {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    song.path = localPath
                    refreshSongAdapter()
                    val shouldAddToQueue = pendingQueueSongIds.remove(stableId(song))
                    Log.i(
                        TAG,
                        "downloadComplete song=${stableId(song)} pendingQueue=$shouldAddToQueue " +
                            "current=${currentSong?.let(::stableId)} preparing=$playbackPreparing path=$localPath",
                    )
                    if (shouldAddToQueue) addReadySongToQueue(song)
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true && playbackPreparing) hideDownloadProgress()
                    if (!shouldAddToQueue && currentSong?.equals(song) == true && playbackPreparing) {
                        switchToLocalPlayback(song)
                    }
                })
                enforceStorageReserve()
            }

            override fun onDownloadFailed(song: Song, error: String) {
                if (extraCallback != null) extraCallback.onDownloadFailed(song, error)
                if (::stateDatabase.isInitialized) runCatching {
                    stateDatabase.updateDownload(song, "failed", 0, error = error)
                }
                main.post(Runnable {
                    val wasPendingQueue = pendingQueueSongIds.remove(stableId(song))
                    if (currentSong != null && currentSong!!.equals(song)) playbackPreparing = false
                    for (task in downloads) {
                        if (task.song === song || task.song.id != null && task.song.id == song.id) {
                            task.state = "失败:" + error
                            break
                        }
                    }
                    refreshSongAdapter()
                    if (currentTabIndex == 6) loadDownloadedList()
                    if (currentSong?.equals(song) == true) hideDownloadProgress()
                    if (wasPendingQueue) toast("下载失败，未加入已点：${song.title}")
                })
            }
        })
    }

    /**
     * 检查指定歌曲是否正在下载中。
     * 
     * 
     * 使用 SongOkDownloadManager 检查真实的下载状态。
     * 
     * @param song 要检查的歌曲
     * @return true 表示正在下载
     */
    /** 从 Song 中提取云端歌曲 TID (filename 格式: "7678785.ts" / "7586669.ls" → "7678785" / "7586669")  */
    private fun extractTid(song: Song): String? {
        if (song.filename != null) {
            // 去掉 .ts 或 .ls 扩展名
            if (song.filename!!.endsWith(".ts")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            if (song.filename!!.endsWith(".ls")) return song.filename!!.substring(
                0,
                song.filename!!.length - 3
            )
            return song.filename
        }
        return song.id
    }

    private fun isDownloading(song: Song): Boolean {
        if (SongOkDownloadManager.isDownloading(song)) {
            return true
        }
        for (task in downloads) {
            val sameSong = task.song === song || stableId(task.song) == stableId(song)
            val active = task.state == "等待" || task.state == "等待下载" ||
                task.state == "下载中" || task.state == "正在取消"
            if (sameSong && active) return true
        }
        return false
    }

    private fun playNext(fromCompletion: Boolean = false) {
        if (advancingPlayback) return
        advancingPlayback = true
        try {
            advancePlayback(fromCompletion)
        } finally {
            advancingPlayback = false
        }
    }

    private fun advancePlayback(fromCompletion: Boolean) {
        // 单曲循环只影响自然播完；用户主动切歌始终进入下一首。
        if (fromCompletion && loopOne && player != null && currentSong != null) {
            playWhenPrepared = true
            player!!.seekTo(0)
            prepareExternalVocal(currentSong, 0)
            player!!.start()
            startVocalIfReady()
            return
        }
        // 队列为空(最后一首已播完/随机续播中未点歌)
        if (orderQueue!!.isEmpty()) {
            if (fromCompletion && autoNext) {
                playRandomLocalSong()
                return
            }
            // 用户主动切歌/停止:取消挂起的随机续播,进入空闲态。
            randomLocalRequestSerial++
            playbackPreparing = false
            playWhenPrepared = false
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            updateBottomBar(null, false)
            if (status != null) status!!.setText("播放完成，请点歌")
            return
        }
        // 移除当前歌曲进已唱列表。按稳定 ID 匹配,避免对象实例不同导致移不掉而重复播放同一首。
        val current = currentSong
        if (current != null) {
            sangHistory.add(0, current)
            recordScore(current)
            removeFromQueueByStableId(current)
        }
        saveState()
        renderQueue()
        if ((!fromCompletion || autoNext) && !orderQueue.isEmpty()) {
            play(orderQueue!![0])
        } else if (fromCompletion && autoNext && orderQueue.isEmpty()) {
            // 点歌清单播放完毕 → 自动续播本地已下载歌曲
            playRandomLocalSong()
        } else {
            // 显式切歌但队列已空:同样取消挂起的随机续播,避免用户停歌后又被随机歌打断。
            randomLocalRequestSerial++
            playbackPreparing = false
            playWhenPrepared = false
            releaseVocalPlayer()
            currentSong = null
            clearLyrics()
            updateBottomBar(null, false)
            if (status != null) status!!.setText("播放完成" + (if (orderQueue.isEmpty()) "" else "，点击播放继续"))
        }
    }

    /** 按稳定 ID 从已点队列移除歌曲(等价于移除“当前播放”，但不依赖对象实例相等)。 */
    private fun removeFromQueueByStableId(song: Song) {
        val id = stableId(song)
        val index = orderQueue!!.indexOfFirst { stableId(it) == id }
        if (index >= 0) orderQueue!!.removeAt(index)
    }

    /**
     * 从本机所有有效歌曲中随机续播(启动自动播放 / 点歌列表播放完毕均走此逻辑)。
     * 随机歌曲不写入已点列表；用户此时点歌会立即取消本次随机请求并优先播放已点歌曲。
     */
    private fun playRandomLocalSong(startup: Boolean = false) {
        val previousSongId = currentSong?.let(::stableId) ?: lastRandomLocalSongId
        val requestSerial = ++randomLocalRequestSerial
        playbackPreparing = true
        playWhenPrepared = true
        releaseVocalPlayer()
        currentSong = null
        clearLyrics()
        updateBottomBar(null, false)
        status?.text = if (startup) "正在随机播放本地歌曲..." else "已点播放完成，正在随机本地歌曲..."
        persistRuntimeState()

        io.execute {
            val localSongs = localSongInventory()
            library.scanLocal().forEach { song ->
                val file = song.path?.let(::File) ?: return@forEach
                if (SongFileValidator.inspect(
                        file,
                        SongFileValidator.requiresTransportStream(file),
                    ).valid
                ) {
                    localSongs.putIfAbsent(stableId(song), song)
                }
            }
            val allLocalSongs = localSongs.values.toList()
            val alternatives = allLocalSongs.filter { stableId(it) != previousSongId }
            val selected = (if (alternatives.isNotEmpty()) alternatives else allLocalSongs).randomOrNull()
            main.post {
                if (requestSerial != randomLocalRequestSerial || orderQueue!!.isNotEmpty() || currentSong != null) {
                    return@post
                }
                if (selected != null) {
                    lastRandomLocalSongId = stableId(selected)
                    status?.text = "随机播放：${selected.title}"
                    play(selected)
                } else {
                    playbackPreparing = false
                    playWhenPrepared = false
                    updateBottomBar(null, false)
                    status?.text = "播放完成，本地暂无可播放歌曲"
                    persistRuntimeState()
                }
            }
        }
    }

    private fun togglePlay() {
        if (player == null) return
        val targetPlaying = !playWhenPrepared
        playWhenPrepared = targetPlaying
        if (playbackPreparing) {
            updateBottomBar(currentSong, targetPlaying)
            if (targetPlaying) hidePlaybackNoticeNow() else showPlaybackModeNotice("暂停", true)
            scheduleAutoFullscreen()
            persistRuntimeState()
            return
        }
        if (!targetPlaying) {
            player!!.pause()
            pauseVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText("播放")
            showPlaybackModeNotice("暂停", true)
        } else {
            player!!.start()
            startVocalIfReady()
            if (textPlayPause != null) textPlayPause.setText("暂停")
            hidePlaybackNoticeNow()
        }
        updateBottomBar(currentSong, targetPlaying)
        scheduleAutoFullscreen()
        persistRuntimeState()
    }

    private fun replay() {
        if (player == null) return
        playWhenPrepared = true
        suppressCompletionUntil = System.currentTimeMillis() + 1500L
        player!!.seekTo(0)
        updateLyricView()
        prepareExternalVocal(currentSong, 0)
        player!!.start()
        startVocalIfReady()
        hidePlaybackNoticeNow()
        updateBottomBar(currentSong, true)
        showSongIntro(currentSong)
        persistRuntimeState()
    }

    private fun applyPlaybackMode() {
        val videoPlayer = player ?: return
        videoPlayer.setTone(tone)
        val external = prepareExternalVocal(
            currentSong,
            player?.currentPosition ?: 0
        )
        val embedded = !external && "自动" == vocalChannelMode &&
            prepareEmbeddedDualTrackVocal(currentSong, player?.currentPosition ?: 0)
        var trackSelected = false
        var channelApplied = false
        if (!external && !embedded && "自动" == vocalChannelMode) {
            trackSelected = applySelectedAudioTrack()
            if (!trackSelected && currentSong != null && currentSong!!.accomp > 0) {
                channelApplied = applyChannelByAccomp()
            }
        }
        val alternateTrackActive = embedded && originalVocal != embeddedMainTrackOriginal
        val volume = if (external || alternateTrackActive || "静音练唱" == singMode) {
            0f
        } else {
            max(0f, min(1f, musicVolume / 100f))
        }
        try {
            when {
                external || "静音练唱" == singMode -> videoPlayer.setPlaybackVolume(0f, 0f)
                embedded -> videoPlayer.setPlaybackVolume(volume, volume)
                trackSelected -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
                "自动" != vocalChannelMode -> {
                    videoPlayer.selectAudioChannel(selectedChannelForMode(), volume)
                    status?.text = "声道模式：$vocalChannelMode / ${if (originalVocal) "原唱" else "伴唱"}"
                }
                channelApplied -> Unit
                else -> videoPlayer.selectAudioChannel(IjkMediaPlayer.AUDIO_CHANNEL_STEREO, volume)
            }
        } catch (ignored: Exception) {
        }
        applyVocalVolume()
        if (embedded && vocalPlayerPrepared) {
            val mainPosition = videoPlayer.currentPosition
            val alternatePosition = embeddedVocalEngine?.currentPosition ?: mainPosition
            Log.i(
                TAG,
                "Embedded seamless switch original=$originalVocal main=$mainPosition " +
                    "alternate=$alternatePosition drift=${alternatePosition - mainPosition}",
            )
        }
    }

    /**
     * 基于 songs.accomp 字段自动切换原唱/伴奏声道。
     * 
     * 
     * accomp=1:左声道=伴奏,右声道=原唱
     * accomp=2:左声道=原唱,右声道=伴奏
     * 
     * 
     * 当 originalVocal=true 时,播放原唱声道,静音伴奏声道;
     * 当 originalVocal=false 时,播放伴奏声道,静音原唱声道。
     * 
     * @return true 表示成功应用声道切换
     */
    private fun applyChannelByAccomp(): Boolean {
        val videoPlayer = player ?: return false
        if (currentSong == null || currentSong!!.accomp <= 0) return false
        try {
            val volume = max(0f, min(1f, musicVolume / 100f))
            val channel = if (currentSong!!.accomp == 1) {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
            } else {
                if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
            }
            videoPlayer.selectAudioChannel(channel, volume)
            status!!.setText((if (originalVocal) "原唱" else "伴唱") + "(声道" + currentSong!!.accomp + ")")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun applySelectedAudioTrack(): Boolean {
        val selected = player?.selectAudioTrack(originalVocal) == true
        if (selected) status?.text = if (originalVocal) "已选择原唱音轨" else "已选择伴唱音轨"
        return selected
    }

    private fun selectedChannelForMode(): Int = when (vocalChannelMode) {
        "左伴右原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_RIGHT else IjkMediaPlayer.AUDIO_CHANNEL_LEFT
        "右伴左原" -> if (originalVocal) IjkMediaPlayer.AUDIO_CHANNEL_LEFT else IjkMediaPlayer.AUDIO_CHANNEL_RIGHT
        else -> IjkMediaPlayer.AUDIO_CHANNEL_STEREO
    }

    private fun channelVolumes(volume: Float): FloatArray {
        if ("左伴右原" == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(0f, volume) else floatArrayOf(volume, 0f)
        }
        if ("右伴左原" == vocalChannelMode) {
            return if (originalVocal) floatArrayOf(volume, 0f) else floatArrayOf(0f, volume)
        }
        return floatArrayOf(volume, volume)
    }

    private fun prepareExternalVocal(song: Song?, positionMs: Int): Boolean {
        val path = selectedVocalPath(song)
        if (!hasUrl(path)) {
            if (embeddedDualTrackMode && embeddedVocalEngine != null) {
                pendingVocalPosition = adjustedAudioPosition(positionMs)
                if (vocalPlayerPrepared) runCatching {
                    val drift = kotlin.math.abs(embeddedVocalEngine!!.currentPosition - pendingVocalPosition)
                    if (drift > 300 && originalVocal == embeddedMainTrackOriginal) {
                        embeddedVocalEngine!!.seekTo(pendingVocalPosition)
                    }
                }
                return false
            }
            releaseVocalPlayer()
            return false
        }
        if (path == currentVocalPath && vocalPlayer != null) {
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            if (vocalPlayerPrepared) {
                runCatching {
                    vocalPlayer!!.seekTo(pendingVocalPosition)
                    applyVocalVolume()
                    if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                }
            }
            return true
        }
        releaseVocalPlayer()
        try {
            embeddedDualTrackMode = false
            val candidate = MediaPlayer()
            vocalPlayer = candidate
            vocalPlayerPrepared = false
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            candidate.setDataSource(path)
            candidate.setAudioStreamType(AudioManager.STREAM_MUSIC)
            candidate.setOnCompletionListener(OnCompletionListener { _: MediaPlayer? -> })
            candidate.setOnPreparedListener { preparedPlayer ->
                if (vocalPlayer !== preparedPlayer || currentVocalPath != path) {
                    runCatching { preparedPlayer.release() }
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                if (pendingVocalPosition > 0) runCatching { preparedPlayer.seekTo(pendingVocalPosition) }
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
            }
            candidate.setOnErrorListener { failedPlayer, _, _ ->
                if (vocalPlayer === failedPlayer) releaseVocalPlayer()
                true
            }
            candidate.prepareAsync()
            return true
        } catch (e: Exception) {
            releaseVocalPlayer()
            toast("外置原伴唱加载失败：" + e.message)
            return false
        }
    }

    /**
     * 双音轨 TS 使用一个常驻的静音副播放器预解码另一条音轨。切换时只交叉调整两个
     * 播放器的音量，不再对主 IJKPlayer 调用 selectTrack，避免 AudioTrack 被销毁重建。
     */
    private fun prepareEmbeddedDualTrackVocal(song: Song?, positionMs: Int): Boolean {
        val videoPlayer = player ?: return false
        val path = song?.path?.takeIf { it.isNotBlank() } ?: return false
        if (videoPlayer.audioTrackCount() < 2 || !File(path).isFile) {
            if (embeddedDualTrackMode) releaseVocalPlayer()
            return false
        }
        if (embeddedDualTrackMode && currentVocalPath == path && embeddedVocalEngine != null) {
            if (!vocalPlayerPrepared && embeddedMainTrackOriginal != originalVocal) {
                releaseVocalPlayer()
            } else {
                applyVocalVolume()
                return vocalPlayerPrepared
            }
        }

        releaseVocalPlayer()
        return try {
            embeddedDualTrackMode = true
            embeddedMainTrackOriginal = originalVocal
            pendingVocalPosition = adjustedAudioPosition(positionMs)
            currentVocalPath = path
            val candidate = KtvPlaybackEngine(this)
            embeddedVocalEngine = candidate
            vocalPlayerPrepared = false
            candidate.setVolume(0f, 0f)
            candidate.setOnCompletionListener(null)
            candidate.setOnPreparedListener {
                if (embeddedVocalEngine !== candidate || currentVocalPath != path || !embeddedDualTrackMode) {
                    candidate.release()
                    return@setOnPreparedListener
                }
                if (candidate.audioTrackCount() < 2 ||
                    !candidate.selectAudioTrack(!embeddedMainTrackOriginal)
                ) {
                    Log.w(
                        TAG,
                        "Embedded seamless vocal unavailable: tracks=${candidate.audioTrackCount()} path=$path",
                    )
                    releaseVocalPlayer()
                    return@setOnPreparedListener
                }
                vocalPlayerPrepared = true
                // The secondary decoder starts roughly one audio buffer behind the visible player.
                // Give it a small head start once, while it is still muted, so later volume-only
                // switches land on the same presentation timestamp.
                val synchronizedPosition = adjustedAudioPosition(
                    (player?.currentPosition ?: pendingVocalPosition) + 120,
                )
                if (synchronizedPosition > 0) candidate.seekTo(synchronizedPosition)
                applyVocalVolume()
                if (playWhenPrepared) startVocalIfReady() else pauseVocalIfReady()
                Log.i(
                    TAG,
                    "Embedded seamless vocal ready tracks=${candidate.audioTrackCount()} " +
                        "mainOriginal=$embeddedMainTrackOriginal path=$path",
                )
            }
            candidate.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Embedded seamless vocal failed what=$what extra=$extra path=$path")
                if (embeddedVocalEngine === candidate) releaseVocalPlayer()
                true
            }
            candidate.setVideoUri(Uri.fromFile(File(path)))
            false
        } catch (e: Exception) {
            Log.w(TAG, "Embedded seamless vocal prepare failed path=$path", e)
            releaseVocalPlayer()
            false
        }
    }

    private fun selectedVocalPath(song: Song?): String {
        if (song == null) return ""
        val direct = if (originalVocal) song.originalPath else song.accompanyPath
        val databasePath = if (originalVocal) song.pOrigin else song.pAccomp
        return direct?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: databasePath?.takeIf { it.isNotBlank() && it !in setOf("0", "1", "2") }
            ?: ""
    }

    private fun applyVocalVolume() {
        if (vocalPlayer == null && embeddedVocalEngine == null) return
        val alternateTrackActive = embeddedDualTrackMode && originalVocal != embeddedMainTrackOriginal
        val volume = if ("静音练唱" == singMode || embeddedDualTrackMode && !alternateTrackActive) {
            0f
        } else {
            max(0f, min(1f, musicVolume / 100f))
        }
        try {
            if (embeddedDualTrackMode) {
                embeddedVocalEngine?.setVolume(volume, volume)
            } else {
                vocalPlayer?.setVolume(volume, volume)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun startVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying != true) embeddedVocalEngine?.start()
            } else if (vocalPlayer?.isPlaying != true) {
                vocalPlayer?.start()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun pauseVocalIfReady() {
        if (!vocalPlayerPrepared) return
        try {
            if (embeddedDualTrackMode) {
                if (embeddedVocalEngine?.isPlaying == true) embeddedVocalEngine?.pause()
            } else if (vocalPlayer?.isPlaying == true) {
                vocalPlayer?.pause()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun releaseVocalPlayer() {
        embeddedVocalEngine?.release()
        embeddedVocalEngine = null
        if (vocalPlayer != null) {
            try {
                vocalPlayer!!.release()
            } catch (ignored: Exception) {
            }
        }
        vocalPlayer = null
        vocalPlayerPrepared = false
        embeddedDualTrackMode = false
        embeddedMainTrackOriginal = true
        pendingVocalPosition = 0
        currentVocalPath = ""
    }

    private fun adjustedAudioPosition(videoPositionMs: Int): Int {
        return max(0, videoPositionMs - audioDelayMs)
    }

    private fun loadLyrics(song: Song?) {
        lyricLines.clear()
        if (song == null || !hasUrl(song.lyricPath)) {
            showLyricText("暂无歌词", "")
            return
        }
        val lyricPath = song.lyricPath.orEmpty()
        val file = File(lyricPath)
        if (!file.exists() || !file.getName().lowercase().endsWith(".lrc")) {
            showLyricText("歌词文件：" + file.getName(), "暂仅同步 LRC 歌词")
            return
        }
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) parseLyricLine(line)
                Collections.sort<LyricLine?>(
                    lyricLines, Comparator.comparingInt<LyricLine?>(
                        ToIntFunction { item: LyricLine? -> item!!.timeMs })
                )
                updateLyricView()
            }
        } catch (e: Exception) {
            showLyricText("歌词读取失败", e.message)
        }
    }

    private fun parseLyricLine(line: String?) {
        val value = if (line == null) "" else line.trim { it <= ' ' }
        if (value.isEmpty()) return
        val lastBracket = value.lastIndexOf(']')
        if (!value.startsWith("[") || lastBracket < 0 || lastBracket >= value.length - 1) return
        val text = value.substring(lastBracket + 1).trim { it <= ' ' }
        if (text.isEmpty()) return
        var index = 0
        while (index < lastBracket) {
            val open = value.indexOf('[', index)
            val close = value.indexOf(']', open + 1)
            if (open < 0 || close < 0 || close > lastBracket) break
            val time = parseLyricTime(value.substring(open + 1, close))
            if (time >= 0) lyricLines.add(LyricLine(time, text))
            index = close + 1
        }
    }

    private fun parseLyricTime(value: String): Int {
        try {
            val parts = value.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size != 2) return -1
            val minutes = parts[0].toInt()
            val sec = parts[1].split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val seconds = sec[0].toInt()
            var millis = 0
            if (sec.size > 1) {
                val fraction = (sec[1] + "00").substring(0, 2)
                millis = fraction.toInt() * 10
            }
            return minutes * 60000 + seconds * 1000 + millis
        } catch (e: Exception) {
            return -1
        }
    }

    private fun updateLyricView() {
        if (lyricCurrent == null || lyricNext == null || lyricLines.isEmpty() || player == null) return
        val pos = player!!.currentPosition
        var current = 0
        for (i in lyricLines.indices) {
            if (lyricLines.get(i)!!.timeMs <= pos) current = i
            else break
        }
        val next = if (current + 1 < lyricLines.size) lyricLines.get(current + 1)!!.text else ""
        showLyricText(lyricLines.get(current)!!.text, next)
    }

    private fun clearLyrics() {
        lyricLines.clear()
        showLyricText("暂无歌词", "")
    }

    private fun showLyricText(current: String?, next: String?) {
        if (lyricCurrent != null) lyricCurrent!!.setText(if (current == null) "" else current)
        if (lyricNext != null) lyricNext!!.setText(if (next == null) "" else next)
    }

    private fun moveSelectedQueueToTop() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos > 0 && pos < orderQueue.size) {
            val song = orderQueue.removeAt(pos)
            orderQueue.add(0, song)
            saveState()
            renderQueue()
        }
    }

    private fun removeSelectedQueue() {
        if (queueList == null || orderQueue!!.isEmpty()) return
        var pos = queueList!!.getCheckedItemPosition()
        if (pos < 0) pos = queueList!!.getSelectedItemPosition()
        if (pos >= 0 && pos < orderQueue.size) {
            orderQueue.removeAt(pos)
            saveState()
            renderQueue()
        }
    }

    private fun showSongActions(song: Song) {
        val hasLocalFile = hasValidLocalSongFile(song)
        val isFavorite = store.isFavorite(song)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(22))
        }
        val title = TextView(this).apply {
            text = song.title.orEmpty()
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(20))
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lateinit var dialog: AlertDialog
        fun action(iconRes: Int, labelText: String, iconTint: Int? = null, block: () -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_song_action_focus)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                iconTint?.let { imageTintList = ColorStateList.valueOf(it) }
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(92, 92, 97))
                }
            }, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(TextView(this@MainActivity).apply {
                text = labelText; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { dialog.dismiss(); block() }
        }
        actions.addView(action(R.drawable.ott_ic_top, "置顶") {
            if (!orderQueue!!.contains(song)) addToQueue(song)
            moveQueuedSongToNext(song)
            toast("已置顶：${song.title}")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(action(
            if (isFavorite) R.drawable.ott_ic_favorite_cancel else R.drawable.ott_ic_favorite,
            if (isFavorite) "取消收藏" else "收藏",
            if (isFavorite) Color.rgb(233, 30, 99) else null,
        ) {
            val added = store.toggleFavorite(song)
            saveState()
            toast(if (added) "已收藏" else "已取消收藏")
            if (browseMode == "favorites") {
                visibleSongs.removeAll { stableId(it) == stableId(song) }
                renderSongList()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (hasLocalFile) {
            actions.addView(action(R.drawable.ott_ic_delete, "删除文件") {
                confirmDeleteLocalFile(song)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = song.singer.orEmpty()
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundResource(R.drawable.ott_bg_item)
            isFocusable = false
            isClickable = false
        }, LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(22) })
        dialog = AlertDialog.Builder(this).setView(panel).create()
        dialog.showForTv(afterShow = { actions.getChildAt(0)?.requestFocus() })
    }

    private fun hasValidLocalSongFile(song: Song): Boolean {
        val direct = song.path?.let(::File)
        if (direct != null && SongFileValidator.inspect(
                direct,
                SongFileValidator.requiresTransportStream(direct),
            ).valid
        ) return true
        return isDownloaded(song)
    }

    private fun confirmDeleteLocalFile(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("删除本地文件")
            .setMessage("确定删除 ${song.title} 的本地文件吗？歌曲仍保留在曲库中。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> deleteDownloadedSong(song) }
            .create().showForTv()
    }

    private fun showSongInfo(song: Song) {
        val message = ("歌名：" + song.title
                + "\n歌手：" + song.singer
                + "\n分类：" + song.category
                + "\n语种：" + song.language
                + "\n拼音：" + song.pinyin
                + "\n原唱资源：" + resourceState(song.originalUrl, song.originalPath)
                + "\n伴唱资源：" + resourceState(song.accompanyUrl, song.accompanyPath)
                + "\n歌词资源：" + resourceState(song.lyricUrl, song.lyricPath)
                + "\n来源：" + (if (song.remote) "网络曲库" else song.path))
        AlertDialog.Builder(this).setTitle("歌曲信息").setMessage(message)
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun resourceState(remoteUrl: String?, localPath: String?): String {
        if (hasUrl(localPath)) return "本地已下载"
        if (hasUrl(remoteUrl)) return "网络可下载"
        return "无"
    }

    private fun confirmRemoveSong(song: Song) {
        val title = if (song.remote) "从网络曲库移除" else "删除本地文件"
        val message = if (song.remote)
            "该操作会在本机隐藏这首网络曲库歌曲。"
        else
            "该操作会删除本地文件，并从已点、收藏和歌单中移除。"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message + "\n\n" + song.displayTitle())
            .setPositiveButton(
                "确定",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int -> removeSong(song) })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun removeSong(song: Song) {
        val id = stableId(song)
        if (!song.remote && song.path != null && !song.path!!.isEmpty()) {
            val file = File(song.path.orEmpty())
            if (file.exists() && !file.delete()) {
                toast("文件删除失败")
                return
            }
        }
        store.hiddenSongIds.add(id)
        store.favoriteIds.remove(id)
        for (ids in store.playlistSongs.values) ids.remove(id)
        removeSongFromList(orderQueue!!, id)
        removeSongFromList(sangHistory, id)
        saveState()
        refreshLibrary(Runnable {
            toast("已移除：" + song.title)
            if ("收藏" == currentPage) showFavorites()
            else if ("已点" == currentPage) showQueue()
            else showSongs("全部")
        })
    }

    private fun removeSongFromList(songs: MutableList<Song>, id: String?) {
        for (i in songs.indices.reversed()) {
            if (stableId(songs.get(i)) == id) songs.removeAt(i)
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setHint("歌单名称")
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton(
                "创建",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val name = input.getText().toString().trim { it <= ' ' }
                    if (!name.isEmpty() && !store.playlists.contains(name)) {
                        store.playlists.add(name)
                        saveState()
                    }
                    showPlaylists()
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun fetchCatalog() {
        val url: String = prefs()!!.getString(KEY_CATALOG_URL, "")!!
        if (url.trim { it <= ' ' }.isEmpty()) {
            showCatalogDialog()
            return
        }
        busy(true, "正在同步网络曲库...")
        io.execute(Runnable {
            try {
                val array = CatalogClient().fetch(url)
                library.saveRemote(array)
                library.scanLocal()
                main.post(Runnable {
                    busy(false, "网络曲库已同步：" + array.length() + " 首")
                    if (currentTabIndex == 6) loadDownloadedList()
                    else showSongs("网络")
                })
            } catch (e: Exception) {
                main.post(Runnable { busy(false, "网络曲库同步失败：" + e.message) })
            }
        })
    }

    private fun refreshLibrary(after: Runnable?) {
        busy(true, "正在扫描曲库...")
        io.execute(Runnable {
            val locals = library.scanLocal().size
            library.loadCachedRemote()
            main.post(Runnable {
                busy(
                    false,
                    "曲库已更新：本地 " + locals + " 首，总计 " + library.allSongs().size + " 首"
                )
                if (after != null) after.run()
            })
        })
    }

    private fun showCatalogDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(prefs()!!.getString(KEY_CATALOG_URL, ""))
        input.setHint("http://你的服务器/catalog.json")
        AlertDialog.Builder(this)
            .setTitle("网络曲库源")
            .setMessage("JSON: [{\"title\":\"歌名\",\"singer\":\"歌手\",\"category\":\"分类\",\"url\":\"http://.../song.mp4\"}]")
            .setView(input)
            .setPositiveButton(
                "保存并同步",
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    prefs()!!.edit()
                        .putString(KEY_CATALOG_URL, input.getText().toString().trim { it <= ' ' })
                        .apply()
                    fetchCatalog()
                })
            .setNegativeButton("取消", null)
            .create().showForTv()
    }

    private fun showKeyboardDialog() {
        if (search == null) return
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        val preview = label(search!!.getText().toString(), 22, Color.WHITE)
        preview.setMinHeight(dp(54))
        preview.setBackgroundColor(PANEL_2)
        box.addView(preview, LinearLayout.LayoutParams(-1, dp(58)))

        val grid = GridLayout(this)
        grid.setColumnCount(8)
        val keys = arrayOf<String?>(
            "A", "B", "C", "D", "E", "F", "G", "H",
            "I", "J", "K", "L", "M", "N", "O", "P",
            "Q", "R", "S", "T", "U", "V", "W", "X",
            "Y", "Z", "0", "1", "2", "3", "4", "5",
            "6", "7", "8", "9", "删", "清", "空格", "搜索"
        )
        val dialog = AlertDialog.Builder(this).setTitle("拼音搜索").setView(box).create()
        for (key in keys) {
            val keyView = button(key, View.OnClickListener { v: View? ->
                val value = (v as TextView).getText().toString()
                var text = preview.getText().toString()
                if ("删" == value) {
                    text = if (text.length > 0) text.substring(0, text.length - 1) else ""
                } else if ("清" == value) {
                    text = ""
                } else if ("空格" == value) {
                    text += " "
                } else if ("搜索" == value) {
                    search!!.setText(text)
                    filterSongs("全部", text)
                    dialog.dismiss()
                    return@OnClickListener
                } else {
                    text += value
                }
                preview.setText(text)
                search!!.setText(text)
            })
            keyView.setGravity(Gravity.CENTER)
            val lp = GridLayout.LayoutParams()
            lp.width = dp(if ("空格" == key || "搜索" == key) 126 else 72)
            lp.height = dp(58)
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            grid.addView(keyView, lp)
        }
        box.addView(grid)
        dialog.setOnShowListener(OnShowListener { d: DialogInterface? ->
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus()
        })
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()
    }

    private fun renderSongs() {
        if (songAdapter == null) return
        songAdapter!!.clear()
        for (i in visibleSongs.indices) {
            val song = visibleSongs.get(i)
            songAdapter!!.add(
                String.format(
                    Locale.ROOT,
                    "%02d  %s%s  -  %s    [%s/%s]%s",
                    i + 1,
                    if (store.isFavorite(song)) "★ " else "",
                    song.title,
                    song.singer,
                    song.category,
                    song.language,
                    if (song.remote) "  下载" else "  点歌"
                )
            )
        }
        songAdapter!!.notifyDataSetChanged()
        header!!.setText(currentPage + "  " + visibleSongs.size)
    }

    private fun renderQueue() {
        if (queueAdapter == null) return
        queueAdapter!!.clear()
        for (i in orderQueue!!.indices) queueAdapter!!.add(
            (i + 1).toString() + ". " + orderQueue.get(
                i
            ).displayTitle()
        )
        queueAdapter!!.notifyDataSetChanged()
    }

    private fun addNav(text: String?, listener: View.OnClickListener) {
        val index = navButtons.size
        val btn = button(text, listener)
        // 注册到列表以便维护选中态
        navButtons.add(btn)
        // 点击时更新选中态
        btn.setOnClickListener(View.OnClickListener { v: View? ->
            selectNav(index)
            listener.onClick(v)
        })
        nav!!.addView(btn, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private fun addTile(
        grid: GridLayout,
        title: String?,
        sub: String?,
        listener: View.OnClickListener?
    ) {
        // 卡片式磁贴:圆角背景 + 描边 + 焦点高亮
        val tile = label(title + "\n" + sub, 20, TEXT_WHITE)
        tile.setFocusable(true)
        tile.setClickable(true)
        tile.setGravity(Gravity.CENTER)
        tile.setMinHeight(dp(110))
        // 默认背景:深色圆角 + 浅描边
        tile.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
        tile.setOnClickListener(listener)
        tile.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                // 焦点态:青绿色圆角 + 白色描边
                v!!.setBackground(roundedBg(FOCUS, 8, Color.argb(180, 255, 255, 255), 2))
                tile.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackground(roundedBg(PANEL, 8, DIVIDER, 1))
                tile.setTextColor(TEXT_WHITE)
            }
        })
        val lp = GridLayout.LayoutParams()
        lp.width = dp(210)
        lp.height = dp(126)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        grid.addView(tile, lp)
    }

    private fun setPage(page: String) {
        currentPage = page
        if (header != null) header!!.setText(page)
        if (listTitle != null) listTitle!!.setText(page)
    }

    private fun panel(): LinearLayout {
        val layout = LinearLayout(this)
        layout.setOrientation(LinearLayout.VERTICAL)
        layout.setPadding(dp(12), dp(12), dp(12), dp(12))
        // 圆角面板背景
        layout.setBackground(roundedBg(PANEL, 6, 0, 0))
        return layout
    }

    private fun sectionTitle(text: String?): TextView {
        val view = label(text, 22, GOLD)
        view.setMinHeight(dp(46))
        // 标题底部加一条浅色分割线
        view.setPadding(dp(8), dp(6), dp(8), dp(10))
        return view
    }

    // ===== TV 布局视图字段 =====
    /** 视频背景  */
    private var videoBackground: KtvVideoView? = null
    private var playerLayerRoot: FrameLayout? = null
    private var playerFocusBorder: View? = null
    private var playerTouchOverlay: View? = null
    private var homePlayerHost: View? = null
    private var subPagePlayerHost: View? = null
    private var activePlayerHost: View? = null
    private var lastHomeFocusSource: View? = null
    private var lastHomeFocusTarget: View? = null
    private var lastHomeFocusReverseKey: Int = KeyEvent.KEYCODE_UNKNOWN

    /** 顶部状态栏区域  */
    private val topStatusBar: LinearLayout? = null
    /** 当前播放提示  */
    /** 下载速度提示  */
    private var textDownloadSpeed: TextView? = null

    /** 下载进度容器  */
    private var downloadProgressContainer: LinearLayout? = null

    /** 下载状态文字  */
    private var textDownloadStatus: TextView? = null

    /** 下载进度条  */
    private var downloadProgressBar: ProgressBar? = null

    /** Tab 容器  */
    private var tabContainer: LinearLayout? = null

    /** 已点列表按钮  */

    /** 已点数量徽标  */
    private var textOrderBadge: TextView? = null
    /** 搜索按钮  */
    /** 搜索输入框  */
    private var searchInput: EditText? = null

    /** 搜索执行按钮  */
    private var btnDoSearch: TextView? = null

    /** 分类列表容器  */
    private var categoryList: LinearLayout? = null

    /** 列表标题  */
    private var listTitle: TextView? = null

    /** 分页信息  */
    private var textPageInfo: TextView? = null

    /** 上一页按钮  */
    private var btnPrevPage: TextView? = null

    /** 下一页按钮  */
    private var btnNextPage: TextView? = null

    /** 当前播放歌曲标题  */
    private var playerSongTitle: TextView? = null

    /** 当前播放歌手  */
    private var playerSongSinger: TextView? = null
    /** 原唱/伴唱切换按钮  */
    /** 原唱/伴唱文本  */
    private val textVocalMode: TextView? = null
    private var breadcrumbCurrent: TextView? = null

    /** 播放/暂停文本  */
    private val textPlayPause: TextView? = null

    /** 切歌按钮  */
    private val btnNextSong: TextView? = null

    /** 重唱按钮  */
    private val btnReplay: TextView? = null

    /** 音量按钮  */
    private val btnVolume: TextView? = null
    /** ===== 新版UI字段(顶栏/底栏/首页3列) =====  */
    /** 顶部状态栏  */
    private var topBar: View? = null

    /** 底部控制栏  */
    private var bottomBar: View? = null

    /** 顶栏 logo  */
    private var textLogo: ImageView? = null
    private var topBrandName: TextView? = null
    private var contentFrame: FrameLayout? = null
    private var browsePagination: View? = null

    /** 顶栏搜索按钮  */
    private var btnTopSearch: TextView? = null

    /** 顶栏已点按钮(整条)  */
    private var btnTopOrder: LinearLayout? = null
    private var textTopOrderLabel: TextView? = null

    /** 已点数量徽章  */
    private var textTopOrderBadge: TextView? = null

    /** 顶栏伴唱按钮  */
    private var btnTopVocal: TextView? = null

    /** 顶栏切歌按钮  */
    private var btnTopNext: TextView? = null

    /** 顶栏暂停按钮  */
    private var btnTopPause: TextView? = null

    /** 顶栏重唱按钮  */
    private var btnTopReplay: TextView? = null

    /** 顶栏调音按钮  */
    private var btnTopTone: TextView? = null

    /** 底栏猜你想唱  */
    private var btnBottomGuess: TextView? = null

    /** 底栏设置  */
    private var btnBottomSettings: TextView? = null

    /** 底栏扫码点歌 */
    private var btnBottomMobileOrder: TextView? = null

    /** 底栏退出  */
    private var btnBottomExit: TextView? = null

    /** 首页 3 列布局根  */
    private var homeLayout: LinearLayout? = null

    /** 首页视频窗口  */
    private var homeVideo: KtvVideoView? = null

    /** 首页视频标题  */
    private var homePlayerTitle: TextView? = null

    /** 首页视频歌手  */
    private var homePlayerSinger: TextView? = null

    /** 首页4个分类卡片  */
    private var homeCardRank: TextView? = null
    private var homeCardSong: TextView? = null
    private var homeCardSinger: TextView? = null
    private var homeCardLocal: TextView? = null
    private var homeCardRegular: TextView? = null
    private var homeCardFavorite: TextView? = null
    private var homeCardCategory: TextView? = null

    /** 新歌榜海报分页  */
    private var textPosterPage: TextView? = null
    private var homePosterView: View? = null

    /** 当前歌曲顶部提示(用于子页面左列)  */
    private var textCurrentSongTip: TextView? = null

    /** 子页面返回按钮(右栏顶部)  */
    private var btnBack: TextView? = null

    /** 品牌标识  */
    private var textBrandRight: TextView? = null

    /** 左侧栏布局引用  */
    private var leftColumn: LinearLayout? = null
    private var rightColumn: LinearLayout? = null

    /** 子页面二级分类滚动容器(语种筛选)  */
    private var subCategoryScroll: HorizontalScrollView? = null
    private var keyboardArea: View? = null
    private var rankCategoryGrid: GridLayout? = null
    private var paginationBar: View? = null
    private var fullScreenContainer: FrameLayout? = null
    private var isFullScreen = false
    private var playerReturnSurface: KtvVideoView? = null
    private var playerReturnHost: View? = null
    private var fullScreenPauseText: TextView? = null
    private var fullScreenVocalText: TextView? = null
    private var fullScreenPauseIcon: ImageView? = null
    private var fullScreenVocalIcon: ImageView? = null
    private var fullScreenSongInfo: TextView? = null
    private var fullScreenSeekBar: SeekBar? = null
    private var fullScreenTime: TextView? = null
    private var fullScreenSeeking = false
    private var fullScreenControls: LinearLayout? = null
    private var fullScreenProgressRow: View? = null
    private var fullScreenToneControl: View? = null
    private var fullScreenVolumePanel: View? = null
    private var fullScreenVolumeIndicator: CircularVolumeView? = null
    private var fullScreenVolumeMinusButton: TextView? = null
    private var fullScreenVolumePlusButton: TextView? = null
    private var fullScreenVolumeMuteButton: TextView? = null
    private var fullScreenPreviousVolume = 70
    private var randomLocalRequestSerial = 0L
    private var lastRandomLocalSongId: String? = null
    /** 启动时无恢复播放/空队列: 曲库就绪后自动随机播放本地已下载歌曲(仅触发一次)。 */
    private var startupRandomPending = false
    private var advancingPlayback = false
    private var lastAutoAdvanceAtMs = 0L
    private var fullScreenChromeVisible = false
    private val hideFullScreenVolumeIndicatorRunnable = Runnable {
        fullScreenVolumeIndicator?.animate()?.alpha(0f)?.setDuration(220L)?.withEndAction {
            fullScreenVolumeIndicator?.visibility = View.GONE
            fullScreenVolumeIndicator?.alpha = 1f
        }?.start()
    }
    private val hideFullScreenChromeRunnable = Runnable {
        if (isFullScreen && fullScreenVolumePanel?.visibility != View.VISIBLE) {
            setFullScreenChromeVisible(false)
        }
    }
    private var playbackModeNotice: TextView? = null
    private var songIntroNotice: TextView? = null
    private var songIntroIcon: TextView? = null
    private val hidePlaybackModeNotice = Runnable {
        playbackModeNotice?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
            playbackModeNotice?.visibility = View.GONE
        }?.start()
    }
    private val hideSongIntroNotice = Runnable {
        songIntroNotice?.animate()?.alpha(0f)?.setDuration(250L)?.withEndAction {
            songIntroNotice?.visibility = View.GONE
            songIntroIcon?.visibility = View.GONE
        }?.start()
    }

    /** 当前选中的 Tab 索引  */
    private var currentTabIndex = -1 // -1 = 首页

    /** 当前分类列表  */
    private val currentCategories: MutableList<String> = ArrayList<String>()

    /** 排行榜显示名对应的数据库 playlist id，索引与 currentCategories 一致。 */
    private val rankPlaylistIds: MutableList<String> = ArrayList<String>()

    /** 当前分类索引  */
    private var currentCategoryIndex = 0

    private var categoryParentPage = 0
    private var categoryParentQuery = ""

    private fun label(text: String?, sp: Int, color: Int): TextView {
        val view = TextView(this)
        view.setText(text)
        view.setTextSize(sp.toFloat())
        view.setTextColor(color)
        view.setGravity(Gravity.CENTER_VERTICAL)
        view.setPadding(dp(8), dp(6), dp(8), dp(6))
        return view
    }

    private fun setTopControlIcon(view: TextView?, drawableRes: Int) {
        if (view == null) return
        val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, drawableRes)?.mutate()
        icon?.setBounds(0, 0, dp(18), dp(18))
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawablePadding = dp(5)
    }

    private fun button(text: String?, listener: View.OnClickListener?): TextView {
        val view = label(text, 18, TEXT_WHITE)
        view.setFocusable(true)
        view.setClickable(true)
        view.setMinHeight(dp(50))
        view.setOnClickListener(listener)
        // 焦点态:圆角青绿色背景;失去焦点:透明
        view.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                val gd = GradientDrawable()
                gd.setColor(FOCUS)
                gd.setCornerRadius(dp(6).toFloat())
                gd.setStroke(dp(1), Color.argb(100, 255, 255, 255))
                v!!.setBackground(gd)
                view.setTextColor(Color.rgb(8, 12, 18))
            } else {
                v!!.setBackgroundColor(Color.TRANSPARENT)
                view.setTextColor(TEXT_WHITE)
            }
        })
        return view
    }

    /**
     * 创建圆角面板背景 drawable
     * @param bgColor 背景色
     * @param cornerRadiusDp 圆角半径(dp)
     * @param strokeColor 描边色(0 表示无描边)
     * @param strokeWidthDp 描边宽度(dp)
     * @return GradientDrawable 圆角背景
     */
    private fun roundedBg(
        bgColor: Int,
        cornerRadiusDp: Int,
        strokeColor: Int,
        strokeWidthDp: Int
    ): GradientDrawable {
        val gd = GradientDrawable()
        gd.setColor(bgColor)
        gd.setCornerRadius(dp(cornerRadiusDp).toFloat())
        if (strokeColor != 0 && strokeWidthDp > 0) {
            gd.setStroke(dp(strokeWidthDp), strokeColor)
        }
        return gd
    }

    private fun listView(): ListView {
        val view = ListView(this)
        view.setBackgroundColor(PANEL_2)
        view.setCacheColorHint(Color.TRANSPARENT)
        // 分割线:半透明白色,1px 高
        view.setDivider(ColorDrawable(DIVIDER))
        view.setDividerHeight(dp(1))
        view.setFocusable(true)
        return view
    }

    private fun tvAdapter(): ArrayAdapter<String?> {
        return object :
            ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, ArrayList<String?>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(TEXT_WHITE)
                view.setTextSize(18f)
                view.setMinHeight(dp(48))
                view.setGravity(Gravity.CENTER_VERTICAL)
                view.setPadding(dp(14), dp(4), dp(14), dp(4))
                return view
            }
        }
    }

    /**
     * 初始化 TV 布局的视图组件和事件监听
     */
    private fun initTvLayout() {
        // 绑定主布局视图组件
        val subPageVideoPlaceholder = findViewById<KtvVideoView?>(R.id.video_background)
        playerLayerRoot = findViewById(R.id.layout_root)
        homePlayerHost = findViewById(R.id.home_player_container)
        subPagePlayerHost = findViewById(R.id.player_container)
        textDownloadSpeed = findViewById<TextView?>(R.id.text_download_speed)
        downloadProgressContainer = findViewById<LinearLayout?>(R.id.download_progress_container)
        textDownloadStatus = findViewById<TextView?>(R.id.text_download_status)
        downloadProgressBar = findViewById<ProgressBar?>(R.id.download_progress_bar)
        tabContainer = findViewById<LinearLayout?>(R.id.tab_container)
        // 子页面沿用顶部已点数字徽章。
        textOrderBadge = null
        searchInput = findViewById<EditText?>(R.id.search_input)
        btnDoSearch = findViewById<TextView?>(R.id.btn_do_search)
        categoryList = findViewById<LinearLayout>(R.id.category_list)
        textPageInfo = findViewById<TextView>(R.id.text_page_info)
        btnPrevPage = findViewById<TextView?>(R.id.btn_prev_page)
        btnNextPage = findViewById<TextView?>(R.id.btn_next_page)
        songList = findViewById<ListView?>(R.id.song_list)
        playerSongTitle = findViewById<TextView?>(R.id.player_song_title)
        playerSongSinger = findViewById<TextView?>(R.id.player_song_singer)
        breadcrumbCurrent = findViewById<TextView?>(R.id.breadcrumb_current)
        // 新布局不显示独立列表标题，不能让业务标题覆盖面包屑。
        listTitle = TextView(this)
        val breadcrumbHome = findViewById<TextView?>(R.id.breadcrumb_home)
        if (breadcrumbHome != null) breadcrumbHome.setOnClickListener { navigateBackToHome() }

        // ===== 顶栏/底栏控件 =====
        topBar = findViewById<View?>(R.id.top_bar)
        bottomBar = findViewById<View?>(R.id.bottom_bar)
        textLogo = findViewById<ImageView?>(R.id.text_logo)
        topBrandName = findViewById<TextView?>(R.id.top_brand_name)
        contentFrame = findViewById<FrameLayout?>(R.id.content_frame)
        browsePagination = findViewById<View?>(R.id.browse_pagination)
        btnTopSearch = findViewById<TextView?>(R.id.btn_top_search)
        btnTopOrder = findViewById<LinearLayout?>(R.id.btn_top_order)
        textTopOrderLabel = findViewById<TextView?>(R.id.text_top_order_label)
        textTopOrderBadge = findViewById<TextView?>(R.id.text_top_order_badge)
        btnTopVocal = findViewById<TextView?>(R.id.btn_top_vocal)
        btnTopNext = findViewById<TextView?>(R.id.btn_top_next)
        btnTopPause = findViewById<TextView?>(R.id.btn_top_pause)
        btnTopReplay = findViewById<TextView?>(R.id.btn_top_replay)
        btnTopTone = findViewById<TextView?>(R.id.btn_top_tone)
        btnTopVocal?.text = vocalActionText()
        btnTopPause?.text = if (playWhenPrepared) "暂停" else "播放"
        setTopControlIcon(textTopOrderLabel, R.drawable.ott_ic_ctrl_orderlist)
        setTopControlIcon(btnTopVocal, vocalActionIcon())
        setTopControlIcon(btnTopNext, R.drawable.ott_ic_ctrl_switch_song)
        setTopControlIcon(
            btnTopPause,
            if (playWhenPrepared) R.drawable.ott_ic_ctrl_pause else R.drawable.ott_ic_ctrl_play,
        )
        setTopControlIcon(btnTopReplay, R.drawable.ott_ic_ctrl_replay)
        setTopControlIcon(btnTopTone, R.drawable.ott_ic_ctrl_volume)
        btnBottomGuess = findViewById<TextView?>(R.id.btn_bottom_guess)
        btnBottomMobileOrder = findViewById<TextView?>(R.id.btn_bottom_mobile_order)
        btnBottomSettings = findViewById<TextView?>(R.id.btn_bottom_settings)
        btnBottomExit = findViewById<TextView?>(R.id.btn_bottom_exit)

        // ===== 首页 3 列布局 =====
        homeLayout = findViewById<LinearLayout?>(R.id.home_layout)
        val homeVideoPlaceholder = findViewById<KtvVideoView?>(R.id.home_video_background)
        homePlayerTitle = findViewById<TextView?>(R.id.home_player_song_title)
        homePlayerSinger = findViewById<TextView?>(R.id.home_player_song_singer)
        homeCardRank = findViewById<TextView?>(R.id.home_card_rank)
        homeCardSong = findViewById<TextView?>(R.id.home_card_song)
        homeCardSinger = findViewById<TextView?>(R.id.home_card_singer)
        homeCardLocal = findViewById<TextView?>(R.id.home_card_local)
        homeCardRegular = findViewById<TextView?>(R.id.home_card_regular)
        homeCardFavorite = findViewById<TextView?>(R.id.home_card_favorite)
        homeCardCategory = findViewById<TextView?>(R.id.home_card_category)
        textPosterPage = findViewById<TextView?>(R.id.text_poster_page)

        // ===== 子页面外壳 =====
        textCurrentSongTip = findViewById<TextView?>(R.id.text_current_song_tip)
        btnBack = findViewById<TextView?>(R.id.btn_back)
        textBrandRight = findViewById<TextView?>(R.id.text_brand_right)
        leftColumn = findViewById<LinearLayout?>(R.id.left_column)
        rightColumn = findViewById<LinearLayout?>(R.id.right_column)
        subCategoryScroll = findViewById<HorizontalScrollView?>(R.id.sub_category_scroll)
        keyboardArea = findViewById<View?>(R.id.keyboard_area)
        rankCategoryGrid = findViewById(R.id.rank_category_grid)
        paginationBar = findViewById<View?>(R.id.pagination_bar)
        fullScreenContainer = findViewById<FrameLayout?>(R.id.container)

        // 设置顶栏按钮事件
        if (btnTopSearch != null) btnTopSearch!!.setOnClickListener(View.OnClickListener { v: View? -> showQuickSearchPage() })
        if (btnTopOrder != null) btnTopOrder!!.setOnClickListener(View.OnClickListener { v: View? -> showOrderListPage() })
        if (btnTopVocal != null) btnTopVocal!!.setOnClickListener(View.OnClickListener { v: View? -> toggleOriginalVocal() })
        if (btnTopNext != null) btnTopNext!!.setOnClickListener(View.OnClickListener { v: View? -> playNext() })
        if (btnTopPause != null) btnTopPause!!.setOnClickListener(View.OnClickListener { v: View? -> togglePlay() })
        if (btnTopReplay != null) btnTopReplay!!.setOnClickListener(View.OnClickListener { v: View? -> replay() })
        if (btnTopTone != null) btnTopTone!!.setOnClickListener(View.OnClickListener { v: View? -> showAudioControlDialog() })

        // 设置底栏按钮事件
        if (btnBottomGuess != null) btnBottomGuess!!.setOnClickListener(View.OnClickListener { v: View? -> showGuessPage() })
        if (btnBottomMobileOrder != null) btnBottomMobileOrder!!.setOnClickListener(View.OnClickListener { showMobileRemoteDialog() })
        if (btnBottomSettings != null) btnBottomSettings!!.setOnClickListener(View.OnClickListener { v: View? -> showSettingsPage() })
        if (btnBottomExit != null) btnBottomExit!!.setOnClickListener(View.OnClickListener { v: View? -> showExitDialog() })

        // 设置首页4个分类卡片点击事件
        if (homeCardRank != null) homeCardRank!!.setOnClickListener(View.OnClickListener { v: View? -> loadHotSongs() })
        if (homeCardSong != null) homeCardSong!!.setOnClickListener(View.OnClickListener { v: View? -> showSearchPage() })
        if (homeCardSinger != null) homeCardSinger!!.setOnClickListener(View.OnClickListener { v: View? -> loadSingers() })
        if (homeCardLocal != null) homeCardLocal!!.setOnClickListener(View.OnClickListener { v: View? -> showLocalPage() })
        if (homeCardRegular != null) homeCardRegular!!.setOnClickListener(View.OnClickListener { v: View? -> loadSangHistory() })
        if (homeCardFavorite != null) homeCardFavorite!!.setOnClickListener(View.OnClickListener { v: View? -> loadFavorites() })
        if (homeCardCategory != null) homeCardCategory!!.setOnClickListener(View.OnClickListener { v: View? -> loadCategoryPlaylists() })
        val homePoster = findViewById<View?>(R.id.home_poster_container)
        homePosterView = homePoster
        textPosterPage?.text = "1/${homeBanners.size}"
        main.removeCallbacks(homeBannerRunnable)
        main.postDelayed(homeBannerRunnable, 5000L)
        if (homePoster != null) homePoster.setOnClickListener(View.OnClickListener { v: View? -> loadHotSongs() })
        // 原版始终保留同一个视频窗口，仅在首页、子页和全屏间调整边界。
        // 两个 XML View 只用于提供精确的布局锚点，避免页面操作重建解码 Surface。
        homeVideoPlaceholder?.visibility = View.GONE
        subPageVideoPlaceholder?.visibility = View.GONE
        val persistentVideo = KtvVideoView(this).apply {
            setOnClickListener {
                if (isFullScreen) exitFullScreenPlayer() else showFullScreenPlayer()
            }
        }
        val root = playerLayerRoot
        if (root != null) {
            val maskIndex = root.indexOfChild(findViewById<View>(R.id.mask))
            val insertionIndex = if (maskIndex >= 0) maskIndex else root.childCount
            root.addView(persistentVideo, insertionIndex, FrameLayout.LayoutParams(1, 1))
            playerFocusBorder = View(this).apply {
                setBackgroundResource(R.drawable.bg_player_focus)
                isClickable = false
                isFocusable = false
                visibility = View.GONE
                elevation = dp(6).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
            playbackModeNotice = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.WHITE)
                visibility = View.GONE
                alpha = 0f
                elevation = dp(8).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(104), dp(104))) }
            songIntroNotice = TextView(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(94), 0, dp(20), 0)
                textSize = 18f
                setTextColor(Color.WHITE)
                maxLines = 2
                visibility = View.GONE
                alpha = 0f
                elevation = dp(7).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(dp(330), dp(92))) }
            songIntroIcon = TextView(this).apply {
                text = "♫"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(254, 78, 184), Color.rgb(87, 52, 230))
                ).apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Color.argb(210, 255, 255, 255)) }
                visibility = View.GONE
                alpha = 0f
                elevation = dp(9).toFloat()
            }.also { root.addView(it, insertionIndex + 2, FrameLayout.LayoutParams(dp(78), dp(78))) }
            playerTouchOverlay = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                setOnClickListener { showFullScreenPlayer() }
                elevation = dp(10).toFloat()
            }.also { root.addView(it, insertionIndex + 1, FrameLayout.LayoutParams(1, 1)) }
        }
        homeVideo = persistentVideo
        videoBackground = persistentVideo
        player = persistentVideo
        persistentVideo.bind(playbackEngine)
        playbackEngine.attach(persistentVideo)
        movePlayerToHost(homePlayerHost)
        listOfNotNull(homePlayerHost, subPagePlayerHost).forEach(::bindPlayerHostFocus)
        // The persistent video surface sits above its placeholder; forward touch entry to full screen too.
        persistentVideo.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) showFullScreenPlayer()
            true
        }

        // 设置子页面返回按钮
        if (btnBack != null) btnBack!!.setOnClickListener { navigateBack() }

        // 设置 Tab 导航
        setupTabs()
        // 构建虚拟键盘
        buildKeyboard()

        // 设置搜索/翻页事件
        if (btnDoSearch != null) btnDoSearch!!.setOnClickListener(View.OnClickListener { v: View? -> performSearch() })
        if (searchInput != null) {
            searchInput!!.setOnEditorActionListener(OnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
                performSearch()
                true
            })
            searchInput!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    if (!suppressSearchWatcher) scheduleKeyboardSearch()
                }
            })
        }
        if (btnPrevPage != null) btnPrevPage!!.setOnClickListener(View.OnClickListener { v: View? -> prevPage() })
        if (btnNextPage != null) btnNextPage!!.setOnClickListener(View.OnClickListener { v: View? -> nextPage() })
        listOfNotNull(
            btnTopSearch, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
            btnBottomGuess, btnBottomMobileOrder, btnBottomSettings, btnBottomExit, btnBack, btnPrevPage, btnNextPage,
            homeCardRank, homeCardSong, homeCardSinger, homeCardLocal, homeCardRegular,
            homeCardFavorite, homeCardCategory,
        ).forEach(::installPressFeedback)
        listOfNotNull(homePlayerHost, subPagePlayerHost, homePoster).forEach(TvFocusStyler::preserveBackground)
        (homePoster as? FrameLayout)?.foreground = getDrawable(R.drawable.bg_player_focus)
        configureTvFocusNavigation()
        prepareTvFocusableTree(window.decorView)

        // 设置歌曲列表点击事件
        songList!!.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if (browseMode == "settings" || browseMode == "settings_section") {
                activeSettingsEntries.getOrNull(position)?.takeIf { it.checked == null }?.action?.invoke()
                return@OnItemClickListener
            }
            if (browseMode == "quick" || browseMode == "category_list") return@OnItemClickListener
            if ("singer_list" == browseMode) {
                if (position < visibleSingers.size) {
                    val singer = visibleSingers.get(position)!!
                    loadSingerSongs(singer[0], singer[1])
                }
                return@OnItemClickListener
            }
            if (position < visibleSongs.size) {
                val song = visibleSongs.get(position)
                if (currentTabIndex == -1) {
                    // 首页: 点击卡片导航
                    showHomePage()
                } else if (currentTabIndex == 4) {
                    // 已点列表点击只调整为下一首，切歌只能由明确的“切歌”操作触发。
                    if (currentSong != null && currentSong!!.equals(song)) {
                        toast("正在播放: " + song.title)
                    } else {
                        moveQueuedSongToNext(song)
                    }
                } else if (currentTabIndex == 5) {
                    // 已唱历史: 重新点歌
                    addToQueue(song)
                    renderSongList()
                } else if (currentTabIndex == 6) {
                    // 下载列表中的歌曲同样按点播入队，不能抢占当前播放。
                    addToQueue(song)
                } else if (currentTabIndex == 7) {
                    // 收藏: 点歌
                    addToQueue(song)
                    renderSongList()
                } else {
                    addToQueue(song)
                    renderSongList()
                }
            }
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: kotlin.Long ->
            if ("settings" == browseMode || "singer_list" == browseMode) return@OnItemLongClickListener false
            if (position < 0 || position >= visibleSongs.size) return@OnItemLongClickListener false
            val song = visibleSongs.get(position)
            if (currentTabIndex == 4) {
                if (currentSong != null && currentSong!!.equals(song)) {
                    toast("正在播放的歌曲不能删除")
                    return@OnItemLongClickListener true
                }
                orderQueue!!.remove(song)
                saveState()
                loadOrderedList()
                toast("已删除: " + song.title)
            } else {
                showSongActions(song)
            }
            true
        })
    }

    /**
     * 首页 - 仿原版3列布局(视频+4卡片+新歌榜海报)
     */
    private fun showHomePage() {
        if (!restoringFocusRoute) focusReturnStack.clear()
        browseRequestVersion++
        main.removeCallbacks(keyboardSearchRunnable)
        currentPage = "首页"
        currentTabIndex = -1
        currentCategories.clear()
        currentCategoryIndex = -1
        clearHomeFocusTransition()
        updateCategories()
        // 隐藏子页面外壳,显示首页 3 列
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.GONE)
        topBar?.apply {
            visibility = View.VISIBLE
            translationY = 0f
            setBackgroundResource(R.drawable.bg_top_bar)
        }
        textLogo?.visibility = View.VISIBLE
        topBrandName?.visibility = View.VISIBLE
        btnTopSearch?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        updateContentFrameMargins(chromeBarHeight(), chromeBarHeight())
        if (homeLayout != null) homeLayout!!.setVisibility(View.VISIBLE)
        configureTvFocusNavigation()
        movePlayerToHost(homePlayerHost)
        // 同步当前播放信息到首页
        syncPlayerInfo()
        // 隐藏已点的子页面辅助控件
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
        }
    }

    /**
     * 切换到子页面外壳(左视频+键盘 / 右内容)
     * @param breadcrumb 面包屑显示文字(主页 / xxx)
     */
    private fun showSubPageShell(breadcrumb: String) {
        rememberReturnPointIfNeeded(breadcrumb)
        browseRequestVersion++
        visibleSongs.clear()
        visibleSingers.clear()
        songList?.adapter = tvAdapter()
        currentPage = breadcrumb
        if (homeLayout != null) homeLayout!!.setVisibility(View.GONE)
        clearHomeTopFocusNavigation()
        topBar?.apply {
            visibility = View.VISIBLE
            // 紧凑横屏的内容区顶部内边距只有 10dp；继续下移 22dp 会让顶部
            // 控制按钮压到面包屑/返回栏上。窄屏只做轻微下移，保留两栏间距。
            translationY = dp(if (compactUi) 8 else 22).toFloat()
            setBackgroundColor(Color.TRANSPARENT)
        }
        textLogo?.visibility = View.GONE
        topBrandName?.visibility = View.GONE
        btnTopSearch?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        updateContentFrameMargins(0, 0)
        val shell = findViewById<View?>(R.id.main_layout)
        if (shell != null) shell.setVisibility(View.VISIBLE)
        if (breadcrumbCurrent != null) {
            breadcrumbCurrent!!.text = breadcrumb.removePrefix("主页").ifEmpty { " / 首页" }
        }
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.VISIBLE)
        rankCategoryGrid?.visibility = View.GONE
        if (tabContainer != null) tabContainer!!.setVisibility(View.VISIBLE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.VISIBLE
        if (songList != null) {
            songList!!.setBackgroundResource(R.drawable.bg_song_list)
            songList!!.setDivider(ColorDrawable(Color.argb(34, 255, 255, 255)))
            songList!!.setDividerHeight(dp(1))
            songList!!.selector = ColorDrawable(Color.TRANSPARENT)
            songList!!.itemsCanFocus = true
            songList!!.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        movePlayerToHost(subPagePlayerHost)
        window.decorView.post {
            prepareTvFocusableTree(window.decorView)
        }
    }

    private fun updateContentFrameMargins(top: Int, bottom: Int) {
        val frame = contentFrame ?: return
        val params = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin == top && params.bottomMargin == bottom) return
        params.topMargin = top
        params.bottomMargin = bottom
        frame.layoutParams = params
    }

    private fun movePlayerToHost(host: View?) {
        if (host == null || isFullScreen) return
        activePlayerHost = host
        host.post { applyPlayerBounds(host) }
    }

    private fun bindPlayerHostFocus(host: View) {
        host.isClickable = true
        host.isFocusable = true
        host.isFocusableInTouchMode = false
        host.setOnClickListener { showFullScreenPlayer() }
        host.setOnFocusChangeListener { _, hasFocus ->
            playerFocusBorder?.apply {
                isActivated = hasFocus
                visibility = if (hasFocus && !isFullScreen) View.VISIBLE else View.GONE
            }
            if (hasFocus && !isFullScreen) {
                activePlayerHost = host
                applyPlayerBounds(host)
            }
        }
    }

    private fun applyPlayerBounds(host: View) {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        if (host.width <= 0 || host.height <= 0 || host.visibility != View.VISIBLE) return
        val rootLocation = IntArray(2)
        val hostLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        host.getLocationInWindow(hostLocation)
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(host.width, host.height)
        params.width = host.width
        params.height = host.height
        params.leftMargin = hostLocation[0] - rootLocation[0]
        params.topMargin = hostLocation[1] - rootLocation[1]
        video.layoutParams = params
        video.visibility = View.VISIBLE
        playerFocusBorder?.let { border ->
            val borderParams = (border.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            borderParams.width = host.width
            borderParams.height = host.height
            borderParams.leftMargin = params.leftMargin
            borderParams.topMargin = params.topMargin
            border.layoutParams = borderParams
            border.isActivated = host.hasFocus()
            border.visibility = if (host.hasFocus() && !isFullScreen) View.VISIBLE else View.GONE
        }
        playerTouchOverlay?.let { overlay ->
            val overlayParams = (overlay.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(host.width, host.height)
            overlayParams.width = host.width
            overlayParams.height = host.height
            overlayParams.leftMargin = params.leftMargin
            overlayParams.topMargin = params.topMargin
            overlay.layoutParams = overlayParams
            overlay.visibility = if (isFullScreen) View.GONE else View.VISIBLE
        }
        updatePlaybackNoticeBounds()
    }

    private fun expandPlayerToFullScreen() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val params = (video.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.leftMargin = 0
        params.topMargin = 0
        video.layoutParams = params
        playerFocusBorder?.visibility = View.GONE
        video.bringToFront()
        songIntroNotice?.bringToFront()
        songIntroIcon?.bringToFront()
        playbackModeNotice?.bringToFront()
        updatePlaybackNoticeBounds()
        fullScreenContainer?.bringToFront()
    }

    private fun updatePlaybackNoticeBounds() {
        val root = playerLayerRoot ?: return
        val video = player ?: return
        val videoParams = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = if (videoParams.width > 0) videoParams.width else root.width
        val height = if (videoParams.height > 0) videoParams.height else root.height
        playbackModeNotice?.let { notice ->
            val size = dp(104)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = size; params.height = size
                params.leftMargin = videoParams.leftMargin + (width - size) / 2
                params.topMargin = videoParams.topMargin + (height - size) / 2
                notice.layoutParams = params
            }
        }
        songIntroNotice?.let { notice ->
            val noticeWidth = min(dp(330), (width * 0.72f).toInt())
            val noticeHeight = dp(92)
            (notice.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = noticeWidth; params.height = noticeHeight
                params.leftMargin = videoParams.leftMargin + (width - noticeWidth) / 2
                params.topMargin = videoParams.topMargin + (height - noticeHeight) / 2
                notice.layoutParams = params
                songIntroIcon?.let { icon ->
                    (icon.layoutParams as? FrameLayout.LayoutParams)?.let { iconParams ->
                        iconParams.width = dp(78); iconParams.height = dp(78)
                        iconParams.leftMargin = params.leftMargin + dp(8)
                        iconParams.topMargin = params.topMargin + dp(7)
                        icon.layoutParams = iconParams
                    }
                }
            }
        }
    }

    private fun showPlaybackModeNotice(mode: String, persistent: Boolean = false) {
        val notice = playbackModeNotice ?: return
        main.removeCallbacks(hidePlaybackModeNotice)
        notice.text = when (mode) {
            "暂停" -> "Ⅱ\n暂停"
            "原唱" -> "♟\n原唱"
            else -> "♛\n伴唱"
        }
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(151, 126, 255), Color.rgb(74, 39, 220))
        ).apply { shape = GradientDrawable.OVAL }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        notice.alpha = 0f
        notice.animate().alpha(1f).setDuration(140L).start()
        if (!persistent) main.postDelayed(hidePlaybackModeNotice, 1400L)
    }

    private fun hidePlaybackNoticeNow() {
        main.removeCallbacks(hidePlaybackModeNotice)
        playbackModeNotice?.visibility = View.GONE
        playbackModeNotice?.alpha = 0f
    }

    private fun showSongIntro(song: Song?) {
        if (!songTitleSubtitleEnabled || song == null) return
        val notice = songIntroNotice ?: return
        main.removeCallbacks(hideSongIntroNotice)
        notice.text = "${song.title}\n${song.singer.orEmpty()}"
        notice.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(163, 67, 225), Color.rgb(242, 48, 174), Color.argb(20, 242, 48, 174))
        ).apply { cornerRadius = dp(3).toFloat() }
        updatePlaybackNoticeBounds()
        notice.visibility = View.VISIBLE
        songIntroIcon?.visibility = View.VISIBLE
        notice.alpha = 0f
        songIntroIcon?.alpha = 0f
        notice.animate().alpha(1f).setDuration(180L).start()
        songIntroIcon?.animate()?.alpha(1f)?.setDuration(180L)?.start()
        main.postDelayed(hideSongIntroNotice, 3800L)
    }

    /**
     * 同步当前播放信息到首页和子页面的播放器覆盖层
     */
    private fun syncPlayerInfo() {
        val title = if (currentSong == null) "未播放" else currentSong!!.title
        val singer = if (currentSong == null) "" else currentSong!!.singer
        if (homePlayerTitle != null) homePlayerTitle!!.text = currentAndNextText()
        if (homePlayerSinger != null) homePlayerSinger!!.setText(singer)
        if (playerSongTitle != null) playerSongTitle!!.setText(title)
        if (playerSongSinger != null) playerSongSinger!!.setText(singer)
        // 顶部状态栏 - 当前/下首
        if (textCurrentSongTip != null) {
            val cur =
                "当前: " + title + (if (singer == null || singer.isEmpty()) "" else " - " + singer)
            val nxt = if (orderQueue!!.size >= 2)
                orderQueue.get(1).title + (if (orderQueue.get(1).singer == null || orderQueue.get(
                        1
                    ).singer!!.isEmpty()
                ) "" else " - " + orderQueue.get(1).singer)
            else
                "无"
            textCurrentSongTip!!.setText(cur + "  下首: " + nxt)
        }
        fullScreenSongInfo?.text = currentAndNextText()
        updateOrderBadge()
        if (browseMode == "ordered" || browseMode == "sang" || browseMode == "downloads") {
            renderSongList()
        }
    }

    private fun currentAndNextText(): String {
        val current = currentSong?.let { song ->
            song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        } ?: "未播放"
        val currentIndex = currentSong?.let { orderQueue?.indexOf(it) } ?: -1
        val next = when {
            currentIndex >= 0 -> orderQueue?.getOrNull(currentIndex + 1)
            currentSong == null -> orderQueue?.firstOrNull()
            else -> orderQueue?.firstOrNull { currentSong?.equals(it) != true }
        }?.let { song -> song.title.orEmpty() + song.singer?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty() }
            ?: "无"
        return "当前：$current    下一首：$next"
    }

    /** 顶栏搜索按钮 - 跳到搜索页  */
    private fun showSearchPage() {
        searchScope = "song"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 歌名")
        currentTabIndex = 0
        setupTabs()
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategoryIndex = 0
        searchLanguage = "全部"
        updateCategories()
        performSearch()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 0 || searchScope != "song") return@post
                currentCategories.clear()
                currentCategories.add("全部")
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showQuickSearchPage() {
        searchScope = "quick"
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 快搜")
        currentTabIndex = 8
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "quick"
        browsePage = 0
        loadQuickSearchPage("")
    }

    /** 顶栏已点按钮  */
    private fun showOrderListPage() {
        showSubPageShell("主页 / 已点")
        currentTabIndex = 4
        setupTabs()
        loadOrderedList()
    }

    /** 首页"本地"卡片 - 显示已下载歌曲  */
    private fun showLocalPage() {
        searchScope = "local"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 10
        showSubPageShell("主页 / 本地")
        setupTabs()
        keyboardArea?.visibility = View.VISIBLE
        browseMode = "local"
        browseParam = "全部"
        browsePage = 0
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategoryIndex = 0
        searchLanguage = "全部"
        updateCategories()
        loadLocalCatalogPage()
        io.execute {
            val languages = if (library.muse.isAvailable()) library.muse.languages() else emptyList()
            main.post {
                if (currentTabIndex != 10 || searchScope != "local") return@post
                currentCategories.clear()
                currentCategories.add("全部")
                currentCategories.addAll(languages)
                currentCategoryIndex = currentCategories.indexOf(searchLanguage).coerceAtLeast(0)
                updateCategories()
            }
        }
    }

    private fun showAllSongsPage() {
        showSearchPage()
    }

    /** 底栏"猜你想唱" - 加载推荐歌曲  */
    private fun showGuessPage() {
        val root = layoutInflater.inflate(R.layout.dialog_song_recommend, null)
        val grid = root.findViewById<GridLayout>(R.id.recommend_song_grid)
        val confirm = root.findViewById<TextView>(R.id.button_recommend_confirm)
        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<View>(R.id.image_recommend_close).setOnClickListener { dialog.dismiss() }
        confirm.isEnabled = false
        confirm.alpha = 0.55f
        val selected = ArrayList<Song>()
        confirm.setOnClickListener {
            selected.forEach(::addToQueue)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.87f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.90f).toInt(),
                )
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.56f }
            }
            TvFocusStyler.disablePlatformHighlightTree(root)
            root.alpha = 0f
            root.scaleX = 0.96f
            root.scaleY = 0.96f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
            root.findViewById<View>(R.id.image_recommend_close).apply {
                isFocusable = true
                requestFocus()
            }
        }
        dialog.restoreSourceFocusOnDismiss()
        dialog.show()

        val historySnapshot = sangHistory.toList()
        val queueSnapshot = orderQueue.orEmpty().toList()
        val immediateRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
            library.allSongs().asSequence())
            .distinctBy(::stableId)
            .take(10)
            .toList()
        selected.addAll(immediateRecommendations)
        populateRecommendationGrid(grid, immediateRecommendations, historySnapshot)
        confirm.text = "一键点歌(${immediateRecommendations.size}首)"
        confirm.isEnabled = immediateRecommendations.isNotEmpty()
        confirm.alpha = if (immediateRecommendations.isEmpty()) 0.55f else 1f
        io.execute {
            val quickSongs = library.muse.quickSongs(30)
            val quickRecommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                quickSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(quickRecommendations)
                populateRecommendationGrid(grid, quickRecommendations, historySnapshot)
                confirm.text = "一键点歌(${quickRecommendations.size}首)"
                confirm.isEnabled = quickRecommendations.isNotEmpty()
                confirm.alpha = if (quickRecommendations.isEmpty()) 0.55f else 1f
            }
            val hotSongs = library.muse.hotSongs(0, 30)
            val recommendations = (historySnapshot.asSequence() + queueSnapshot.asSequence() +
                hotSongs.asSequence() + immediateRecommendations.asSequence())
                .distinctBy(::stableId)
                .take(10)
                .toList()
            main.post {
                if (!dialog.isShowing) return@post
                selected.clear()
                selected.addAll(recommendations)
                populateRecommendationGrid(grid, recommendations, historySnapshot)
                confirm.text = "一键点歌(${recommendations.size}首)"
                confirm.isEnabled = recommendations.isNotEmpty()
                confirm.alpha = if (recommendations.isEmpty()) 0.55f else 1f
            }
        }
    }

    private fun populateRecommendationGrid(
        grid: GridLayout,
        songs: List<Song>,
        history: List<Song>,
    ) {
        grid.removeAllViews()
        songs.take(10).forEachIndexed { index, song ->
            val item = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.ott_bg_item_not_selected)
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(5), dp(104), dp(5))
                    addView(label(song.title, 16, Color.WHITE).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(25)))
                    addView(label(song.singer, 13, Color.rgb(193, 193, 193)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(22)))
                }, FrameLayout.LayoutParams(-1, -1))
                val historyCount = history.count { stableId(it) == stableId(song) }
                val recommendationLabel = when {
                    historyCount > 0 -> "您共唱过${historyCount}次"
                    index == 3 -> "飙升榜TOP1"
                    index == 5 -> "新歌榜TOP5"
                    index == 7 -> "抖音榜TOP1"
                    index >= 8 -> "人气热歌"
                    else -> "热门TOP${index + 1}"
                }
                addView(label(
                    recommendationLabel,
                    12,
                    if (historyCount > 0) Color.rgb(193, 193, 193) else Color.rgb(253, 51, 89),
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, 0, dp(8), 0)
                }, FrameLayout.LayoutParams(dp(104), -1, Gravity.END))
                setOnClickListener {
                    addToQueue(song)
                    toast("已点歌：${song.title}")
                }
            }
            installPressFeedback(item)
            grid.addView(item, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / 2)
                columnSpec = GridLayout.spec(index % 2)
                width = (((resources.displayMetrics.widthPixels * 0.87f).toInt() - dp(28)) / 2)
                height = max(dp(48), (((resources.displayMetrics.heightPixels * 0.90f).toInt() - dp(163)) / 5) - dp(6))
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
    }

    /**
     * 设置顶部 Tab 导航栏
     */
    private fun setupTabs() {
        if (tabContainer == null) return
        val tabs: Array<out String?>
        val targets: IntArray
        if (currentTabIndex >= 4 && currentTabIndex <= 6) {
            tabs = arrayOf<String>("已点", "已唱", "下载")
            targets = intArrayOf(4, 5, 6)
        } else if (currentTabIndex == 1 || currentTabIndex == 2 || currentTabIndex == 3 ||
            currentTabIndex == 7 || currentTabIndex == 8 || currentTabIndex == 9
        ) {
            tabContainer!!.setVisibility(View.GONE)
            return
        } else {
            tabs = arrayOf<String>("全网", "本地")
            targets = intArrayOf(0, 10)
        }
        tabContainer!!.setVisibility(View.VISIBLE)

        fun bindTab(tab: TextView, label: String, index: Int) {
            tab.text = label
            tab.textSize = 14f
            tab.minWidth = 0
            tab.setPadding(0, 0, 0, 0)
            tab.gravity = Gravity.CENTER
            tab.setAccessibleFocus("focus:tab:$label", "分类，$label")
            tab.isFocusable = true
            tab.isClickable = true
            updateTabStyle(tab, index == currentTabIndex)
            tab.setOnClickListener {
                currentTabIndex = index
                when (index) {
                    0 -> showAllSongsPage()
                    1 -> loadSingers()
                    2 -> loadLanguages()
                    3 -> loadWordCounts()
                    4 -> loadOrderedList()
                    5 -> loadSangHistory()
                    6 -> loadDownloadedList()
                    7 -> loadFavorites()
                    8 -> showSettingsPage()
                    10 -> showLocalPage()
                }
            }
        }

        val canReuse = tabContainer!!.childCount == tabs.size && tabs.indices.all { position ->
            (tabContainer!!.getChildAt(position) as? TextView)?.text?.toString() == tabs[position]
        }
        if (canReuse) {
            tabs.indices.forEach { position ->
                bindTab(
                    tabContainer!!.getChildAt(position) as TextView,
                    tabs[position].orEmpty(),
                    targets[position],
                )
            }
            return
        }

        val focusedTab = window.decorView.findFocus()?.takeIf { isDescendantOf(it, tabContainer) }
            ?.let(::captureFocusBookmark)
        tabContainer!!.removeAllViews()
        for (i in tabs.indices) {
            val index = targets[i]
            val tab = TextView(this)
            bindTab(tab, tabs[i].orEmpty(), index)
            installPressFeedback(tab)
            tabContainer!!.addView(tab, LinearLayout.LayoutParams(dp(76), -1))
        }
        restoreFocusBookmark(focusedTab)
    }

    private fun installPressFeedback(view: View) {
        TvFocusStyler.install(view)
        if (view.isClickable) {
            view.isFocusable = true
            view.isFocusableInTouchMode = false
        }
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.84f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun configureTvFocusNavigation() {
        btnTopSearch?.nextFocusDownId = R.id.home_player_container
        btnTopOrder?.nextFocusDownId = R.id.home_card_rank
        btnTopVocal?.nextFocusDownId = R.id.home_card_rank
        btnTopNext?.nextFocusDownId = R.id.home_card_rank
        btnTopPause?.nextFocusDownId = R.id.home_card_rank
        btnTopReplay?.nextFocusDownId = R.id.home_poster_container
        btnTopTone?.nextFocusDownId = R.id.home_poster_container

        homePlayerHost?.nextFocusUpId = R.id.btn_top_search
        homePlayerHost?.nextFocusDownId = R.id.home_card_regular
        homePlayerHost?.nextFocusRightId = R.id.home_card_rank

        homeCardRank?.nextFocusUpId = R.id.btn_top_pause
        homeCardRank?.nextFocusLeftId = R.id.home_player_container
        homeCardRank?.nextFocusDownId = R.id.home_card_song
        homeCardRank?.nextFocusRightId = R.id.home_poster_container
        homeCardSong?.nextFocusUpId = R.id.home_card_rank
        homeCardSong?.nextFocusDownId = R.id.home_card_singer
        homeCardSong?.nextFocusRightId = R.id.home_poster_container
        homeCardSinger?.nextFocusUpId = R.id.home_card_song
        homeCardSinger?.nextFocusDownId = R.id.home_card_local
        homeCardSinger?.nextFocusRightId = R.id.home_poster_container
        homeCardLocal?.nextFocusUpId = R.id.home_card_singer
        homeCardLocal?.nextFocusLeftId = R.id.home_card_category
        homeCardLocal?.nextFocusDownId = R.id.btn_bottom_settings
        homeCardLocal?.nextFocusRightId = R.id.home_poster_container
        homeCardRegular?.nextFocusUpId = R.id.home_player_container
        homeCardRegular?.nextFocusRightId = R.id.home_card_favorite
        homeCardRegular?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardFavorite?.nextFocusUpId = R.id.btn_top_search
        homeCardFavorite?.nextFocusLeftId = R.id.home_card_regular
        homeCardFavorite?.nextFocusRightId = R.id.home_card_category
        homeCardFavorite?.nextFocusDownId = R.id.btn_bottom_guess
        homeCardCategory?.nextFocusUpId = R.id.home_card_rank
        homeCardCategory?.nextFocusLeftId = R.id.home_card_favorite
        homeCardCategory?.nextFocusRightId = R.id.home_card_local
        homeCardCategory?.nextFocusDownId = R.id.btn_bottom_settings
        homePosterView?.nextFocusUpId = R.id.btn_top_tone
        homePosterView?.nextFocusLeftId = R.id.home_card_song
        homePosterView?.nextFocusDownId = R.id.btn_bottom_exit

        btnBottomGuess?.nextFocusUpId = R.id.home_card_regular
        btnBottomGuess?.nextFocusRightId = R.id.btn_bottom_mobile_order
        btnBottomMobileOrder?.nextFocusUpId = R.id.home_card_local
        btnBottomMobileOrder?.nextFocusLeftId = R.id.btn_bottom_guess
        btnBottomMobileOrder?.nextFocusRightId = R.id.btn_bottom_settings
        btnBottomSettings?.nextFocusUpId = R.id.home_card_local
        btnBottomSettings?.nextFocusLeftId = R.id.btn_bottom_mobile_order
        btnBottomSettings?.nextFocusRightId = R.id.btn_bottom_exit
        btnBottomExit?.nextFocusUpId = R.id.home_poster_container
        btnBottomExit?.nextFocusLeftId = R.id.btn_bottom_settings
    }

    private fun clearHomeFocusTransition() {
        lastHomeFocusSource = null
        lastHomeFocusTarget = null
        lastHomeFocusReverseKey = KeyEvent.KEYCODE_UNKNOWN
    }

    private fun oppositeDpadKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_UP
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun clearHomeTopFocusNavigation() {
        listOfNotNull(
            btnTopSearch, btnTopOrder, btnTopVocal, btnTopNext, btnTopPause, btnTopReplay, btnTopTone,
        ).forEach { it.nextFocusDownId = View.NO_ID }
    }

    /**
     * 更新 Tab 样式(选中/未选中)
     */
    private fun updateTabStyle(tab: TextView, selected: Boolean) {
        val normalText = if (selected) Color.rgb(253, 51, 89) else Color.argb(180, 241, 241, 241)
        tab.setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(),
            ),
            intArrayOf(Color.WHITE, Color.WHITE, normalText),
        ))
        val normal = GradientDrawable().apply {
            setColor(if (selected) Color.argb(30, 253, 51, 89) else Color.TRANSPARENT)
            cornerRadius = dp(4).toFloat()
        }
        val focused = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(232, 49, 111), Color.rgb(255, 154, 48)),
        ).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), Color.argb(150, 255, 255, 255))
        }
        tab.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * 构建虚拟键盘 (拼音首字母 + 数字 + 功能键)
     * 4 行字母 + 1 行数字 = 完整 26 字母键盘
     */
    private fun buildKeyboard() {
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        if (grid == null) return
        renderKeyboard("abc")
        // 绑定输入模式切换
        val keyAbc = findViewById<TextView?>(R.id.key_abc)
        val key123 = findViewById<TextView?>(R.id.key_123)
        val keySpace = findViewById<TextView?>(R.id.key_space)
        val keyBackspace = findViewById<TextView?>(R.id.key_backspace)
        val keyClear = findViewById<TextView?>(R.id.key_clear)
        if (keyAbc != null) keyAbc.setOnClickListener(View.OnClickListener { v: View? ->
            hideSystemKeyboard()
            keyAbc.setBackgroundResource(R.drawable.bg_key_mode)
            keyAbc.setTextColor(Color.rgb(0x27, 0xCC, 0xA4))
            if (key123 != null) {
                key123.setBackgroundResource(R.drawable.bg_key_mode_dim)
                key123.setTextColor(Color.argb(153, 255, 255, 255))
            }
            renderKeyboard("abc")
        })
        if (key123 != null) key123.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keySpace != null) keySpace.setOnClickListener(View.OnClickListener { v: View? ->
            showSystemKeyboard()
        })
        if (keyBackspace != null) keyBackspace.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput == null) return@OnClickListener
            val s = searchInput!!.getText().toString()
            if (!s.isEmpty()) {
                searchInput!!.setText(s.substring(0, s.length - 1))
                searchInput!!.setSelection(searchInput!!.getText().length)
                scheduleKeyboardSearch()
            }
        })
        if (keyClear != null) keyClear.setOnClickListener(View.OnClickListener { v: View? ->
            if (searchInput != null) {
                searchInput!!.setText("")
                scheduleKeyboardSearch()
            }
        })
    }

    /**
     * 切换键盘模式(ABC/123)并重建键盘
     * @param mode "abc" 或 "123"
     */
    private var keyboardMode: String? = "abc"
    private fun renderKeyboard(mode: String?) {
        keyboardMode = mode
        val grid = findViewById<LinearLayout?>(R.id.keyboard_grid)
        if (grid == null) return
        grid.removeAllViews()
        val rows: Array<Array<String?>?>?
        if ("123" == mode) {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("1", "2", "3", "4", "5", "6", "7"),
                arrayOf<String?>("8", "9", "0", "", "", "", ""),
                arrayOf<String?>("", "", "", "", "", "", ""),
                arrayOf<String?>("ABC", "", "", "", "", "", ""),
            )
        } else {
            rows = arrayOf<Array<String?>?>(
                arrayOf<String?>("A", "B", "C", "D", "E", "F", "G"),
                arrayOf<String?>("H", "I", "J", "K", "L", "M", "N"),
                arrayOf<String?>("O", "P", "Q", "R", "S", "T", "U"),
                arrayOf<String?>("V", "W", "X", "Y", "Z", "123", ""),
            )
        }
        for (row in rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.setOrientation(LinearLayout.HORIZONTAL)
            rowLayout.setGravity(Gravity.CENTER)
            rowLayout.setWeightSum(row!!.size.toFloat())
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            rowLp.bottomMargin = dp(2)
            grid.addView(rowLayout, rowLp)
            for (key in row) {
                val btn = TextView(this)
                btn.setText(key)
                btn.setTextSize(15f)
                btn.setTextColor(Color.rgb(241, 241, 241))
                btn.setGravity(Gravity.CENTER)
                btn.setBackgroundColor(Color.TRANSPARENT)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                lp.leftMargin = 2
                lp.rightMargin = 2
                btn.setLayoutParams(lp)
                btn.visibility = if (key.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                btn.setOnClickListener {
                    when (key) {
                        "123" -> renderKeyboard("123")
                        "ABC" -> renderKeyboard("abc")
                        else -> appendToSearch(key)
                    }
                }
                btn.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> btn.setBackgroundResource(R.drawable.bg_key_char_pressed)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> btn.setBackgroundColor(Color.TRANSPARENT)
                    }
                    false
                }
                rowLayout.addView(btn)
            }
        }
    }

    /**
     * 向搜索框追加文本
     */
    private fun appendToSearch(key: String?) {
        if (searchInput == null) return
        val s = searchInput!!.getText().toString()
        searchInput!!.setText(s + key)
        searchInput!!.setSelection(searchInput!!.getText().length)
        scheduleKeyboardSearch()
    }

    private fun scheduleKeyboardSearch() {
        main.removeCallbacks(keyboardSearchRunnable)
        main.postDelayed(keyboardSearchRunnable, 350L)
    }

    private fun setSearchText(value: String) {
        val input = searchInput ?: return
        main.removeCallbacks(keyboardSearchRunnable)
        suppressSearchWatcher = true
        input.setText(value)
        input.setSelection(input.text.length)
        suppressSearchWatcher = false
    }

    private fun clearSearchForFilterChange() {
        activeSearchQuery = ""
        setSearchText("")
    }

    private fun showSystemKeyboard() {
        val input = searchInput ?: return
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSystemKeyboard() {
        val input = searchInput ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * 加载热门歌曲列表
     */
    private fun loadHotSongs() {
        searchScope = "none"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 9
        showSubPageShell("主页 / 排行榜")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        rankCategoryGrid?.visibility = View.VISIBLE
        listTitle!!.setText("排行榜")
        browseMode = "rank"
        browseParam = ""
        browsePage = 0
        currentCategories.clear()
        rankPlaylistIds.clear()
        currentCategoryIndex = 0
        updateCategories()
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, "曲库加载中...")
            return
        }
        io.execute {
            val playlists = library.muse.rankPlaylists()
            main.post {
                if (currentTabIndex != 9 || browseMode != "rank") return@post
                currentCategories.clear()
                rankPlaylistIds.clear()
                playlists.forEach { playlist ->
                    rankPlaylistIds.add(playlist[0].orEmpty())
                    currentCategories.add(playlist[1].orEmpty())
                }
                currentCategoryIndex = playlists.indexOfFirst {
                    it[2] == "2" || it[1] == "热歌榜"
                }.takeIf { it >= 0 } ?: 0
                browseParam = rankPlaylistIds.getOrNull(currentCategoryIndex).orEmpty()
                listTitle!!.text = currentCategories.getOrNull(currentCategoryIndex) ?: "排行榜"
                updateRankCategoryGrid()
                subCategoryScroll?.visibility = View.GONE
                loadBrowsePage()
            }
        }
    }

    private fun updateRankCategoryGrid() {
        val grid = rankCategoryGrid ?: return
        grid.removeAllViews()
        currentCategories.take(9).forEachIndexed { index, title ->
            val tile = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setBackgroundResource(
                    if (index == currentCategoryIndex) R.drawable.ott_bg_rank_item_selected
                    else R.drawable.ott_bg_rank_item_not_selected
                )
                val trophy = getDrawable(R.drawable.ic_default_rank_avatar)?.mutate()
                trophy?.setBounds(0, 0, dp(42), dp(42))
                setCompoundDrawables(null, trophy, null, null)
                compoundDrawablePadding = dp(1)
                setOnClickListener {
                    if (index == currentCategoryIndex) return@setOnClickListener
                    currentCategoryIndex = index
                    browseMode = "rank"
                    browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                    browsePage = 0
                    listTitle?.text = title
                    updateRankCategoryGrid()
                    loadBrowsePage()
                }
            }
            installPressFeedback(tile)
            grid.addView(tile, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / 3, 1f)
                columnSpec = GridLayout.spec(index % 3, 1f)
                width = 0
                height = 0
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun loadCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 2
        showSubPageShell("主页 / 分类")
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = ""
        browsePage = 0
        loadCategoryPlaylistPage("")
    }

    private fun returnToCategoryPlaylists() {
        searchScope = "playlist"
        activeSearchQuery = categoryParentQuery
        setSearchText(categoryParentQuery)
        currentTabIndex = 2
        showSubPageShell("主页 / 分类")
        setupTabs()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "category_list"
        browseParam = categoryParentQuery
        browsePage = categoryParentPage
        loadCategoryPlaylistPage(categoryParentQuery)
    }

    private fun loadCategoryPlaylistPage(query: String = activeSearchQuery) {
        browseMode = "category_list"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        val pageSize = 12
        io.execute {
            val playlists = library.muse.categoryPlaylists(requestedQuery, requestedPage * pageSize, pageSize)
            val total = library.muse.categoryPlaylistCount(requestedQuery)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "category_list" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visiblePlaylists.clear()
                visiblePlaylists.addAll(playlists)
                songList?.apply {
                    dividerHeight = 0
                    adapter = PlaylistGridAdapter(this@MainActivity, playlists) { playlist ->
                        openCategoryPlaylist(playlist)
                    }
                }
                updatePageInfo()
            }
        }
    }

    private fun openCategoryPlaylist(playlist: Array<String?>) {
        categoryParentPage = browsePage
        categoryParentQuery = activeSearchQuery
        clearSearchForFilterChange()
        showSubPageShell("主页 / 分类 / ${playlist.getOrNull(1).orEmpty()}")
        currentTabIndex = 2
        setupTabs()
        searchScope = "playlist_songs"
        searchContextId = playlist.getOrNull(0).orEmpty()
        currentCategories.clear()
        currentCategoryIndex = -1
        updateCategories()
        browseMode = "playlist_id"
        browseParam = playlist.getOrNull(0).orEmpty()
        browsePage = 0
        loadBrowsePage()
    }

    private fun loadQuickSearchPage(query: String = activeSearchQuery) {
        browseMode = "quick"
        browseParam = query
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedQuery = query.trim()
        io.execute {
            val songs = if (requestedQuery.isEmpty()) {
                library.muse.hotSongs(requestedPage * 6, 6)
            } else {
                library.muse.searchSongs(requestedQuery, requestedPage * 6, 6)
            }
            val singers = if (requestedQuery.isEmpty()) {
                library.muse.singers("", "", requestedPage * 4, 4)
            } else {
                library.muse.searchSingers(requestedQuery, "", "", requestedPage * 4, 4)
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.songCount()
            } else {
                library.muse.searchSongCount(requestedQuery)
            }
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "quick" ||
                    browsePage != requestedPage || activeSearchQuery != requestedQuery
                ) return@post
                browseTotalCount = total
                browseTotalPages = (total + 5) / 6
                visibleSongs.clear()
                visibleSongs.addAll(songs)
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList?.apply {
                    dividerHeight = 0
                    adapter = QuickSearchAdapter(
                        this@MainActivity,
                        singers,
                        songs,
                        onSingerClick = { singer -> loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1)) },
                        onSongClick = { song -> addToQueue(song) },
                        onSongMore = { song -> showSongActions(song) },
                    )
                }
                updatePageInfo()
            }
        }
    }

    /**
     * 加载语种列表
     */
    private fun loadLanguages() {
        currentTabIndex = 2
        showSubPageShell("主页 / 语种")
        setupTabs()
        listTitle!!.setText("语种点歌")
        if (library.muse.isAvailable()) {
            currentCategories.clear()
            currentCategories.addAll(library.muse.languages())
            currentCategoryIndex = 0
            updateCategories()
            if (!currentCategories.isEmpty()) {
                browseMode = "language"
                browseParam = currentCategories.get(0)
                browsePage = 0
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载字数分类
     */
    private fun loadWordCounts() {
        currentTabIndex = 3
        showSubPageShell("主页 / 字数")
        setupTabs()
        listTitle!!.setText("字数点歌")
        currentCategories.clear()
        currentCategories.add("2字")
        currentCategories.add("3字")
        currentCategories.add("4字")
        currentCategories.add("5字")
        currentCategories.add("6字")
        currentCategories.add("7字以上")
        currentCategoryIndex = 0
        updateCategories()
        browseMode = "wordcount"
        browseParam = "4" // 默认显示4字歌曲
        browsePage = 0
        loadBrowsePage()
    }

    /**
     * 加载歌手列表
     */
    private fun loadSingers() {
        searchScope = "singer"
        activeSearchQuery = ""
        setSearchText("")
        currentTabIndex = 1
        showSubPageShell("主页 / 歌星")
        setupTabs()
        listTitle!!.setText("歌星点歌")
        currentCategories.clear()
        currentCategories.add("全部")
        currentCategories.add("大陆男")
        currentCategories.add("大陆女")
        currentCategories.add("港台男")
        currentCategories.add("港台女")
        currentCategories.add("中国组合")
        currentCategories.add("外国组合")
        currentCategoryIndex = 0
        updateCategories()
        browsePage = 0
        loadSingerList("全部")
    }

    private fun loadSingerList(type: String?, query: String = activeSearchQuery) {
        browseMode = "singer_list"
        browseParam = type
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, "曲库加载中...")
            return
        }
        val requestVersion = ++browseRequestVersion
        val requestedPage = browsePage
        val requestedType = type.orEmpty()
        val requestedQuery = query.trim()
        val pageSize = 8
        val offset = requestedPage * pageSize
        val (areaFilter, typeFilter) = singerFilter(type)
        io.execute(Runnable {
            val singers: MutableList<Array<String?>> = if (requestedQuery.isEmpty()) {
                library.muse.singers(areaFilter, typeFilter, offset, pageSize)
            } else {
                library.muse.searchSingers(requestedQuery, areaFilter, typeFilter, offset, pageSize)
            }
            val total = if (requestedQuery.isEmpty()) {
                library.muse.singerCount(areaFilter, typeFilter)
            } else {
                library.muse.searchSingerCount(requestedQuery, areaFilter, typeFilter)
            }
            main.post(Runnable {
                if (requestVersion != browseRequestVersion || browseMode != "singer_list" ||
                    browseParam.orEmpty() != requestedType || browsePage != requestedPage ||
                    activeSearchQuery != requestedQuery
                ) return@Runnable
                browseTotalCount = total
                browseTotalPages = (total + pageSize - 1) / pageSize
                visibleSingers.clear()
                visibleSingers.addAll(singers)
                songList!!.dividerHeight = 0
                songList!!.adapter = SingerGridAdapter(this, singers) { singer ->
                    loadSingerSongs(singer.getOrNull(0), singer.getOrNull(1))
                }
                updatePageInfo()
            })
        })
    }

    private fun singerFilter(category: String?): Pair<String, String> = when (category) {
        "大陆男" -> "大陆" to "男"
        "大陆女" -> "大陆" to "女"
        "港台男" -> "港台" to "男"
        "港台女" -> "港台" to "女"
        "中国组合" -> "中国" to "组合"
        "外国组合" -> "外国" to "组合"
        else -> "" to ""
    }

    private fun loadSingerSongs(singerId: String?, singerName: String?) {
        searchScope = "singer_songs"
        searchContextId = singerId.orEmpty()
        activeSearchQuery = ""
        setSearchText("")
        showSubPageShell("主页 / 歌星 / " + singerName)
        currentTabIndex = 1
        setupTabs()
        browseMode = "singer_id"
        browseParam = singerId
        browsePage = 0
        listTitle!!.setText(singerName)
        loadBrowsePage()
    }

    /**
     * 加载收藏歌曲
     */
    /**
     * 加载歌单
     */
    private fun loadPlaylists() {
        showSubPageShell("主页 / 歌单")
        listTitle!!.setText("我的歌单")
        currentCategories.clear()
        // 获取歌单列表(从数据库 playlists 表)
        val playlistData: MutableList<Array<String?>> = library.muse.playlists(0, 100)
        if (playlistData.isEmpty()) {
            currentCategories.add("暂无歌单")
        } else {
            for (p in playlistData) {
                currentCategories.add(p[1].orEmpty()) // p[1] 是歌单名称
            }
        }
        currentCategoryIndex = 0
        updateCategories()
        if (!currentCategories.isEmpty() && currentCategories.get(0) != "暂无歌单") {
            browseMode = "playlist"
            browseParam = currentCategories.get(0)
            browsePage = 0
            loadBrowsePage()
        }
    }

    /**
     * 加载已点歌曲列表 (Tab 4)
     */
    private fun loadOrderedList() {
        currentTabIndex = 4
        showSubPageShell("主页 / 已点")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText("已点歌曲 (" + orderQueue!!.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "ordered"
        browseParam = ""
        browsePage = 0
        // 直接更新歌曲列表
        visibleSongs.clear()
        visibleSongs.addAll(orderQueue)
        updateCategories()
        renderSongList()
        updateOrderBadge()
    }

    /**
     * 加载下载列表 (Tab 5)
     */
    private fun loadDownloadedList() {
        currentTabIndex = 6
        showSubPageShell("主页 / 下载")
        setupTabs()
        listTitle!!.setText("下载管理")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "downloads"
        browseParam = ""
        browsePage = 0
        // The original download page is a task queue. Completed files move to the local-song page.
        visibleSongs.clear()
        for (task in downloads) {
            visibleSongs.add(task.song)
        }
        if (visibleSongs.isEmpty()) {
            toast("暂无下载任务")
        }
        updateCategories()
        renderSongList()
    }

    private fun loadLocalCatalogPage() {
        browseMode = "local"
        val language = searchLanguage.ifEmpty { browseParam.orEmpty() }
        val query = activeSearchQuery.trim().uppercase(Locale.ROOT)
        val requestedPage = browsePage
        val requestVersion = ++browseRequestVersion
        val pageSize = MuseDatabase.PAGE_SIZE
        io.execute {
            val localSongs = localSongInventory()
            downloads.asSequence().map { it.song }
                .plus(library.allSongs().asSequence())
                .forEach { song ->
                if (song.hasLocalFile() || isDownloaded(song)) {
                    localSongs.putIfAbsent(stableId(song), song)
                }
            }
            val filtered = localSongs.values.filter {
                val languageMatches = language.isEmpty() || language == "全部" || it.language == language
                val text = listOf(it.title, it.singer, it.pinyin, it.pinyinFull)
                    .joinToString(" ").uppercase(Locale.ROOT)
                languageMatches && (query.isEmpty() || query in text)
            }
            val from = (requestedPage * pageSize).coerceAtMost(filtered.size)
            val page = filtered.drop(from).take(pageSize)
            main.post {
                if (requestVersion != browseRequestVersion || browseMode != "local" ||
                    browsePage != requestedPage || activeSearchQuery.uppercase(Locale.ROOT) != query ||
                    searchLanguage != language
                ) return@post
                browseTotalCount = filtered.size
                browseTotalPages = (filtered.size + pageSize - 1) / pageSize
                visibleSongs.clear()
                visibleSongs.addAll(page)
                renderSongList()
                updatePageInfo()
            }
        }
    }

    /**
     * 加载已唱历史 (Tab 5)
     */
    private fun loadSangHistory() {
        currentTabIndex = 5
        showSubPageShell("主页 / 已唱")
        setupTabs()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        listTitle!!.setText("已唱历史 (" + sangHistory.size + ")")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "sang"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()
        visibleSongs.addAll(sangHistory)
        updateCategories()
        renderSongList()
    }

    /**
     * 加载收藏列表 (Tab 7) - 从 KtvStore 收藏和数据库查询
     */
    private fun loadFavorites() {
        currentTabIndex = 7
        showSubPageShell("主页 / 收藏")
        setupTabs()
        listTitle!!.setText("我的收藏")
        currentCategories.clear()
        currentCategoryIndex = 0
        browseMode = "favorites"
        browseParam = ""
        browsePage = 0
        visibleSongs.clear()
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, "曲库加载中...")
            return
        }
        // 从数据库查询收藏歌曲
        io.execute(Runnable {
            val result: MutableList<Song> = ArrayList<Song>()
            for (songId in store.favoriteIds) {
                val song = library.muse.songById(songId)
                    ?: library.allSongs().firstOrNull { stableId(it) == songId }
                if (song != null) result.add(song)
            }
            main.post(Runnable {
                visibleSongs.clear()
                visibleSongs.addAll(result)
                updateCategories()
                renderSongList()
                if (visibleSongs.isEmpty()) toast("暂无收藏，长按歌曲可收藏")
            })
        })
    }

    /**
     * 显示设置对话框 (旧版 Tab 8, 现已改为弹窗)
     */
    private fun showSettingsPage(requestInitialFocus: Boolean = true) {
        currentTabIndex = 8
        showSubPageShell("主页 / 设置")
        browseMode = "settings"
        visibleSongs.clear()
        currentCategories.clear()
        updateCategories()
        if (keyboardArea != null) keyboardArea!!.setVisibility(View.GONE)
        if (tabContainer != null) tabContainer!!.setVisibility(View.GONE)
        if (subCategoryScroll != null) subCategoryScroll!!.setVisibility(View.GONE)
        if (paginationBar != null) paginationBar!!.setVisibility(View.GONE)
        browsePagination?.visibility = View.GONE

        val entries = listOf(
            SettingsEntry(R.drawable.ott_ic_data_setting, "数据设置", "数据库、歌曲下载、歌曲文件相关设置") { showSettingsSection(1) },
            SettingsEntry(R.drawable.ott_ic_play, "播放设置", "播放器、视频窗口相关设置") { showSettingsSection(2) },
            SettingsEntry(R.drawable.ott_ic_sound, "声音设置") { showSoundSettingsDialog() },
            SettingsEntry(
                R.drawable.ott_ic_data_upgrade,
                "应用更新",
                "当前版本 ${BuildConfig.VERSION_NAME}，更新来源：Gitea Release",
                "检查更新",
            ) { checkAppUpdate(true) },
            SettingsEntry(R.drawable.ott_ic_about_us, "关于", actionText = "查看") { showAboutDialog() },
        )
        activeSettingsEntries = entries
        songList!!.setDivider(ColorDrawable(Color.TRANSPARENT))
        songList!!.setDividerHeight(dp(12))
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
    }

    private fun openSettingsItem(position: Int) {
        activeSettingsEntries.getOrNull(position)?.action?.invoke()
    }

    private fun showSettingsSection(section: Int, requestInitialFocus: Boolean = true) {
        val names = arrayOf("网络设置", "数据设置", "播放设置", "声音设置", "界面设置")
        currentTabIndex = 8
        showSubPageShell("主页 / 设置 / ${names[section]}")
        browseMode = "settings_section"
        currentCategories.clear()
        updateCategories()
        keyboardArea?.visibility = View.GONE
        tabContainer?.visibility = View.GONE
        browsePagination?.visibility = View.GONE
        val entries = when (section) {
            0 -> listOf(
                SettingsEntry(R.drawable.ott_ic_wifi_setting, "无线网络") {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                },
            )
            1 -> listOf(
                SettingsEntry(
                    R.drawable.ott_ic_data_upgrade,
                    "曲库",
                    "统计中…",
                    "更新",
                ) {
                    // 手动强制检查更新(绕过 30 天时效; 仅当服务器版本更新时才下载)
                    forceDatabaseSync()
                },
                SettingsEntry(R.drawable.ott_ic_data_setting, "U盘加歌", "扫描U盘内按规定命名的歌曲文件，并添加到曲库中", "立即加歌") { toast("未检测到U盘") },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, "预留存储空间", "当前 ${String.format(Locale.ROOT, "%.1f", reserveStorageGb)} GB") { showReserveStorageDialog() },
                SettingsEntry(R.drawable.ott_ic_data_reset, "自动删歌", "当剩余存储空间低于预留空间时，自动删除最久未使用的歌曲", action = {
                    autoDeleteSongs = !autoDeleteSongs
                    saveState()
                    if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                }, checked = autoDeleteSongs),
                SettingsEntry(R.drawable.ott_ic_data_upgrade, "重置数据库", "删除本地数据库并重新下载", "重置") { confirmResetDatabase() },
                SettingsEntry(R.drawable.ott_ic_setting_storage_space, "硬盘读写权限") { toast(storageStatusText()) },
            )
            2 -> listOf(
                SettingsEntry(R.drawable.ott_ic_download_setting, "开机清空下载列表", "开机时删除本地已下载歌曲并清理数据库状态", action = { clearDownloadsOnBoot = !clearDownloadsOnBoot; saveState() }, checked = clearDownloadsOnBoot),
                SettingsEntry(R.drawable.ott_ic_tv_display_mode, "自动全屏", "设置多久未操作后自动进入全屏", "设置间隔") { showAutoFullscreenDialog() },
                SettingsEntry(R.drawable.ott_ic_play, "音画同步") { showAudioSyncDialog() },
                SettingsEntry(R.drawable.ott_ic_marquee_setting, "歌曲片头字幕", action = { songTitleSubtitleEnabled = !songTitleSubtitleEnabled; saveState() }, checked = songTitleSubtitleEnabled),
            )
            3 -> listOf(
                SettingsEntry(R.drawable.ott_ic_sound, "声音设置") { showSoundSettingsDialog() },
            )
            else -> emptyList()
        }
        activeSettingsEntries = entries
        songList!!.divider = ColorDrawable(Color.TRANSPARENT)
        songList!!.dividerHeight = dp(12)
        songList!!.setBackgroundColor(Color.TRANSPARENT)
        val settingsAdapter = SettingsListAdapter(this, entries, R.id.btn_back)
        activeSettingsAdapter = settingsAdapter
        songList!!.adapter = settingsAdapter
        btnBack?.nextFocusDownId = settingsAdapter.firstActionId
        if (requestInitialFocus) settingsAdapter.requestInitialFocus(songList!!)
        // 数据设置: 曲库/下载数统计较耗时(逐文件校验), 移到 IO 线程, 避免进入界面卡顿。
        if (section == 1) refreshLibraryStatsAsync(settingsAdapter)
    }

    /** IO 线程统计曲库歌曲数与本地已下载数, 完成后原位回填"曲库"条副标题。 */
    private fun refreshLibraryStatsAsync(adapter: SettingsListAdapter) {
        io.execute {
            val count = runCatching { library.muse.songCount() }.getOrDefault(0)
            val downloaded = runCatching { countDownloadedFiles() }.getOrDefault(0)
            // 同步时效(30 天): 未到期不联网, 到期后才触发服务器同步。
            val syncText = DatabaseBootstrapper.syncStatusText()
            main.post {
                if (activeSettingsAdapter === adapter) {
                    adapter.updateSubtitle(
                        "曲库",
                        "数据库 $count 首  本地已下载 $downloaded 首  ·  ${DatabaseBootstrapper.SYNC_TTL_DAYS} 天同步: $syncText",
                    )
                }
            }
        }
    }

    /**
     * 手动强制检查曲库更新(绕过 30 天时效)。
     * 仅在服务器版本更新(或本地库不可用)时才下载, 避免无谓的大文件传输。
     */
    private fun forceDatabaseSync() {
        showDatabaseLoading(true, "正在检查曲库更新...", null)
        io.execute {
            val remoteVersion = DatabaseBootstrapper.fetchRemoteVersion()
            if (remoteVersion == null) {
                main.post {
                    showDatabaseLoading(false, "", null)
                    toast("检查失败：无法连接曲库服务器")
                    showSettingsSection(1, false)
                }
                return@execute
            }
            val localVersion = DatabaseBootstrapper.getLocalDbVersion()
            if (library.muse.isAvailable() && remoteVersion == localVersion) {
                DatabaseBootstrapper.markSynced()
                main.post {
                    showDatabaseLoading(false, "", null)
                    toast("曲库已是最新版本")
                    showSettingsSection(1, false)
                }
                return@execute
            }
            library.muse.close()
            val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
            val ok = result.isSuccess && library.muse.open()
            main.post {
                if (ok) {
                    showDatabaseLoading(false, "", null)
                    toast("曲库同步完成")
                    showSettingsSection(1, false)
                } else {
                    showDatabaseLoadingFailure("曲库同步失败：${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.activeNetworkInfo?.isConnected == true
    }

    private fun networkStatusText(): String = if (isNetworkConnected()) "已连接" else "未连接"

    private fun storageStatusText(): String {
        val root = Environment.getExternalStorageDirectory()
        return "可用 ${formatSize(root.usableSpace)} / 总计 ${formatSize(root.totalSpace)}"
    }

    private fun downloadedSongDirectory(): File =
        File(MuseDatabase.VIDEO_ROOT, MuseDatabase.CLOUD_SONG_DIR)

    private fun countDownloadedFiles(): Int = localSongInventory().size

    private fun localSongInventory(): LinkedHashMap<String, Song> {
        val result = LinkedHashMap<String, Song>()
        library.muse.localPathSongs().forEach { song ->
            val resolved = File(MuseDatabase.resolveSongFilePath(song))
            if (SongFileValidator.inspect(
                    resolved,
                    SongFileValidator.requiresTransportStream(resolved),
                ).valid
            ) {
                song.path = resolved.absolutePath
                result.putIfAbsent(stableId(song), song)
            }
        }

        val downloadedFiles = MuseDatabase.songDirectories()
            .flatMap { it.listFiles().orEmpty().asIterable() }
            .distinctBy { it.name }
        val inspections = downloadedFiles.asSequence()
            .filter { it.isFile && !it.name.endsWith(".download") }
            .associateWith { file ->
                SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file))
            }
        inspections.filter { (file, inspection) ->
            file.parentFile?.absolutePath == downloadedSongDirectory().absolutePath && !inspection.valid
        }.keys.forEach { invalid ->
            Log.w(TAG, "Deleting invalid downloaded song: ${invalid.absolutePath}, ${inspections[invalid]?.reason}")
            SongFileValidator.forget(invalid)
            cleanupSidecars(invalid)
            invalid.delete()
        }
        val validFiles = inspections.filter { (file, inspection) -> file.exists() && inspection.valid }.keys
        val filesByName = validFiles.associateBy { it.name }
        val databaseSongs = library.muse.songsByFilenames(filesByName.keys)
        databaseSongs.forEach { song ->
            filesByName[song.filename]?.let { song.path = it.absolutePath }
            result.putIfAbsent(stableId(song), song)
        }
        val matchedNames = databaseSongs.mapNotNullTo(HashSet()) { it.filename }
        validFiles.filter { it.name !in matchedNames }.forEach { file ->
            val song = Song.local(file.absolutePath, file.name)
            song.filename = file.name
            result.putIfAbsent(stableId(song), song)
        }
        return result
    }

    private fun clearDownloadedFilesOnBoot() {
        downloadedSongDirectory().listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                cleanupSidecars(file)
                runCatching { file.delete() }
            }
        }
        downloads.clear()
        runCatching { stateDatabase.clearDownloads() }
    }

    private fun isBelowReservedStorage(): Boolean {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        return downloadedSongDirectory().usableSpace < required
    }

    private fun enforceStorageReserve(showResult: Boolean = false) {
        if (!autoDeleteSongs || !isBelowReservedStorage()) return
        io.execute {
            val deleted = purgeColdDownloadedFiles()
            val enough = !isBelowReservedStorage()
            if (showResult) main.post {
                toast(
                    if (enough) "已清理 $deleted 首歌曲，预留空间已恢复"
                    else "已清理 $deleted 首歌曲，受保护歌曲不会被删除，空间仍不足",
                )
                if (browseMode == "settings_section") showSettingsSection(1, false)
            }
        }
    }

    private fun purgeColdDownloadedFiles(): Int {
        val required = (reserveStorageGb * 1024.0 * 1024.0 * 1024.0).toLong()
        val protectedNames = buildSet {
            currentSong?.filename?.let(::add)
            orderQueue.orEmpty().mapNotNullTo(this) { it.filename }
        }
        var deletedCount = 0
        downloadedSongDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in protectedNames && !it.name.endsWith(".download") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (downloadedSongDirectory().usableSpace >= required) return deletedCount
                cleanupSidecars(file)
                if (file.delete()) {
                    deletedCount++
                    library.allSongs().filter { it.filename == file.name }.forEach { song ->
                        song.path = null
                        runCatching { stateDatabase.removeDownload(song) }
                    }
                }
            }
        return deletedCount
    }

    private fun confirmResetDatabase() {
        AlertDialog.Builder(this)
            .setTitle("重置数据库")
            .setMessage("将删除本地曲库数据库，然后重新从官方曲库同步下载。不会保留备份，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> resetDatabaseFromMuse() }
            .create().showForTv()
    }

    private fun resetDatabaseFromMuse() {
        showDatabaseLoading(true, "正在重置数据库...", null)
        io.execute {
            library.muse.close()
            MuseDatabase.defaultDbFile().delete()
            val result = DatabaseBootstrapper.download(::showDatabaseDownloadProgress)
            val opened = result.isSuccess && library.muse.open()
            main.post {
                if (opened) {
                    showDatabaseLoading(false, "", null)
                    toast("数据库重置完成")
                    showSettingsSection(1, false)
                } else {
                    showDatabaseLoadingFailure("数据库重置失败：${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }
        }
    }

    private fun showDatabaseLoading(show: Boolean, message: String, progressValue: Int?) {
        if (!show) {
            databaseLoadingOverlay?.visibility = View.GONE
            return
        }
        if (databaseLoadingOverlay == null) {
            val root = findViewById<FrameLayout>(R.id.layout_root)
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(24, 17, 45))
                isClickable = true
                isFocusable = true
                elevation = dp(30).toFloat()
            }
            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ktv_logo)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(112), dp(112)))
                addView(ProgressBar(this@MainActivity, null, 0, R.style.LoadingProgressBar), LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                    topMargin = dp(22)
                })
                databaseLoadingText = TextView(this@MainActivity).apply {
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(20), dp(18), dp(20), dp(12))
                }
                addView(databaseLoadingText, LinearLayout.LayoutParams(dp(620), -2))
                databaseLoadingProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progressDrawable = getDrawable(R.drawable.bg_download_progress)
                }
                addView(databaseLoadingProgress, LinearLayout.LayoutParams(dp(620), dp(10)))
                databaseRetryButton = TextView(this@MainActivity).apply {
                    text = "重试"
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(45, 20, 65))
                    isClickable = true
                    isFocusable = true
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.rgb(255, 204, 92))
                    }
                }
                addView(databaseRetryButton, LinearLayout.LayoutParams(dp(180), dp(54)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(24)
                })
            }
            overlay.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            databaseLoadingOverlay = overlay
        }
        databaseLoadingOverlay?.visibility = View.VISIBLE
        databaseLoadingText?.text = message
        databaseLoadingProgress?.apply {
            visibility = if (progressValue == null) View.INVISIBLE else View.VISIBLE
            progress = progressValue ?: 0
        }
        databaseRetryButton?.apply {
            visibility = View.GONE
            isEnabled = true
            setOnClickListener(null)
        }
        databaseLoadingOverlay?.bringToFront()
    }

    private fun showDatabaseLoadingFailure(message: String) {
        showDatabaseLoading(true, "$message\n请检查网络或空间后重试", null)
        databaseRetryButton?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                isEnabled = false
                initializeDatabase()
            }
            requestFocus()
        }
    }

    // ===== 设置子功能 (新Tab版) =====
    private fun dialogSingleChoiceGroup(
        labels: Array<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): android.widget.RadioGroup = android.widget.RadioGroup(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                tag = index
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = index == selected
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                TvFocusStyler.installAction(this)
            })
        }
        setOnCheckedChangeListener { group, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            onSelected(index)
        }
    }

    private fun dialogMultiChoiceGroup(
        labels: Array<String>,
        checked: BooleanArray,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), dp(6))
        labels.forEachIndexed { index, label ->
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = checked.getOrElse(index) { false }
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(8), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                )
                setOnCheckedChangeListener { _, value -> checked[index] = value }
                TvFocusStyler.installAction(this)
            })
        }
    }

    private fun showScreenDialog() {
        val items = arrayOf<String>("16 : 9", "4 : 3", "全屏")
        val selected = when (screenMode) {
            "16 : 9" -> 0
            "4 : 3" -> 1
            else -> 2
        }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this)
            .setTitle("视频比例")
            .setView(choices)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                screenMode = items[pending]
                saveState()
                applyScreenMode()
            }.create().showForTv()
    }

    private fun showAutoFullscreenDialog() {
        val items = arrayOf("30秒", "45秒", "1分钟", "2分钟", "关闭（不推荐）")
        val seconds = intArrayOf(30, 45, 60, 120, 0)
        val selected = seconds.indexOf(autoFullscreenSeconds).let { if (it >= 0) it else 4 }
        var pending = selected
        val choices = dialogSingleChoiceGroup(items, selected) { pending = it }
        AlertDialog.Builder(this).setTitle("设置间隔")
            .setView(choices)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                autoFullscreenSeconds = seconds[pending]
                saveState()
                scheduleAutoFullscreen()
                showSettingsSection(2, false)
            }.create().showForTv()
    }

    private fun showReserveStorageDialog() {
        val totalGb = downloadedSongDirectory().totalSpace.toDouble() / 1024.0 / 1024.0 / 1024.0
        val values = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
            .filter { it <= max(0.5, totalGb * 0.95) }
            .ifEmpty { listOf(0.5) }
        val labels = values.map { value ->
            "${String.format(Locale.ROOT, if (value < 1) "%.1f" else "%.0f", value)} GB"
        }.toTypedArray()
        var selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - reserveStorageGb) } ?: 0
        AlertDialog.Builder(this).setTitle("预留存储空间")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                reserveStorageGb = values[selected]
                saveState()
                if (autoDeleteSongs) enforceStorageReserve(showResult = true)
                showSettingsSection(1, false)
            }.create().showForTv()
    }

    private fun showAudioSyncDialog() {
        var pendingDelay = audioDelayMs
        val seek = SeekBar(this).apply { max = 40; progress = (pendingDelay / 50 + 20).coerceIn(0, 40) }
        val value = TextView(this).apply { gravity = Gravity.CENTER; textSize = 18f }
        fun update() { value.text = "声音${if (pendingDelay >= 0) "提前" else "延后"} ${kotlin.math.abs(pendingDelay) / 1000f} 秒" }
        update()
        seek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { pendingDelay = (progress - 20) * 50; update() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(8), dp(32), dp(16)); addView(value); addView(seek) }
        AlertDialog.Builder(this).setTitle("音画同步").setView(box).setMessage("重唱或切歌后生效")
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ -> audioDelayMs = pendingDelay; saveState() }
            .create().showForTv()
    }

    private fun showBannerConfigDialog() {
        val labels = arrayOf("新歌", "戏曲", "广场舞")
        val pending = booleanArrayOf(true, true, true)
        val choices = dialogMultiChoiceGroup(labels, pending)
        AlertDialog.Builder(this).setTitle("轮播图配置")
            .setView(choices)
            .setNegativeButton("关闭", null).setPositiveButton("确认", null)
            .create().showForTv()
    }

    private fun showSoundSettingsDialog() {
        var pendingOrderedSongOriginal = orderedSongOriginal
        var pendingOrderedVolumeMode = orderedVolumeMode
        var pendingOrderedVocalMode = orderedVocalMode
        var pendingMusicVolume = musicVolume
        fun title(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0)
        }
        fun choices(labels: Array<String>, selected: Int, horizontal: Boolean = false, changed: (Int) -> Unit) =
            android.widget.RadioGroup(this).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                labels.forEachIndexed { index, label ->
                    addView(android.widget.RadioButton(this@MainActivity).apply {
                        text = label; textSize = 15f; setTextColor(Color.WHITE); isChecked = index == selected
                        id = View.generateViewId(); tag = index
                        minimumHeight = 0
                        minHeight = 0
                        setPadding(0, 0, dp(6), 0)
                        layoutParams = android.widget.RadioGroup.LayoutParams(-2, dp(30))
                        TvFocusStyler.installAction(this)
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                    changed(index)
                }
            }
        val musicVolumeTitle = title("歌曲最大音量  ${pendingMusicVolume}%")
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title("切到点播歌曲时音量："))
            addView(choices(arrayOf("跟随上首音量", "跟随上首点播歌曲音量"), pendingOrderedVolumeMode) { pendingOrderedVolumeMode = it })
            addView(musicVolumeTitle)
            addView(SeekBar(this@MainActivity).apply {
                max = 100; progress = pendingMusicVolume
                setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        pendingMusicVolume = value
                        musicVolumeTitle.text = "歌曲最大音量  ${pendingMusicVolume}%"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title("点播歌曲原伴唱"))
            addView(choices(arrayOf("原唱", "伴唱"), if (pendingOrderedSongOriginal) 0 else 1, true) { pendingOrderedSongOriginal = it == 0 })
            addView(title("切到点播歌曲时原伴唱："))
            addView(choices(arrayOf("跟随上首原伴唱", "跟随上首点播原伴唱", "固定点播原伴唱"), pendingOrderedVocalMode) { pendingOrderedVocalMode = it })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(28), dp(4), dp(28), dp(12))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(28) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("声音设置")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setPositiveButton("确认") { _, _ ->
                orderedSongOriginal = pendingOrderedSongOriginal
                orderedVolumeMode = pendingOrderedVolumeMode
                orderedVocalMode = pendingOrderedVocalMode
                musicVolume = pendingMusicVolume
                applyPlaybackMode()
                saveState()
            }.create()
        dialog.showForTv(DialogInterface.BUTTON_NEGATIVE) {
            val available = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.setLayout(min(dp(790), available), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showSingModeDialog() {
        val items = arrayOf<String>("普通演唱", "评分演唱", "静音练唱")
        AlertDialog.Builder(this)
            .setTitle("演唱模式 (当前: " + singMode + ")")
            .setItems(items, DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                singMode = items[w]
                if ("评分演唱" == singMode) scoreEnabled = true
                else if ("静音练唱" == singMode) {
                    scoreEnabled = false
                    musicVolume = 0
                }
                saveState()
                applyPlaybackMode()
                toast("演唱模式: " + singMode)
            }).create().showForTv()
    }

    private fun showVocalChannelDialog() {
        val items = arrayOf("自动", "左伴右原", "右伴左原", "全声道")
        AlertDialog.Builder(this)
            .setTitle("声道模式 (当前: $vocalChannelMode)")
            .setItems(items) { _, which ->
                setVocalChannelMode(items[which])
                showSettingsSection(3)
            }
            .create().showForTv()
    }

    private fun showMarqueeDialog() {
        val status = if (marqueeEnabled) "已开启: " + marqueeText else "已关闭"
        AlertDialog.Builder(this)
            .setTitle("跑马灯设置 - " + status)
            .setItems(
                arrayOf<String>("开启", "关闭", "编辑文字"),
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    if (w == 0) {
                        marqueeEnabled = true
                        saveState()
                    } else if (w == 1) {
                        marqueeEnabled = false
                        saveState()
                    } else {
                        val input = EditText(this)
                        input.setText(marqueeText)
                        input.setHint("输入跑马灯文字")
                        AlertDialog.Builder(this)
                            .setTitle("编辑跑马灯文字")
                            .setView(input)
                            .setPositiveButton(
                                "确定",
                                DialogInterface.OnClickListener { dd: DialogInterface?, ww: Int ->
                                    marqueeText = input.getText().toString()
                                    marqueeEnabled = true
                                    saveState()
                                }).setNegativeButton("取消", null).create().showForTv()
                    }
                    toast("跑马灯: " + (if (marqueeEnabled) marqueeText else "已关闭"))
                }).create().showForTv()
    }

    private fun showNetworkDlg() {
        var ip = "未知"
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                val addr = ni.getInetAddresses()
                while (addr.hasMoreElements()) {
                    val ia = addr.nextElement()
                    if (!ia.isLoopbackAddress() && ia is Inet4Address) {
                        ip = ia.getHostAddress().orEmpty()
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle("网络设置")
            .setMessage("IP: " + ip + "\n手机遥控端口: 8765\n扫码连接手机点歌")
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于麦动")
            .setMessage(
                "power by 抖音@杨家三郎\n" +
                    "此软件免费开源，请勿付费获取\n" +
                    "版本：" + BuildConfig.VERSION_NAME
            )
            .setPositiveButton("确定", null).create().showForTv(DialogInterface.BUTTON_POSITIVE)
    }

    private fun applyScreenMode() {
        if (player != null) {
            try {
                applyPlaybackMode()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 更新分类列表 UI
     */
    private fun updateCategories() {
        val focusedCategory = window.decorView.findFocus()?.takeIf { isDescendantOf(it, categoryList) }
            ?.let(::captureFocusBookmark)
        categoryList!!.removeAllViews()
        subCategoryScroll?.visibility = if (currentCategories.isEmpty()) View.GONE else View.VISIBLE
        for (i in currentCategories.indices) {
            val index = i
            val cat = TextView(this)
            cat.setText(currentCategories.get(i))
            cat.setTextSize(16f)
            cat.setPadding(dp(12), 0, dp(12), 0)
            cat.gravity = Gravity.CENTER
            cat.setAccessibleFocus("focus:category:${currentCategories[i]}", "分类，${currentCategories[i]}")
            cat.setFocusable(true)
            cat.setClickable(true)
            // 设置选中状态
            if (i == currentCategoryIndex) {
                cat.setTextColor(Color.rgb(253, 51, 89))
                cat.setBackgroundColor(Color.argb(30, 253, 51, 89))
            } else {
                cat.setTextColor(Color.argb(200, 241, 241, 241))
                cat.setBackgroundColor(Color.TRANSPARENT)
            }
            cat.setOnClickListener(View.OnClickListener { v: View? ->
                currentCategoryIndex = index
                updateCategories()
                // 根据分类加载歌曲
                val category = currentCategories.get(index)
                clearSearchForFilterChange()
                when (currentTabIndex) {
                    0 -> if ("全部" == category) {
                        browseMode = "hot"
                        browseParam = ""
                        searchLanguage = "全部"
                    } else {
                        browseMode = "language"
                        browseParam = category
                        searchLanguage = category
                    }

                    1 -> {
                        browsePage = 0
                        loadSingerList(category)
                        return@OnClickListener
                    }

                    2 -> {
                        browseMode = "language"
                        browseParam = category
                    }

                    3 -> {
                        browseMode = "wordcount"
                        browseParam = getWordCountParam(category)
                    }

                    5 -> {
                        browseMode = "playlist"
                        browseParam = category
                    }

                    9 -> {
                        browseMode = "rank"
                        browseParam = rankPlaylistIds.getOrNull(index).orEmpty()
                        listTitle!!.text = category
                    }

                    10 -> {
                        browseMode = "local"
                        browseParam = category
                        searchLanguage = category
                        browsePage = 0
                        loadLocalCatalogPage()
                        return@OnClickListener
                    }
                }
                browsePage = 0
                loadBrowsePage()
            })
            installPressFeedback(cat)
            categoryList!!.addView(cat)
        }
        restoreFocusBookmark(focusedCategory)
    }

    /**
     * 将字数分类转换为查询参数
     */
    private fun getWordCountParam(category: String): String {
        when (category) {
            "2字" -> return "2"
            "3字" -> return "3"
            "4字" -> return "4"
            "5字" -> return "5"
            "6字" -> return "6"
            else -> return "7" // 7字以上
        }
    }

    /**
     * 执行搜索
     */
    private fun performSearch() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        activeSearchQuery = query
        browsePage = 0
        when (searchScope) {
            "quick" -> loadQuickSearchPage(query)
            "singer" -> {
                val category = currentCategories.getOrNull(currentCategoryIndex) ?: "全部"
                loadSingerList(category, query)
            }
            "playlist" -> loadCategoryPlaylistPage(query)
            "local" -> loadLocalCatalogPage()
            "singer_songs" -> {
                browseMode = if (query.isEmpty()) "singer_id" else "singer_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            "playlist_songs" -> {
                browseMode = if (query.isEmpty()) "playlist_id" else "playlist_id_search"
                browseParam = searchContextId
                loadBrowsePage()
            }
            else -> {
                searchLanguage = currentCategories.getOrNull(currentCategoryIndex) ?: "全部"
                if (query.isEmpty()) {
                    browseMode = if (searchLanguage == "全部") "hot" else "language"
                    browseParam = if (searchLanguage == "全部") "" else searchLanguage
                    listTitle?.text = if (searchLanguage == "全部") "热门歌曲" else searchLanguage
                } else {
                    browseMode = "search"
                    browseParam = query
                    listTitle?.text = "搜索结果: $query"
                }
                loadBrowsePage()
            }
        }
    }

    /**
     * 加载浏览分页数据
     */
    private fun loadBrowsePage() {
        if (!library.muse.isAvailable()) {
            catalogRefreshPending = true
            busy(true, "曲库加载中...")
            return
        }
        catalogRefreshPending = false
        busy(false, "")
        val requestVersion = ++browseRequestVersion
        val requestedMode = browseMode
        val requestedParam = browseParam.orEmpty()
        val requestedPage = browsePage
        val requestedLanguage = searchLanguage
        val requestedSearchQuery = activeSearchQuery
        val pageSize = when (requestedMode) {
            "rank" -> 6
            "singer_list" -> 8
            else -> MuseDatabase.PAGE_SIZE
        }
        val key = BrowseCacheKey(
            requestedMode,
            requestedParam,
            requestedLanguage,
            requestedSearchQuery,
            requestedPage,
            pageSize,
        )
        val cached = synchronized(browsePageCache) { browsePageCache[key] }
        if (cached != null) {
            applyBrowsePage(key, cached, requestVersion)
            prefetchBrowseNeighbors(key, cached.total)
            return
        }
        requestBrowsePage(key, requestVersion, true)
    }

    private fun browseCountKey(key: BrowseCacheKey): String =
        listOf(key.mode, key.param, key.language, key.searchQuery).joinToString("\u0001")

    private fun requestBrowsePage(key: BrowseCacheKey, requestVersion: Int, display: Boolean) {
        if (display) synchronized(browseDisplayRequests) { browseDisplayRequests[key] = requestVersion }
        synchronized(browseRequestsInFlight) {
            if (!browseRequestsInFlight.add(key)) return
        }
        io.execute(Runnable {
            try {
                val result = queryBrowsePage(key)
                synchronized(browsePageCache) { browsePageCache[key] = result }
                val displayVersion = synchronized(browseDisplayRequests) { browseDisplayRequests.remove(key) }
                if (displayVersion != null) main.post { applyBrowsePage(key, result, displayVersion) }
                prefetchBrowseNeighbors(key, result.total)
            } finally {
                synchronized(browseRequestsInFlight) { browseRequestsInFlight.remove(key) }
            }
        })
    }

    private fun queryBrowsePage(key: BrowseCacheKey): BrowsePageResult {
            var songs: MutableList<Song>? = null
            val countKey = browseCountKey(key)
            var total = synchronized(browseCountCache) { browseCountCache[countKey] } ?: -1
            val offset = key.page * key.pageSize
            if (library.muse.isAvailable()) {
                when (key.mode) {
                    "hot" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "guess" -> {
                        songs = library.muse.hotSongs(offset, key.pageSize)
                        if (total < 0) total = library.muse.songCount()
                    }

                    "rank" -> {
                        songs = library.muse.rankSongs(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.rankSongCount(key.param)
                    }

                    "language" -> {
                        songs = library.muse.songsByLanguage(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByLanguage(key.param)
                    }

                    "wordcount" -> {
                        val wc = parseIntSafe(key.param, 0)
                        songs = library.muse.songsByWordCount(wc, offset, key.pageSize)
                        if (total < 0) total = library.muse.countByWordCount(wc)
                    }

                    "singer" -> {
                        songs = library.muse.songsBySingerName(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySingerName(key.param)
                    }

                    "singer_id" -> {
                        songs = library.muse.songsBySinger(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSongsBySinger(key.param)
                    }

                    "singer_id_search" -> {
                        songs = library.muse.searchSongsBySinger(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsBySinger(key.param, key.searchQuery)
                    }

                    "search" -> {
                        songs = library.muse.searchSongs(key.param, offset, key.pageSize, key.language)
                        if (total < 0) total = library.muse.searchSongCount(key.param, key.language)
                    }

                    "favorite" -> {
                        // 收藏功能暂未实现,返回空列表
                        songs = ArrayList<Song>()
                        total = 0
                    }

                    "playlist" -> {
                        // 歌单查询(通过歌单名称查找 ID)
                        val plist: MutableList<Array<String?>> = library.muse.playlists(0, 100)
                        var playlistId = ""
                        for (p in plist) {
                            if (key.param == p[1]) {
                                playlistId = p[0].orEmpty()
                                break
                            }
                        }
                        if (!playlistId.isEmpty()) {
                            songs = library.muse.songsInPlaylist(playlistId, offset, key.pageSize)
                            if (total < 0) total = library.muse.playlistSongCount(playlistId)
                        } else {
                            songs = ArrayList<Song>()
                            total = 0
                        }
                    }

                    "playlist_id" -> {
                        songs = library.muse.songsInPlaylist(key.param, offset, key.pageSize)
                        if (total < 0) total = library.muse.playlistSongCount(key.param)
                    }

                    "playlist_id_search" -> {
                        songs = library.muse.searchSongsInPlaylist(key.param, key.searchQuery, offset, key.pageSize)
                        if (total < 0) total = library.muse.countSearchSongsInPlaylist(key.param, key.searchQuery)
                    }
                }
            }
            if (total < 0) total = 0
            synchronized(browseCountCache) { browseCountCache[countKey] = total }
            return BrowsePageResult(songs.orEmpty(), total)
    }

    private fun applyBrowsePage(key: BrowseCacheKey, result: BrowsePageResult, requestVersion: Int) {
        if (requestVersion != browseRequestVersion || key.mode != browseMode ||
            key.param != browseParam.orEmpty() || key.page != browsePage ||
            key.language != searchLanguage || key.searchQuery != activeSearchQuery
        ) return
        browseTotalCount = result.total
        browseTotalPages = (result.total + key.pageSize - 1) / key.pageSize
        visibleSongs.clear()
        visibleSongs.addAll(result.songs)
        renderSongList()
        updatePageInfo()
    }

    private fun prefetchBrowseNeighbors(key: BrowseCacheKey, total: Int) {
        val totalPages = (total + key.pageSize - 1) / key.pageSize
        listOf(key.page - 1, key.page + 1)
            .filter { it in 0 until totalPages }
            .forEach { page ->
                val neighbor = key.copy(page = page)
                val cached = synchronized(browsePageCache) { browsePageCache.containsKey(neighbor) }
                if (!cached) requestBrowsePage(neighbor, browseRequestVersion, false)
            }
    }

    private fun refreshCurrentCatalogPage() {
        catalogRefreshPending = false
        synchronized(browsePageCache) { browsePageCache.clear() }
        synchronized(browseCountCache) { browseCountCache.clear() }
        busy(false, "曲库加载完成")
        when {
            browseMode == "singer_list" -> loadSingerList(browseParam)
            browseMode == "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            browseMode == "quick" -> loadQuickSearchPage(activeSearchQuery)
            currentTabIndex == 3 -> loadWordCounts()
            currentTabIndex == 7 -> loadFavorites()
            else -> loadBrowsePage()
        }
    }

    /**
     * 轻量刷新当前歌曲列表 Adapter (不重建), 用于下载状态变更后即时更新 UI。
     */
    private fun refreshSongAdapter() {
        modernSongAdapter?.let { adapter ->
            if (songList?.adapter === adapter) adapter.notifyDataSetChanged()
        }
    }

    /**
     * 渲染歌曲列表
     */
    private fun renderSongList() {
        val list = songList ?: return
        val focusedView = window.decorView.findFocus()?.takeIf { isDescendantOf(it, list) }
        val focusedMarker = focusedView?.focusMarker()
            ?.takeIf { it.startsWith(SongListAdapter.FOCUS_PREFIX) }
        val oldSelectedPosition = list.selectedItemPosition
        val oldFocusedTop = focusedView?.top
        if (browseMode == "ordered") {
            visibleSongs.clear()
            visibleSongs.addAll(orderQueue.orEmpty())
        } else if (browseMode == "sang") {
            visibleSongs.clear()
            visibleSongs.addAll(sangHistory)
        }
        if (visibleSongs.isEmpty()) {
            modernSongAdapter = null
            list.adapter = tvAdapter().apply { add("暂无歌曲") }
            return
        }
        list.dividerHeight = 0
        val mode = when (browseMode) {
            "rank", "ordered", "downloads", "sang" -> browseMode
            else -> "catalog"
        }
        val pageOffset = if (mode == "rank") browsePage * browsePageSize() else 0
        val existing = modernSongAdapter?.takeIf { it.matches(mode, pageOffset) && list.adapter === it }
        // 分页网格/排行列表: 行高自适应铺满屏幕(2列目录每页5行, 排行单列每页6行);
        // 可滚动的队列/下载/已唱列表保持自然行高(rowsPerPage=0)。
        val rowsPerPage = when (mode) {
            "catalog" -> 5
            "rank" -> 6
            else -> 0
        }
        val listAdapter = existing ?: SongListAdapter(
            this,
            visibleSongs,
            mode,
            pageOffset,
            btnPrevPage?.id ?: View.NO_ID,
            btnNextPage?.id ?: View.NO_ID,
            { song -> songRowState(song) },
            SongListCallbacks(
                onPrimary = { song -> handleSongPrimary(song) },
                onDownload = { song -> downloadSong(song) },
                onMore = { song -> showSongActions(song) },
                onTop = { song -> moveQueuedSongToNext(song) },
                onDelete = { song ->
                    if (browseMode == "downloads") deleteDownloadedSong(song) else deleteQueuedSong(song)
                },
                onPauseResume = { song -> toggleDownloadPause(song) },
            ),
            initialRowHeightPx = 0,
            rowsPerPage = rowsPerPage,
        ).also {
            modernSongAdapter = it
            list.adapter = it
        }
        if (existing != null) listAdapter.notifyDataSetChanged()
        // 布局完成后按实际可视高度拉满各行, 消除投影/高分屏底部空白。
        if (rowsPerPage > 0) {
            list.post { if (list.adapter === listAdapter) listAdapter.fitRowHeight(list.height) }
        }

        val targetPosition = focusedMarker?.let(listAdapter::adapterPositionFor)
            ?: oldSelectedPosition.takeIf { it >= 0 }
        if (targetPosition != null && focusedMarker != null) {
            list.setSelectionFromTop(targetPosition, oldFocusedTop ?: 0)
            list.post {
                if (list.adapter !== listAdapter) return@post
                list.setSelection(targetPosition)
                val row = list.getChildAt(targetPosition - list.firstVisiblePosition)
                val target = row?.let { findViewByFocusMarker(it, focusedMarker) }
                if (target != null) {
                    target.requestFocus()
                    target.refreshDrawableState()
                    target.jumpDrawablesToCurrentState()
                    target.invalidate()
                }
            }
        }
    }

    private fun findViewByFocusMarker(root: View, marker: String): View? {
        if (root.focusMarker() == marker) return root
        if (root is ViewGroup) repeat(root.childCount) { index ->
            findViewByFocusMarker(root.getChildAt(index), marker)?.let { return it }
        }
        return null
    }

    private fun songRowState(song: Song): SongRowState {
        val task = downloads.lastOrNull { stableId(it.song) == stableId(song) }
        val taskState = task?.state.orEmpty()
        return SongRowState(
            current = currentSong?.equals(song) == true,
            queued = orderQueue!!.contains(song),
            local = song.hasLocalFile(),
            downloading = isDownloading(song),
            failed = taskState.startsWith("失败"),
            paused = taskState.contains("暂停") || taskState.contains("中断"),
            progress = task?.progress ?: SongOkDownloadManager.getDownloadProgress(song),
        )
    }

    private fun handleSongPrimary(song: Song) {
        var rendered = false
        when (browseMode) {
            "ordered" -> {
                if (currentSong?.equals(song) == true) toast("正在播放: ${song.title}")
                else moveQueuedSongToNext(song)
                rendered = true
            }
            "downloads" -> addToQueue(song)
            "sang" -> addToQueue(song)
            else -> addToQueue(song)
        }
        persistRuntimeState()
        if (!rendered) renderSongList()
    }

    private fun moveQueuedSongToNext(song: Song) {
        if (currentSong?.equals(song) == true) return
        orderQueue!!.remove(song)
        val target = if (currentSong != null && orderQueue!!.isNotEmpty()) 1 else 0
        orderQueue.add(target.coerceAtMost(orderQueue.size), song)
        persistRuntimeState()
        syncPlayerInfo()
        renderSongList()
    }

    private fun deleteQueuedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast("正在播放的歌曲不能删除")
            return
        }
        orderQueue!!.remove(song)
        persistRuntimeState()
        syncPlayerInfo()
        renderSongList()
    }

    private fun toggleDownloadPause(song: Song) {
        val task = downloads.lastOrNull { stableId(it.song) == stableId(song) }
        if (isDownloading(song)) {
            SongOkDownloadManager.cancelDownload(song)
            task?.state = "已暂停"
            stateIo.execute { stateDatabase.updateDownload(song, "paused", task?.progress ?: 0) }
            renderSongList()
        } else {
            downloadSong(song)
        }
    }

    private fun deleteDownloadedSong(song: Song) {
        if (currentSong?.equals(song) == true) {
            toast("正在播放的歌曲不能删除")
            return
        }
        SongOkDownloadManager.cancelDownload(song)
        val files = listOfNotNull(song.path?.let(::File), SongOkDownloadManager.getLocalFile(song))
            .distinctBy { it.absolutePath }
        io.execute {
            val failed = files.filter { file ->
                if (!file.exists()) return@filter false
                cleanupSidecars(file)
                !runCatching { file.delete() }.getOrDefault(false)
            }
            if (failed.isEmpty()) {
                song.path = null
                runCatching { stateDatabase.removeDownload(song) }
                main.post {
                    downloads.removeAll { stableId(it.song) == stableId(song) }
                    if (browseMode == "downloads") loadDownloadedList() else reloadCurrentBrowsePage()
                    toast("已删除本地文件")
                }
            } else {
                main.post { toast("文件删除失败，请检查存储权限") }
            }
        }
    }

    /**
     * 更新分页信息
     */
    private fun updatePageInfo() {
        textPageInfo!!.setText(String.format("第%d/%d页", browsePage + 1, max(1, browseTotalPages)))
    }

    /**
     * 上一页
     */
    private fun prevPage() {
        if (browsePage > 0) {
            browsePage--
            reloadCurrentBrowsePage()
        }
    }

    /**
     * 下一页
     */
    private fun nextPage() {
        if (browsePage < browseTotalPages - 1) {
            browsePage++
            reloadCurrentBrowsePage()
        }
    }

    private fun reloadCurrentBrowsePage() {
        when (browseMode) {
            "singer_list" -> loadSingerList(browseParam, activeSearchQuery)
            "local" -> loadLocalCatalogPage()
            "category_list" -> loadCategoryPlaylistPage(activeSearchQuery)
            "quick" -> loadQuickSearchPage(activeSearchQuery)
            else -> loadBrowsePage()
        }
    }

    /**
     * 显示已点列表对话框
     */
    private fun showQueueDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("已点列表 (" + orderQueue!!.size + ")")
        if (orderQueue.isEmpty()) {
            builder.setMessage("暂无已点歌曲")
            builder.setPositiveButton("确定", null)
        } else {
            val items: Array<String> = Array(orderQueue.size) { "" }
            for (i in orderQueue.indices) {
                val s = orderQueue.get(i)
                val marker = if (currentSong != null && currentSong!!.equals(s)) " ▶" else ""
                items[i] = (i + 1).toString() + ". " + s.title + " - " + s.singer + marker
            }
            builder.setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val selected = orderQueue.get(which)
                    if (currentSong != null && currentSong!!.equals(selected)) {
                        toast("正在播放: " + selected.title)
                    } else {
                        // 只设为下一首，不打断当前歌曲。
                        moveQueuedSongToNext(selected)
                    }
                })
            builder.setNegativeButton(
                "删除选中",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    // 显示删除对话框
                    val delItems: Array<String> = Array(orderQueue.size) { "" }
                    for (i in orderQueue.indices) {
                        delItems[i] = orderQueue.get(i).title + " - " + orderQueue.get(i).singer
                    }
                    AlertDialog.Builder(this)
                        .setTitle("选择要删除的歌曲")
                        .setItems(
                            delItems,
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                val removed = orderQueue.removeAt(w)
                                if (currentSong != null && currentSong!!.equals(removed)) {
                                    playNext()
                                }
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast("已删除: " + removed.title)
                            })
                        .create().showForTv()
                })
            builder.setNeutralButton(
                "清空全部",
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    AlertDialog.Builder(this)
                        .setTitle("确认清空")
                        .setMessage("确定要清空全部" + orderQueue.size + "首已点歌曲吗?")
                        .setPositiveButton(
                            "确定",
                            DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                                orderQueue.clear()
                                if (player != null) player!!.stopPlayback()
                                playbackPreparing = false
                                playWhenPrepared = false
                                currentMediaPlayer = null
                                releaseVocalPlayer()
                                currentSong = null
                                clearLyrics()
                                updateBottomBar(null, false)
                                updateOrderBadge()
                                saveState()
                                if (currentTabIndex == 4) loadOrderedList()
                                toast("已清空列表")
                            })
                        .setNegativeButton("取消", null)
                        .create().showForTv()
                })
        }
        builder.create().showForTv()
    }

    /**
     * 更新已点数量徽标(顶栏 + 子页面旧按钮)
     */
    private fun updateOrderBadge() {
        val n = if (orderQueue == null) 0 else orderQueue.size
        if (textTopOrderBadge != null) textTopOrderBadge!!.setText(n.toString())
        if (textOrderBadge != null) textOrderBadge!!.setText(n.toString())
    }

    /**
     * 添加歌曲到已点列表
     */
    private fun addToQueue(song: Song) {
        if (orderQueue!!.contains(song)) {
            toast("已在列表中: " + song.title)
            return
        }
        if (!isSongReadyForQueue(song)) {
            val id = stableId(song)
            val newlyPending = pendingQueueSongIds.add(id)
            toast(
                if (newlyPending) "正在下载，完成后自动加入已点：${song.title}"
                else "歌曲正在下载，尚未进入已点：${song.title}",
            )
            if (!isDownloading(song)) downloadSong(song)
            return
        }
        addReadySongToQueue(song)
    }

    private fun isSongReadyForQueue(song: Song): Boolean =
        SongOkDownloadManager.isDownloaded(song) || song.hasLocalFile()

    private fun addReadySongToQueue(song: Song) {
        if (orderQueue!!.contains(song)) return
        val wasQueueEmpty = orderQueue!!.isEmpty()
        orderQueue.add(song)
        song.playCount++
        if (::stateDatabase.isInitialized) runCatching {
            stateIo.execute { stateDatabase.markSongClicked(song) }
        }
        saveState()
        updateOrderBadge()
        if (currentPage == "已点" || currentPage == "首页") renderQueue()
        toast("已点: " + song.title + (if (song.singer != null) " - " + song.singer else ""))
        if (wasQueueEmpty) play(song)
    }

    private fun scheduleAutoFullscreen() {
        main.removeCallbacks(autoFullscreenRunnable)
        if (autoFullscreenSeconds > 0 && currentSong != null && playWhenPrepared && !isFullScreen) {
            main.postDelayed(autoFullscreenRunnable, autoFullscreenSeconds * 1000L)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!isFullScreen) scheduleAutoFullscreen()
    }

    private fun busy(show: Boolean, message: String?) {
        if (progress != null) {
            progress!!.setVisibility(if (show) View.VISIBLE else View.GONE)
        }
        if (status != null) {
            status!!.setText(message)
        }
    }

    private fun toast(text: String?) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun prefs(): SharedPreferences? {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    /**
     * 将当前运行时状态同步到 KtvStore 并持久化到磁盘。
     * 
     * 
     * 供 Fragment 在修改已点队列、收藏等数据后调用,确保状态不丢失。
     */
    fun saveState() {
        store.loopOne = loopOne
        store.autoNext = autoNext
        store.originalVocal = originalVocal
        store.vocalChannelMode = vocalChannelMode
        store.musicVolume = musicVolume
        store.micVolume = micVolume
        store.tone = tone
        store.atmosphere = atmosphere
        store.singMode = singMode
        store.recordEnabled = recordEnabled
        store.scoreEnabled = scoreEnabled
        store.marqueeEnabled = marqueeEnabled
        store.marqueeText = marqueeText!!
        store.playWhileDownloading = playWhileDownloading
        store.clearDownloadsOnBoot = clearDownloadsOnBoot
        store.autoFullscreenSeconds = autoFullscreenSeconds
        store.showUsbSongs = showUsbSongs
        store.autoDeleteSongs = autoDeleteSongs
        store.reserveStorageGb = reserveStorageGb
        store.floatingButtonEnabled = floatingButtonEnabled
        store.songTitleSubtitleEnabled = songTitleSubtitleEnabled
        store.orderedSongOriginal = orderedSongOriginal
        store.orderedVolumeMode = orderedVolumeMode
        store.orderedVocalMode = orderedVocalMode
        store.voiceEngineEnabled = voiceEngineEnabled
        store.voiceEngineVolume = voiceEngineVolume
        store.lightMode = lightMode!!
        store.audioDelayMs = audioDelayMs
        store.screenBrightness = screenBrightness
        store.screenMode = screenMode
        store.doubleScreenEnabled = doubleScreenEnabled
        store.tableBroadcastEnabled = tableBroadcastEnabled
        store.tableBroadcastSeconds = tableBroadcastSeconds
        store.tableBroadcastText = tableBroadcastText!!
        val storeSnapshot = store.snapshotJson()
        runCatching { stateIo.execute { store.writeSnapshot(storeSnapshot) } }
        persistRuntimeState()
    }

    private fun persistRuntimeState() {
        if (!::stateDatabase.isInitialized) return
        val playbackState = when {
            currentSong == null -> "idle"
            playbackPreparing && playWhenPrepared -> "preparing"
            !playWhenPrepared -> "paused"
            player?.isPlaying == true -> "playing"
            else -> "queued"
        }
        val queueSnapshot = orderQueue.orEmpty().take(200).map { Song.fromJson(it.toJson()) }
        val historySnapshot = sangHistory.take(200).map { Song.fromJson(it.toJson()) }
        val currentId = currentSong?.let(::stableId)
        val currentSnapshot = currentId?.let { id ->
            queueSnapshot.firstOrNull { stableId(it) == id }
        }
        runCatching {
            stateIo.execute {
                stateDatabase.syncQueue(queueSnapshot, historySnapshot, currentSnapshot, playbackState)
            }
        }
    }

    private fun loadStateFromStore() {
        loopOne = store.loopOne
        autoNext = store.autoNext
        originalVocal = store.originalVocal
        vocalChannelMode = store.vocalChannelMode
        musicVolume = store.musicVolume
        micVolume = store.micVolume
        tone = store.tone.coerceIn(-5, 5)
        atmosphere = store.atmosphere
        singMode = store.singMode
        recordEnabled = store.recordEnabled
        scoreEnabled = store.scoreEnabled
        marqueeEnabled = store.marqueeEnabled
        marqueeText = store.marqueeText
        playWhileDownloading = store.playWhileDownloading
        clearDownloadsOnBoot = store.clearDownloadsOnBoot
        autoFullscreenSeconds = store.autoFullscreenSeconds
        showUsbSongs = store.showUsbSongs
        autoDeleteSongs = store.autoDeleteSongs
        reserveStorageGb = store.reserveStorageGb
        floatingButtonEnabled = store.floatingButtonEnabled
        songTitleSubtitleEnabled = store.songTitleSubtitleEnabled
        orderedSongOriginal = store.orderedSongOriginal
        orderedVolumeMode = store.orderedVolumeMode
        orderedVocalMode = store.orderedVocalMode
        voiceEngineEnabled = store.voiceEngineEnabled
        voiceEngineVolume = store.voiceEngineVolume
        lightMode = store.lightMode
        audioDelayMs = store.audioDelayMs
        screenBrightness = store.screenBrightness
        screenMode = store.screenMode
        doubleScreenEnabled = store.doubleScreenEnabled
        tableBroadcastEnabled = store.tableBroadcastEnabled
        tableBroadcastSeconds = store.tableBroadcastSeconds
        tableBroadcastText = store.tableBroadcastText
    }

    private fun dp(value: Int): Int {
        return (value * getResources().getDisplayMetrics().density + 0.5f).toInt()
    }

    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 10
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            recreate()
        }
        if (requestCode == 11 && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
        if (requestCode == REQUEST_MIC_PERMISSION && grantResults.size > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            micActivator.start()
            Log.i(TAG, "麦克风权限已授予, 麦克风输入激活")
        } else if (requestCode == REQUEST_MIC_PERMISSION) {
            Log.w(TAG, "麦克风权限被拒绝: 部分设备将无麦克风演唱声音输出")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        focusRestoreSerial++
        val code = event.getKeyCode()
        if (isFullScreen) {
            val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            val direction = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            val volumePanelVisible = fullScreenVolumePanel?.visibility == View.VISIBLE
            val volumePanelHasFocus = volumePanelVisible && fullScreenVolumePanel?.hasFocus() == true
            if (code == KeyEvent.KEYCODE_BACK && volumePanelVisible) {
                main.removeCallbacks(hideFullScreenVolumeIndicatorRunnable)
                fullScreenVolumePanel?.visibility = View.GONE
                fullScreenToneControl?.requestFocus()
                setFullScreenChromeVisible(true)
                return true
            }
            if (center && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if (volumePanelVisible && (center || direction) && !volumePanelHasFocus &&
                fullScreenControls?.hasFocus() != true
            ) {
                setFullScreenChromeVisible(true)
                fullScreenVolumeMinusButton?.requestFocus()
                return true
            }
            if (center && fullScreenControls?.hasFocus() != true && !volumePanelHasFocus) {
                setFullScreenChromeVisible(true)
                fullScreenControls?.getChildAt(0)?.requestFocus()
                return true
            }
            if (direction && !fullScreenChromeVisible) {
                setFullScreenChromeVisible(true)
                return true
            }
            if ((center || direction) && fullScreenChromeVisible) {
                main.removeCallbacks(hideFullScreenChromeRunnable)
                main.postDelayed(hideFullScreenChromeRunnable, 5000L)
            }
            if (volumePanelHasFocus && code == KeyEvent.KEYCODE_DPAD_UP) {
                fullScreenToneControl?.requestFocus()
                return true
            }
            if (volumePanelHasFocus && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true
            }
            if (volumePanelVisible && window.decorView.findFocus() === fullScreenToneControl &&
                code == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                fullScreenVolumeMinusButton?.requestFocus()
                return true
            }
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE) {
            togglePlay()
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNext()
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (isFullScreen) {
                exitFullScreenPlayer()
                return true
            }
            if (currentTabIndex != -1 || "首页" != currentPage) {
                navigateBack()
                return true
            }
        }
        val homeDirection = homeLayout?.visibility == View.VISIBLE && currentPage == "首页" &&
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        if (homeDirection) {
            val focused = window.decorView.findFocus()
            val reverseTarget = lastHomeFocusSource
            if (focused === lastHomeFocusTarget && code == lastHomeFocusReverseKey &&
                reverseTarget?.isAttachedToWindow == true && reverseTarget.isShown && reverseTarget.isEnabled
            ) {
                if (reverseTarget.requestFocus()) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = reverseTarget
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                    return true
                }
                clearHomeFocusTransition()
            }

            val handled = super.dispatchKeyEvent(event)
            main.post {
                val destination = window.decorView.findFocus()
                if (focused != null && destination != null && destination !== focused &&
                    homeLayout?.visibility == View.VISIBLE && currentPage == "首页"
                ) {
                    lastHomeFocusSource = focused
                    lastHomeFocusTarget = destination
                    lastHomeFocusReverseKey = oppositeDpadKey(code)
                }
            }
            return handled
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { prepareTvFocusableTree(window.decorView) }
    }

    private fun navigateBack() {
        val returnPoint = focusReturnStack.pollLast()
        if (returnPoint != null) {
            restoringFocusRoute = true
            try {
                returnPoint.restorePage()
            } finally {
                restoringFocusRoute = false
            }
            restoreFocusBookmark(returnPoint.focus, returnPoint.directFocus)
            return
        }
        when (browseMode) {
            "playlist_id", "playlist_id_search" -> returnToCategoryPlaylists()
            "settings_section" -> showSettingsPage()
            else -> showHomePage()
        }
    }

    private fun navigateBackToHome() {
        while (focusReturnStack.size > 1) focusReturnStack.pollLast()
        if (focusReturnStack.isNotEmpty()) navigateBack() else showHomePage()
    }

    private fun rememberReturnPointIfNeeded(targetPage: String) {
        if (restoringFocusRoute || targetPage == currentPage) return
        val focus = captureFocusBookmark(window.decorView.findFocus())
        val fromHome = homeLayout?.visibility == View.VISIBLE || currentPage == "首页"
        if (fromHome) {
            focusReturnStack.clear()
            focusReturnStack.addLast(FocusReturnPoint({ showHomePage() }, focus, window.decorView.findFocus()))
            return
        }
        val currentDepth = currentPage.count { it == '/' }
        val targetDepth = targetPage.count { it == '/' }
        if (targetDepth <= currentDepth) return

        val savedMode = browseMode
        val savedParam = browseParam
        val savedPage = browsePage
        val savedQuery = activeSearchQuery
        val savedCategory = currentCategories.getOrNull(currentCategoryIndex)
        val restorePage: () -> Unit = when (savedMode) {
            "singer_list" -> ({
                loadSingers()
                browsePage = savedPage
                savedCategory?.let { browseParam = it }
                activeSearchQuery = savedQuery
                loadSingerList(savedCategory ?: "全部", savedQuery)
            })
            "category_list" -> ({
                searchScope = "playlist"
                activeSearchQuery = savedQuery
                setSearchText(savedQuery)
                currentTabIndex = 2
                showSubPageShell("主页 / 分类")
                setupTabs()
                currentCategories.clear()
                currentCategoryIndex = -1
                updateCategories()
                browseMode = "category_list"
                browseParam = savedParam
                browsePage = savedPage
                loadCategoryPlaylistPage(savedQuery)
            })
            "settings" -> ({ showSettingsPage(false) })
            else -> ({ showHomePage() })
        }
        focusReturnStack.addLast(FocusReturnPoint(restorePage, focus, window.decorView.findFocus()))
    }

    private fun captureFocusBookmark(view: View?): FocusBookmark? {
        if (view == null) return null
        val resourceName = if (view.id != View.NO_ID && (view.id ushr 24) != 0) {
            runCatching { resources.getResourceName(view.id) }.getOrNull()
        } else null
        val marker = view.focusMarker()
        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        val groupText = if (view is ViewGroup) collectFocusText(view).takeIf { it.isNotBlank() } else null
        return FocusBookmark(resourceName, marker, text, groupText)
    }

    private fun collectFocusText(view: View): String {
        val values = ArrayList<String>(3)
        fun collect(node: View) {
            if (values.size >= 3) return
            if (node is TextView) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            } else if (node is ViewGroup) {
                for (index in 0 until node.childCount) collect(node.getChildAt(index))
            }
        }
        collect(view)
        return values.joinToString("|")
    }

    private fun restoreFocusBookmark(bookmark: FocusBookmark?, preferredView: View? = null) {
        if (bookmark == null && preferredView == null) return
        val serial = ++focusRestoreSerial
        val settingsList = songList
        val settingsAdapter = settingsList?.adapter as? SettingsListAdapter
        val settingsPosition = bookmark?.marker?.let { marker ->
            settingsAdapter?.positionForMarker(marker)
        }
        if (settingsList != null && settingsAdapter != null && settingsPosition != null) {
            settingsAdapter.requestFocusAt(settingsList, settingsPosition)
            return
        }
        var restored = false
        listOf(0L, 60L, 180L, 450L, 900L).forEach { delay ->
            main.postDelayed({
                if (restored || serial != focusRestoreSerial) return@postDelayed
                val target = preferredView?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable }
                    ?: bookmark?.let { findFocusTarget(window.decorView, it) }
                if (target != null) {
                    restored = target.requestFocus() || target.requestFocusFromTouch()
                    if (restored) {
                        target.refreshDrawableState()
                        target.jumpDrawablesToCurrentState()
                        target.invalidate()
                    }
                }
            }, delay)
        }
    }

    private fun findFocusTarget(root: View, bookmark: FocusBookmark): View? {
        var textMatch: View? = null
        var groupMatch: View? = null
        fun visit(view: View): View? {
            if (view.visibility != View.VISIBLE || !view.isShown || !view.isEnabled) return null
            if (view.isFocusable) {
                val resourceName = if (view.id != View.NO_ID) {
                    runCatching { resources.getResourceName(view.id) }.getOrNull()
                } else null
                if (bookmark.resourceName != null && resourceName == bookmark.resourceName) return view
                if (bookmark.marker != null && view.focusMarker() == bookmark.marker) return view
                if (textMatch == null && bookmark.text != null &&
                    (view as? TextView)?.text?.toString() == bookmark.text
                ) textMatch = view
                if (groupMatch == null && bookmark.groupText != null && view is ViewGroup &&
                    collectFocusText(view) == bookmark.groupText
                ) groupMatch = view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return visit(root) ?: textMatch ?: groupMatch
    }

    private fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun prepareTvFocusableTree(view: View) {
        if (view.isClickable && view.visibility == View.VISIBLE && view.isEnabled) {
            TvFocusStyler.install(view)
            if (view.contentDescription.isNullOrBlank()) {
                val spoken = when (view) {
                    is TextView -> view.text?.toString()?.trim().orEmpty()
                    is ViewGroup -> view.visibleTextLabel()
                    else -> ""
                }
                if (spoken.isNotEmpty()) view.contentDescription = spoken
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) prepareTvFocusableTree(view.getChildAt(index))
        }
    }

    private fun showFavorites() {
        setPage("收藏")
        content!!.removeAllViews()
        val panel = panel()
        content!!.addView(panel, LinearLayout.LayoutParams(-1, -1))
        panel.addView(sectionTitle("我的收藏"))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        panel.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isFavorite(song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylists() {
        setPage("歌单")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("我的歌单"))
        for (playlist in store.playlists) {
            left.addView(
                button(
                    playlist + "  " + store.songsInPlaylist(playlist).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(playlist) })
            )
        }
        left.addView(
            button(
                "新建歌单",
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )
        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle("歌单歌曲"))
        right.addView(
            label(
                "选择左侧歌单查看歌曲；在点歌、排行、歌星等页面长按歌曲可加入或移出歌单。",
                20,
                Color.LTGRAY
            )
        )
    }

    private fun showPlaylistSongs(playlist: String) {
        setPage("歌单")
        content!!.removeAllViews()
        val left = panel()
        content!!.addView(left, LinearLayout.LayoutParams(dp(300), -1))
        left.addView(sectionTitle("我的歌单"))
        for (item in store.playlists) {
            left.addView(
                button(
                    (if (item == playlist) "> " else "") + item + "  " + store.songsInPlaylist(item).size,
                    View.OnClickListener { v: View? -> showPlaylistSongs(item) })
            )
        }
        left.addView(
            button(
                "新建歌单",
                View.OnClickListener { v: View? -> showCreatePlaylistDialog() })
        )

        val right = panel()
        content!!.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        right.addView(sectionTitle(playlist))
        songList = listView()
        songAdapter = tvAdapter()
        songList!!.setAdapter(songAdapter)
        songList!!.setOnItemClickListener(OnItemClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            handleSong(
                visibleSongs.get(pos)
            )
        })
        songList!!.setOnItemLongClickListener(OnItemLongClickListener { p: AdapterView<*>?, v: View?, pos: Int, id: kotlin.Long ->
            showSongActions(visibleSongs.get(pos))
            true
        })
        right.addView(songList, LinearLayout.LayoutParams(-1, 0, 1f))
        visibleSongs.clear()
        for (song in library.allSongs()) {
            if (store.isInPlaylist(playlist, song)) visibleSongs.add(song)
        }
        renderSongs()
    }

    private fun showPlaylistPicker(song: Song) {
        val items: Array<String> = Array(store.playlists.size) { "" }
        for (i in store.playlists.indices) {
            val playlist = store.playlists.get(i)
            items[i] = (if (store.isInPlaylist(playlist, song)) "移出 " else "加入 ") + playlist
        }
        AlertDialog.Builder(this)
            .setTitle("选择歌单")
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val playlist = store.playlists.get(which)
                    val added = store.togglePlaylistSong(playlist, song)
                    saveState()
                    toast((if (added) "已加入：" else "已移出：") + playlist)
                    if ("歌单" == currentPage) showPlaylistSongs(playlist)
                })
            .create().showForTv()
    }

    companion object {
        // ===== 歌曲源设备身份(用于 w.w345.my 等歌曲源的保活/认证伪装) =====
        // 歌曲源通过共享后台按 device 判定合法性, App 会向 hbeat.ktvsky.com
        // 周期心跳(whoami)维持设备活跃;本 App 若不做心跳,设备可能被判为不合法而限流。
        // 设备标识与抓包请求一致(mac + sn 见 KtvJsBridge.DEVICE_MAC/DEVICE_SN)。
        private const val LEGACY_PACKAGE = "com.origjoy.local.ktv"
        private const val LEGACY_DEVICE_ID = "ae60e9547958" // androidid / _mac
        private const val LEGACY_KTV_ID = "10006789"        // 门店/包厢 id
        private const val HEARTBEAT_INTERVAL_MS = 3600_000L

        // ===== 主题色(对标原版麦动 配色) =====
        /** 主背景:极深蓝黑(原版 #080C12 系)  */
        private val BG = Color.rgb(8, 12, 18)

        /** 面板背景:深蓝灰(原版卡片底色)  */
        private val PANEL = Color.rgb(22, 31, 42)

        /** 次级面板:更深(列表区背景)  */
        private val PANEL_2 = Color.rgb(15, 22, 31)

        /** 焦点色:青绿色(原版选中态)  */
        private val FOCUS = Color.rgb(39, 204, 164)

        /** 金色:标题与高亮(原版 color_yellow_3 #FFE363 系)  */
        private val GOLD = Color.rgb(245, 190, 89)

        /** 强调红:选中态/激活态(原版 color_red #FD3359)  */
        private val ACCENT_RED = Color.rgb(253, 51, 89)

        /** 文本白:主文本色(原版 color_white_3 #F1F1F1)  */
        private val TEXT_WHITE = Color.rgb(241, 241, 241)

        /** 次要文本:70% 透明白(原版 color_white_3_70)  */
        private val TEXT_DIM = Color.argb(180, 241, 241, 241)

        /** 导航选中背景:半透明红  */
        private val NAV_SELECTED = Color.argb(50, 253, 51, 89)

        /** 分割线颜色  */
        private val DIVIDER = Color.argb(80, 241, 241, 241)
        private const val PREFS = "ktv"
        private const val KEY_CATALOG_URL = "catalog_url"
        private const val KEY_SHOW_MOBILE_QR = "show_mobile_qr"
        private const val REQUEST_MIC_PERMISSION = 12

        private const val TAG = "MainActivity"
    }
}
