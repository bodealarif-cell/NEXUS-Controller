# Windows receiver

## Requirements
Windows 10 1809+ / Windows 11 x64, .NET 8 **Desktop Runtime** (https://dotnet.microsoft.com/download/dotnet/8.0). For Bluetooth: a BLE 4.0+ adapter that supports the **peripheral role** (the app tells you if it does not; use Wi-Fi mode then).

## Install / uninstall
Download `NexusReceiver-win-x64.zip` from the Windows workflow artifacts, unzip anywhere, run `NexusReceiver.exe`. Uninstall = delete the folder (settings live in `%APPDATA%\NexusReceiver\settings.json`). Nothing is installed silently.

## Use
1. Start the app; note the 6-digit PIN. Tick *Bluetooth LE* (or *Wi-Fi*, which opens UDP port 47810 — allow it in the Windows Firewall prompt; keep it off on untrusted networks).
2. On the phone choose the same transport, scan/enter IP, type the PIN, Connect.
3. The diagnostics panel shows live inputs even with no virtual controller.

## Virtual controller (optional)
The app can create a virtual Xbox 360 pad through **ViGEmBus** via the `Nefarius.ViGEm.Client` NuGet package. **Status checked on 2026-10-23: the ViGEmBus project is retired and archived (Nov 2023, no further updates).** It still works for many users, is BSD-3-Clause licensed and its installer is distributed by Nefarius Software Solutions. Because it is unmaintained: keep it optional, install only from the official source, and expect possible incompatibility with future Windows updates (kernel driver; Secure Boot/HVCI configurations can block it). The app never installs it; installing needs administrator rights and is done by you.
Games with anti-cheat or that reject virtual devices will not work; no universal compatibility is claimed. Test with a controller tester (e.g. https://hardwaretester.com/gamepad or `joy.cpl`).

Racing mapping: steering→left stick X, gas→RT, brake→LT, gear up/down→RB/LB, handbrake→X, nitro→Y.
