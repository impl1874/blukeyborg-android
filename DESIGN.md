# BluKeyborg Android — Architecture & Design

## Overview

BluKeyborg is the Android companion app for the BlueKeyboard/BluKeyborg USB HID dongle. It communicates with the dongle over Bluetooth Low Energy (BLE) using a custom Nordic UART-style protocol, establishes a mutual-TLS (MTLS) secure session, and sends keyboard HID reports that translate to USB keystrokes on the host computer.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                             │
│  MainActivity → TypeFragment / DevicesFragment / etc.       │
└────────────────────────┬──────────────────────────────────┘
                         │ calls
┌────────────────────────▼──────────────────────────────────┐
│                   BleHub (Singleton)                      │
│  • BLE connection lifecycle                                │
│  • MTLS handshake                                         │
│  • Binary framing (B0/B1/B2/B3, A0/A1/A2/A3, C*/D*/E*)  │
│  • Exposes connected: LiveData<Boolean>                    │
└────────┬───────────────────────┬──────────────────────────┘
         │ delegates to          │ delegates to
┌────────▼────────┐    ┌─────────▼─────────────────────────┐
│ BluetoothDevice │    │         BleAppSec                  │
│ Manager        │    │  • APPKEY storage (AndroidKeyStore) │
│  • GATT connect│    │  • RSA keypair per device          │
│  • NUS read/write│   │  • APPKEY encryption/decryption    │
│  • BLE scan    │    │  • PBKDF2 provisioning            │
└─────────────────┘    └───────────────────────────────────┘
```

## Key Components

### BleHub (Singleton)

The central orchestration object. Acts as the single BLE + security authority for the entire app.

**Responsibilities:**
- Manages full connection lifecycle: GATT connect, service discovery, pairing observation, MTLS handshake, layout fetch
- Owns `MtlsState` (session ID, derived keys `kEnc/kMac/kIv`, sequence counters)
- Implements binary framing/deframing for BLE frames
- Exposes `connected: LiveData<Boolean>` so UI knows when MTLS session is ready

**Key public APIs:**
| Method | Description |
|--------|-------------|
| `init(context)` | Call once from `App.onCreate()` |
| `autoConnectFromPrefs()` | Silent connect for app startup |
| `autoConnectForServices()` | Silent connect for AIDL plugins |
| `connectAndFetchLayoutSimple()` | User-initiated connect |
| `sendStringAwaitHash(value)` | Send text with MD5 integrity check (D0/D1) |
| `sendRawKeyTap(mods, usage, repeat)` | Fast key tap for raw HID (0xE0) |
| `enableFastKeys()` | Enable firmware "raw fast mode" (C8) |
| `sendRawMouseEvent(buttons, dx, dy, wheel)` | Send mouse event (0xE1) |
| `getLayout()` / `setLayoutString()` | Query/set keyboard layout |

### BleAppSec (Singleton)

Handles APPKEY storage and encryption. The APPKEY is a 32-byte symmetric key provisioned to each dongle.

**Security Model:**
- Per-device 32-byte APPKEY stored encrypted in SharedPreferences
- RSA keypair per device generated in AndroidKeyStore (non-exportable)
- APPKEY encrypted with RSA/OAEP before storage
- Device identity derived via `SHA-256(deviceId)` truncated to 128 bits

**Key methods:**
| Method | Description |
|--------|-------------|
| `putKey(context, deviceId, key32)` | Encrypt and store APPKEY |
| `getKey(context, deviceId)` | Decrypt and retrieve APPKEY |
| `hasAppKey(context, deviceId)` | Check if APPKEY exists |
| `clearKey(context, deviceId)` | Remove stored ciphertext |

### BluetoothDeviceManager

Low-level BLE transport manager. UI-agnostic; exposes LiveData and callbacks.

**Responsibilities:**
- Maintains merged list of bonded + scanned BLE devices
- Runs one-shot RSSI scans for auto-connect
- Manages persistent GATT connection
- Implements full GATT callback: connection state, MTU negotiation, service discovery, CCCD writes, characteristic notifications, write confirmations

**BLE Service/Characteristics (Nordic UART NUS):**
| UUID | Name | Direction |
|------|------|-----------|
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Service | — |
| `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | TX (write) | App → Dongle |
| `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | RX (notify) | Dongle → App |

## BLE Protocol

### Binary Frame Format

```
Unencrypted: [OP u8][LEN u16 LE][PAYLOAD...]
Encrypted (B3): [0xB3][LEN u16 LE][seq_be16][clen_be16][cipher][mac16]
```

### Opcode Summary

| Opcode | Name | Direction | Description |
|--------|------|-----------|-------------|
| **APPKEY Onboarding (pre-MTLS)** |
| `0xA0` | GET_APPKEY | App→Dongle | Request KDF params + challenge |
| `0xA2` | APPKEY_CHALLENGE | Dongle→App | `[salt16][iters4][chal16]` |
| `0xA3` | APPKEY_PROOF | App→Dongle | HMAC-SHA256(verif, "APPKEY"\|\|chal) |
| `0xA1` | APPKEY_RESPONSE | Dongle→App | 32-byte raw or 48-byte wrapped AppKey |
| **MTLS Handshake** |
| `0xB0` | SERVER_HELLO | Dongle→App | P-256 pubkey (65 bytes) + session ID (4 bytes) |
| `0xB1` | CLIENT_KEYX | App→Dongle | Ephemeral pub (65 bytes) + HMAC (16 bytes) |
| `0xB2` | SERVER_FINISH | Dongle→App | HMAC confirmation (16 bytes) |
| `0xB3` | ENCRYPTED_RECORD | Both | Wrapped encrypted frame |
| **Application Commands (MTLS-protected)** |
| `0xC0` | SET_LAYOUT | App→Dongle | Set keyboard layout |
| `0xC1` | GET_INFO | App→Dongle | Query firmware info |
| `0xC2` | INFO_VALUE | Dongle→App | `"LAYOUT=X; PROTO=1.6; FW=2.1.0"` |
| `0xC4` | RESET_TO_DEFAULT | App→Dongle | Factory reset |
| `0xC8` | SET_RAW_FAST_MODE | App→Dongle | Enable 0xE0 fast path |
| `0xD0` | SEND_STRING | App→Dongle | Type UTF-8 text |
| `0xD1` | SEND_RESULT | Dongle→App | `[status1][MD5_16]` |
| `0xE0` | RAW_KEY_TAP | App→Dongle | Fast raw HID key (no encryption) |
| `0xE1` | RAW_MOUSE_EVENT | App→Dongle | Mouse move/click/scroll |

## Security Model

### APPKEY Provisioning Flow

```
1. User sets password via Wi-Fi setup portal on the dongle
2. Dongle derives 32-byte APPKEY using PBKDF2(password, salt, iterations)
3. App retrieves APPKEY via challenge-response (A0/A2/A3/A1)
4. App stores APPKEY encrypted in SharedPreferences (RSA/OAEP)
```

### MTLS Handshake

```
1. Ephemeral P-256 keypair generated per session
2. ECDH shared secret derived with dongle's static public key
3. Session key: HKDF-SHA256(APPKEY, ECDH shared, "MT1"||sid||...)
4. kEnc/kMac/kIv derived from sessKey via HMAC
5. B1/B2 use HMAC integrity to verify both sides have same APPKEY
6. Sequence counters (16-bit) prevent replay attacks
```

## UI Navigation

```
MainActivity (shell with NavHostFragment)
 ├── TopBar: Settings | LED indicator | Close
 ├── NavHostFragment
 │    ├── DevicesFragment (start if no device selected)
 │    │    └── SetupFragment (full-screen provisioning)
 │    └── TypeFragment (start if device provisioned)
 └── BottomBar: Devices | Type | SpecialKeys | FullKeyboard | Remote

