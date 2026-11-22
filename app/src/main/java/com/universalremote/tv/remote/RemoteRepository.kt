package com.universalremote.tv.remote

import android.content.Context
import android.util.Log
import com.universalremote.tv.connection.RemoteSession
import com.universalremote.tv.discovery.DiscoveredTv
import com.universalremote.tv.discovery.TvProbe
import com.universalremote.tv.pairing.InvalidPairingCodeException
import com.universalremote.tv.pairing.PairingClient
import com.universalremote.tv.protocol.Feature
import com.universalremote.tv.protocol.RemoteMessages
import com.universalremote.tv.protocol.TextFieldInfo
import com.universalremote.tv.protocol.TvDeviceInfo
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.protocol.VolumeInfo
import com.universalremote.tv.storage.DeviceStore
import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: PairedDevice) : ConnectionState
    data class Connected(val device: PairedDevice, val info: TvDeviceInfo) : ConnectionState
    data class Failed(val device: PairedDevice?, val reason: String) : ConnectionState
}

/**
 * Single source of truth for the TV connection and every command sent to it.
 *
 * The UI observes [connection] and the small state flows below and calls the command methods;
 * nothing in the UI layer knows about sockets or protobuf.
 */
class RemoteRepository(context: Context) {

    private val appContext = context.applicationContext
    val store = DeviceStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    private var session: RemoteSession? = null
    private var reconnectJob: Job? = null
    private var userDisconnected = false

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    private val _poweredOn = MutableStateFlow<Boolean?>(null)
    val poweredOn: StateFlow<Boolean?> = _poweredOn.asStateFlow()

