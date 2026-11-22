package com.universalremote.tv.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.ui.MainViewModel
import com.universalremote.tv.ui.components.PillKey
import com.universalremote.tv.voice.SpeechRecognition
import com.universalremote.tv.voice.SpeechState
import com.universalremote.tv.voice.VoiceStreamer
import kotlinx.coroutines.launch

/**
 * Voice screen.
 *
 * Preferred path: the TV's own voice channel. The phone streams 8 kHz PCM to the TV, which hands
 * it to whatever voice service it has (Google Assistant on Google TV). If the TV does not open
 * that channel, the phone recognises speech itself and types the transcript into the TV — a
 * visibly different, more limited mode.
 */
@Composable
fun VoiceScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val language by vm.voiceLanguage.collectAsStateWithLifecycle()
    val preferNative by vm.preferNativeVoice.collectAsStateWithLifecycle()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    val scope = rememberCoroutineScope()
    var nativeSession by remember { mutableStateOf<Int?>(null) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var usingFallback by remember { mutableStateOf(false) }

    val streamer = remember {
        VoiceStreamer(scope) { chunk ->
            nativeSession?.let { vm.repository.sendVoiceAudio(it, chunk) }
        }
    }
    val speech = remember { SpeechRecognition(context) }
    val speechState by speech.state.collectAsStateWithLifecycle()

    LaunchedEffect(speechState) {
        when (val state = speechState) {
            is SpeechState.Final -> {
                vm.sendText(state.text)
                status = "Sent to the TV: “${state.text}”"
                listening = false
            }
            is SpeechState.Error -> {
                status = state.message
                listening = false
            }
            SpeechState.Listening -> listening = true
            else -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (nativeSession != null) {
                streamer.stop()
                nativeSession?.let { vm.repository.endNativeVoice(it) }
                nativeSession = null
            }
            speech.clear()
        }
    }

    val nativeMode = connected && vm.repository.supportsVoice && preferNative

    fun startListening() {
        if (!connected) {
            status = "Connect to a TV first."
            return
        }
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        status = null
        if (nativeMode) {
            scope.launch {
                status = "Opening the TV's voice input…"
                val session = vm.repository.startNativeVoice()
                if (session == null) {
                    usingFallback = true
                    status = "The TV did not open its voice channel, so speech is recognised on the phone."
                    speech.start(language)
                } else {
                    nativeSession = session
                    listening = streamer.start()
                    if (!listening) {
                        vm.repository.endNativeVoice(session)
                        nativeSession = null
                        status = "The microphone could not be started."
                    } else {
                        status = "Listening — tap again to send."
                    }
                }
            }
        } else {
            usingFallback = true
            speech.start(language)
        }
    }

    fun stopListening() {
        val session = nativeSession
        if (session != null) {
            streamer.stop()
            vm.repository.endNativeVoice(session)
            nativeSession = null
            status = "Audio sent to the TV."
        }
        speech.stop()
        listening = false
    }

    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Voice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        Text(
            if (nativeMode) "TV voice input" else "Speech recognition fallback",
            style = MaterialTheme.typography.bodySmall,
            color = if (nativeMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = connected) {
                    if (listening) stopListening() else startListening()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (connected) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                contentDescription = "Voice input",
                tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = when {
                !connected -> "Connect to a TV first."
                listening -> "Listening… tap to stop"
                else -> "Tap the microphone and speak"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        if (speechState is SpeechState.Partial) {
            Spacer(Modifier.height(8.dp))
            Text(
                (speechState as SpeechState.Partial).text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (speechState is SpeechState.Final) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Recognised: “${(speechState as SpeechState.Final).text}”",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        status?.let { message ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!permissionGranted) {
            Spacer(Modifier.height(16.dp))
            PillKey(
                icon = Icons.Rounded.Mic,
                label = "Allow microphone",
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { vm.setVoiceLanguage("id-ID") },
                        label = { Text("Bahasa Indonesia") },
                        enabled = !nativeMode,
                    )
                    AssistChip(
                        onClick = { vm.setVoiceLanguage("en-US") },
                        label = { Text("English") },
                        enabled = !nativeMode,
                    )
                }
                Text(
                    text = if (nativeMode) {
                        "Audio is streamed to the TV's own voice service over the local network, so the " +
                            "TV handles the request exactly like its physical remote. The phone does not " +
                            "recognise the speech itself in this mode."
                    } else {
                        "This TV did not offer its native voice channel. Speech is recognised on this " +
                            "phone and the transcript is sent to the TV as typed text. That is not " +
                            "Google Assistant — the TV only receives text."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Audio is sent only to your TV over the local network. Nothing is recorded or stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
