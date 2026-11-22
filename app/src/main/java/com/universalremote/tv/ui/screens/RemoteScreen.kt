package com.universalremote.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.components.Dpad
import com.universalremote.tv.ui.components.LabeledKey
import com.universalremote.tv.ui.components.PillKey
import com.universalremote.tv.ui.components.RemoteKey
import com.universalremote.tv.ui.components.SectionTitle
import com.universalremote.tv.ui.components.StatusDot
import com.universalremote.tv.ui.components.VolumeRocker

/**
 * The classic remote: power, D-pad, navigation, playback, volume and the utility keys, laid out
 * like the physical Google TV remote rather than as a plain button grid.
 */
@Composable
fun RemoteScreen(
    vm: MainViewModel,
    onOpenDevices: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onOpenTouchpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val supported = connection is ConnectionState.Connected
    val deviceName = when (val state = connection) {
        is ConnectionState.Connected -> state.info.displayName
        is ConnectionState.Connecting -> state.device.name
        is ConnectionState.Failed -> state.device?.name ?: "No TV"
        ConnectionState.Disconnected -> vm.pairedDevices.value.firstOrNull()?.name ?: "No TV"
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionHeader(
            deviceName = deviceName,
            connection = connection,
            volumeLabel = volume?.let { info ->
                if (info.max > 0) "Vol ${info.level}/${info.max}${if (info.muted) " · muted" else ""}" else null
            },
            onOpenDevices = onOpenDevices,
            onPower = { vm.sendKey(TvKey.POWER) },
            enabled = supported,
        )

        Spacer(Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            VolumeRocker(
                onVolumeUp = { vm.sendKey(TvKey.VOLUME_UP) },
                onVolumeDown = { vm.sendKey(TvKey.VOLUME_DOWN) },
                onMute = { vm.sendKey(TvKey.VOLUME_MUTE) },
                enabled = supported,
            )
            Spacer(Modifier.width(12.dp))
            Dpad(
                onUp = { vm.sendKey(TvKey.DPAD_UP) },
                onDown = { vm.sendKey(TvKey.DPAD_DOWN) },
                onLeft = { vm.sendKey(TvKey.DPAD_LEFT) },
                onRight = { vm.sendKey(TvKey.DPAD_RIGHT) },
                onOk = { vm.sendKey(TvKey.DPAD_CENTER) },
                enabled = supported,
            )
            Spacer(Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RemoteKey(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Channel up",
                    onClick = { vm.sendKey(TvKey.CHANNEL_UP) },
                    enabled = supported,
                    size = 54.dp,
                )
                Text(
                    "CH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RemoteKey(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Channel down",
                    onClick = { vm.sendKey(TvKey.CHANNEL_DOWN) },
                    enabled = supported,
                    size = 54.dp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteKey(Icons.Rounded.ArrowBack, "Back", { vm.sendKey(TvKey.BACK) }, enabled = supported)
            RemoteKey(Icons.Rounded.Home, "Home", { vm.sendKey(TvKey.HOME) }, enabled = supported)
            RemoteKey(Icons.Rounded.Menu, "Menu", { vm.sendKey(TvKey.MENU) }, enabled = supported)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Playback", Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LabeledKey(
                icon = Icons.Rounded.SkipPrevious,
                label = "Prev",
                onClick = { vm.sendKey(TvKey.MEDIA_PREVIOUS) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.FastRewind,
                label = "Rewind",
                onClick = { vm.sendKey(TvKey.MEDIA_REWIND) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Pause,
                label = "Play/Pause",
                onClick = { vm.sendKey(TvKey.PLAY_PAUSE) },
                enabled = supported,
                modifier = Modifier.weight(1.2f),
            )
            LabeledKey(
                icon = Icons.Rounded.FastForward,
                label = "Forward",
                onClick = { vm.sendKey(TvKey.MEDIA_FAST_FORWARD) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.SkipNext,
                label = "Next",
                onClick = { vm.sendKey(TvKey.MEDIA_NEXT) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LabeledKey(
                icon = Icons.Rounded.PlayArrow,
                label = "Play",
                onClick = { vm.sendKey(TvKey.MEDIA_PLAY) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Pause,
                label = "Pause",
                onClick = { vm.sendKey(TvKey.MEDIA_PAUSE) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Stop,
                label = "Stop",
                onClick = { vm.sendKey(TvKey.STOP) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("TV", Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LabeledKey(
                icon = Icons.Rounded.Input,
                label = "Input",
                onClick = { vm.sendKey(TvKey.TV_INPUT) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                onClick = { vm.sendKey(TvKey.SETTINGS) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Apps,
                label = "Recent",
                onClick = { vm.sendKey(TvKey.APP_SWITCH) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
            LabeledKey(
                icon = Icons.Rounded.Search,
                label = "Search",
                onClick = { vm.sendKey(TvKey.SEARCH) },
                enabled = supported,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PillKey(
                icon = Icons.Rounded.Keyboard,
                label = "Keyboard",
                onClick = onOpenKeyboard,
                modifier = Modifier.weight(1f),
            )
            PillKey(
                icon = Icons.Rounded.TouchApp,
                label = "Touchpad",
                onClick = onOpenTouchpad,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        PillKey(
            icon = Icons.Rounded.Mic,
            label = "Voice",
            onClick = onOpenVoice,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            enabled = supported,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConnectionHeader(
    deviceName: String,
    connection: ConnectionState,
    volumeLabel: String?,
    onOpenDevices: () -> Unit,
    onPower: () -> Unit,
    enabled: Boolean,
) {
    val dotColor = when (connection) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.secondary
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    val status = when (connection) {
        is ConnectionState.Connected -> volumeLabel ?: "Connected"
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Failed -> connection.reason
        ConnectionState.Disconnected -> "Not connected"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)),
        onClick = onOpenDevices,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                StatusDot(dotColor)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RemoteKey(
                icon = Icons.Rounded.PowerSettingsNew,
                contentDescription = "Power",
                onClick = onPower,
                enabled = enabled,
                size = 46.dp,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(6.dp))
        }
    }
}
