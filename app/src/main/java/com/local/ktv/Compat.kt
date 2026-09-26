package com.local.ktv

import android.os.Build
import java.net.URLConnection

/**
 * 低版本兼容工具。
 *
 * 背景：`minSdk 21`，但代码里用过若干 API 22 不存在的方法，在 Android 5.x
 * （如极米 H1S / GMUI2.0 / Android 5.1.1）上会在运行期抛 `NoSuchMethodError`。
 * 这里把这类调用收敛成带版本判断的兼容函数。
 */

/**
 * 读取 HTTP 响应体长度。
 *
 * `URLConnection.getContentLengthLong()` 是 **API 24(N)** 才加入的；API 21~23 上直接调用
 * 会抛 `NoSuchMethodError`。低版本回退到 `getContentLength()`（int，2GB 以内等价）。
 */
fun URLConnection.contentLengthCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        contentLengthLong
    } else {
        @Suppress("DEPRECATION")
        contentLength.toLong()
    }
