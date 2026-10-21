# RadioClip 📡📋 (BETA)

> **Decentralized, 100% Offline P2P Clipboard & File Sharing for Android**  
> Powered by Bluetooth Low Energy (BLE GATT) and Wi-Fi Direct (P2P). Zero servers, zero internet, zero cloud dependencies.

---

> [!IMPORTANT]
> **Status: Public Beta (v1.0-beta)**  
> RadioClip is actively evolving. Core transport protocols and peer discovery are functional and undergoing stress testing across diverse Android radio hardware and manufacturer OS flavors.

---

## 🌟 Overview

**RadioClip** turns your Android device into an autonomous local data radio station. It creates direct device-to-device wireless mesh links to synchronize clipboard text and transfer local files without:
- ❌ Internet connectivity or Wi-Fi routers
- ❌ External servers, cloud sync, or accounts
- ❌ Tracking, telemetry, or user data harvesting

All communications occur over standard local RF interfaces (`Bluetooth LE` and `Wi-Fi P2P`).

---

## 🚀 Key Features

- **⚡ Dual-Engine Hybrid Routing**:
  - **BLE GATT Transport**: Ultra low-power background transport for small text snippets, clipboard sync, and signaling (≤ 20 KB).
  - **Wi-Fi Direct (P2P) Transport**: High-throughput multi-megabyte direct Wi-Fi channel for files, photos, audio, and large payloads.
- **📱 Background Sync Service**:
  - Runs a foreground persistent Android Service (`connectedDevice`) maintaining radio liveness and listening for incoming payloads.
- **👥 Active Peer Discovery & Manual Connect**:
  - Scans and advertises simultaneously. Displays peer RSSI, device addresses, and connection statuses.
  - Interactive "Connect" buttons for targeted P2P link establishment.
- **📥 In-App File Opening**:
  - Received files are safely saved to app-specific external storage and can be opened immediately via secure `FileProvider` intents.
- **🎨 Modern Jetpack Compose UI**:
  - Material 3 dashboard with real-time transport health cards, directional payload badges (Sent vs Received), transport indicators, and live activity feed.

---

## 🔒 Security & Privacy Notice

- **No Secrets or Credentials**: This repository contains no API keys, private keys, keystores, backend URLs, or credentials.
- **Zero Internet Requirement**: The app operates strictly peer-to-peer over local radios. No telemetry, crash reporting, or external outbound network calls exist in the codebase.
- **Standard Local Permissions**: BLE and Wi-Fi Direct permissions specify `neverForLocation` where supported on modern Android versions (Android 12+ / API 31+).

---

## 📦 Building & Generating APK

### 1. Prerequisites
- Android Studio Ladybug / Meerkat or later
- JDK 11 or JDK 17
- Android SDK Platforms 30 through 35

### 2. Build Debug APK via CLI
From the root of the project, run:
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Git Release / GitHub Distribution
> **Can you put the APK directly in the Git repository?**  
> **Best Practice Recommendation:** Do **not** commit compiled `.apk` binary files directly into git history. Binary files bloat the `.git` repository size permanently.  
> Instead, host the APK on **GitHub Releases**:
> 1. Push your code to GitHub.
> 2. Go to **Releases** > **Draft a new release**.
> 3. Tag the release (e.g., `v1.0.0-beta`).
> 4. Attach `app-debug.apk` directly to the release assets for users to download.

---

## 📋 Architecture

```
app/src/main/java/com/almog/radioclip/
├── MainActivity.kt          # Compose UI entry point & permission orchestrator
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
MIT License. Free and open source.
