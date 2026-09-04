package com.local.ktv

import android.app.Application
import android.util.Log
import com.liulishuo.okdownload.OkDownload

class KtvApplication : Application() {
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
