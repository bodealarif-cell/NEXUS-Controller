# NEXUS Controller

Turn an Android phone into a wireless game controller (Gamepad and Racing layouts) for a Windows PC, with an experimental Bluetooth-HID route to Android tablets.

> **Honest status:** the shared protocol, session/security logic, settings, connection/reconnect logic, fail-safe and the receiver core are implemented **and covered by automated tests that ran green (Kotlin 61/61, C# 31/31)**. The Android UI, Bluetooth code, Windows UI/BLE/ViGEm code and CI workflows are written but **have not been compiled or run yet**; no APK/EXE is claimed to exist. See [docs/IMPLEMENTATION_REPORT.md](docs/IMPLEMENTATION_REPORT.md).

## Architecture
```
 Android phone (Kotlin/Compose)                                 Windows PC (C#/.NET 8)
┌────────────────────────────┐                         ┌──────────────────────────────┐
│ Touch UI / tilt sensor     │                         │ BLE GATT server │ UDP listener│
│  → RawInput                │   NEXUS protocol v1     │        ↓            ↓        │
│ StateBuilder (deadzone,    │  (33-byte INPUT, PIN    │      ReceiverHub (session,   │
│  curves, calibration)      │   handshake, PING/PONG) │      rate-limit, fail-safe)  │
│ ConnectionManager          │ ───────────────────────►│        ↓             ↓       │
│  (reconnect, heartbeat)    │  BLE GATT  or  UDP      │  Diagnostics    IVirtualPad  │
│ Transport: BLE | UDP | HID │                         │  (always on)   (ViGEm, opt.) │
└────────────────────────────┘                         └──────────────────────────────┘
        └── experimental: Bluetooth HID gamepad ──► any host that accepts a BT gamepad (e.g. Android tablet)
```

## Features
Gamepad: 2 analog sticks (radial dead zone, curve, sensitivity, inversion), D-pad, 4 remappable face buttons, LB/RB, analog LT/RT, Start/Select, L3/R3, multi-touch, size/opacity/mirror layout. Racing: wheel or tilt steering with dead zone/sensitivity/return speed/calibration, gas/brake/handbrake, manual/auto gears, nitro. Haptics, 3 profiles, export/import, performance mode, 30–120 Hz update rate, measured RTT, bounded-backoff reconnect, fail-safe release everywhere (app pause, disconnect, timeout).

## Repository layout
```
android-controller/   Android app (Compose)       shared-protocol/kotlin   protocol + pure-Kotlin core (also compiled into the app)
windows-receiver/     WinForms receiver           shared-protocol/dotnet   C# protocol library
tests/                C# tests, Kotlin runner script, test plan             docs/   protocol, build, Windows, troubleshooting, report
.github/workflows/    android.yml, windows.yml
```

## Quick start
* **Get binaries:** push to GitHub → Actions → *Android* / *Windows* → download artifacts (only valid when the run is green). See [docs/BUILD.md](docs/BUILD.md).
* **Windows:** [docs/WINDOWS.md](docs/WINDOWS.md) (install, PIN, ViGEm notes, firewall).
* **Pairing (Bluetooth LE):** Windows app → tick *Bluetooth LE* → phone: *Bluetooth LE* → Scan → pick receiver → enter PIN → Connect. The OS may ask to pair; the PIN is the app-level check.
* **Wi-Fi:** tick *Wi-Fi* on Windows, enter the shown IP on the phone, enter the PIN.

## Platform limitations (read this)
1. Bluetooth/Wi-Fi *transport* between NEXUS apps ≠ 2. Bluetooth *HID gamepad emulation* ≠ 3. *system-wide input injection*. Only 1 is implemented for Windows; 2 is experimental; 3 is impossible for ordinary Android apps and is not attempted (no Accessibility tricks).
2. BLE messages never automatically become gamepad input in Android games.
3. ViGEmBus (virtual pad on Windows) is a **retired** project; optional, community-risk. Games with anti-cheat may reject virtual controllers. Universal compatibility is not claimed.

## Security notes
No listener is on by default; Wi-Fi/BLE receivers are opt-in. PIN-authenticated session (HMAC, constant-time compare, 5-try lockout), source pinning, packet validation and rate limits. **No encryption**; use trusted networks. The PIN is never stored or logged.

## Troubleshooting · Tests · Contributing · License
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) · [docs/TEST_PLAN.md](docs/TEST_PLAN.md) (all hardware tests currently NOT RUN) · Contributions: open a PR, keep `tests/run-kotlin-tests.sh` and the C# tests green, never commit keystores. License: MIT ([LICENSE](LICENSE)).
