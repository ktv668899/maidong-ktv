package com.local.ktv

import android.app.Application
import android.content.Context
import android.util.Log
import com.liulishuo.okdownload.OkDownload

class KtvApplication : Application() {
    /**
     * 存储根目录必须在**任何** AppPaths 访问之前解析好：
     * 部分定制盒子（极米 H1S / Android 5.1.1）上 `/storage/emulated/0` 不可写，
     * 历史实现直接崩溃。这里用 attachBaseContext 抢在所有业务代码之前完成探测。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        base?.let {
            runCatching { AppPaths.init(it) }
                .onFailure { error -> Log.e(TAG, "AppPaths.init failed", error) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        OkDownload.setSingletonInstance(OkDownload.Builder(this).build())
        Log.i(TAG, "KTV application initialized without native services")
    }

    companion object {
        private const val TAG = "KtvApplication"

        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
