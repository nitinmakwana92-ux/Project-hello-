package com.example.mycompose.hello.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object CoinDCXRetrofit {

    private const val BASE_URL =
        "https://public.coindcx.com/"

    val api: CoinDCXApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
            .create(CoinDCXApi::class.java)
    }
}
