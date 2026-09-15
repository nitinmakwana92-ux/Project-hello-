package com.example.mycompose.hello.viewmodel

import android.app.Application

/** Single process-wide bot owner shared by Activity and foreground service. */
object TradingBotRuntime {
    @Volatile
    private var instance: TradingViewModel? = null

    @Synchronized
    fun getOrCreate(application: Application): TradingViewModel {
        return instance ?: TradingViewModel(application).also { instance = it }
    }
}
