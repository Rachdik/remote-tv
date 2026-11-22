# Installation

## Install the APK

Two artifacts are produced:

| File | Use |
| --- | --- |
| `dist/UniversalTVRemote-1.0.2-release.apk` | Signed release build. Use this on a phone. |
| `dist/UniversalTVRemote-1.0.2-debug.apk` | Debug build with `-debug` suffix, installs side by side. |

Install over USB:

```bash
adb install -r dist/UniversalTVRemote-1.0.2-release.apk
```

Or copy the APK to the phone and open it (allow "install unknown apps" for the file manager).

Requirements: Android 7.1.2 (API 25) or newer. No Google Play Services dependency is required to
run the app; the speech fallback uses whichever `SpeechRecognizer` the phone provides.

---

## Build from source

### With Android Studio

1. **File → Open** → the project folder.
2. Let Gradle sync (it downloads AGP 8.5.2, Kotlin 1.9.24 and Compose 1.6).
3. Run ▸ on a connected device.

### From the command line

Prerequisites:

* JDK 17
* Android SDK with **platform 35** and **build-tools 35.0.0**

```bash
export ANDROID_HOME=/path/to/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # protocol tests
./gradlew assembleRelease      # release APK (signed if keystore.properties exists)
```

`local.properties` is git-ignored; every machine generates its own.

### Release signing

The repository contains a throwaway signing key so that `assembleRelease` produces an installable
APK. **Replace it before distributing the app.**

```bash
keytool -genkeypair -v -keystore universal-tv-remote.jks -alias universal-tv-remote \
  -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties
# edit keystore.properties: storeFile / storePassword / keyAlias / keyPassword
./gradlew assembleRelease
```

Minification is currently disabled for release (`isMinifyEnabled` in `app/build.gradle.kts`) so the
shipped APK contains no code transformation that has not been run on a device. Once you have
verified the app on your phone and TV, you can enable `isMinifyEnabled`/`isShrinkResources`; the
app uses no reflection, so the default rules are sufficient.

---

## Project configuration

| Setting | Value |
| --- | --- |
| `applicationId` | `com.universalremote.tv` |
| `minSdk` | 25 (Android 7.1.2) |
| `targetSdk` / `compileSdk` | 35 |
| Language | Kotlin 1.9.24, Java 17 bytecode |
| UI | Jetpack Compose (BOM 2024.06.00), Material 3 |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `RECORD_AUDIO` (runtime, voice only), `VIBRATE` |

No location permission is requested: discovery uses `NsdManager`, which does not need it.
`

---

## Uninstall / reset

Uninstalling removes the generated client identity and the paired device list. To re-pair without
uninstalling, use **Devices → Forget**, and on the TV clear the data of *Android TV Remote
Service*.

## Troubleshooting

### "The TV did not start the pairing dialog"

The TV accepted the secure connection but never opened its pairing screen. The pairing service
only advertises a dialog when it is idle, so a previous attempt that was interrupted can leave it
occupied.

1. Reboot the TV (power cycle, not standby).
2. On the TV: **Settings → Apps → Show system apps → Android TV Remote Service → Storage → Clear data**.
3. In the phone app, forget the TV, then pair again and keep the app in the foreground until it
   finishes.

Since 1.0.2 the message ends with where the handshake stopped and how the TLS session looked, for
example:

```
The TV did not start the pairing dialog. It accepted the connection but never answered the
pairing request, so no code appeared on the TV. Connection: TLSv1.2 TLS_AES_128_GCM_SHA256,
client certificate presented, TV certificate 8f3c1a20.
```

* `client certificate presented` — the TV received this phone's certificate, so the problem is on
  the TV side (stale pairing record, see step 2).
* `client certificate NOT presented` — the TV never asked for it; update to a build from this
  repository, this was fixed by always answering the TV's certificate request.
* `Nothing answered on port 6466` — the TV's remote service is not listening; check the IP and that
  the TV is fully awake.

If the TV never acknowledges the pairing request at all, the app now retries once with a freshly
generated client certificate, because a TV can keep a broken record for a certificate it has
already seen. This retry is skipped when you already have other TVs paired, since the same
certificate is what those TVs trust.

### "The TV did not answer the configuration request"

The control channel was established (TLS completed) but the TV ignored the handshake. This almost
always means the TV no longer has this phone's certificate registered, which happens after the
Remote Service data is cleared, a factory reset, or a failed pairing that the app could not detect.

1. On the TV, clear the data of **Android TV Remote Service** as described above.
2. In the app, forget the TV and pair it again.
3. If it still ignores the handshake, make sure the phone and the TV are on the same subnet and that
   the router does not have "AP isolation" / "client isolation" enabled.

Version 1.0.1 retries the handshake on both service ports (6467 and 6466) and stops the automatic
reconnect after four attempts instead of showing an endless "Connecting…" state.

Version 1.0.2 always presents the client certificate on the pairing connection (TLS clients can
otherwise skip it when the TV asks without listing acceptable issuers), logs every pairing frame
under the `PairingClient` tag, and reports the exact step that stalled.
