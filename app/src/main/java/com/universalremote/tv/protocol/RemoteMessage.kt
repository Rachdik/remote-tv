package com.universalremote.tv.protocol

/**
 * Key codes from the `RemoteKeyCode` enum of `remotemessage.proto`, which mirrors
 * `android.view.KeyEvent`. Only the codes the remote UI exposes are listed.
 */
enum class TvKey(val code: Int) {
    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),
    DPAD_CENTER(23),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    POWER(26),
    BACK(4),
    HOME(3),
    MENU(82),
    PLAY_PAUSE(85),
    STOP(86),
    MEDIA_NEXT(87),
    MEDIA_PREVIOUS(88),
    MEDIA_REWIND(89),
    MEDIA_FAST_FORWARD(90),
    MUTE(91),
    MEDIA_PLAY(126),
    MEDIA_PAUSE(127),
    VOLUME_MUTE(164),
    INFO(165),
    CHANNEL_UP(166),
    CHANNEL_DOWN(167),
    TV(170),
    GUIDE(172),
    SETTINGS(176),
    TV_POWER(177),
    TV_INPUT(178),
    APP_SWITCH(187),
    SEARCH(84),
    ASSIST(219),
    WAKEUP(224),
    SLEEP(223),
    VOICE_ASSIST(231),
    DEL(67),
    FORWARD_DEL(112),
    ENTER(66),
    SPACE(62),
    TAB(61),
}

/** `RemoteDirection` enum. */
enum class KeyDirection(val code: Int) {
    START_LONG(1),
    END_LONG(2),
    SHORT(3),
}

/** Feature bitmask advertised in `RemoteConfigure.code1`. */
object Feature {
    const val PING = 1 shl 0
    const val KEY = 1 shl 1
    const val IME = 1 shl 2
    const val VOICE = 1 shl 3
    const val UNKNOWN_1 = 1 shl 4
    const val POWER = 1 shl 5
    const val VOLUME = 1 shl 6
    const val APP_LINK = 1 shl 9

    /** Everything this client knows how to drive. The TV intersects it with its own mask. */
    const val REQUESTED = PING or KEY or IME or VOICE or POWER or VOLUME or APP_LINK
}

/** Field numbers of the outer `RemoteMessage`. */
object RemoteField {
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val ERROR = 3
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    const val KEY_INJECT = 10
    const val IME_KEY_INJECT = 20
    const val IME_BATCH_EDIT = 21
    const val IME_SHOW_REQUEST = 22
    const val VOICE_BEGIN = 30
    const val VOICE_PAYLOAD = 31
    const val VOICE_END = 32
    const val START = 40
    const val SET_VOLUME_LEVEL = 50
    const val ADJUST_VOLUME_LEVEL = 51
    const val SET_PREFERRED_AUDIO_DEVICE = 60
    const val RESET_PREFERRED_AUDIO_DEVICE = 61
    const val APP_LINK_LAUNCH_REQUEST = 90
}

