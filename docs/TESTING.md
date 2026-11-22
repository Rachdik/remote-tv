# Testing report

This file records what was **actually executed** and what was not. No hardware TV was available in
the build environment, so the protocol is verified against a local server that implements the TV
side of the protocol, and everything that needs a real TV or phone runtime is listed as untested.

## Environment

| Item | Value |
| --- | --- |
| JDK | Zulu OpenJDK 17.0.10 |
| Gradle | 8.9 (wrapper pinned to 8.9) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| SDK | platform 35, build-tools 35.0.0 |
| Host | Linux x86-64 container, 2 vCPU, 3 GB RAM, no KVM, no adb device |
| Date | 2026-11-22 |

## Commands executed and their results

```
gradle assembleDebug            → BUILD SUCCESSFUL   app-debug.apk           16 MB
gradle testDebugUnitTest        → BUILD SUCCESSFUL   13 tests, 0 failures, 0 skipped
gradle assembleRelease          → BUILD SUCCESSFUL   app-release.apk        10 MB (signed, minification off)
apksigner verify                → Verifies (APK Signature Scheme v2)
aapt2 dump badging              → minSdkVersion 25, targetSdkVersion 35,
                                  launchable activity com.universalremote.tv.ui.MainActivity
```

Unit and integration tests, all green:

```
ProtocolCodecTest  (10)
  varint encodes and decodes the protobuf reference values
  key inject matches the schema encoding
  configure carries the requested feature mask
  parser reads configure ping volume and voice begin
  unknown fields are skipped instead of failing
  ime batch edit encodes counters and text
  framing survives split and coalesced messages
  pairing messages match the polo schema
  client identity produces a usable self-signed certificate
  pairing code check digits are verified against the certificate

FakeTvServerTest   (3)
  pairs with the on screen code and then controls the tv
  rejects a pairing code that does not match the tv certificate
  control channel negotiates features answers pings and sends commands
```

### What `FakeTvServerTest` actually exercises

`FakeTv` is a local TLS server that behaves like the TV: it requests the client certificate,
implements the Polo handshake, then the control channel.

* **Mutual TLS with the generated client identity** — the server reads the client's certificate
  from the TLS session and **recomputes the pairing secret from it**, then compares it with what
  the app sent. A wrong code, wrong key encoding or wrong hash input order fails the test.
* **Pairing handshake order** — request/ack, options, configuration/ack, secret/ack, including the
  six-character hexadecimal encoding negotiation.
* **Code verification** — a code whose check digits do not match the certificate is rejected
  locally, before any secret is transmitted.
* **Control handshake** — `RemoteConfigure` both ways, feature mask negotiation (623), and
  `RemoteSetActive`.
* **Keep-alive** — the server sends `remote_ping_request { val1 = 5 }` and asserts the app answers
  with `remote_ping_response { val1 = 5 }`.
* **Inbound state** — `remote_set_volume_level { max 100, level 42 }` and
  `remote_start { started = true }` surface as repository events.
* **Commands** — `KEYCODE_DPAD_UP (19)`, `KEYCODE_DPAD_CENTER (23)`, `KEYCODE_BACK (4)` arrive in
  order; `remote_ime_batch_edit` carries the full UTF-8 text and the echoed counters;
  `KEYCODE_SEARCH` provokes `remote_voice_begin { session_id = 7 }`, and the app then sends
  `remote_voice_begin`, `remote_voice_payload` (byte-exact) and `remote_voice_end` for that session.

## Not tested

The following need a real phone and/or a real Android TV and were **not** executed:

* Installing and launching the APK on a device, and every UI interaction (no emulator was
  available: the container has no KVM, so a system image would not boot in reasonable time).
* mDNS discovery on a real LAN, SSDP fallback, multicast lock behaviour.
* `AndroidKeyStore` AES-GCM encryption of the stored identity (JVM tests use plain PKCS#12).
* The self-signed certificate inside Android's TLS stack as a client certificate (verified only
  against the JDK TLS implementation).
* Voice capture through `AudioRecord` at 8 kHz on real hardware, and the TV's reaction to it.
* `SpeechRecognizer` availability and Indonesian recognition quality on a real phone.
* Real TVs' firmware quirks: which of 6466/6467 is pairing on a given model, sleep/wake behaviour,
  and whether a given TV advertises the `VOICE` feature.
* Haptics, dark/light rendering, layout on small and large screens.
* Release-build behaviour at runtime (the signed release APK was not executed).

## Checklist from the project brief

| # | Required test | Status |
| --- | --- | --- |
| 1 | Detect an Android TV over Wi-Fi | Not tested on hardware. Discovery code is `NsdManager` + SSDP + TCP probe; only the probe logic was compile-checked. |
| 2 | Pair with the TV | Protocol path tested end-to-end against `FakeTv`; not on a real TV. |
| 3 | Reconnect to a stored TV | Reconnect logic tested indirectly (session re-handshake); not against real firmware. |
| 4 | Send D-pad commands | Encoding and delivery tested (`KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT = 19/20/21/22`); not on a real TV. |
| 5 | Send OK and Back | Delivery tested (`DPAD_CENTER = 23`, `BACK = 4`); not on a real TV. |
| 6 | Send Home | Encoding tested; not on a real TV. |
| 7 | Volume | Volume events (`remote_set_volume_level`) tested; volume key delivery tested; not on a real TV. |
| 8 | Keyboard | `remote_ime_batch_edit` shape and delivery tested; not against a real TV field. |
| 9 | Voice input | Voice session handshake and payload framing tested; microphone and the TV's assistant not tested. |
| 10 | Disconnected connection | Reconnect/backoff logic is code-reviewed only; the socket-loss path was exercised only by closing the test server early in one run. |
| 11 | Minimum API 25 | Not executed. `minSdkVersion 25` is in the manifest; every newer API used is behind a `Build.VERSION` check or a compat library. |
| 12 | Multiple Android TV versions | Not tested — no devices were available. |

## Tested device list

| Device | Android version | Result |
| --- | --- | --- |
| *(none — no physical Android TV or Google TV was available)* | — | — |
| Local `FakeTv` harness (JVM) | protocol v2 | pass |

If you run this on real hardware, the fastest way to report a problem is to capture `adb logcat -s
RemoteSession PairingClient MdnsDiscovery` while reproducing it.
