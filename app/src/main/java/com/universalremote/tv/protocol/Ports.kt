package com.universalremote.tv.protocol

/**
 * The two fixed TCP services of Android TV Remote Service.
 *
 * The ports are not interchangeable: [CONTROL] is the long-lived remote channel and [PAIRING] is
 * the "Polo" pairing channel. Both speak mutually authenticated TLS, so connecting to the wrong
 * one still completes the handshake and only fails later, when the TV silently ignores the frames
 * it does not expect. That asymmetry is easy to mistake for a broken TV or certificate.
 */
object Ports {
    /** Remote control channel: key presses, IME, voice, pings. */
    const val CONTROL = 6466

    /** Pairing channel: the six-character code handshake. */
    const val PAIRING = 6467
}
