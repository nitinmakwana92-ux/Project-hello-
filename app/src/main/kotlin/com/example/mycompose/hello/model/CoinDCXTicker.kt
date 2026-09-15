package com.example.mycompose.model

import com.google.gson.annotations.SerializedName

data class CoinDCXTicker(

    @SerializedName("market")
    val market:String,

    @SerializedName("last_price")
    val lastPrice:String,

    @SerializedName("volume")
    val volume:String
)