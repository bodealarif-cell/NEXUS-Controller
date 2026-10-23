# Implementation report

Date: 2026-10-23. Environment of the authoring session: Linux sandbox, JDK 21, .NET SDK 8.0.132, kotlinc 2.0.20, **no Android SDK, no Windows, no GitHub access, no physical devices, no NuGet/Maven access.**

## Automated tests actually executed
| Suite | Command | Result |
|---|---|---|
| Kotlin protocol + core | `tests/run-kotlin-tests.sh` | **61 passed, 0 failed** (3 consecutive runs, no flakiness) |
| C# protocol + session + receiver core | `dotnet run --project tests/dotnet/Nexus.Tests` | **31 passed, 0 failed** |

Covered: encode/decode round trip; cross-language byte-identical golden vector; clamping/NaN; malformed, truncated, oversized, bit-flipped and fuzzed packets (20 000 random inputs ×2 languages: none accepted); duplicate/out-of-order/wrap-around sequences; watchdog fail-safe; PIN handshake, wrong PIN, lockout, busy, version mismatch, source pinning; rate limiting; session timeout; release on disconnect/shutdown; stick/trigger/steering/tilt maths; button remapping; settings sanitising & import/export; XInput and HID report mapping; **ConnectionManager** connect, bounded reconnect, wrong-PIN no-retry, dead-link detection, neutral on user disconnect and `releaseAll`, adaptive traffic; **real UDP sockets on loopback** end to end.

## Implemented but NOT compiled or run here (needs CI / hardware)
* Android app (Compose UI, BLE GATT client, HID transport, permissions, sensors). Written against pinned versions; **never compiled** in this session. Expect to fix small compile errors on first CI run.
* Windows app (WinForms UI, BLE GATT server, UDP listener, ViGEm adapter). Only `Hub.cs` (receiver core) was compiled/tested. The rest requires the Windows Desktop SDK + NuGet.
* GitHub workflows: written, never executed. No build is claimed to succeed. No artifact links exist.
* Gradle project for shared tests (`shared-protocol/kotlin`): unexecuted (same tests pass via `kotlinc`).

## Feature status
| Feature | Status |
|---|---|
| Versioned protocol, auth handshake, fail-safe, tests | Implemented, tested |
| Wi-Fi (UDP) transport | Implemented; sender side tested over loopback; Windows listener untested on Windows |
| BLE transport (Android client ↔ Windows GATT server) | Implemented, **untested**; Windows peripheral role depends on adapter |
| Gamepad & Racing UI, tilt steering, calibration, settings, profiles | Implemented, uncompiled |
| Windows virtual Xbox 360 pad | Implemented via ViGEm (**retired project**), untested; diagnostics mode independent |
| Bluetooth HID gamepad (phone → tablet) | **Experimental**, untested, phone-dependent |
| Android receiver app speaking NEXUS protocol | **Not implemented** (HID mode is the documented path) |
| System-wide input injection on Android | **Unsupported by design**; accessibility injection deliberately not used |
| Measured CPU/memory/battery/latency on devices | **None recorded** (RTT is measured live in-app; no benchmark numbers exist) |

## Known limitations / remaining work
1. First CI run: fix any compile errors; add Gradle wrapper if wanted.
2. Hardware validation of `docs/TEST_PLAN.md`; record real latency/CPU/battery numbers here.
3. Receiver-side haptic/rumble feedback is not implemented.
4. Wi-Fi discovery is manual (enter IP); no encryption.
5. Android NEXUS receiver app; per-model HID axis mapping tables.
6. Replace ViGEm if a maintained alternative is chosen (the `IVirtualPad` interface isolates it).
7. Tilt direction sign is unverified on devices (use *Invert steering* + calibration).
