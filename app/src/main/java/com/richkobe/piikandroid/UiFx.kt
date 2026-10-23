package com.richkobe.piikandroid

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * 主页与 WebView 页共用的度量换算和小互动效果。
 * 抽出来是为了让两个 Activity 不各写一份 dp() 与按压动画。
 */
object UiFx {
    fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    /**
     * 点击反馈：立即执行动作（不等动画，避免手感发黏），同时做一次
     * 「按下缩小 → 回弹过冲」的缩放，让按钮有按下去的实感。
     */
    fun tapBounce(view: View, action: () -> Unit) {
        action()
        view.animate().cancel()
        view.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .setDuration(60)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }
}
