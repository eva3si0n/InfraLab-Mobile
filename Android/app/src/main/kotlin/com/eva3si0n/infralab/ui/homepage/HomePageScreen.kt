package com.eva3si0n.infralab.ui.homepage

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.eva3si0n.infralab.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePageScreen(vm: AppViewModel) {
    val url = vm.homePageBaseURL.trim()
    var webRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HomePage") },
                actions = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { webRef?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    }
                }
            )
        }
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            if (url.isEmpty()) {
                Text(
                    "HomePage not configured — add URL in Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webRef = this
                            loadUrl(url)
                        }
                    },
                    update = { webRef = it }
                )
            }
        }
    }
}
