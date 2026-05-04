# btdashboard — Home Assistant over Bluetooth (Android & Android Auto)

[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-brightgreen)](https://developer.android.com/about/versions/android-8.0)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-blue)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)](https://developer.android.com/jetpack/compose)
[![Android Auto](https://img.shields.io/badge/Android%20Auto-Ready-success)](https://developer.android.com/training/cars)
[![License](https://img.shields.io/github/license/Gruni22/ha-android)](LICENSE)

Standalone Android app that controls a Home Assistant instance **over Bluetooth Low Energy** — no Wi-Fi required. The same APK doubles as an **Android Auto** app, so your dashboards follow you into the car.

> **Companion HA integration:** [`ha-bluetooth`](https://github.com/Gruni22/ha-bluetooth)
> **Companion firmware:** [`esp32-ha`](https://github.com/Gruni22/esp32-ha)

---

## Why?

The official [Home Assistant Companion](https://github.com/home-assistant/android) app needs an IP route to the HA host — Wi-Fi, VPN, or Nabu Casa. That's annoying when:

- you're outside Wi-Fi range but standing next to the house
- you don't want a cloud account or open ports
- you're driving and don't want to depend on tethering

`btdashboard` solves that by speaking BLE to a small ESP32-S3 plugged into your HA host. Lights, switches, locks, covers, sensors — all of it works without any IP connectivity between phone and HA.

---

## Features

- 🔌 **Pure-BLE control** — no Wi-Fi, no internet, no cloud account
- 📷 **QR-code setup** — scan the passcode shown by Home Assistant once, you're done
- 🏠 **HA-Lovelace-style UI** — domain-grouped tile cards, area navigation, dashboard tabs, hamburger drawer
- 🚗 **Android Auto** — built-in `CarAppService`, dashboards filtered for car use (any view containing "aa" in its name)
- 💡 **Brightness & speed sliders** — for lights and fans, both on phone and in the car
- 🗄️ **Local Room database** — areas, devices and dashboards are synced once and survive restarts; state changes stream in over BLE
- 🌐 **Localized** — English & German out of the box (`values/`, `values-de/`)
- 🔁 **Server-push state updates** — Home Assistant pushes `state_changed` events; the UI updates instantly without polling

---

## Architecture

```
┌─────────────────────────────┐         ┌──────────────────────┐
│  btdashboard                │  BLE    │   ESP32-S3           │
│  Phone UI / Android Auto    │◀═══════▶│   (esp32-ha firmware)│
│  Room DB · Compose · Hilt   │         └─────────┬────────────┘
└─────────────────────────────┘                   │ USB-CDC
                                                  ▼
                                         ┌──────────────────────┐
                                         │  Home Assistant      │
                                         │  ha-bluetooth integ. │
                                         └──────────────────────┘
```

**App-side stack:**

- **UI:** Jetpack Compose + Material 3 (dark/light)
- **DI:** Hilt
- **DB:** Room (areas, entities, dashboards, views)
- **BLE:** Nordic Android BLE Library — `BleManager` with chunked TX/RX, MTU 247
- **QR:** Google ML Kit Barcode Scanning + CameraX
- **Coroutines + StateFlow** throughout

---

## Install

### From a release APK

1. Grab the latest APK from the [**Releases**](https://github.com/Gruni22/ha-android/releases) page.
2. On your phone, allow installs from your browser/file manager.
3. Open the APK to install.

### From source

```bash
git clone https://github.com/Gruni22/ha-android.git
cd ha-android
./gradlew :btdashboard:assembleDebug
adb install -r btdashboard/build/outputs/apk/debug/btdashboard-debug.apk
```

Requires JDK 17 and the Android SDK (API 34). Easiest path is opening the project in Android Studio Hedgehog or newer.

---

## First-run setup

1. Make sure the HA side is ready — the [`ha-bluetooth`](https://github.com/Gruni22/ha-bluetooth) integration is installed and an ESP32 with [`esp32-ha`](https://github.com/Gruni22/esp32-ha) firmware is plugged in.
2. Open the app → **"Connect via Bluetooth"**.
3. Grant the Bluetooth and Location permissions when prompted (Android requires location for BLE scanning on API 26-30).
4. The app scans for devices advertising the HA service UUID — pick yours from the list. Default name: **`Homeassistant_Home`**.
5. **Scan the QR code** shown by Home Assistant (Settings → Devices & Services → Bluetooth API → notification). The 32-bit passcode is stored locally.
6. The initial sync runs (areas → devices → dashboards). Done.

---

## Android Auto

The same APK exposes a `CarAppService`. As soon as the phone is connected to a head unit (or to the [Desktop Head Unit](https://developer.android.com/training/cars/testing) for development), **Home Assistant Bluetooth** appears in the launcher.

### Dashboard filter

To avoid cluttering the head unit with bedroom-only or maintenance dashboards, the car UI only shows views whose **title or path contains "aa"** (case-insensitive). Example:

| HA View | Phone | Android Auto |
|---------|-------|--------------|
| `dashboard-bt/test` | ✓ | ✗ |
| `dashboard-bt/test-aa-1` | ✓ | ✓ |
| `dashboard-test/aa-test2` | ✓ | ✓ |
| `dashboard-test/0` | ✓ | ✗ |

Phone and car maintain **independent dashboard selections** — switching the active dashboard in the car does not affect the phone, and vice versa.

### Test on a PC

```bash
# 1. Install the Desktop Head Unit (DHU) from the Android Studio SDK Manager
#    (Extras → Android Auto Desktop Head Unit emulator).
# 2. On the phone: Android Auto app → tap version 10× → Developer settings → "Head Unit Server".
adb forward tcp:5277 tcp:5277
desktop-head-unit
```

When the DHU connects, the BLE service running on the phone is shared into the car app — you'll see the same dashboards live.

---

## Project layout

```
btdashboard/src/main/kotlin/io/homeassistant/btdashboard/
├── BtDashboardApp.kt           ← Hilt application entry
├── MainActivity.kt             ← Compose host
├── bluetooth/                  ← BLE scanning, GATT, pairing receiver
│   └── ble/                    ← Nordic-based GATT transport, chunking
├── car/                        ← Android Auto: CarAppService, screens
│   ├── BtCarAppService.kt
│   ├── BtCarEntityScreen.kt
│   ├── BtCarLightDetailScreen.kt
│   └── BtCarDashboardListScreen.kt
├── config/                     ← BtConfig: stored device addr, passcode, last sync
├── dashboard/                  ← Domain-grouped UI, HaTileCard, drawer
├── db/                         ← Room: AreaEntity, EntityEntity, DashboardEntity, ViewEntity
├── di/                         ← Hilt modules
├── github/                     ← Optional fetch of HA-domain metadata
├── protocol/                   ← PacketCodec (CRC16, magic, passcode)
├── service/                    ← BleConnectionService — long-lived foreground BLE owner
├── settings/                   ← Settings screen + view model
├── setup/                      ← First-run wizard, QR scanner, sync progress
├── sync/                       ← SyncManager: REQ_AREAS → REQ_DEVICES → REQ_DASHBOARDS
└── welcome/                    ← Welcome screen shown before any device is paired
```

`common/` (sibling Gradle module) holds shared Kotlin utilities lifted from the upstream HA Companion app.

---

## BLE protocol

Each frame is wrapped with a passcode header and chunked over BLE:

```
[0xAA 0xBB][passcode u32 BE][cmd u8][flags u8][len u16 BE][payload …][crc16 BE][0xCC 0xDD]
```

Chunks add a 1-byte continuation flag (`0x00` = more, `0x01` = final) and are at most 244 bytes of payload (MTU 247 − 3 ATT). See [`ha-bluetooth`'s README](https://github.com/Gruni22/ha-bluetooth#protocol-details) for the full command-code table.

---

## Permissions

| Permission | Why |
|------------|-----|
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (API 31+) | scan, connect, GATT |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (API ≤ 30) | legacy BLE on older devices |
| `ACCESS_FINE_LOCATION` (API 26-30) | required for BLE scanning on Android 8-11 |
| `CAMERA` | QR-code scanning during setup |
| `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_CONNECTED_DEVICE`) | keep the BLE link alive while the screen is off |
| `POST_NOTIFICATIONS` | foreground-service ongoing notification (Android 13+) |
| `INTERNET` | only used by the optional GitHub-metadata fetcher (HA domain hints) |

The app **never** opens an outbound TCP connection to your Home Assistant. The only network use is the optional GitHub fetch for type metadata, which can be disabled in Settings.

---

## Troubleshooting

<details>
<summary><b>Setup hangs at "Synchronisiere Daten…" / "Syncing data…"</b></summary>

The phone connected via BLE but the Pi-side response never arrived. In 95% of cases this is stale NimBLE state on the ESP32 — **unplug the ESP32 from USB, wait 5 s, plug it back in**, then re-run setup.
</details>

<details>
<summary><b>QR scanner can't read the code</b></summary>

The QR code from Home Assistant is high-density. Hold the phone ~15 cm from the screen, with the camera at a slight angle to avoid glare. The scanner uses Google ML Kit and works offline; no network round-trip is required.
</details>

<details>
<summary><b>"Bluetooth permission required" loop</b></summary>

Android sometimes silently revokes one-time permissions on app suspend. Open **Settings → Apps → Home Assistant Bluetooth → Permissions** and grant Bluetooth + Location permanently.
</details>

<details>
<summary><b>Android Auto crashes when opening the app</b></summary>

Make sure the phone has a valid BLE connection *before* launching the car app — the head-unit screens depend on the foreground `BleConnectionService`. If the service was killed, re-open the phone app first to re-establish.
</details>

<details>
<summary><b>Toggle works once but state stays stale</b></summary>

This indicates the `state_changed` push from HA isn't reaching the app. Check that the HA integration is at v0.3+ — earlier versions had a `_sync_in_progress` flag that suppressed pushes after a sync timeout.
</details>

---

## Development

### Run a debug build

```bash
./gradlew :btdashboard:installDebug
```

### Inspect logs

```bash
adb logcat --pid=$(adb shell pidof io.homeassistant.btdashboard.debug) \
  | grep -E "Timber|HaPacketClient|HaBleManager|SyncManager"
```

Useful tags:

| Tag | What you'll see |
|-----|-----------------|
| `HaBleManager` | Nordic BLE library log: connect, MTU negotiation, CCCD writes, notification reception |
| `HaPacketClient` | One line per outbound `cmd=0xXX` and inbound frame |
| `SyncManager` | Initial sync progress, `REQ_AREAS → REQ_DEVICES → REQ_DASHBOARDS` |
| `BleGattTransport` | Chunk reassembly per inbound notification |

### Build a release APK

```bash
./gradlew :btdashboard:assembleRelease
# signing config required — see btdashboard/build.gradle.kts
```

---

## Roadmap

- [ ] Custom dashboard editor (drag-rearrange tiles directly in the app)
- [ ] Wear OS companion (BLE-relay via the phone)
- [ ] Voice control via Android Auto's assistant intent
- [ ] Multi-instance support (switch between several HA hosts)

---

## Contributing

PRs welcome. Please open an issue first for larger features so design can be discussed.

When reporting BLE issues, attach:
1. App `logcat` (filtered as above)
2. HA log (`ha core logs | grep bluetooth_api`)
3. Device model + Android version

## License

[MIT](LICENSE) — see `LICENSE.md` in the repo root.
