package com.example.mycompose.hello.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.mycompose.hello.util.CoinIconUtil

@Composable
fun CoinLogo(
    symbol: String,
    modifier: Modifier = Modifier
) {

    var error by remember {
        mutableStateOf(false)
    }

    if (!error) {

        AsyncImage(
            model = CoinIconUtil.getIconUrl(symbol),
            contentDescription = symbol,
            modifier = modifier
                .size(55.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            onError = {
                error = true
            }
        )

    } else {

        Box(
            modifier = modifier
                .size(55.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {

            Text(
                text = symbol.take(2),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}