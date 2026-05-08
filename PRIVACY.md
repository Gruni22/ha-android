# Privacy Policy — btdashboard

_Last updated: 2026-05-08_

This Privacy Policy describes how the Android application **btdashboard – HA over Bluetooth** ("the App", package `io.github.gruni22.btdashboard`) handles user data. The App is an open-source, third-party companion application for Home Assistant. It is **not** affiliated with, endorsed by, or distributed by Nabu Casa, Inc. or the Home Assistant project.

## Summary in plain language

- The App works **fully offline**. It does not send any data to the developer, to Google, or to any external server.
- The only network use is an **optional** Bluetooth connection to a user-owned ESP32 gateway plugged into the user's own Home Assistant instance.
- The App does **not** use analytics, tracking, advertising, or crash reporting.
- The Camera is used **only locally** to scan a setup QR code; no images are transmitted or stored.
- All data the App stores stays in the App's private storage on the device and can be deleted by uninstalling the App.

## Data the App stores locally on the device

The App keeps the following data exclusively in the App's private storage on the user's Android device. None of it is transmitted to any third party.

| Data | Purpose | Storage |
|------|---------|---------|
| Bluetooth MAC address of the user-paired Home Assistant gateway | reconnect to the same gateway between sessions | Encrypted SharedPreferences |
| 32-bit setup passcode for that gateway | authenticate every Bluetooth packet | Encrypted SharedPreferences |
| Cached Home Assistant entity, area and dashboard data | offline display of the user's smart-home state | Local Room (SQLite) database |
| Last known state of each entity | UI rendering | Local Room (SQLite) database |

All locally stored data is removed when the App is uninstalled or when the user taps "Remove active device" in the App's Settings screen.

## Data the App does NOT collect

- No personal information (name, email, address, phone number)
- No location data
- No contacts, calendar, files, or media
- No advertising ID or device identifier (Android ID, IMEI, etc.)
- No usage analytics or telemetry
- No crash reports
- No data sold or shared with third parties

## Permissions the App requests and why

| Permission | Reason |
|------------|--------|
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+) | Discover and connect to the user's ESP32 gateway via Bluetooth Low Energy |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android 11 and below) | Same purpose, legacy permission names |
| `ACCESS_FINE_LOCATION` (Android 11 and below only) | Required by the Android operating system for BLE scanning on these versions; the App does **not** read or store the device's location |
| `CAMERA` | Scan the QR code that Home Assistant displays during initial setup (one-time, on the device, no images stored) |
| `INTERNET` | Optional one-time download of Home Assistant entity-type metadata from a public GitHub repository (can be disabled in Settings) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the Bluetooth connection to the gateway alive while the screen is off |
| `POST_NOTIFICATIONS` (Android 13+) | Show the ongoing-service notification required by the system |

## Network communication

- **Bluetooth Low Energy** to the user's own ESP32 gateway. This is the only "external" channel under normal operation. The data exchanged is the user's own smart-home state and commands.
- **One-time HTTPS request to `raw.githubusercontent.com`**, only if the user enables the optional "Fetch HA type info from GitHub" setting. No identifiers are sent — only a public unauthenticated GET request.
- The App makes **no** other outbound connections, ever.

## Children's privacy

The App is not directed at children under 13 and does not knowingly collect any data from them.

## Changes to this Privacy Policy

If this policy changes, the new version will be committed to the public source repository and a new App release will reference the new policy. Significant changes will be noted in the release notes.

## Contact

For questions or requests regarding this policy, please open an issue at:

**https://github.com/Gruni22/ha-android/issues**

Source code:

- App: <https://github.com/Gruni22/ha-android>
- Home Assistant integration: <https://github.com/Gruni22/ha-bluetooth>
- ESP32 firmware: <https://github.com/Gruni22/esp32-ha>
