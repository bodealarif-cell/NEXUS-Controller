# Building

## Without a local computer
Push the repository to GitHub. `Actions → Android` produces `nexus-controller-debug-apk`; `Actions → Windows` produces `nexus-receiver-win-x64`. Workflows only trigger on their own paths; both can be started manually (workflow_dispatch). Open the finished run and download from *Artifacts*. A build is only "verified" when that run is green.

## Locally
* Protocol/core tests (no dependencies): `tests/run-kotlin-tests.sh` (needs `kotlinc` + JDK 17+), `dotnet run --project tests/dotnet/Nexus.Tests` (.NET 8 SDK).
* Android: JDK 17, Android SDK platform 34 + build-tools 34.0.0, Gradle 8.9 → `gradle -p android-controller :app:assembleDebug`. (No Gradle wrapper JAR is committed; CI installs Gradle explicitly. Run `gradle wrapper` once if you want one.)
* Windows: `dotnet publish windows-receiver/NexusReceiver -c Release -r win-x64 --self-contained false`.

## Release signing
Never commit keystores. The Android module reads `NEXUS_KEYSTORE_PATH`, `NEXUS_KEYSTORE_PASSWORD`, `NEXUS_KEY_ALIAS`, `NEXUS_KEY_PASSWORD` from the environment; when absent, release builds are unsigned.

Pinned: AGP 8.5.2, Kotlin 2.0.20, Gradle 8.9, Compose BOM 2024.09.00, compileSdk/targetSdk 34, JVM target 17, .NET 8.
