package com.example.mycompose.hello.data.model

import com.google.gson.annotations.SerializedName

data class Balance(
    @SerializedName("currency")
    val currency: String,
    @SerializedName("balance")
    val balance: Double,
    @SerializedName("locked_balance")
    val lockedBalance: Double = 0.0
)