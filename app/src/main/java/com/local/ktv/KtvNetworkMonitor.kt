package com.local.ktv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 设备网络状态监测（断网 / 恢复），供「网络未连接」提示与断网重连后的会话重建使用。
 *
 * 只依赖 `ConnectivityManager` 的实时回调，不轮询：
 * - `onAvailable` / `onLost` / `onCapabilitiesChanged` 维护「缺省网络是否具备互联网能力」；
 * - 状态**变化**时回调上层（已切到主线程），由上层处理提示与恢复动作。
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
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 开始监测；[onChange] 在**状态变化**时回调（主线程）。
     *
     * ⚠️ 第二参数 [initial] 区分「启动时的初始状态」与「真正的断网→恢复」：
     * 注册回调的当下系统就会回报一次初始状态，上层若把它当成"网络已恢复"，
     * 就会在启动阶段做一遍**重复且有害**的恢复动作（重建歌曲源会话、复位窗口守卫、
     * 把状态栏写成"网络已恢复"）。见 [MainActivity.onNetworkStateChanged]。
     */
    fun start(context: Context, onChange: (online: Boolean, initial: Boolean) -> Unit) {
        if (callback != null) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager = cm
        val initial = currentOnline(cm)
        online = initial
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(true, onChange)
            }

            override fun onLost(network: Network) {
                // 掉了一个网络不代表彻底没网（可能还有以太网/其它网络），重新判定一次。
                publish(currentOnline(manager), onChange)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                publish(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), onChange)
            }
        }
        callback = cb
        val registered = runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            true
        }.getOrElse {
            Log.w(TAG, "registerNetworkCallback failed: ${it.message}")
            false
        }
        if (!registered) {
            callback = null
            manager = null
        }
        Log.i(TAG, "monitor started, online=$initial registered=$registered")
        onChange(initial, true)
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { manager?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "unregister failed: ${it.message}") }
        callback = null
        manager = null
        Log.i(TAG, "monitor stopped")
    }

    private fun publish(value: Boolean, onChange: (Boolean, Boolean) -> Unit) {
        mainHandler.post {
            if (value == online) return@post
            online = value
            Log.i(TAG, "network state changed: online=$value")
            onChange(value, false)
        }
    }

    /** 主动复检一次（例如从后台回到前台），有变化才回调。 */
    fun refresh(onChange: (online: Boolean, initial: Boolean) -> Unit) {
        publish(currentOnline(manager), onChange)
    }

    private fun currentOnline(cm: ConnectivityManager?): Boolean {
        val manager = cm ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return false
                val caps = manager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "currentOnline failed: ${e.message}")
            true
        }
    }
}
