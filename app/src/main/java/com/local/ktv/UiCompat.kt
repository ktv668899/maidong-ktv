package com.local.ktv

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi

/**
 * Android 4.4(API 19) 的 UI 兼容封装。
 *
 * 涉及 Android 5.0(API 21) 才有的类型全部隔离在 [Api21] 内部对象里：
 * 低版本设备永远不会加载引用了不存在类型（ViewOutlineProvider / Outline 等）的那个类，
 * 避免 Dalvik/ART 在类加载或方法验证时抛 NoClassDefFoundError / VerifyError。
 */
object UiCompat {

    /**
     * 设置控件阴影高度(elevation)。
     * Android 4.4 没有材质阴影概念，直接跳过 —— 视觉上少一层阴影，不影响布局与交互。
     */
    fun setElevation(view: View?, elevationPx: Float) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21.setElevation(view, elevationPx)
        }
    }

    /**
     * 把控件裁剪成圆形（头像/封面常用）。
     * Android 4.4 不支持 outline 裁剪，退化为保持原样（方形），不影响功能。
     */
    fun applyCircleOutline(view: View?) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21.applyCircleOutline(view)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private object Api21 {
        fun setElevation(view: View, elevationPx: Float) {
            view.elevation = elevationPx
        }

        fun applyCircleOutline(view: View) {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            view.clipToOutline = true
        }
    }
}
