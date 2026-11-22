package com.universalremote.tv.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.BuildConfig
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.storage.DeviceStore
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.components.SectionTitle

/** App settings: appearance, remote behaviour, language for the speech fallback and privacy. */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onManageDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val haptics by vm.haptics.collectAsStateWithLifecycle()
    val autoReconnect by vm.autoReconnect.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val voiceLanguage by vm.voiceLanguage.collectAsStateWithLifecycle()
    val preferNative by vm.preferNativeVoice.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        SectionTitle("Appearance")
        Card {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = themeMode == DeviceStore.THEME_SYSTEM,
                    onClick = { vm.setTheme(DeviceStore.THEME_SYSTEM) },
                    label = { Text("System") },
                )
                FilterChip(
                    selected = themeMode == DeviceStore.THEME_DARK,
                    onClick = { vm.setTheme(DeviceStore.THEME_DARK) },
                    label = { Text("Dark") },
                )
                FilterChip(
                    selected = themeMode == DeviceStore.THEME_LIGHT,
                    onClick = { vm.setTheme(DeviceStore.THEME_LIGHT) },
                    label = { Text("Light") },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    title = "Use wallpaper colors",
                    subtitle = "Material You palette (Android 12+)",
                    checked = dynamicColor,
                    onCheckedChange = vm::setDynamicColor,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Remote")
        Card {
            SettingSwitch(
                title = "Haptic feedback",
                subtitle = "Vibrate on every key press",
                checked = haptics,
                onCheckedChange = vm::setHaptics,
            )
            SettingSwitch(
                title = "Reconnect automatically",
                subtitle = "Restore the connection when the TV or Wi-Fi comes back",
                checked = autoReconnect,
                onCheckedChange = vm::setAutoReconnect,
            )
            SettingSwitch(
                title = "Keep the screen awake",
                subtitle = "While the remote is open",
                checked = keepScreenOn,
                onCheckedChange = vm::setKeepScreenOn,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Voice")
        Card {
            SettingSwitch(
                title = "Use the TV's voice input",
                subtitle = "Stream audio to the TV when it offers the voice channel",
                checked = preferNative,
                onCheckedChange = vm::setPreferNativeVoice,
            )
            Spacer(Modifier.height(6.dp))
            Text("Recognition language (fallback mode)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = voiceLanguage == "id-ID",
                    onClick = { vm.setVoiceLanguage("id-ID") },
                    label = { Text("Bahasa Indonesia") },
                )
                FilterChip(
                    selected = voiceLanguage == "en-US",
                    onClick = { vm.setVoiceLanguage("en-US") },
                    label = { Text("English (US)") },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Devices")
        Card {
            val connectedDevice = (connection as? ConnectionState.Connected)?.device
            Text(
                connectedDevice?.let { "${it.name} · ${it.host}" } ?: "No TV connected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onManageDevices) {
                    androidx.compose.material3.Icon(Icons.Rounded.Tv, contentDescription = null)
                    Text("  Manage paired TVs")
                }
                if (connectedDevice != null) {
                    TextButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("About")
        Card {
            Text("Universal TV Remote ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Protocol: Android TV Remote v2 over mutually authenticated TLS with protobuf " +
                    "messages (ports 6466 and 6467). Discovery uses mDNS (_androidtvremote2._tcp) " +
                    "with an SSDP fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Privacy: discovery and control happen only on your local network. Pairing " +
                    "credentials are stored on this device, encrypted with a key held in the " +
                    "Android keystore. Nothing is sent to a third-party server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
