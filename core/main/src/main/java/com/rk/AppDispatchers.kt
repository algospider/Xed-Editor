package com.rk

import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors

object AppDispatchers {
    val IO = Dispatchers.IO
    val Main = Dispatchers.Main
    val Default = Dispatchers.Default
    val Unconfined = Dispatchers.Unconfined

    private val startupThreadPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "xed-startup").apply { priority = Thread.NORM_PRIORITY }
    }

    val Startup = startupThreadPool.asCoroutineDispatcher()
}
