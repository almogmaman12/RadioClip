package com.almog.radioclip.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.radioclip.core.BleMode
import com.almog.radioclip.core.DiagLog
import com.almog.radioclip.core.Settings
import com.almog.radioclip.util.SystemChecks
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    isPaired: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onChangePairing: () -> Unit,
    onRemovePairing: () -> Unit,
    onCopyText: (String) -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("Pairing") {
                Text(
                    if (isPaired) "Paired: everything is encrypted with your pairing code."
                    else "Not paired: nothing is sent or accepted yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChangePairing) { Text(if (isPaired) "Change code" else "Set code") }
                    if (isPaired) TextButton(onClick = onRemovePairing) { Text("Remove") }
                }
            }

            Section("This device") {
                var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(Settings.MAX_NAME_LENGTH)
                        onChange { s -> s.copy(deviceName = name) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name shown to other devices") },
                    placeholder = { Text("Phone model") },
                    singleLine = true
                )
            }

            Section("Clipboard") {
                SwitchRow(
                    "Send what I copy automatically",
                    "Whatever you copy is sent to your connected devices.",
                    settings.clipboardAutoSend
                ) { v -> onChange { it.copy(clipboardAutoSend = v) } }
                SwitchRow(
                    "Put received text on my clipboard",
                    "Off: a notification with a Copy button is shown instead.",
                    settings.clipboardAutoApply
                ) { v -> onChange { it.copy(clipboardAutoApply = v) } }
                SwitchRow(
                    "Skip passwords",
                    "Don't send text that Android marks as sensitive (password managers do this).",
                    settings.clipboardSkipSensitive
                ) { v -> onChange { it.copy(clipboardSkipSensitive = v) } }
                SwitchRow(
                    "Hide received text in Android's clipboard preview",
                    "Android 13 and newer only.",
                    settings.clipboardHidePreview
                ) { v -> onChange { it.copy(clipboardHidePreview = v) } }
                SwitchRow(
                    "Notify when text arrives",
                    "Shows a notification (hidden on the lock screen).",
                    settings.notifyOnText
                ) { v -> onChange { it.copy(notifyOnText = v) } }
            }

            Section("Bluetooth") {
                Text(
                    "How fast devices find each other. Faster uses more battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RadioRow("Battery saver", "Slowest to find devices", settings.bleMode == BleMode.LOW_POWER) {
                    onChange { it.copy(bleMode = BleMode.LOW_POWER) }
                }
                RadioRow("Balanced", "Recommended", settings.bleMode == BleMode.BALANCED) {
                    onChange { it.copy(bleMode = BleMode.BALANCED) }
                }
                RadioRow("Fastest", "Quickest to connect, most battery", settings.bleMode == BleMode.LOW_LATENCY) {
                    onChange { it.copy(bleMode = BleMode.LOW_LATENCY) }
                }
            }

            Section("Wi-Fi Direct (files)") {
                SwitchRow(
                    "Use Wi-Fi Direct",
                    "Needed for files and large text. Off: Bluetooth only, small text.",
                    settings.useWifiDirect
                ) { v -> onChange { it.copy(useWifiDirect = v) } }
                SwitchRow(
                    "Show all nearby Wi-Fi devices",
                    "Off: hide TVs, printers and other non-phone devices.",
                    settings.wifiShowAll
                ) { v -> onChange { it.copy(wifiShowAll = v) } }
            }

            Section("Files and app") {
                Text("Delete received files after", style = MaterialTheme.typography.bodyMedium)
                Settings.DELETE_AFTER_DAYS_OPTIONS.forEach { days ->
                    RadioRow(
                        when (days) {
                            0 -> "Never"
                            1 -> "1 day"
                            else -> "$days days"
                        },
                        null,
                        settings.autoDeleteDays == days
                    ) { onChange { it.copy(autoDeleteDays = days) } }
                }
                SwitchRow(
                    "Start after the phone restarts",
                    null,
                    settings.startOnBoot
                ) { v -> onChange { it.copy(startOnBoot = v) } }

                var ignoring by remember { mutableStateOf(SystemChecks.isIgnoringBatteryOptimizations(context)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        ignoring = SystemChecks.isIgnoringBatteryOptimizations(context)
                        delay(2000)
                    }
                }
                Text(
                    if (ignoring) "Battery optimization: off for RadioClip (good)."
                    else "Battery optimization is on. Some phones stop RadioClip in the background; turning it off helps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!ignoring) {
                    OutlinedButton(onClick = { SystemChecks.openBatterySettings(context) }) {
                        Text("Open battery settings")
                    }
                }
            }

            Section("Diagnostics") {
                var lines by remember { mutableStateOf(DiagLog.buffer.snapshot()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        lines = DiagLog.buffer.snapshot()
                        delay(1000)
                    }
                }
                val text = lines.takeLast(80).joinToString("\n")
                Text(
                    text.ifBlank { "No events yet." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopyText(DiagLog.buffer.snapshot().joinToString("\n")) }) { Text("Copy") }
                    OutlinedButton(onClick = { shareLog(context, DiagLog.buffer.snapshot().joinToString("\n")) }) { Text("Share") }
                    TextButton(onClick = { DiagLog.buffer.clear(); lines = emptyList() }) { Text("Clear") }
                }
                Text(
                    "The log never contains clipboard text or file contents.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section("About") {
                Text("RadioClip – offline clipboard and file sync", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Both devices need the same pairing code and the same app version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun shareLog(context: Context, log: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, log)
        putExtra(Intent.EXTRA_SUBJECT, "RadioClip log")
    }
    context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