Modal Activities (full-screen overlays):
 ├── FullKeyboardActivity — landscape software keyboard
 ├── RemoteControlActivity — media/presentation remote
 └── ShareSendPopupActivity — share intent receiver
```

## KeePassDX Integration

### AIDL Service

Implements `IOutputCredentialsService.aidl` from KeePassDX:

```java
interface IOutputCredentialsService {
    String getProviderName();  // returns "BluKeyborg"
    int sendPayload(String requestId, String mode, String username,
                    String password, String otp, String entryTitle, String entryUuid);
}
```

Supports modes: `user`, `pass`, `user_tab_pass_enter`, `user_enter_pass_enter`.

### KP2A Plugin

BroadcastReceivers for KeePass2Android's plugin actions:
- `ACTION_OPEN_ENTRY` — adds "BluKeyborg(Username)", "BluKeyborg(Password)" entry actions
- `ACTION_ENTRY_ACTION_SELECTED` — handles actual send when user taps menu item

## Data Flow: User Action to USB HID Output

```
1. User types text in TypeFragment → taps Send
2. TypeFragment calls BleHub.sendStringAwaitHash(value)
3. BleHub checks mtls state; if not established, calls connectAndEstablishSecureTo():
   a. connect() via BluetoothDeviceManager
   b. ensureNotificationsEnabled()
   c. waitForB0() — receives server hello
   d. doBinaryHandshakeFromB0() — B1/B2 MTLS handshake
