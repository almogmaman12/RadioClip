# RadioClip 📡📋 (BETA)

> **Offline P2P Clipboard & File Sharing for Android**  
> Powered by Bluetooth Low Energy (BLE GATT) and Wi-Fi Direct (P2P). No servers, no internet, no cloud. Everything is end-to-end encrypted with a pairing code you share between your devices.

---

> [!IMPORTANT]
> **Status: Public Beta (v1.0-beta)**  
> RadioClip is actively evolving. Core transport protocols and peer discovery are functional and undergoing testing across different Android radio hardware and manufacturer OS flavors.

---

## 🌟 Overview

**RadioClip** creates direct device-to-device wireless links to synchronize clipboard text and transfer local files without:
- ❌ Internet connectivity or Wi-Fi routers
- ❌ External servers, cloud sync, or accounts
- ❌ Tracking, telemetry, or user data harvesting

All communication happens over local radios (`Bluetooth LE` and `Wi-Fi Direct`). Links are point-to-point: devices talk directly to each other, there is no relaying between third devices.

---

## 🚀 Key Features

- **⚡ Dual-Engine Hybrid Routing**:
  - **BLE GATT Transport**: low-power transport for small text snippets and clipboard sync (≤ 20 KB).
  - **Wi-Fi Direct (P2P) Transport**: high-throughput direct channel for files, photos, audio and larger text (files up to 1 GB, text up to 1 MB).
- **🔐 Pairing-code encryption**: all text and files are encrypted and authenticated with AES-256-GCM using a key derived from a code you enter on each device (see below). Devices without the code can neither read nor inject data.
- **📱 Background Sync Service**: a foreground Android service (`connectedDevice`) keeps the radios alive and listens for incoming payloads.
- **👥 Peer Discovery & Manual Connect**: scans and advertises at the same time and shows device addresses and connection status, with a "Connect" button for Wi-Fi Direct links.
- **📥 In-App File Opening**: received files are stored in the app's own external storage (`Android/data/com.almog.radioclip/files/received/`) and can be opened through `FileProvider`. Existing files are never overwritten.
- **🎨 Jetpack Compose UI**: Material 3 dashboard with transport status, sent/received badges and an activity feed.

---

## 🔐 Pairing & Encryption

1. Open RadioClip on every device you want to sync.
2. On one device press **Generate** and note the code (or choose your own, at least 8 characters).
3. Enter the same code on the other device(s) and press **Pair** on each.

Until a device is paired it sends nothing and drops everything it receives. After pairing, every BLE message and every Wi-Fi Direct stream is encrypted with AES-256-GCM (key derived from the code with PBKDF2-HMAC-SHA256). Tampered, replayed, reordered or truncated data is rejected.

What this does **not** protect against: someone who learns the code can read everything, so treat it like a password (generated codes have 60 bits of entropy; short custom codes are weaker). There is no forward secrecy, and the fact that a RadioClip device is nearby is visible to anyone scanning Bluetooth. The key is stored in app-private storage (backups are disabled).

---

## 🔒 Security & Privacy Notice

- **No secrets in the repo**: no API keys, private keys, keystores, backend URLs or credentials.
- **No internet use**: no telemetry, crash reporting or outbound network calls. (Android requires the `INTERNET` permission for any socket, including the local Wi-Fi Direct ones; it is only used for peer-to-peer connections.)
- **Hardened receive path**: file names from other devices are sanitized and can't escape the receive folder, sizes are capped, sockets have timeouts, partial files are deleted, and the number of simultaneous connections is limited.
- **Standard local permissions**: BLE and Wi-Fi Direct permissions use `neverForLocation` where supported (Android 12+ / API 31+).

---

## ⚠️ Known Limitations

- **Clipboard in the background**: since Android 10, apps that are not in the foreground may be blocked from reading the clipboard. Automatic "copy on one phone, paste on the other" worked in first real-world tests, but behavior can differ between Android versions and manufacturers. If it doesn't trigger on your device, use the in-app **Send** box or **Pick File**.
- Wi-Fi Direct needs the other device to accept the connection request, and some manufacturers restrict it in the background.
- BLE and Wi-Fi Direct behavior varies a lot between phones; please open an issue with your device model if something doesn't work.

---

## 📦 Building

### Prerequisites
- Android Studio Ladybug / Meerkat or later
- JDK 11 or JDK 17
- Android SDK Platforms 30 through 35

### Debug APK
```bash
./gradlew assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Prebuilt APKs belong on GitHub Releases, not in the git history.

### Unit tests
```bash
./gradlew testDebugUnitTest
```
The encryption, stream framing, BLE chunking and file-name safety logic live in `core/` as plain Kotlin and are covered by JVM tests.

---

## 📋 Architecture

```
app/src/main/java/com/almog/radioclip/
├── MainActivity.kt          # Compose UI entry point & permission orchestrator
├── core/                    # Pure Kotlin logic (unit tested)
│   ├── Crypto.kt            # Pairing-code key derivation, AES-GCM encrypt/decrypt
│   ├── SecureStreams.kt     # Encrypted, authenticated framing for TCP streams
│   ├── ChunkCodec.kt        # BLE packet splitting & ordered reassembly
│   └── FileNames.kt         # Safe handling of received file names
├── security/
│   └── PairingStore.kt      # Stores the derived key in app-private storage
├── service/
│   └── RadioSyncService.kt  # Foreground Service binding BLE & Wi-Fi transports
├── routing/
│   └── SyncRouter.kt        # Payload routing decisions (BLE vs Wi-Fi)
├── transport/
│   ├── DeviceTransport.kt   # Common transport interface & lifecycle
│   ├── ble/
│   │   └── BleEngine.kt     # BLE GATT server, advertiser, client, scanner
│   └── wifi/
│       └── WifiEngine.kt    # Wi-Fi Direct manager & TCP socket client/server
├── model/
│   └── Payload.kt           # Payload, PeerDevice, TransportState models
└── ui/
    ├── DashboardScreen.kt   # Jetpack Compose dashboard UI
    └── theme/               # Material 3 styling & tokens
```

---

## 📄 License
MIT License. See [LICENSE](LICENSE).
