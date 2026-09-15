package com.example.mycompose.hello.utils

import android.util.Log

object Logger {
    private const val TAG = "AlgoBot"
    
    fun info(message: String) {
        Log.i(TAG, "ℹ️ $message")
    }
    
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, " $message", throwable)
    }
    
    fun debug(message: String) {
        Log.d(TAG, "🔍 $message")
    }
    
    fun trade(message: String) {
        Log.w(TAG, "💰 $message")
    }
}