# Universal TV Remote

A working Android remote control for **Android TV** and **Google TV** over the local Wi-Fi
network. It speaks the same protocol as the official Google TV remote app: Android TV Remote
Service v2 — mutually authenticated TLS with protobuf messages, pairing with the six-character
code shown on the TV. No ADB, no root, no companion app on the TV.

Kotlin · Jetpack Compose · MVVM · Coroutines/StateFlow · minSdk 25 (Android 7.1.2) · targetSdk 35

---

## Download

The built APKs are committed to this repository, so there is nothing to compile to try the app.
Android 7.1.2 (API 25) or newer is required.

| Build | Download |
| --- | --- |
| **Release 1.0.5** (signed, use this) | [UniversalTVRemote-1.0.5-release.apk](https://github.com/Rachdik/remote-tv/raw/main/dist/UniversalTVRemote-1.0.5-release.apk) |
| Debug 1.0.5 (installs side by side) | [UniversalTVRemote-1.0.5-debug.apk](https://github.com/Rachdik/remote-tv/raw/main/dist/UniversalTVRemote-1.0.5-debug.apk) |

On the phone, open the link and allow "install unknown apps" for the browser. Over USB:
`adb install -r UniversalTVRemote-1.0.5-release.apk`. See [docs/INSTALL.md](docs/INSTALL.md) for
pairing and troubleshooting.

---

## What it does

| Area | Status |
| --- | --- |
| TV discovery (mDNS `_androidtvremote2._tcp` + SSDP fallback + manual IP) | implemented |
| Pairing with the on-screen code, persistent pairing store | implemented |
| Reconnect to a saved TV, multiple TVs, forget a TV | implemented |
| D-pad, OK, Back, Home, Menu | implemented |
| Power, input, TV settings, recent apps, channel up/down | implemented |
| Play, pause, play/pause, stop, rewind, fast-forward, previous, next | implemented |
| Volume up/down/mute | implemented |
| Virtual keyboard (text, backspace, enter, space, clipboard paste, clear, Unicode) | implemented |
| Touchpad (swipe → D-pad, tap → OK, two-finger scroll, haptics) | implemented |
| Voice: TV-native voice channel (streams PCM to the TV) | implemented |
| Voice: on-phone speech recognition fallback (transcript typed into the TV) | implemented |
| Dark/light theme, dynamic color, settings | implemented |

Honest caveats are collected in [Limitations](#limitations) and [docs/TESTING.md](docs/TESTING.md).

---

## Requirements

* Android phone or tablet, **Android 7.1.2 (API 25) or newer**. Built and tested as far as API 35.
* TV and phone on the **same Wi-Fi network** (no guest-network / AP isolation between them).
* Android TV or Google TV with the system app **Android TV Remote Service** (present on
  Google TV/Android TV 8+; some vendor boxes omit it).

## Build

```bash
# JDK 17 and an Android SDK with platform 35 + build-tools 35 are required
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed when keystore.properties exists, see below
```

Android Studio: **File → Open** the project folder, let it sync, press Run.

Release signing uses `keystore.properties` (copy `keystore.properties.example`). The repository
ships a throwaway key so the release APK in `dist/` is installable; generate your own key before
publishing anywhere.

## Install and first run

1. Install `UniversalTVRemote-1.0.5-release.apk` (or the debug APK).
2. Open the app. It scans the network automatically — TVs appear under *Found on network*.
   If multicast is filtered on your network, use **IP address** and type the TV's IP
   (TV: Settings → Network & Internet → your Wi-Fi → IP address).
3. Tap **Pair**. The TV shows a six-character code.
4. Type the code. The app verifies it locally against the TV's certificate before sending, so a
   typo is reported immediately instead of failing on the TV.
5. The remote opens and the TV's own name/model is shown in the header.

Pairing is one-time per TV. Later launches reconnect to the last TV automatically.

## Project layout

```
app/src/main/java/com/universalremote/tv/
├── protocol/    protobuf writer/reader, framing, remote + pairing messages, key codes
├── crypto/      self-signed client identity, TLS context, pairing secret
├── pairing/     pairing handshake state machine
├── connection/  remote session: TLS channel, configure handshake, key/text/voice send
├── discovery/   mDNS (NsdManager), SSDP, port probing
├── storage/     encrypted identity + paired device store (Android keystore AES-GCM)
├── remote/      RemoteRepository: single source of truth for the connection and commands
├── voice/       PCM capture for the TV channel, SpeechRecognizer fallback
└── ui/          Compose screens (remote, touchpad, keyboard, voice, settings, devices)
```

The protocol layer knows nothing about Android UI, and the UI never touches a socket.

## Protocol

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the full description: service discovery, the Polo
pairing handshake and secret derivation, the control channel handshake, key injection, IME text
edits, voice streaming, keep-alive and error handling.

## Pairing, step by step

See [docs/PAIRING.md](docs/PAIRING.md).

## Testing

See [docs/TESTING.md](docs/TESTING.md) — what was actually executed, and what still needs a
physical TV.

## Limitations

* **Power on from deep standby** is not guaranteed. The protocol can send `KEYCODE_POWER` and a
  TV that is fully off (not just asleep) cannot receive it — waking from cold requires
  Wake-on-LAN or an IR blaster, neither of which is part of Android TV Remote Service.
* Buttons the foreground app does not implement (for example *Stop* or *Channel up* on a
  streaming app) are accepted by the transport and ignored by the TV. The app shows a message
  only when the TV rejects key input entirely.
* **Voice** uses the TV's own voice channel when the TV advertises it. The fallback recognises
  speech on the phone and types the transcript — it is **not** Google Assistant, and the TV gets
  text, not an audio query.
* Text input requires a focused text field on the TV (that is how the protocol works). The app
  shows when the TV reports a focused field, and the *Open TV search* button opens one.
* The TV's certificate is not verified against a CA — the protocol has none. Trust comes from the
  pairing secret; see docs/PROTOCOL.md § Security notes.
* Devices with a vendor-modified remote service, or with *Android TV Remote Service* disabled,
  cannot pair. The app reports the failure instead of pretending to work.

## Privacy

Discovery and control stay on the local network. The pairing identity is encrypted at rest with a
key that never leaves the Android keystore, and backups exclude it. Audio is streamed to the TV
and is never written to disk or sent to any server.
