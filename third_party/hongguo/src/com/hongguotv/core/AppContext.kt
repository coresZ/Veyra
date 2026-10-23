package com.hongguotv.core

import android.content.Context

object AppContext {
    @Volatile
    private var ctx: Context? = null

    @JvmStatic
    fun init(context: Context) {
        ctx = context.applicationContext
    }

    @JvmStatic
    fun get(): Context {
        return ctx ?: throw IllegalStateException("AppContext not initialized")
    }
}
