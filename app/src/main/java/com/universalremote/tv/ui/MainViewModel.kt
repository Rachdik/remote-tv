package com.universalremote.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universalremote.tv.UniversalRemoteApp
import com.universalremote.tv.discovery.DiscoveredTv
import com.universalremote.tv.discovery.MdnsDiscovery
import com.universalremote.tv.discovery.SsdpDiscovery
import com.universalremote.tv.discovery.TvProbe
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.storage.DeviceStore
import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data class Connecting(val name: String) : PairingUiState
    data class AwaitingCode(val name: String, val error: String? = null) : PairingUiState
    data class Success(val device: PairedDevice) : PairingUiState
    data class Failed(val message: String) : PairingUiState
}

/**
 * View model for the whole app: owns discovery, the pairing dialog state and the settings that
 * the UI binds to. TV commands go straight to [RemoteRepository], which keeps protocol details
 * out of the UI.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as UniversalRemoteApp
    val repository = app.repository
    val store: DeviceStore = repository.store

    private val discovery = MdnsDiscovery(application)
    private val ssdpCandidates = MutableStateFlow<List<DiscoveredTv>>(emptyList())

    val connection: StateFlow<ConnectionState> = repository.connection
    val pairedDevices: StateFlow<List<PairedDevice>> = repository.pairedDevices
    val volume = repository.volume
    val poweredOn = repository.poweredOn
    val currentApp = repository.currentApp
    val textField = repository.textField
    val messages = repository.messages

    val foundTvs: StateFlow<List<DiscoveredTv>> =
        combine(discovery.devices, ssdpCandidates) { mdns, ssdp ->
            (mdns + ssdp).distinctBy { it.host }.sortedBy { it.name.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    private var codeDeferred: CompletableDeferred<String?>? = null
    private var pairingJob: kotlinx.coroutines.Job? = null

    private val _themeMode = MutableStateFlow(store.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(store.dynamicColor)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _haptics = MutableStateFlow(store.hapticsEnabled)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _autoReconnect = MutableStateFlow(store.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(store.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _voiceLanguage = MutableStateFlow(store.voiceLanguage)
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    private val _preferNativeVoice = MutableStateFlow(store.preferNativeVoice)
    val preferNativeVoice: StateFlow<Boolean> = _preferNativeVoice.asStateFlow()

    val clientName: String =
        Build.MODEL?.takeIf { it.isNotBlank() }?.let { "Universal TV Remote ($it)" } ?: "Universal TV Remote"

    val startDestination: String
        get() = if (repository.lastDevice() != null) ROUTE_REMOTE else ROUTE_DEVICES

    // --- discovery --------------------------------------------------------------------------

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        ssdpCandidates.value = emptyList()
        discovery.clear()
        discovery.start()
        viewModelScope.launch {
            // SSDP runs alongside mDNS because some access points filter multicast replies.
            withContext(Dispatchers.IO) {
                val hosts = runCatching { SsdpDiscovery().search(3000) }.getOrDefault(emptyList())
                val found = hosts.mapNotNull { host ->
                    val probe = TvProbe.probe(host)
                    if (probe.reachable) {
                        DiscoveredTv(
                            name = "Android TV ($host)",
                            host = probe.host,
                            model = "",
                            port = probe.remotePort,
                        )
                    } else {
                        null
                    }
                }
                ssdpCandidates.value = found
            }
        }
        viewModelScope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        discovery.stop()
        _scanning.value = false
    }

    // --- pairing ----------------------------------------------------------------------------

    fun pairWith(tv: DiscoveredTv) {
        if (pairingJob?.isActive == true) return
        val deferred = CompletableDeferred<String?>()
        codeDeferred = deferred
        _pairing.value = PairingUiState.Connecting(tv.name)
        pairingJob = viewModelScope.launch {
            val result = repository.pair(
                discovered = tv,
                clientName = clientName,
                onCodeRequired = {
                    val current = _pairing.value
                    if (current is PairingUiState.Connecting) {
                        _pairing.value = PairingUiState.AwaitingCode(current.name)
                    }
                },
                codeProvider = { deferred.await() },
            )
            result.fold(
                onSuccess = { device ->
                    _pairing.value = PairingUiState.Success(device)
                    repository.connect(device)
                },
                onFailure = { error ->
                    if (deferred.isCancelled) {
                        _pairing.value = PairingUiState.Idle
                    } else {
                        _pairing.value = PairingUiState.Failed(
                            error.message ?: "Pairing failed. Check the code and try again.",
                        )
                    }
                },
            )
            codeDeferred = null
        }
    }

    fun startPairingByIp(ip: String) {
        val host = ip.trim()
        if (host.isBlank()) return
        viewModelScope.launch {
            _pairing.value = PairingUiState.Connecting(host)
            val probe = withContext(Dispatchers.IO) { TvProbe.probe(host) }
            if (!probe.reachable) {
                _pairing.value = PairingUiState.Failed(probe.detail)
                return@launch
            }
            pairWith(
                DiscoveredTv(
                    name = host,
                    host = probe.host,
                    model = "",
                    port = probe.remotePort,
                ),
            )
        }
    }

    fun submitPairingCode(code: String) {
        val deferred = codeDeferred ?: return
        if (code.length != 6) {
            val current = _pairing.value
            if (current is PairingUiState.AwaitingCode) {
                _pairing.value = current.copy(error = "Enter the 6 characters shown on the TV.")
            }
            return
        }
        deferred.complete(code)
    }

    fun cancelPairing() {
        codeDeferred?.cancel()
        codeDeferred = null
        pairingJob?.cancel()
        pairingJob = null
        _pairing.value = PairingUiState.Idle
    }

    fun dismissPairing() {
        if (_pairing.value is PairingUiState.Success || _pairing.value is PairingUiState.Failed) {
            _pairing.value = PairingUiState.Idle
        }
    }

    // --- devices ----------------------------------------------------------------------------

    fun connect(device: PairedDevice) = repository.connect(device)

    fun forget(device: PairedDevice) {
        repository.forget(device)
        repository.refreshDevices()
    }

    fun disconnect() = repository.disconnect()

    // --- settings ---------------------------------------------------------------------------

    fun setTheme(mode: String) {
        store.themeMode = mode
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        store.dynamicColor = enabled
        _dynamicColor.value = enabled
    }

    fun setHaptics(enabled: Boolean) {
        store.hapticsEnabled = enabled
        _haptics.value = enabled
    }

    fun setAutoReconnect(enabled: Boolean) {
        store.autoReconnect = enabled
        _autoReconnect.value = enabled
    }

    fun setKeepScreenOn(enabled: Boolean) {
        store.keepScreenOn = enabled
        _keepScreenOn.value = enabled
    }

    fun setVoiceLanguage(tag: String) {
        store.voiceLanguage = tag
        _voiceLanguage.value = tag
    }

    fun setPreferNativeVoice(enabled: Boolean) {
        store.preferNativeVoice = enabled
        _preferNativeVoice.value = enabled
    }

    // --- commands ---------------------------------------------------------------------------

    fun sendKey(key: TvKey) = repository.sendKey(key)

    fun sendText(text: String) = repository.sendText(text)

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }

    companion object {
        const val ROUTE_DEVICES = "devices"
        const val ROUTE_REMOTE = "remote"
        const val ROUTE_TOUCHPAD = "touchpad"
        const val ROUTE_KEYBOARD = "keyboard"
        const val ROUTE_VOICE = "voice"
        const val ROUTE_SETTINGS = "settings"
        private const val SCAN_DURATION_MS = 15000L
    }
}
