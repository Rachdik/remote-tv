package com.universalremote.tv.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalremote.tv.ui.theme.LocalHapticsEnabled

/** Returns a tap handler that also triggers haptics when the user enabled them. */
@Composable
fun rememberTapHaptic(): () -> Unit {
    val enabled = LocalHapticsEnabled.current
    val view = LocalView.current
    return remember(enabled, view) {
        {
            if (enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
}

/** Circular key such as the D-pad arrows or OK. */
@Composable
fun RemoteKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    val haptic = rememberTapHaptic()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
            ) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) content else content.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Rounded rectangle key with a caption, used for the media and utility rows. */
@Composable
fun LabeledKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val haptic = rememberTapHaptic()
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val tint = if (!enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    } else if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                haptic()
                onClick()
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Pill-shaped key, for the wide actions at the bottom of the remote. */
@Composable
fun PillKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    val haptic = rememberTapHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                haptic()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1)
    }
}

/** The D-pad ring: four arrows around a centre OK key. */
@Composable
fun Dpad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    enabled: Boolean = true,
) {
    val ring = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .size(238.dp)
            .clip(CircleShape)
            .background(ring),
        contentAlignment = Alignment.Center,
    ) {
        RemoteKey(
            icon = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Up",
            onClick = onUp,
            enabled = enabled,
            size = 74.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            container = Color.Transparent,
        )
        RemoteKey(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Down",
            onClick = onDown,
            enabled = enabled,
            size = 74.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            container = Color.Transparent,
        )
        RemoteKey(
            icon = Icons.Rounded.KeyboardArrowLeft,
            contentDescription = "Left",
            onClick = onLeft,
            enabled = enabled,
            size = 74.dp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            container = Color.Transparent,
        )
        RemoteKey(
            icon = Icons.Rounded.KeyboardArrowRight,
            contentDescription = "Right",
            onClick = onRight,
            enabled = enabled,
            size = 74.dp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            container = Color.Transparent,
        )
        val okHaptic = rememberTapHaptic()
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "OK") {
                    okHaptic()
                    onOk()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "OK",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Vertical volume rocker with a mute key between the two halves. */
@Composable
fun VolumeRocker(
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onMute: () -> Unit,
    enabled: Boolean = true,
) {
    val haptic = rememberTapHaptic()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 56.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { haptic(); onVolumeUp() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Volume up",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "VOL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 48.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { haptic(); onMute() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.VolumeOff,
                contentDescription = "Mute",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 56.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { haptic(); onVolumeDown() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Remove,
                contentDescription = "Volume down",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small coloured dot plus label used for the connection banner. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Section heading with a caption. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Empty-state block used by the devices screen. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .alpha(0.9f),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
