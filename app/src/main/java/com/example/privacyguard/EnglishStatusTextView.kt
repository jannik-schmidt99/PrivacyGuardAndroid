package com.example.privacyguard

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textview.MaterialTextView

class EnglishStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : MaterialTextView(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        val translated = text?.toString()
            ?.replace("Lade Apps und Netzwerkstatistik…", "Loading apps and network statistics…")
            ?.replace("Nutzungszugriff aktiv", "Usage access enabled")
            ?.replace("Nutzungszugriff fehlt", "Usage access missing")
            ?.replace(" Apps · ", " apps · ")
            ?.replace(" gesperrt", " blocked")
        super.setText(translated, type)
    }
}