/** Device information exchanged in `RemoteConfigure`. */
data class TvDeviceInfo(
    val model: String,
    val vendor: String,
    val appVersion: String,
) {
    val displayName: String
        get() = listOf(vendor, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android TV" }
}

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

/** State of the text field the TV reports through the IME messages. */
data class TextFieldInfo(val fieldCounter: Int, val value: String, val focused: Boolean)

/**
 * Builders and parser for the `RemoteMessage` envelope used on the remote channel (TLS, port
 * 6467 by default).
 */
object RemoteMessages {

    private const val CLIENT_PACKAGE = "com.universalremote.tv"
    private const val CLIENT_VERSION = "1.0.2"

    /** `RemoteConfigure` with this client's feature mask and device information. */
    fun configure(code1: Int = Feature.REQUESTED): ByteArray =
        ProtoWriter().message(RemoteField.CONFIGURE) {
            int(1, code1, writeDefault = true)
            message(2) {
                string(1, android.os.Build.MODEL ?: "Android")
                string(2, android.os.Build.MANUFACTURER ?: "Android")
                int(3, 1)
                string(4, "1")
                string(5, CLIENT_PACKAGE)
                string(6, CLIENT_VERSION)
            }
        }.toByteArray()

    /** `RemoteSetActive`. Sent after configuring so the TV treats this client as the active remote. */
    fun setActive(active: Int = Feature.REQUESTED): ByteArray =
        ProtoWriter().message(RemoteField.SET_ACTIVE) {
            int(1, active, writeDefault = true)
        }.toByteArray()

    fun keyInject(key: TvKey, direction: KeyDirection = KeyDirection.SHORT): ByteArray =
        ProtoWriter().message(RemoteField.KEY_INJECT) {
            int(1, key.code, writeDefault = true)
            int(2, direction.code, writeDefault = true)
        }.toByteArray()

    fun pingResponse(value: Int): ByteArray =
        ProtoWriter().message(RemoteField.PING_RESPONSE) {
            int(1, value, writeDefault = true)
        }.toByteArray()

    /**
     * `RemoteImeBatchEdit` inserting [text] at the cursor of the focused field.
     *
     * [imeCounter] and [fieldCounter] are echo values taken from the TV's IME messages; the TV
     * uses them to line up edits with the field it is currently showing.
     */
    fun imeBatchEdit(text: String, imeCounter: Int, fieldCounter: Int): ByteArray =
        ProtoWriter().message(RemoteField.IME_BATCH_EDIT) {
            int(1, imeCounter, writeDefault = true)
            int(2, fieldCounter, writeDefault = true)
            message(3) {
                int(1, 1, writeDefault = true)
                message(2) {
                    int(1, text.length, writeDefault = true)
                    int(2, text.length, writeDefault = true)
                    string(3, text)
                }
            }
        }.toByteArray()

    fun voiceBegin(sessionId: Int): ByteArray =
        ProtoWriter().message(RemoteField.VOICE_BEGIN) {
            int(1, sessionId, writeDefault = true)
        }.toByteArray()

    fun voicePayload(sessionId: Int, samples: ByteArray): ByteArray =
        ProtoWriter().message(RemoteField.VOICE_PAYLOAD) {
            int(1, sessionId, writeDefault = true)
            bytes(2, samples)
        }.toByteArray()

    fun voiceEnd(sessionId: Int): ByteArray =
        ProtoWriter().message(RemoteField.VOICE_END) {
            int(1, sessionId, writeDefault = true)
        }.toByteArray()

    fun launchApp(appLink: String): ByteArray =
        ProtoWriter().message(RemoteField.APP_LINK_LAUNCH_REQUEST) {
            string(1, appLink)
        }.toByteArray()

    /** Everything the TV can say back on this channel, decoded. */
    sealed interface Event {
        data class ConfigureReceived(
            val features: Int,
            val deviceInfo: TvDeviceInfo,
        ) : Event

        data class Ping(val value: Int) : Event
        data class SetActive(val active: Int) : Event
        data class Started(val started: Boolean) : Event
        data class Volume(val info: VolumeInfo) : Event
        data class ImeKeyInject(
            val currentApp: String,
            val appCounter: Int,
            val fieldCounter: Int,
        ) : Event

        data class ImeShowRequest(val fieldCounter: Int, val value: String) : Event
        data class ImeBatchEdit(val imeCounter: Int, val fieldCounter: Int) : Event
        data class VoiceBegin(val sessionId: Int) : Event
        data class TvError(val message: String) : Event
    }

    fun parse(payload: ByteArray): List<Event> {
        val events = mutableListOf<Event>()
        val reader = ProtoReader(payload)
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                RemoteField.CONFIGURE -> {
                    val sub = reader.readMessage()
                    var features = 0
                    var deviceInfo = TvDeviceInfo("", "", "")
                    while (true) {
                        val f = sub.nextField() ?: break
                        when (f.number) {
                            1 -> features = sub.readInt()
                            2 -> {
                                val info = sub.readMessage()
                                var model = ""
                                var vendor = ""
                                var version = ""
                                while (true) {
                                    val i = info.nextField() ?: break
                                    when (i.number) {
                                        1 -> model = info.readString()
                                        2 -> vendor = info.readString()
                                        6 -> version = info.readString()
                                        else -> info.skip(i.wireType)
                                    }
                                }
                                deviceInfo = TvDeviceInfo(model, vendor, version)
                            }
                            else -> sub.skip(f.wireType)
                        }
                    }
                    events += Event.ConfigureReceived(features, deviceInfo)
                }

                RemoteField.PING_REQUEST -> {
                    val sub = reader.readMessage()
                    var value = 0
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) value = sub.readInt() else sub.skip(f.wireType)
                    }
                    events += Event.Ping(value)
                }

                RemoteField.SET_ACTIVE -> {
                    val sub = reader.readMessage()
                    var active = 0
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) active = sub.readInt() else sub.skip(f.wireType)
                    }
                    events += Event.SetActive(active)
                }

                RemoteField.START -> {
                    val sub = reader.readMessage()
                    var started = false
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) started = sub.readBool() else sub.skip(f.wireType)
                    }
                    events += Event.Started(started)
                }

                RemoteField.SET_VOLUME_LEVEL -> {
                    val sub = reader.readMessage()
                    var level = 0
                    var max = 0
                    var muted = false
                    while (true) {
                        val f = sub.nextField() ?: break
                        when (f.number) {
                            6 -> max = sub.readInt()
                            7 -> level = sub.readInt()
                            8 -> muted = sub.readBool()
                            else -> sub.skip(f.wireType)
                        }
                    }
                    events += Event.Volume(VolumeInfo(level, max, muted))
                }

                RemoteField.IME_KEY_INJECT -> {
                    val sub = reader.readMessage()
                    var app = ""
                    var appCounter = 0
                    var fieldCounter = 0
                    while (true) {
                        val f = sub.nextField() ?: break
                        when (f.number) {
                            1 -> {
                                val info = sub.readMessage()
                                while (true) {
                                    val i = info.nextField() ?: break
                                    when (i.number) {
                                        1 -> appCounter = info.readInt()
                                        12 -> app = info.readString()
                                        else -> info.skip(i.wireType)
                                    }
                                }
                            }
                            2 -> {
                                val status = sub.readMessage()
                                while (true) {
                                    val s = status.nextField() ?: break
                                    if (s.number == 1) fieldCounter = status.readInt() else status.skip(s.wireType)
                                }
                            }
                            else -> sub.skip(f.wireType)
                        }
                    }
                    events += Event.ImeKeyInject(app, appCounter, fieldCounter)
                }

                RemoteField.IME_SHOW_REQUEST -> {
                    val sub = reader.readMessage()
                    var fieldCounter = 0
                    var value = ""
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 2 && f.wireType == ProtoWriter.WIRE_LENGTH_DELIMITED) {
                            val status = sub.readMessage()
                            while (true) {
                                val s = status.nextField() ?: break
                                when (s.number) {
                                    1 -> fieldCounter = status.readInt()
                                    2 -> value = status.readString()
                                    else -> status.skip(s.wireType)
                                }
                            }
                        } else {
                            sub.skip(f.wireType)
                        }
                    }
                    events += Event.ImeShowRequest(fieldCounter, value)
                }

                RemoteField.IME_BATCH_EDIT -> {
                    val sub = reader.readMessage()
                    var imeCounter = 0
                    var fieldCounter = 0
                    while (true) {
                        val f = sub.nextField() ?: break
                        when (f.number) {
                            1 -> imeCounter = sub.readInt()
                            2 -> fieldCounter = sub.readInt()
                            else -> sub.skip(f.wireType)
                        }
                    }
                    events += Event.ImeBatchEdit(imeCounter, fieldCounter)
                }

                RemoteField.VOICE_BEGIN -> {
                    val sub = reader.readMessage()
                    var sessionId = 0
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) sessionId = sub.readInt() else sub.skip(f.wireType)
                    }
                    events += Event.VoiceBegin(sessionId)
                }

                RemoteField.ERROR -> {
                    val sub = reader.readMessage()
                    var value = false
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) value = sub.readBool() else sub.skip(f.wireType)
                    }
                    events += Event.TvError("TV reported an error (value=$value)")
                }

                else -> reader.skip(field.wireType)
            }
        }
        return events
    }
}
