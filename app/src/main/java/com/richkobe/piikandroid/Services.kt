package com.richkobe.piikandroid

import android.content.Context

/** Non-null system service lookup (all services we use exist on every phone). */
inline fun <reified T : Any> Context.service(): T =
    getSystemService(T::class.java) ?: error("${T::class.java.simpleName} is unavailable")
