# Protocol reference

The app implements **Android TV Remote Service v2**, the protocol used by the official Google TV
mobile remote. It was implemented from the published schema and the behaviour of the reference
implementations (`tronikos/androidtvremote2`, `louis49/androidtv-remote`), then verified against a
local test server that behaves the same way (see [TESTING.md](TESTING.md)).

No ADB is used anywhere.

---

## 1. Discovery

* Service type: `_androidtvremote2._tcp.` (DNS-SD / mDNS), queried through Android's
  `NsdManager`.
* TXT records: `fn` = friendly name, `md` = model.
* The advertised port is the control port (6466).
* Fallback 1: SSDP `M-SEARCH` for `urn:dial-multiscreen-org:service:dial:1`, then a TCP probe of
  the candidate address.
* Fallback 2: manual IP. Both 6466 and 6467 are probed to confirm the service is listening; the
  roles of the two ports never change.

A multicast lock is held while discovery runs, which is required for mDNS replies to reach an
app on Android.

---

## 2. Transport

* TCP with TLS, directly on the service port (no STARTTLS).
* **Pairing port: 6467. Control port: 6466.** These are fixed. Sending a pairing request to 6466
  does not fail at TLS level — the control service completes the handshake and then ignores the
  frame — so the roles must never be swapped.
* The phone presents a **self-signed RSA-2048 client certificate** generated on first launch. The
  TV stores its fingerprint during pairing and only accepts that certificate afterwards.
* The TV's certificate is not CA-verified (the protocol has no PKI). See §7.
* Both channels frame messages as **protobuf with a varint length prefix**:

```
[ varint byte length ][ protobuf payload ]
```

A reader must tolerate a frame split across TCP segments, several frames in one segment, and the
varint itself being split — the app's `readFrame` handles all three.

---

## 3. Pairing ("Polo")

Messages are `polo.OuterMessage` (proto2). Every message carries `protocol_version = 2` and
`status = 200 (STATUS_OK)`.

| Step | Direction | Message |
| --- | --- | --- |
| 1 | phone → TV | `pairing_request { service_name = "atvremote", client_name }` |
| 2 | TV → phone | `pairing_request_ack { server_name }` |
| 3 | phone → TV | `options { input_encodings = [{ type = HEXADECIMAL(3), symbol_length = 6 }], preferred_role = INPUT(1) }` |
| 4 | TV → phone | `options { ... }` |
| 5 | phone → TV | `configuration { encoding = { type = 3, symbol_length = 6 }, client_role = INPUT }` |
| 6 | TV → phone | `configuration_ack` → the TV now displays the code |
| 7 | phone → TV | `secret { secret = SHA-256(...) }` |
| 8 | TV → phone | `secret_ack` → pairing complete |

### Secret derivation

```
sha256( client_modulus || client_exponent || server_modulus || server_exponent || code[2..6] )
```

* Each integer is encoded big-endian, minimal length, unsigned (no sign byte).
* The first byte of the digest must equal `code[0..2]` parsed as hex. The app checks this before
  sending, so a mistyped code is rejected locally.
* The remaining four code characters are only mixed into the digest hash, never sent in clear.

The client certificate's public key must be the one that was used for the hash, so the identity is
generated once and reused for every pairing.

---

## 4. Control channel

`remote.RemoteMessage` (proto3). Field numbers used:

| Field | # | Notes |
| --- | --- | --- |
| `remote_configure` | 1 | `{ code1 = feature mask, device_info }` |
| `remote_set_active` | 2 | `{ active = feature mask }` |
| `remote_error` | 3 | |
| `remote_ping_request` | 8 | TV → phone every ~5 s |
| `remote_ping_response` | 9 | echoed `val1` |
| `remote_key_inject` | 10 | `{ key_code, direction }` |
| `remote_ime_key_inject` | 20 | TV → phone: focused field/app, counters |
| `remote_ime_batch_edit` | 21 | phone → TV: text insertion |
| `remote_ime_show_request` | 22 | TV → phone: field focus with counter |
| `remote_voice_begin` | 30 | `{ session_id }` |
| `remote_voice_payload` | 31 | `{ session_id, samples }` |
| `remote_voice_end` | 32 | `{ session_id }` |
| `remote_start` | 40 | `{ started }` — TV awake/asleep |
| `remote_set_volume_level` | 50 | `{ volume_max, volume_level, volume_muted }` |
| `remote_app_link_launch_request` | 90 | launch by URI |

### Handshake

