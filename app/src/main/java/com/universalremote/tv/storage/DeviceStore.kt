package com.universalremote.tv.storage

import android.content.Context
import android.util.Base64
import android.util.Log
import com.universalremote.tv.crypto.ClientIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** A TV this app has paired with. */
data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val model: String,
    val remotePort: Int = DEFAULT_REMOTE_PORT,
    val pairingPort: Int = DEFAULT_PAIRING_PORT,
    val pairedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_REMOTE_PORT = 6467
        const val DEFAULT_PAIRING_PORT = 6466
    }
}

/**
 * Persists the client identity and the paired devices.
 *
 * The identity's PKCS#12 blob is encrypted with an AES-256-GCM key held in the Android
 * keystore, so a copy of the app's private storage is not enough to impersonate this client
 * against the TV. Pairing state is excluded from backup (see data_extraction_rules.xml).
 */
class DeviceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadIdentity(): ClientIdentity? {
        val encoded = prefs.getString(KEY_IDENTITY, null) ?: return null
        return try {
            val blob = decrypt(Base64.decode(encoded, Base64.NO_WRAP))
            ClientIdentity.fromPkcs12(blob)
        } catch (e: Exception) {
            Log.w(TAG, "Stored identity could not be read; a new one will be generated", e)
            null
        }
    }

    fun saveIdentity(identity: ClientIdentity) {
        val blob = identity.toPkcs12(ClientIdentity.defaultPassword())
        prefs.edit().putString(KEY_IDENTITY, Base64.encodeToString(encrypt(blob), Base64.NO_WRAP)).apply()
    }

    /** Returns the existing identity or creates and stores a new one. */
    fun identityOrCreate(deviceName: String): ClientIdentity {
        loadIdentity()?.let { return it }
        val identity = ClientIdentity.generate(deviceName)
        saveIdentity(identity)
        return identity
    }

    fun devices(): List<PairedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                PairedDevice(
                    id = json.getString("id"),
                    name = json.optString("name", "Android TV"),
                    host = json.getString("host"),
                    model = json.optString("model", ""),
                    remotePort = json.optInt("remotePort", PairedDevice.DEFAULT_REMOTE_PORT),
                    pairingPort = json.optInt("pairingPort", PairedDevice.DEFAULT_PAIRING_PORT),
                    pairedAt = json.optLong("pairedAt", 0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device list is corrupt, starting empty", e)
            emptyList()
        }
    }

    fun saveDevice(device: PairedDevice) {
        val updated = devices().filterNot { it.id == device.id } + device
        saveDevices(updated)
    }

    fun removeDevice(id: String) {
        saveDevices(devices().filterNot { it.id == id })
    }

    fun lastDeviceId(): String? = prefs.getString(KEY_LAST_DEVICE, null)

    fun setLastDeviceId(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_LAST_DEVICE) else putString(KEY_LAST_DEVICE, id) }.apply()
    }

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, "id-ID") ?: "id-ID"
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()

    var preferNativeVoice: Boolean
        get() = prefs.getBoolean(KEY_NATIVE_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_NATIVE_VOICE, value).apply()

    private fun saveDevices(devices: List<PairedDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("id", device.id)
                    put("name", device.name)
                    put("host", device.host)
                    put("model", device.model)
                    put("remotePort", device.remotePort)
                    put("pairingPort", device.pairingPort)
                    put("pairedAt", device.pairedAt)
                },
            )
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    // --- encryption -------------------------------------------------------------------------

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ENCRYPTION_KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ENCRYPTION_KEY,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + cipherText
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val ivLength = payload[0].toInt()
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val cipherText = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    companion object {
        private const val TAG = "DeviceStore"
        private const val PREFS = "universal_tv_remote"
        private const val KEY_IDENTITY = "client_identity"
        private const val KEY_DEVICES = "paired_devices"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_KEEP_AWAKE = "keep_screen_on"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_NATIVE_VOICE = "prefer_native_voice"
        private const val KEY_ENCRYPTION_KEY = "identity_encryption_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }
}