4. sendStringAwaitHash():
   a. Computes MD5 of UTF-8 bytes
   b. Builds inner D0 frame: [0xD0][len][utf8_bytes]
   c. wrapB3() encrypts with AES-CTR + HMAC
   d. sendRawFrame() writes B3 to NUS TX characteristic
   e. awaitAppReply() waits for D1, verifies MD5
5. Dongle firmware parses HID report, emits USB HID keyboard interrupt
6. Host computer receives USB HID report → types characters
```

## Mouse Touchpad

A third remote panel mode "Touchpad" was added to `RemoteControlActivity`, alongside existing Media and Presentation panels.

**Interaction model:**
- **Drag** on touchpad area → sends relative mouse movement (dx, dy) via `BleHub.sendRawMouseEvent()`
- **Tap** on touchpad area → left click
- **Two-finger tap** → right click
- **Two-finger scroll** → vertical scroll wheel
- **Dedicated buttons** below touchpad → left/middle/right click

**New files:**
- `TouchpadView.kt` — Custom View handling touch gestures, tap detection, two-finger scroll
- `VideoCaptureActivity.kt` — USB video capture display (UVC camera)
- `outline_videocam_24.xml` — Video tab icon

**New method in BleHub.kt:**
- `sendRawMouseEvent(buttons, dx, dy, wheel)` — sends opcode 0xE1, MTLS-wrapped
- `clickMouseLeft/Right/Middle()` — convenience helpers
- `scrollMouse(delta)` — scroll wheel helper

**Sensitivity:** configurable via `PreferencesUtil.getTouchpadSensitivity()` (0.5x–4.0x), default 2.0.

## USB Video Capture Display

Added `VideoCaptureActivity` with UVCCamera library for displaying USB UVC capture card video.

**Features:**
- Auto-detect UVC devices via USB host API
- Runtime permission request (CAMERA)
- Full-screen preview to `TextureView`
- Two modes: Monitor (video only) and Interactive (video + touchpad overlay)
- Toggle button to switch between modes

**Requirements:**
- `android.hardware.usb.host` feature
- `android.permission.CAMERA` permission
- UVCCamera library dependency

**Mode toggle:** `[ 📺 Monitor ] [ 🖱️+📺 Interactive ]` button at bottom of video view.

- Min SDK: 23 (Android 6.0)
- Target SDK: 34 (Android 14)
- Kotlin
- AndroidX Navigation Component
- AndroidX Lifecycle (LiveData, ViewModel)
- Bluetooth LE API

## Related Documentation

- [BlueKeyboard Firmware](../blue_keyboard_mouse/HOW_IT_WORKS.md) — BLE → USB HID pipeline
- [BlueKeyboard Security](../blue_keyboard_mouse/SECURITY_OVERVIEW.md) — threat model and MTLS protocol
- [BlueKeyboard Mouse Design](../blue_keyboard_mouse/DESIGN_MOUSE.md) — mouse support implementation
