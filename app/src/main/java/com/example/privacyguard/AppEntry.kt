package com.example.privacyguard

import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val uid: Int,
    val icon: Drawable,
    val privacyPermissions: List<String>,
    val receivedBytes: Long?,
    val sentBytes: Long?
)
