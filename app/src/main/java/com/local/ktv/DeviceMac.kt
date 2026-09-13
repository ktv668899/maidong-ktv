package com.local.ktv

import android.util.Log
import java.net.NetworkInterface
import java.util.Locale

/**
 * 设备 MAC 解析 —— 取「当前实际在用的网卡」的硬件地址。
 *
 * 背景：歌曲源（w.w345.my / e.ac19.cn 等）与设备心跳（whoami）按 `device`(mac + sn)
 * 识别设备。早期实现把 mac 写死为抓包设备的固定值，导致所有设备共用同一身份。
 * 这里改为读取本机当前启用网卡的 MAC（12 位小写十六进制、无分隔符）。
 *
 * 取值优先级：
 *   1) 启用中、非虚拟、名称形如 `wlan*` / `eth*` / `en*` 的网卡（有线优先于无线）；
 *   2) 其它启用中的非虚拟网卡；
 *   3) [FALLBACK_MAC]（历史抓包值，保证读不到时仍沿用旧身份，不会变成空值）。
 *
 * 注意：
 * - Android 6 起 `WifiInfo.getMacAddress()` 恒返回 `02:00:00:00:00:00`，
 *   因此这里不使用 WifiManager，直接读 [NetworkInterface.getHardwareAddress]；
 * - `NetworkInterface.isUp/isVirtual` 是 API 24 才有的方法，低版本上会抛错，
 *   统一用 [safeUp]/[safeVirtual] 包一层，失败时按「可用/非虚拟」处理；
 * - 结果按进程缓存，避免每次点歌/心跳都枚举网卡；网络切换时调用 [refresh] 重新解析。
 */
object DeviceMac {
    private const val TAG = "DeviceMac"

    /** 历史抓包设备 MAC：读不到真实网卡时兜底，保证设备身份始终非空。 */
    const val FALLBACK_MAC = "ae60e9547958"

    private const val HEX = "0123456789abcdef"

    @Volatile private var cached: String? = null

    /** 当前网卡 MAC（小写、无分隔符）；读不到返回空串。 */
    fun current(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val value = read()
            cached = value
            if (value.isEmpty()) Log.w(TAG, "no usable network interface mac, fallback to legacy id")
            return value
        }
    }

    /** 当前网卡 MAC；读不到时回退到 [FALLBACK_MAC]。 */
    fun effective(): String = current().ifEmpty { FALLBACK_MAC }

    /** 清空缓存并重新解析（网络切换 / 网卡热插拔后调用）。 */
    fun refresh(): String {
        synchronized(this) { cached = null }
        return current()
    }

    private fun read(): String {
        var candidate = ""
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement() ?: continue
                if (!safeUp(nif) || safeLoopback(nif) || safeVirtual(nif)) continue
                val name = nif.name.orEmpty().lowercase(Locale.ROOT)
                if (isIgnoredName(name)) continue
                val hex = nif.hardwareAddress.toMacHex()
                if (hex.isEmpty()) continue
                // 有线/无线网卡直接采用；否则先记下，继续找更合适的。
                if (isPreferredName(name)) return hex
                if (candidate.isEmpty()) candidate = hex
            }
        } catch (e: Throwable) {
            Log.w(TAG, "read network mac failed: ${e.message}")
        }
        return candidate
    }

    /** 虚拟/点对点/蜂窝接口不算「当前使用的网卡」。 */
    private fun isIgnoredName(name: String): Boolean =
        name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg") ||
            name.startsWith("rmnet") || name.startsWith("radio") || name.startsWith("ccmni") ||
            name.startsWith("pdp") || name.startsWith("dummy") || name.startsWith("sit") ||
            name.startsWith("ip6tnl") || name.startsWith("gre") || name.contains("vpn")

    private fun isPreferredName(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")

    private fun ByteArray?.toMacHex(): String {
        if (this == null || size < 6) return ""
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        val hex = sb.toString()
        // 全 0 与 Android 6+ 的占位 MAC 视为无效。
        return if (hex == "000000000000" || hex == "020000000000") "" else hex
    }

    private fun safeUp(nif: NetworkInterface): Boolean =
        try { nif.isUp } catch (e: Throwable) { true }

    private fun safeLoopback(nif: NetworkInterface): Boolean =
        try { nif.isLoopback } catch (e: Throwable) { false }

    private fun safeVirtual(nif: NetworkInterface): Boolean =
        try { nif.isVirtual } catch (e: Throwable) { false }
}
