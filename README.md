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
  - **BLE GATT Transport**: Low-power text transfer with authenticated encryption, chunk reassembly, verified receiver acknowledgements and retries (up to 20 KB on the wire).
  - **Wi-Fi Direct (P2P) Transport**: Peer-to-peer TCP for encrypted text and files up to 512 MB with per-chunk authentication and receiver confirmation.
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

## 🔒 Security & Privacy

> [!IMPORTANT]
> **All devices must run protocol v2 and enter the same 16+ character pairing phrase (letters and numbers).** Enter the phrase on both devices in the dashboard. Earlier builds are not protocol-compatible.
>
> - Text and file contents are encrypted and authenticated with AES-256-GCM. A shared key is derived using PBKDF2-HMAC-SHA256; Android Keystore protects its on-device storage. The phrase is never sent over BLE or Wi-Fi Direct.
> - Both transports use receiver acknowledgements and bounded retry attempts; Wi-Fi file chunks are authenticated individually and saved only after all chunks pass verification.
> - **The pairing phrase is shared-group authentication, not verified device identity.** Anyone with the phrase could join the group. Wi-Fi file names, MIME types and sizes are not hidden from the peer network.
> - This is still a development build. It has not been validated on two physical devices, and it has no formal security audit. Avoid transmitting sensitive credentials until you have tested and evaluated the risks.
> - Automatic clipboard sync is off by default and requires opt-in. Android 10+ restricts background clipboard reads, so automatic sync may only work while the app is in the foreground. Manual text and file sending remains available.
> - No accounts, servers, telemetry or external analytics are used. The Android `INTERNET` permission is required for local TCP sockets, not cloud access.

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
security/
└── SecureLink.kt            # Pairing phrase, Keystore, AES-GCM, BLE acknowledgement MAC
```

---

## 🧪 Verification

The `.github/workflows/android-ci.yml` workflow runs unit tests, builds a debug APK and uploads an artifact named **RadioClip-debug-apk** on successful runs. Download the artifact ZIP from the latest successful workflow run and extract `app-debug.apk` to install. Debug builds are not Play Store releases.

For a meaningful two-device release test, exercise:
- Enter the same phrase on two phones, confirm encrypted BLE discovery/connect and text transfer in **both** directions, including Unicode, retries and reconnection. Verify that a wrong phrase cannot receive messages.
- Wi-Fi Direct encrypted text and file transfer both ways, correct pairing before/after connection, no duplicate deliveries after retries, and the 512 MB limit.
- Android permission denial, Bluetooth/Wi-Fi toggling, app rotation, and service shutdown.
- Clipboard opt-in behavior in foreground and background, including Android 10+ clipboard restrictions.

## 📄 License
MIT License. See `LICENSE`.

