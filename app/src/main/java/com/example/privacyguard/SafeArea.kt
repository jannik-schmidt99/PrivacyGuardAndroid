package com.example.privacyguard

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps app content clear of status bars, display cutouts and navigation/gesture areas.
 * This is applied to android.R.id.content so individual layouts can keep their own padding.
 */
fun AppCompatActivity.applySafeAreaInsets() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val content = findViewById<View>(android.R.id.content)
    val initialLeft = content.paddingLeft
    val initialTop = content.paddingTop
    val initialRight = content.paddingRight
    val initialBottom = content.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val safeInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initialLeft + safeInsets.left,
            initialTop + safeInsets.top,
            initialRight + safeInsets.right,
            initialBottom + safeInsets.bottom
        )
        insets
    }

    ViewCompat.requestApplyInsets(content)
}
