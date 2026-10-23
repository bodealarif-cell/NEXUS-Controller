# Manual test plan (hardware)
Status legend: **NOT RUN** until a person executes it on real devices. Nothing below has been run by the authors of this repository's initial commit.

| # | Area | Steps | Expected | Status |
|---|---|---|---|---|
| 1 | Android UI | Install APK; open Gamepad & Racing; touch every control | Pressed state shows; values return to neutral on release | NOT RUN |
| 2 | BT permissions | Fresh install → Connect (BLE); deny, then allow, then deny permanently | Clear guidance each time; "Open settings" appears after permanent denial | NOT RUN |
| 3 | Android→Windows | Windows app: tick Bluetooth LE; phone: Scan, select, enter PIN, Connect | State Connected, RTT shown, diagnostics panel moves | NOT RUN |
| 4 | Virtual controller | Install ViGEmBus; tick *Enable virtual controller*; open `joy.cpl` | "Xbox 360 Controller" listed and responds | NOT RUN |
| 5 | Controller utility | Use hardwaretester.com/gamepad | All sticks/triggers/buttons map as documented | NOT RUN |
| 6 | Android→Android | Phone HID mode; pair from tablet | Tablet shows a gamepad (device-dependent); record result per model | NOT RUN |
| 7 | Orientation | Rotate on menu/controller; split-screen | Menu portrait, controller landscape, no crash/stuck input | NOT RUN |
| 8 | Rapid multi-touch | Both sticks + 3 buttons + triggers simultaneously | Independent, no dropped holds | NOT RUN |
| 9 | Disconnect while held | Hold A+RT, pull Windows PC off Wi-Fi / kill Windows app | Controls neutral within fail-safe timeout; no stuck button on PC | NOT RUN |
| 10 | Bluetooth off/on | Toggle Bluetooth during session | "Reconnecting…" with backoff; recovers or FAILED with guidance | NOT RUN |
| 11 | Latency/CPU/battery | Record RTT, CPU%, battery % per 10 min on named devices | Fill in `docs/IMPLEMENTATION_REPORT.md` table | NOT RUN |
