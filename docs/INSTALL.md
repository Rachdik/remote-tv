# Installation

## Install the APK

Two artifacts are produced:

| File | Use |
| --- | --- |
| `dist/UniversalTVRemote-1.0.0-release.apk` | Signed release build. Use this on a phone. |
| `dist/UniversalTVRemote-1.0.0-debug.apk` | Debug build with `-debug` suffix, installs side by side. |

Install over USB:

```bash
adb install -r dist/UniversalTVRemote-1.0.0-release.apk
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
