package com.example.mycompose.hello.util

object CoinIconUtil {

    fun getIconUrl(symbol: String): String {

        return when(symbol.uppercase()) {

            "BTC" ->
                "https://assets.coingecko.com/coins/images/1/large/bitcoin.png"

            "ETH" ->
                "https://assets.coingecko.com/coins/images/279/large/ethereum.png"

            "BNB" ->
                "https://assets.coingecko.com/coins/images/825/large/bnb-icon2_2x.png"

            "SOL" ->
                "https://assets.coingecko.com/coins/images/4128/large/solana.png"

            "XRP" ->
                "https://assets.coingecko.com/coins/images/44/large/xrp-symbol-white-128.png"

            "DOGE" ->
                "https://assets.coingecko.com/coins/images/5/large/dogecoin.png"

            "ADA" ->
                "https://assets.coingecko.com/coins/images/975/large/cardano.png"

            "DOT" ->
                "https://assets.coingecko.com/coins/images/12171/large/polkadot.png"

            "AVAX" ->
                "https://assets.coingecko.com/coins/images/12559/large/avalanche.png"

            "LINK" ->
                "https://assets.coingecko.com/coins/images/877/large/chainlink-new-logo.png"

            "MATIC" ->
                "https://assets.coingecko.com/coins/images/4713/large/matic-token-icon.png"

            "UNI" ->
                "https://assets.coingecko.com/coins/images/12504/large/uniswap-uni.png"

            "LTC" ->
                "https://assets.coingecko.com/coins/images/2/large/litecoin.png"

            "TRX" ->
                "https://assets.coingecko.com/coins/images/1094/large/tron-logo.png"

            "FTM" ->
                "https://assets.coingecko.com/coins/images/4001/large/Fantom.png"

            "XAU" ->
                "https://img.icons8.com/color/96/gold-bars.png"

            "XAG" ->
                "https://img.icons8.com/color/96/silver-bars.png"

            "WTI" ->
                "https://img.icons8.com/color/96/oil-industry.png"

            "BRENT" ->
                "https://img.icons8.com/color/96/oil-barrel.png"

            "NG" ->
                "https://img.icons8.com/color/96/fire-element.png"

            else ->
                "https://assets.coingecko.com/coins/images/1/large/bitcoin.png"
        }
    }
}