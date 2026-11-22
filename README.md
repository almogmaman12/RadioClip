# RadioClip 📡📋 (BETA)

> **Decentralized, 100% Offline P2P Clipboard & File Sharing for Android**  
> Powered by Bluetooth Low Energy (BLE GATT) and Wi-Fi Direct (P2P). Zero servers, zero internet, zero cloud dependencies.

---

> [!IMPORTANT]
> **Status: Public Beta (v1.0-beta)**  
> RadioClip is actively evolving. Transport reliability is being repaired. Real-device BLE and Wi-Fi Direct testing is still required before using the app for important data.

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
  - BLE discovery and advertising are available; connection requires a manual selection. The dashboard displays peers and connection states.
  - Interactive "Connect" buttons for targeted P2P link establishment.
- **📥 In-App File Opening**:
  - Received files are stored in app-private files, validated for declared size, and can be opened through `FileProvider` intents.
- **🎨 Modern Jetpack Compose UI**:
  - Material 3 dashboard with transport health, connection controls, received-text copy, and Sent / Failed / Received history badges.

---

## 🔒 Security & Privacy Notice

> [!WARNING]
> **Do not use RadioClip to exchange passwords, tokens, or sensitive files yet.**
> The current application protocol does **not** authenticate peers, establish a trusted-device list, or provide end-to-end encryption. Bluetooth and Wi-Fi Direct radio connections are not a substitute for application-level authentication. Treat this as a development prototype.
>
> **Automatic clipboard sync is disabled by default.** It requires an explicit opt-in and can be unreliable on Android 10+ because Android restricts background clipboard access. You can still send text manually and copy received text in the app.
>
> Received files are limited to 512 MB and checked for truncation, but **cryptographic integrity verification and receiver acknowledgments are not implemented yet.** A "Sent" badge confirms only that the local transport completed without raising an error, not that the recipient stored the data.



- **No Secrets or Credentials**: This repository contains no API keys, private keys, keystores, backend URLs, or credentials.
- **Zero Internet Requirement**: The app operates strictly peer-to-peer over local radios. No telemetry, crash reporting, or external outbound network calls exist in the codebase.
- **Standard Local Permissions**: BLE and Wi-Fi Direct permissions specify `neverForLocation` where supported on modern Android versions (Android 12+ / API 31+).

---

## 📦 Building & Generating APK

### 1. Prerequisites
- Android Studio Ladybug / Meerkat or later
- JDK 17
- Android SDK Platform 35 (minimum supported Android version is API 30)

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

## 🧪 Verification

The `.github/workflows/android-ci.yml` workflow runs unit tests and builds a debug APK on pushes and pull requests.

For a meaningful two-device release test, exercise:
- Manual BLE discovery, connect, and text transfer in **both** directions, including long text, Unicode, and reconnection.
- Wi-Fi Direct file transfers both ways, a truncated stream, an invalid filename, and the 512 MB limit.
- Android permission denial, Bluetooth/Wi-Fi toggling, app rotation, and service shutdown.
- Clipboard opt-in behavior in foreground and background, including Android 10+ clipboard restrictions.

## 📄 License
MIT License. See `LICENSE`.