1. Phone connects TLS and sends `remote_configure` with what it supports.
2. TV replies `remote_configure` whose `code1` is a **feature bitmask**:

```
PING = 1<<0   KEY = 1<<1   IME = 1<<2   VOICE = 1<<3
POWER = 1<<5  VOLUME = 1<<6  APP_LINK = 1<<9
```

3. Phone replies `remote_configure` with `requested & supported`, and `remote_set_active` with the
   same value. It answers every later `remote_set_active` too.
4. If `KEY` is missing the TV will ignore all key presses; the app reports that explicitly
   (the known fix on the TV side is clearing *Android TV Remote Service* app data).

### Keep-alive

The TV sends `remote_ping_request` roughly every 5 seconds and drops clients that do not answer.
The app answers every ping immediately with the echoed `val1`, which keeps the connection usable
for instant control without polling.

### Key injection

`remote_key_inject { key_code, direction }` with `direction = SHORT(3)` for normal presses;
`START_LONG(1)`/`END_LONG(2)` are supported by the sender for long presses. Key codes are
`android.view.KeyEvent` constants, e.g. `DPAD_UP = 19`, `DPAD_CENTER = 23`, `BACK = 4`, `HOME = 3`,
`VOLUME_UP = 24`, `VOLUME_MUTE = 164`, `APP_SWITCH = 187`, `SEARCH = 84`.

### Text input

```
remote_ime_batch_edit {
  ime_counter = <echoed from the TV>
  field_counter = <echoed from the TV>
  edit_info { insert = 1, text_field_status { start = len, end = len, value = text } }
}
```

The counters come from `remote_ime_key_inject` / `remote_ime_show_request` /
`remote_ime_batch_edit` messages the TV sends when a text field gains focus; the app tracks them
and falls back to the last seen values. This is how the official app types into YouTube, Google,
Netflix and browser search fields.

### Voice

1. Phone sends `KEYCODE_SEARCH (84)`.
2. TV answers `remote_voice_begin { session_id }` when it opens its voice sheet.
3. Phone replies `remote_voice_begin { session_id }` (required by the protocol).
4. Phone streams `remote_voice_payload { session_id, samples }`:
   * 16-bit PCM, mono, **8 kHz**;
   * chunks of at most 20 KB;
   * every chunk padded with zero bytes to at least 8 KB — some devices (e.g. Shield TV) close
     the connection otherwise.
5. Phone sends `remote_voice_end { session_id }`.

The TV feeds the audio to its own voice service, which is why the assistant behaves the same as
with the physical remote. If no `remote_voice_begin` arrives within the timeout, the app reports
that and falls back to on-phone recognition (see §6).

---

## 5. Reconnection

* The TCP/TLS channel is re-opened and the configure handshake repeated; the stored client
  certificate makes this transparent to the TV (no re-pairing).
* Backoff: 1 s, 2 s, 3 s, 5 s, 5 s, 5 s, then every 15 s while the app is open, until the user
  disconnects.
* A dead socket (TV off, Wi-Fi change) surfaces as a read error and triggers the same path.

---

## 6. Speech-recognition fallback

When the TV does not offer `VOICE` or does not open a voice session, `SpeechRecognizer` (normally
Google's on-device recogniser, Indonesian or English) produces a transcript, and that text is sent
with `remote_ime_batch_edit`. The TV therefore receives **typed text**, not speech. The UI labels
this mode clearly; it is not Google Assistant.

---

## 7. Security notes

* TLS here provides confidentiality and a stable client identity, not PKI trust. Neither side can
  validate the other against a public CA: every TV has a unique self-signed certificate. The
  protocol authenticates the phone with the pairing secret instead, which is why the TV refuses
  connections from a client certificate it did not pair with.
* Pairing credentials (the client private key and certificate) are stored as a PKCS#12 blob
  encrypted with AES-256-GCM under an Android keystore key, in app-private storage, excluded from
  backup and device transfer.
* Nothing is sent to any third-party server. Audio never touches disk.
* A malicious device on the LAN could impersonate a TV only after you pair with it; the code is
  shown on the real TV's screen, and the app verifies the check digits against the presented
  certificate.

---

## 8. Sources

* `remotemessage.proto`, `polo.proto`: <https://github.com/tronikos/androidtvremote2>
  (copied there from `louis49/androidtv-remote` and Google's `google-tv-pairing-protocol`)
* Protocol notes: <https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2)>
* Android key codes: `frameworks/native/include/android/keycodes.h`
