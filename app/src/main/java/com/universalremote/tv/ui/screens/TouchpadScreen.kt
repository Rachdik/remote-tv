package com.universalremote.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.components.RemoteKey
import com.universalremote.tv.ui.components.rememberTapHaptic
import android.os.SystemClock
import kotlin.math.abs

/**
 * Touchpad that translates gestures into D-pad key presses, because the remote protocol carries
 * key events rather than pointer coordinates.
 *
 * - drag: repeated directional presses in the dominant axis
 * - tap: OK
 * - two-finger drag: vertical scroll (D-pad up/down)
 */
@Composable
fun TouchpadScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val haptic = rememberTapHaptic()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Touchpad", style = MaterialTheme.typography.titleLarge)
        Text(
            "Swipe to navigate · Tap to select · Two-finger swipe to scroll",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(connected) {
                    if (!connected) return@pointerInput
                    val stepThreshold = 20.dp.toPx()
                    val tapSlop = 18.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastPosition = down.position
                        var accumulated = Offset.Zero
                        var travelled = 0f
                        var moved = false
                        var maxPointers = 1
                        var lastStepAt = 0L

                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.count { it.pressed }
                            if (active > 0) maxPointers = maxOf(maxPointers, active)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!moved && travelled < tapSlop) {
                                    haptic()
                                    vm.sendKey(TvKey.DPAD_CENTER)
                                }
                                break
                            }
                            val delta = change.position - lastPosition
                            lastPosition = change.position
                            accumulated += delta
                            travelled += delta.getDistance()
                            val now = SystemClock.uptimeMillis()
                            if (accumulated.getDistance() > stepThreshold && now - lastStepAt >= 90) {
                                lastStepAt = now
                                moved = true
                                val vertical = abs(accumulated.y) >= abs(accumulated.x)
                                val key = when {
                                    maxPointers >= 2 -> if (accumulated.y > 0) TvKey.DPAD_DOWN else TvKey.DPAD_UP
                                    vertical -> if (accumulated.y > 0) TvKey.DPAD_DOWN else TvKey.DPAD_UP
                                    else -> if (accumulated.x > 0) TvKey.DPAD_RIGHT else TvKey.DPAD_LEFT
                                }
                                haptic()
                                vm.sendKey(key)
                                accumulated = Offset.Zero
                            }
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (connected) "Swipe anywhere" else "Connect to a TV to use the touchpad",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteKey(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = { vm.sendKey(TvKey.BACK) },
                enabled = connected,
                size = 56.dp,
            )
            RemoteKey(
                icon = Icons.Rounded.Home,
                contentDescription = "Home",
                onClick = { vm.sendKey(TvKey.HOME) },
                enabled = connected,
                size = 56.dp,
            )
        }
    }
}
