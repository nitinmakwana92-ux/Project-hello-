package com.example.mycompose.hello.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mycompose.hello.viewmodel.TradingViewModel

@Composable
fun OpenAiMasterMindSettingsCard(
    viewModel: TradingViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("trading_bot_prefs", Context.MODE_PRIVATE)
    }

    var key by remember { mutableStateOf(prefs.getString("openai_api_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("openai_model", "gpt-5.6-luna") ?: "gpt-5.6-luna") }
    var baseUrl by remember {
        mutableStateOf(prefs.getString("openai_base_url", "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1")
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean("openai_master_mind_enabled", true))
    }
    var status by remember { mutableStateOf("OpenAI Master Mind ready") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF35BFFF)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("🤖 OpenAI Master Mind", fontSize = 19.sp)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.VpnKey, null) }
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI Model") },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API Base URL") },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (key.isBlank()) {
                        status = "❌ OpenAI API key required"
                    } else if (!baseUrl.startsWith("https://")) {
                        status = "❌ API URL must use HTTPS"
                    } else {
                        viewModel.setOpenAiMasterMindConfig(
                            apiKey = key.trim(),
                            model = model.trim().ifBlank { "gpt-5.6-luna" },
                            baseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                            enabled = enabled
                        )
                        status = "✅ OpenAI Master Mind saved"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Activate OpenAI")
            }

            Text(status, fontSize = 10.sp, color = Color(0xFF00E676))
        }
    }
}
