# Troubleshooting

| Problem | Likely cause / fix |
|---|---|
| **Android SDK setup fails** (`SDK location not found`) | Set `ANDROID_HOME` or `sdk.dir` in `android-controller/local.properties`; install `platforms;android-34`, `build-tools;34.0.0` with `sdkmanager`. In CI this is done by `setup-android`. |
| **Gradle build fails** | Use Gradle 8.9 and JDK 17 (not 8/21 for AGP 8.5). Run with `--stacktrace`. Delete `~/.gradle/caches` if dependency resolution is corrupt. |
| **JVM-target mismatch** (`Inconsistent JVM-target compatibility`) | `compileOptions` and `kotlinOptions.jvmTarget` are both 17 in `app/build.gradle.kts`; keep them equal and run Gradle on JDK 17. |
| **Missing project files** | The build needs the real directory tree, not a ZIP. Extract/clone fully; `shared-protocol/kotlin` must exist next to `android-controller` (the app compiles it in). CI validates paths and prints which file is missing. |
| **Bluetooth pairing/connection fails** | Check the permission card in the app (Android 12+: Nearby devices). Bluetooth on; receiver app running with *Bluetooth LE* ticked; adapter supports peripheral role; "Receiver allows only N bytes" means the adapter negotiated a tiny MTU—use Wi-Fi mode. |
| **"Wrong PIN" / "Too many wrong PINs"** | PIN changes on *New PIN* and at every app start. After 5 failures the receiver locks for 30 s. |
| **"Receiver is already connected to another device"** | Press *Disconnect device* in the Windows app, or wait 5 s after the other phone stops. |
| **Unsupported HID functionality** | HID mode needs Android 9+ *and* a Bluetooth stack that implements the HID Device profile; many phones do not. Use BLE/Wi-Fi with the Windows receiver instead. |
| **Windows virtual controller doesn't appear** | ViGEmBus not installed (retired project; install from its official release), driver blocked by Secure Boot/HVCI, or you haven't ticked *Enable virtual controller*. Check `joy.cpl`. |
| **Game doesn't recognise the controller** | Game may reject virtual/Xbox-360-emulated pads, use anti-cheat, or need the app started before the game. Verify in a controller tester first. |
| **Stuck/neutral inputs after Wi-Fi drop** | Expected fail-safe: all controls release after the timeout (default 500 ms); the phone reconnects with backoff and re-authenticates. |
