package com.itonkung.meowtoranger.core

import android.content.Context
import com.itonkung.meowtoranger.core.input.WirelessAdb

/** Process-local ADB session. A new connection is needed after process death. */
object AppRuntime {
    @Volatile private var cached: WirelessAdb? = null
    @Synchronized fun adb(context: Context): WirelessAdb =
        cached ?: WirelessAdb(context.applicationContext).also { cached = it }

    fun status(context: Context, message: String) {
        context.getSharedPreferences("auto", Context.MODE_PRIVATE)
            .edit().putString("last_status", message).apply()
    }
}
