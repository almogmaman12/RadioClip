# RadioClip 📡📋 (BETA)

> **Decentralized, 100% Offline P2P Clipboard & File Sharing for Android**  
> Powered by Bluetooth Low Energy (BLE GATT) and Wi-Fi Direct (P2P). Zero servers, zero internet, zero cloud dependencies.

---

> [!IMPORTANT]
> **Status: Development Beta (v2.4.0-beta)**  
> RadioClip is actively evolving. Transport reliability is being repaired. Real-device BLE and Wi-Fi Direct testing is still required before using the app for important data.

---

## 🌟 Overview

**RadioClip** turns your Android device into an autonomous local data radio station. It creates direct device-to-device wireless mesh links to synchronize clipboard text and transfer local files without:
- ❌ External internet access or Wi-Fi routers
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
- **👥 App-Specific Peer Discovery & Manual Connect**:
  - BLE discovery requires RadioClip's service UUID and a separate transient session identity in its scan response. This groups rotating Bluetooth MAC addresses into one peer row, expires stale results after 45 seconds, and bounds the list to 24 entries.
  - Each RadioClip service start creates a new temporary identity; the app does not broadcast a permanent installation identifier. Bluetooth peers using the older APK cannot be discovered by this build.
  - Wi-Fi Direct advertises and discovers the `RadioClip._radioclip._tcp` DNS-SD service; unrelated devices are not listed.
  - Install this version on both devices. Keep Wi-Fi and Location enabled, open RadioClip on each phone, enter matching pairing phrases, then select a discovered peer.
  - Service discovery is not proof of trust. Transfers require the encrypted authenticated handshake.
- **📥 In-App File Opening**:
  - Received files are stored in app-private files, validated for declared size, and can be opened through `FileProvider` intents.
- **🎨 Modern Jetpack Compose UI**:
  - Material 3 dashboard with transport health, connection controls, received-text copy, and Sent / Failed / Received history badges.

---

## 🔒 Security & Privacy

> [!IMPORTANT]
> **All devices must run protocol v2 and enter the same pairing code (minimum 5 characters; 12+ random characters strongly recommended).** Enter the phrase on both devices in the dashboard. Earlier builds are not protocol-compatible.
>
> - Five-character codes are **weak** and vulnerable to guessing, even though traffic is encrypted. Use a long random code for private files.
> - Text and file contents are encrypted and authenticated with AES-256-GCM. A shared key is derived using PBKDF2-HMAC-SHA256; Android Keystore protects its on-device storage. Encryption always uses provider-generated AES-GCM IVs to avoid Android Keystore's caller-provided-IV restriction. The phrase is never sent over BLE or Wi-Fi Direct.
> - Both transports use receiver acknowledgements and bounded retry attempts; Wi-Fi file chunks are authenticated individually and saved only after all chunks pass verification.
> - **The pairing phrase is shared-group authentication, not verified device identity.** Anyone with the phrase could join the group. Wi-Fi file names, MIME types and sizes are not hidden from the peer network.
> - This is still a development build. It has not been validated on two physical devices, and it has no formal security audit. Avoid transmitting sensitive credentials until you have tested and evaluated the risks.
> - Clipboard sync is **off by default**. Android 10+ allows ordinary apps to read the clipboard only when they have input focus (or when they are the system's default keyboard). A foreground service is **not** an exception. When enabled, RadioClip detects new plain-text clipboard contents while its window is focused, and checks again when you return to it after copying in another app. It skips clips marked sensitive and retries a pending clipboard transfer when a peer reconnects.
- To send copied text from another app, use **Share → RadioClip**, the selection action **Process text**, or the **Send clipboard** action on RadioClip's ongoing notification. The notification brings RadioClip into focus before reading the clipboard. The shared text fills the send field for confirmation.
- The UI reports **Watching**, **Paused**, **Sending**, **Not delivered**, or **Delivered**, and has a **Send current clipboard** action which works even if automatic sync is off. Incoming text remains available in Activity History with a **Copy text** button when your device restricts applying the received clipboard.
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
- Install the new APK on both devices. Confirm BLE scans show **one row per device** even if its private Bluetooth address rotates, and that expired peers disappear after 45 seconds while connected peers stay visible.
- Enter the same phrase on two phones; confirm encrypted BLE discovery/connect and text transfer in **both** directions, including Unicode, retries and reconnection. Verify that a wrong phrase cannot receive messages.
- Wi-Fi Direct encrypted text and file transfer both ways, correct pairing before/after connection, no duplicate deliveries after retries, and the 512 MB limit.
- Android permission denial, Bluetooth/Wi-Fi toggling, app rotation, and service shutdown.
- Clipboard opt-in behavior in foreground and background, including Android 10+ clipboard restrictions.

## 📄 License
MIT License. See `LICENSE`.

