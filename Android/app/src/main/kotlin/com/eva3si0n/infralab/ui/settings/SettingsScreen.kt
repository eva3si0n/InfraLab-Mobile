package com.eva3si0n.infralab.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    var kumaKeyInput by remember { mutableStateOf("") }
    var grafanaTokenInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Kuma
            SettingsCard(title = "Uptime Kuma") {
                UrlField("Base URL", viewModel.kumaBaseURL, viewModel::updateKumaBaseURL)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = viewModel.kumaSlug,
                    onValueChange = viewModel::updateKumaSlug,
                    label = { Text("Status page slug") },
                    placeholder = { Text("default") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TokenField(
                    label = "API Key (optional for public pages)",
                    input = kumaKeyInput,
                    onInputChange = { kumaKeyInput = it },
                    isSaved = viewModel.hasKumaAPIKey()
                )
            }

            // Grafana
            SettingsCard(title = "Grafana") {
                UrlField("Base URL", viewModel.grafanaBaseURL, viewModel::updateGrafanaBaseURL)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = viewModel.grafanaDatasourceUID,
                    onValueChange = viewModel::updateGrafanaDatasourceUID,
                    label = { Text("Datasource UID") },
                    placeholder = { Text("prometheus") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TokenField(
                    label = "Service Account Token",
                    input = grafanaTokenInput,
                    onInputChange = { grafanaTokenInput = it },
                    isSaved = viewModel.hasGrafanaToken()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Native charts query Prometheus via Grafana's datasource proxy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // HomePage
            SettingsCard(title = "HomePage") {
                UrlField("Base URL", viewModel.homePageBaseURL, viewModel::updateHomePageBaseURL)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shown as a web page inside the app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // General
            SettingsCard(title = "General") {
                Text("Auto-refresh interval", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(15L to "15s", 30L to "30s", 60L to "1m", 300L to "5m").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = viewModel.refreshIntervalSecs == value,
                            onClick = { viewModel.updateRefreshInterval(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 4)
                        ) { Text(label) }
                    }
                }
            }

            // Save
            Button(
                onClick = {
                    if (kumaKeyInput.isNotEmpty()) { viewModel.setKumaAPIKey(kumaKeyInput); kumaKeyInput = "" }
                    if (grafanaTokenInput.isNotEmpty()) { viewModel.setGrafanaToken(grafanaTokenInput); grafanaTokenInput = "" }
                    viewModel.stopAutoRefresh()
                    viewModel.refreshAll()
                    viewModel.startAutoRefresh()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Refresh")
            }

            val ctx = LocalContext.current
            val version = remember {
                runCatching {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    "InfraLab Mobile v${pi.versionName} (build ${pi.longVersionCode})"
                }.getOrDefault("InfraLab Mobile")
            }
            Text(
                version,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun UrlField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("https://example.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TokenField(label: String, input: String, onInputChange: (String) -> Unit, isSaved: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(label) },
            placeholder = { if (isSaved) Text("••••••••") else Text("Paste token here") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f)
        )
        if (isSaved && input.isEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
        }
    }
}
