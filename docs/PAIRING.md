# Pairing guide

## On the TV

Nothing to install. The TV must have the system app **Android TV Remote Service** enabled
(Settings → Apps → Show all apps → Show system apps → Android TV Remote Service). This app is what
advertises the service and accepts pairing.

The code dialog appears **after** the phone contacts the TV, so start pairing from the phone:

* **Google TV / Android TV with a remote-accessory setting:**
  Settings → Remotes & Accessories → *Pair remote or accessory*
* Some TVs show the dialog automatically as soon as the phone connects to the pairing port.
* If nothing appears, open *Settings → Apps → Android TV Remote Service → Storage → Clear data*
  and try again. This resets the service without touching your TV settings.

## On the phone

1. **Devices** tab → the app scans with mDNS and SSDP for ~15 s.
   No TV listed? Tap **IP address** and type the TV's IP
   (TV: Settings → Network & Internet → your Wi-Fi network → IP address).
2. Tap **Pair** next to the TV.
3. When the TV shows six characters (0-9, A-F), type them into the dialog and press **Pair**.

## What happens under the hood

* The phone opens TLS to port 6467 and sends a pairing request naming itself.
* Both sides agree on a six-character hexadecimal code.
* The phone combines both certificates' public keys with the code and sends the SHA-256 digest.
* The TV compares it with what its screen shows. On success it remembers the phone's certificate
  and the pairing channel closes.

The complete sequence with field numbers is in [PROTOCOL.md §3](PROTOCOL.md).

## Why the code is checked locally

The first two characters of a correct code are a checksum over the certificates. The app verifies
them before sending anything, which means:

* a typo is reported instantly instead of after the TV rejects it;
* the app never sends a wrong secret and so never trips the TV's lockout on repeated failures.

Error message *"That code does not match this TV"* means the digits do not correspond to the
certificate the TV presented — usually a mistyped character, occasionally a stale dialog. Close
the dialog on the TV, start pairing again and retry.

## Reconnecting later

Pairing is stored per TV: name, IP, model, control and pairing ports, plus the shared client
identity. Reconnection redoes only the *control* handshake (`RemoteConfigure`), never pairing, so
the TV may be asleep-screen, in another app, or just rebooted.

If the TV's IP changes (DHCP), the connection fails; rescan and pair again — the TV keeps the old
pairing, but the app stores devices by address. A fixed DHCP lease for the TV avoids this.

## Removing a device

Devices → the paired TV → **Forget**. This deletes the record locally. To invalidate the pairing
on the TV side as well, clear the data of *Android TV Remote Service* on the TV.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| No TV found | Guest Wi-Fi or AP isolation blocks multicast; use the IP address. Make sure the TV is on the same subnet. |
| "Could not reach the TV's pairing service" | TV off, wrong IP, or remote service disabled. Check that port 6467 is reachable on the same LAN. |
| TV shows no code | Remote service needs a restart (clear its data) or the TV is on a very old firmware. |
| Pairing succeeds but keys do nothing | The TV's remote service is in a bad state: clear its data on the TV, forget the device here, pair again. |
| Connection drops repeatedly | The TV closes idle clients that stop answering pings; the app answers them. Repeated drops usually mean weak Wi-Fi or the TV going to sleep. |
