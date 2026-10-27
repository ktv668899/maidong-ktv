package com.local.ktv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 设备网络状态监测（断网 / 恢复），供「网络未连接」提示与断网重连后的会话重建使用。
 *
 * 按系统版本走两套实现：
 * - **API 21(Lollipop) 及以上**：[ConnectivityManager.registerNetworkCallback] 实时回调，不轮询；
 * - **API 19/20（Android 4.4 KitKat）**：系统没有 NetworkCallback，退回
 *   [ConnectivityManager.CONNECTIVITY_ACTION] 广播 + 收到广播时主动复检。
 *
 * ⚠️ 两套实现各自封装在**独立类**（[Api21Watcher] / [LegacyWatcher]）中：
 * 引用 Android 5.0 API 的类必须保证在 4.4 设备上**根本不会被加载**，否则
 * Dalvik/ART 解析类型时会抛 NoClassDefFoundError / VerifyError。
 *
 * 注意区分两件事：
 * - 这里判断的是**系统级网络可用性**（有没有网）；
 * - 歌曲源的「试用窗口 / 风控」是按**出口 IP** 计的（见 [Eac19WindowGuard]），
 *   换网/重连后即使有网也可能需要重新建立会话 —— 这由上层在恢复回调里处理。
 *
 * 全局可读 [online]，便于下载层判断失败是否由断网引起。
 */
object KtvNetworkMonitor {
    private const val TAG = "KtvNetworkMonitor"

    /** 当前是否有可用网络（默认 true，避免启动瞬间误报断网）。 */
    @Volatile
    var online: Boolean = true
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: ConnectivityManager? = null
    private var appContext: Context? = null
    private var watcher: Watcher? = null

    /** 监测实现的统一出口。 */
    private interface Watcher {
        fun stop()
    }

    /**
     * 开始监测；[onChange] 在**状态变化**时回调（主线程）。
     *
     * ⚠️ 第二参数 [initial] 区分「启动时的初始状态」与「真正的断网→恢复」：
     * 注册回调的当下系统就会回报一次初始状态，上层若把它当成"网络已恢复"，
     * 就会在启动阶段做一遍**重复且有害**的恢复动作（重建歌曲源会话、复位窗口守卫、
     * 把状态栏写成"网络已恢复"）。见 [MainActivity.onNetworkStateChanged]。
     */
    fun start(context: Context, onChange: (online: Boolean, initial: Boolean) -> Unit) {
        if (watcher != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager = cm
        val initial = currentOnline(cm)
        online = initial

        // 用 Build.VERSION.SDK_INT 分支选择实现：低版本分支不触及任何 Android 5.0 API。
        watcher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Watcher(cm, onChange)
        } else {
            LegacyWatcher(ctx, onChange)
        }
        Log.i(TAG, "monitor started (sdk=${Build.VERSION.SDK_INT}), online=$initial")
        onChange(initial, true)
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        manager = null
        appContext = null
        Log.i(TAG, "monitor stopped")
    }

    /** 主动复检一次（例如从后台回到前台），有变化才回调。 */
    fun refresh(onChange: (online: Boolean, initial: Boolean) -> Unit) {
        publish(currentOnline(manager), onChange)
    }

    private fun publish(value: Boolean, onChange: (Boolean, Boolean) -> Unit) {
        mainHandler.post {
            if (value == online) return@post
            online = value
            Log.i(TAG, "network state changed: online=$value")
            onChange(value, false)
        }
    }

    private fun currentOnline(cm: ConnectivityManager?): Boolean {
        val manager = cm ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return false
                val caps = manager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                // Android 4.4/5.x：没有 NetworkCapabilities，用已废弃但仍可用的 activeNetworkInfo。
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "currentOnline failed: ${e.message}")
            true
        }
    }

    // ─── API 21+：NetworkCallback 实时回调 ────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private class Api21Watcher(
        private val cm: ConnectivityManager?,
        private val onChange: (Boolean, Boolean) -> Unit,
    ) : Watcher {
        private val callback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    publish(true, onChange)
                }

                override fun onLost(network: Network) {
                    // 掉了一个网络不代表彻底没网（可能还有以太网/其它网络），重新判定一次。
                    publish(currentOnline(cm), onChange)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    publish(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), onChange)
                }
            }

        init {
            runCatching {
                cm?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                )
            }.onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
        }

        override fun stop() {
            runCatching { cm?.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "unregister failed: ${it.message}") }
        }
    }

    // ─── API 19/20（Android 4.4）：CONNECTIVITY_ACTION 广播 ───────────────
    private class LegacyWatcher(
        private val context: Context,
        private val onChange: (Boolean, Boolean) -> Unit,
    ) : Watcher {
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // 广播本身不带"是否具备互联网能力"，收到后主动复检一次。
                publish(currentOnline(manager), onChange)
            }
        }

        init {
            runCatching {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            }.onFailure { Log.w(TAG, "registerReceiver failed: ${it.message}") }
        }

        override fun stop() {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "unregisterReceiver failed: ${it.message}") }
        }
    }
}
