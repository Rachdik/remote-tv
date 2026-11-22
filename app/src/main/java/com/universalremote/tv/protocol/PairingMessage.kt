package com.universalremote.tv.protocol

/**
 * Pairing channel messages from `polo.proto` (the Google TV pairing protocol).
 *
 * proto2 semantics: `protocol_version` and `status` are required, so they are always written.
 */
object PairingMessage {

    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    private const val F_PROTOCOL_VERSION = 1
    private const val F_STATUS = 2
    private const val F_PAIRING_REQUEST = 10
    private const val F_PAIRING_REQUEST_ACK = 11
    private const val F_OPTIONS = 20
    private const val F_CONFIGURATION = 30
    private const val F_CONFIGURATION_ACK = 31
    private const val F_SECRET = 40
    private const val F_SECRET_ACK = 41

    private const val ROLE_INPUT = 1
    private const val ENCODING_HEXADECIMAL = 3
    private const val CODE_LENGTH = 6

    private fun envelope(body: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter()
            .int(F_PROTOCOL_VERSION, PROTOCOL_VERSION, writeDefault = true)
            .int(F_STATUS, STATUS_OK, writeDefault = true)
            .apply(body)
            .toByteArray()

    fun request(serviceName: String, clientName: String): ByteArray =
        envelope {
            message(F_PAIRING_REQUEST) {
                string(1, serviceName)
                string(2, clientName)
            }
        }

    /** Accepts a six-character hexadecimal code displayed on the TV, as input role. */
    fun options(): ByteArray =
        envelope {
            message(F_OPTIONS) {
                message(1) {
                    int(1, ENCODING_HEXADECIMAL, writeDefault = true)
                    int(2, CODE_LENGTH, writeDefault = true)
                }
                int(3, ROLE_INPUT, writeDefault = true)
            }
        }

    fun configuration(): ByteArray =
        envelope {
            message(F_CONFIGURATION) {
                message(1) {
                    int(1, ENCODING_HEXADECIMAL, writeDefault = true)
                    int(2, CODE_LENGTH, writeDefault = true)
                }
                int(2, ROLE_INPUT, writeDefault = true)
            }
        }

    fun secret(secret: ByteArray): ByteArray =
        envelope {
            message(F_SECRET) { bytes(1, secret) }
        }

    sealed interface Event {
        data class RequestAck(val serverName: String) : Event
        data object Options : Event
        data object ConfigurationAck : Event
        data object SecretAck : Event
        data class Failure(val status: Int) : Event
        data object Unknown : Event
    }

    fun parse(payload: ByteArray): Event {
        val reader = ProtoReader(payload)
        var status = STATUS_OK
        var event: Event? = null
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                F_STATUS -> status = reader.readInt()
                F_PAIRING_REQUEST_ACK -> {
                    val sub = reader.readMessage()
                    var name = ""
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) name = sub.readString() else sub.skip(f.wireType)
                    }
                    event = Event.RequestAck(name)
                }
                F_OPTIONS -> {
                    reader.skip(field.wireType)
                    event = Event.Options
                }
                F_CONFIGURATION_ACK -> {
                    reader.skip(field.wireType)
                    event = Event.ConfigurationAck
                }
                F_SECRET_ACK -> {
                    reader.skip(field.wireType)
                    event = Event.SecretAck
                }
                else -> reader.skip(field.wireType)
            }
        }
        if (status != STATUS_OK) return Event.Failure(status)
        return event ?: Event.Unknown
    }
}
