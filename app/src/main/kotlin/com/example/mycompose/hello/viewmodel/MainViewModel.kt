package com.example.mycompose.hello.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // 1. Sabse pehle ye variable declare hoga (Class ke top par)
    private val _logoMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val logoMap: StateFlow<Map<String, String>> = _logoMap.asStateFlow()

    // 2. Ye aapka function hoga jo data load karega
    fun fetchLogoData() {
        viewModelScope.launch {
            try {
                // Maan lijiye ye aapka network response hai (Example List)
                // Real app mein ye Retrofit/API call se aayega
                val response = listOf(
                    "BTC" to "https://cryptologos.cc/logos/bitcoin-btc-logo.png",
                    "ETH" to "https://cryptologos.cc/logos/ethereum-eth-logo.png",
                    "USDT" to "https://cryptologos.cc/logos/tether-usdt-logo.png"
                )

                // 3. YAHI PAR AAPKA WO CODE AAYEGA! 👇
                _logoMap.value = response.associate { (symbol, url) ->
                    symbol to url
                }
                
            } catch (e: Exception) {
                // Error handle karne ke liye
                e.printStackTrace()
            }
        }
    }
}
           
