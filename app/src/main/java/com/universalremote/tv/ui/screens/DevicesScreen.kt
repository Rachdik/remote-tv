package com.universalremote.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.discovery.DiscoveredTv
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.storage.PairedDevice
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.PairingUiState
import com.universalremote.tv.ui.components.EmptyState
import com.universalremote.tv.ui.components.PillKey
import com.universalremote.tv.ui.components.SectionTitle

/** Device connection page: scan, pair, connect and forget TVs. */
@Composable
fun DevicesScreen(
    vm: MainViewModel,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val found by vm.foundTvs.collectAsStateWithLifecycle()
    val paired by vm.pairedDevices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()

    var ipDialog by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("") }
    val connectedDevice = (connection as? ConnectionState.Connected)?.device

    LaunchedEffect(Unit) { vm.repository.refreshDevices() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("Your TVs", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Paired Android TV and Google TV devices on this network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillKey(
                        icon = Icons.Rounded.Refresh,
                        label = if (scanning) "Scanning…" else "Scan network",
                        onClick = { if (!scanning) vm.startScan() else vm.stopScan() },
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    PillKey(
                        icon = Icons.Rounded.Add,
                        label = "IP address",
                        onClick = { ipDialog = true },
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (scanning) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) }
        }

        if (paired.isNotEmpty()) {
            item { SectionTitle("Paired devices") }
            items(paired, key = { it.id }) { device ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(device.host)
                                    if (device.model.isNotBlank()) append(" · ${device.model}")
                                    if (connectedDevice?.id == device.id) append(" · connected")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connectedDevice?.id == device.id) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                vm.connect(device)
                                onOpenRemote()
                            },
                        ) { Text("Connect") }
                        TextButton(onClick = { vm.forget(device) }) {
                            Text("Forget", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item { SectionTitle("Found on network") }
        if (found.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Tv,
                    title = if (scanning) "Looking for TVs…" else "No TV found yet",
                    body = "Make sure the phone and TV are on the same Wi-Fi network, then scan again. " +
                        "You can also add the TV by its IP address.",
                )
            }
        } else {
            items(found, key = { "found-${it.host}" }) { tv ->
                FoundRow(tv = tv, onPair = { vm.pairWith(tv) })
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (ipDialog) {
        AlertDialog(
            onDismissRequest = { ipDialog = false },
            title = { Text("Add by IP address") },
            text = {
                Column {
                    Text(
                        "Ports 6466 and 6467 are checked automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("TV IP address") },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ipDialog = false
                        vm.startPairingByIp(ipAddress)
                        ipAddress = ""
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { ipDialog = false }) { Text("Cancel") }
            },
        )
    }

    when (val state = pairing) {
        is PairingUiState.AwaitingCode -> PairingCodeDialog(
            name = state.name,
            error = state.error,
            onSubmit = vm::submitPairingCode,
            onCancel = vm::cancelPairing,
        )
        is PairingUiState.Connecting -> AlertDialog(
            onDismissRequest = { vm.cancelPairing() },
            title = { Text("Pair with your TV") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting to ${state.name}…")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelPairing) { Text("Cancel") } },
        )
        is PairingUiState.Success -> AlertDialog(
            onDismissRequest = {
                vm.dismissPairing()
                onOpenRemote()
            },
            title = { Text("Paired successfully") },
            text = {
                Text("${state.device.name} is ready. Keep this phone on the same Wi-Fi network to control it.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.dismissPairing()
                        onOpenRemote()
                    },
                ) { Text("Start controlling") }
            },
        )
        is PairingUiState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissPairing,
            title = { Text("Pairing failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = vm::dismissPairing) { Text("Close") } },
        )
        PairingUiState.Idle -> Unit
    }
}

@Composable
private fun FoundRow(tv: DiscoveredTv, onPair: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tv.name.ifBlank { "Android TV" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(tv.host)
                        if (tv.model.isNotBlank()) append(" · ${tv.model}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPair) { Text("Pair") }
        }
    }
}

@Composable
private fun PairingCodeDialog(
    name: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with your TV") },
        text = {
            Column {
                Text(
                    "Enter the 6-character code shown on $name",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "On the TV: Settings → Remote & Accessories → Pair remote accessory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value.filter { it.isLetterOrDigit() }.take(6).uppercase()
                    },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