    private val _currentApp = MutableStateFlow("")
    val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldInfo(0, "", false))
    val textField: StateFlow<TextFieldInfo> = _textField.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _pairedDevices = MutableStateFlow(store.devices())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private var imeCounter = 0
    private var fieldCounter = 0

    @Volatile
    private var pendingVoiceBegin: CompletableDeferred<Int>? = null

    val isConnected: Boolean get() = _connection.value is ConnectionState.Connected

    val supportsKeys: Boolean get() = session?.negotiatedFeatures?.and(Feature.KEY) == Feature.KEY

    val supportsText: Boolean get() = session?.negotiatedFeatures?.and(Feature.IME) == Feature.IME

    val supportsVoice: Boolean get() = session?.negotiatedFeatures?.and(Feature.VOICE) == Feature.VOICE

    // --- device management ------------------------------------------------------------------

    fun refreshDevices() {
        _pairedDevices.value = store.devices()
    }

    fun lastDevice(): PairedDevice? {
        val id = store.lastDeviceId() ?: return null
        return store.devices().firstOrNull { it.id == id }
    }

    /**
     * Pairs with a TV discovered on the network or entered by hand.
     *
     * @param onCodeRequired invoked once the TV is showing the six-character code.
     * @param codeProvider suspends until the user supplies the code; returns null to abort.
     */
    suspend fun pair(
        discovered: DiscoveredTv,
        clientName: String,
        onCodeRequired: suspend () -> Unit,
        codeProvider: suspend () -> String?,
    ): Result<PairedDevice> {
        val probe = TvProbe.probe(discovered.host, if (discovered.port > 0) discovered.port else PairedDevice.DEFAULT_REMOTE_PORT)
        if (!probe.reachable) {
            return Result.failure(IllegalStateException(probe.detail))
        }
        return pairWithPorts(
            host = probe.host,
            remotePort = probe.remotePort,
            pairingPort = probe.pairingPort,
            name = discovered.name,
            model = discovered.model,
            clientName = clientName,
            onCodeRequired = onCodeRequired,
            codeProvider = codeProvider,
        )
    }

    private suspend fun pairWithPorts(
        host: String,
        remotePort: Int,
        pairingPort: Int,
        name: String,
        model: String,
        clientName: String,
        onCodeRequired: suspend () -> Unit,
        codeProvider: suspend () -> String?,
    ): Result<PairedDevice> {
        val identity = store.identityOrCreate(clientName)
        val client = PairingClient(host, pairingPort, identity, clientName)
        return try {
            val serverName = client.start()
            onCodeRequired()
            val code = codeProvider() ?: return Result.failure(
                IllegalStateException("Pairing was cancelled."),
            )
            client.submitCode(code.trim())
            val device = PairedDevice(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { serverName },
                host = host,
                model = model,
                remotePort = remotePort,
                pairingPort = pairingPort,
            )
            store.saveDevice(device)
            store.setLastDeviceId(device.id)
            refreshDevices()
            Result.success(device)
        } catch (e: InvalidPairingCodeException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Pairing failed", e)
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    fun forget(device: PairedDevice) {
        if ((_connection.value as? ConnectionState.Connected)?.device?.id == device.id) {
            disconnect()
        }
        store.removeDevice(device.id)
        if (store.lastDeviceId() == device.id) store.setLastDeviceId(null)
        refreshDevices()
    }

    // --- connection -------------------------------------------------------------------------

    fun connect(device: PairedDevice, remember: Boolean = true) {
        userDisconnected = false
        reconnectJob?.cancel()
        scope.launch {
            connectMutex.withLock {
                disconnectInternal(notify = false)
                _connection.value = ConnectionState.Connecting(device)
                val identity = store.loadIdentity()
                if (identity == null) {
                    _connection.value = ConnectionState.Failed(device, "This TV has to be paired again.")
                    return@withLock
                }
                val newSession = RemoteSession(
                    device = device,
                    identity = identity,
                    onEvent = ::handleEvent,
                    onClosed = { reason -> handleClosed(device, reason) },
                )
                try {
                    val info = newSession.connect()
                    session = newSession
                    if (remember) {
                        store.setLastDeviceId(device.id)
                        // Keep the stored name in sync with what the TV reports.
                        if (info.model.isNotBlank() && info.model != device.model) {
                            store.saveDevice(device.copy(model = info.model))
                            refreshDevices()
                        }
                    }
                    _connection.value = ConnectionState.Connected(device, info)
                } catch (e: Exception) {
                    newSession.close()
                    session = null
                    _connection.value = ConnectionState.Failed(device, e.message ?: "Could not connect.")
                    scheduleReconnect(device)
                }
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        scope.launch {
            connectMutex.withLock {
                disconnectInternal(notify = true)
            }
        }
    }

    private suspend fun disconnectInternal(notify: Boolean) {
        pendingVoiceBegin?.cancel()
        pendingVoiceBegin = null
        session?.close(if (notify) "Disconnected" else null, notify = false)
        session = null
        _volume.value = null
        _poweredOn.value = null
        _currentApp.value = ""
        _textField.value = TextFieldInfo(0, "", false)
        _connection.value = ConnectionState.Disconnected
    }

    private fun handleClosed(device: PairedDevice, reason: String?) {
        if (session == null) return
        session = null
        _connection.value = ConnectionState.Failed(device, reason ?: "The connection was lost.")
        scheduleReconnect(device)
    }

    private fun scheduleReconnect(device: PairedDevice) {
        if (userDisconnected || !store.autoReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            // Backs off to 15 s and keeps trying while the app is open.
            while (!userDisconnected && store.autoReconnect) {
                attempt++
                val wait = when {
                    attempt <= 3 -> 1000L * attempt
                    attempt <= 6 -> 5000L
                    else -> 15000L
                }
                delay(wait)
                if (userDisconnected || !store.autoReconnect) return@launch
                if (isConnected) return@launch
                _connection.value = ConnectionState.Connecting(device)
                val identity = store.loadIdentity() ?: return@launch
                val candidate = RemoteSession(
                    device = device,
                    identity = identity,
                    onEvent = ::handleEvent,
                    onClosed = { reason -> handleClosed(device, reason) },
                )
                try {
                    val info = candidate.connect()
                    session = candidate
                    _connection.value = ConnectionState.Connected(device, info)
                    return@launch
                } catch (e: Exception) {
                    candidate.close()
                    _connection.value = ConnectionState.Failed(device, e.message ?: "Could not reconnect.")
                }
            }
        }
    }

    // --- commands ---------------------------------------------------------------------------

    fun sendKey(key: TvKey) {
        val active = session
        if (active == null || !isConnected) {
            _messages.tryEmit("Connect to a TV first.")
            return
        }
        if (!supportsKeys) {
            _messages.tryEmit(
                "This TV rejected key input. Clear the data of the Android TV Remote Service app on the TV, then pair again.",
            )
            return
        }
        active.sendKey(key)
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val active = session
        if (active == null || !isConnected) {
            _messages.tryEmit("Connect to a TV first.")
            return
        }
        if (!supportsText) {
            _messages.tryEmit("This TV does not accept text input over the remote protocol.")
            return
        }
        active.sendText(text, imeCounter, fieldCounter)
    }

    fun launchApp(appLink: String) {
        session?.launchApp(appLink)
    }

    /** Opens the TV's own search / assistant sheet, which is what puts a text field on screen. */
    fun openTvSearch() = sendKey(TvKey.SEARCH)

    /**
     * Starts a native voice session on the TV.
     *
     * Sends `KEYCODE_SEARCH` and waits for the TV to open a voice channel. When the TV does not
     * answer within [timeoutMs], returns null so the caller can fall back to speech recognition.
     */
    suspend fun startNativeVoice(timeoutMs: Long = 2500): Int? {
        val active = session ?: return null
        if (!isConnected || !supportsVoice) return null
        val deferred = CompletableDeferred<Int>()
        pendingVoiceBegin = deferred
        active.sendKey(TvKey.SEARCH)
        val sessionId = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingVoiceBegin = null
        if (sessionId == null) return null
        active.sendVoiceBegin(sessionId)
        return sessionId
    }

    fun sendVoiceAudio(sessionId: Int, samples: ByteArray) {
        session?.sendVoicePayload(sessionId, samples)
    }

    fun endNativeVoice(sessionId: Int) {
        session?.sendVoiceEnd(sessionId)
    }

    // --- inbound ----------------------------------------------------------------------------

    private fun handleEvent(event: RemoteMessages.Event) {
        when (event) {
            is RemoteMessages.Event.Volume -> _volume.value = event.info
            is RemoteMessages.Event.Started -> _poweredOn.value = event.started
            is RemoteMessages.Event.ImeKeyInject -> {
                imeCounter = event.appCounter
                fieldCounter = event.fieldCounter
                _currentApp.value = event.currentApp
                _textField.value = TextFieldInfo(event.fieldCounter, _textField.value.value, true)
            }
            is RemoteMessages.Event.ImeShowRequest -> {
                fieldCounter = event.fieldCounter
                _textField.value = TextFieldInfo(event.fieldCounter, event.value, true)
            }
            is RemoteMessages.Event.ImeBatchEdit -> {
                if (imeCounter == 0) imeCounter = event.imeCounter
                if (fieldCounter == 0) fieldCounter = event.fieldCounter
            }
            is RemoteMessages.Event.VoiceBegin -> {
                pendingVoiceBegin?.takeIf { !it.isCompleted }?.complete(event.sessionId)
            }
            is RemoteMessages.Event.TvError -> _messages.tryEmit(event.message)
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "RemoteRepository"
    }
}
