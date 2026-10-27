package com.local.ktv

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
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
            // 老系统(API<24)不读 network_security_config，需要代码补上内置根证书的信任，
            // 否则 Let's Encrypt 新链的接口（曲库同步/点歌登录/JS 热更新）全部握手失败。
            runCatching { TlsCompat.install(it) }
                .onFailure { error -> Log.e(TAG, "TlsCompat.install failed", error) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        enableCompatVectorsForLegacy()
        OkDownload.setSingletonInstance(OkDownload.Builder(this).build())
        Log.i(TAG, "KTV application initialized without native services")
    }

    /**
     * Android 4.4(API 19)上跑 vector drawable 的第二道保障。
     *
     * 配合 app/build.gradle 里的 `vectorDrawables.useSupportLibrary = true`:
     *   - Gradle 侧:不再把 <vector> 光栅化成各密度 PNG,原 XML 保留在 drawable/,
     *     由 androidx.vectordrawable 在运行时物化成 VectorDrawableCompat;
     *   - 运行时侧:仅靠 AppCompat 自己的控件(AppCompatImageView 的 app:srcCompat、
     *     TintTypedArray 走的 AppCompatResources)还不够 —— 代码里的
     *     `View.setBackgroundResource()` / `ImageView.setImageResource()` /
     *     `ContextCompat.getDrawable()` 以及 <selector>/<layer-list> 里的
     *     `<item android:drawable="@drawable/<vector>">`,在 API<21 上都会走
     *     framework 的 Resources,遇到 <vector> 标签直接抛
     *     NotFoundException("invalid drawable tag vector")。
     *
     * 打开这个开关后,AppCompatDelegateImpl.attachBaseContext2() 会把 Activity 的
     * Resources 换成 VectorEnabledTintResources,它覆写的 getDrawable(int) 会转交给
     * ResourceManagerInternal.onDrawableLoadedFromResources(),于是上述所有框架路径
     * 也能拿到 VectorDrawableCompat。
     *
     * 该开关只在 SDK <= 20 生效(VectorEnabledTintResources.shouldBeUsed()),
     * 且必须在任何 drawable 加载之前调用,故放在 Application.onCreate 最前面。
     */
    private fun enableCompatVectorsForLegacy() {
        if (Build.VERSION.SDK_INT > 20) return
        runCatching { AppCompatDelegate.setCompatVectorFromResourcesEnabled(true) }
            .onSuccess { Log.i(TAG, "compat vector from resources enabled (API ${Build.VERSION.SDK_INT})") }
            .onFailure { error -> Log.e(TAG, "setCompatVectorFromResourcesEnabled failed", error) }
    }

    companion object {
        private const val TAG = "KtvApplication"

        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
