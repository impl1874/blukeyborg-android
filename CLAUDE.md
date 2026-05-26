# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
cd BluKeyborg
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build
./gradlew installDebug        # Build and install to device
./gradlew :app:testDebugUnitTest  # Run unit tests
```

Requirements: Android SDK 35, Kotlin 1.9, minSdk 24, targetSdk 35.

## Project Overview

BluKeyborg is the Android companion app for the Blue Keyboard / BluKeyborg USB HID dongle. It communicates with the dongle over BLE, establishes a mutual-TLS (MTLS) session, and sends keyboard/mouse HID reports that appear as typed input on the host computer.

## Architecture

```
┌──────────────────────────────────────────────┐
│                   UI Layer                    │
│  MainActivity → TypeFragment / DevicesFragment │
└─────────────────────┬────────────────────────┘
                      │ calls
┌─────────────────────▼────────────────────────┐
│              BleHub (Singleton object)        │
│  • BLE connection lifecycle                    │
│  • MTLS handshake + session state            │
│  • Binary framing (B0/B1/B2/B3, A0/A1/A2/A3) │
│  • All send* methods (strings, keys, mouse)  │
│  • Exposes connected: LiveData<Boolean>       │
└──────┬────────────────────┬──────────────────┘
       │ delegates to       │ delegates to
┌──────▼──────┐    ┌────────▼──────────────────┐
│ Bluetooth  │    │        BleAppSec           │
│ Device     │    │  • APPKEY storage (RSA)   │
│ Manager    │    │  • RSA keypair per device │
│ (GATT)     │    │  • PBKDF2 provisioning    │
└────────────┘    └───────────────────────────┘
```

## Key Components

| File | Role |
|------|------|
| `BleHub.kt` | Central BLE + MTLS hub. All protocol methods: `sendStringAwaitHash()`, `sendRawKeyTap()`, `sendRawMouseEvent()`, `enableFastKeys()`, etc. |
| `BleAppSec.kt` | APPKEY storage using RSA/OAEP encryption in SharedPreferences, backed by AndroidKeyStore |
| `BluetoothDeviceManager.kt` | Low-level BLE GATT operations, notification streaming, device scanning |
| `RemoteControlActivity.kt` | Media/Presentation/Touchpad panels; volume key interception |
| `TouchpadView.kt` | Custom View handling touchpad gestures (drag→move, tap→click, two-finger→scroll) |
| `VideoCaptureActivity.kt` | UVC camera preview using UVCCamera library |
| `MainActivity.kt` | App shell with bottom navigation (Devices/Type/Remote/Video) |

## BLE Protocol Opcodes

| Opcode | Name | Direction | Description |
|--------|------|-----------|-------------|
| `0xA0/A2/A3/A1` | APPKEY onboarding | Both | PBKDF2 challenge-response to retrieve AppKey |
| `0xB0/B1/B2/B3` | MTLS handshake | Both | Ephemeral ECDH + HKDF session key derivation |
| `0xC0` | SET_LAYOUT | App→Dongle | Set keyboard layout |
| `0xC1` | GET_INFO | App→Dongle | Query firmware version/layout |
| `0xD0/D1` | SEND_STRING | App→Dongle | Type UTF-8 text with MD5 verification |
| `0xE0` | RAW_KEY_TAP | App→Dongle | Fast raw HID key (requires C8 fast-mode) |
| `0xE1` | RAW_MOUSE_EVENT | App→Dongle | Mouse move/click/scroll |

Binary frame: `[OP u8][LEN u16 LE][PAYLOAD...]`
Encrypted (B3): `[0xB3][LEN u16 LE][seq_be16][clen_be16][cipher][mac16]`

## Security Model

- BLE link encryption + bonding (basic protection)
- Application MTLS layer: ECDHE P-256 + AES-CTR + HMAC-SHA256
- APPKEY retrieved via PBKDF2 challenge-response (never transmitted in clear)
- Host computer is **untrusted** — sees only standard USB HID keyboard/mouse

## KeePassDX Integration

- AIDL service (`OutputCredentialsService`) implements `IOutputCredentialsService.aidl`
- KP2A plugin: BroadcastReceivers handle entry action menus (Username/Password/User&Pass)
- Uses `BleHub.autoConnectForServices()` for silent background connection

## New Features (Recent)

- **Touchpad** (`TouchpadView.kt`): drag to move mouse, tap to click, two-finger scroll
- **Mouse commands**: `BleHub.sendRawMouseEvent()` (0xE1), `clickMouseLeft/Right/Middle()`, `scrollMouse()`
- **Video capture**: `VideoCaptureActivity.kt` with UVCCamera for UVC device preview