# NEXUS protocol v1

Little-endian, transport-independent. The same bytes travel over BLE GATT writes/notifications and UDP datagrams.
Reference implementations: `shared-protocol/kotlin/.../Protocol.kt` and `shared-protocol/dotnet/Nexus.Protocol/Protocol.cs`.
A golden test vector (`tests/`) proves both produce identical bytes.

Common header: `0:'N'(0x4E) 1:'X'(0x58) 2:version 3:type` · last byte of every packet = CRC-8 (poly 0x07, init 0) over all preceding bytes.
Max accepted packet: 64 bytes; anything else is dropped before parsing.

| Type | Id | Size | Direction | Body |
|---|---|---|---|---|
| INPUT | 1 | 33 | sender→receiver | see below |
| HELLO | 2 | 15 | sender→receiver | minVer, maxVer, clientNonce[8] |
| CHALLENGE | 3 | 14 | receiver→sender | chosenVer, serverNonce[8] |
| AUTH | 4 | 21 | sender→receiver | HMAC-SHA256(PIN, clientNonce‖serverNonce) truncated to 16 bytes |
| ACCEPT | 5 | 6 | receiver→sender | chosenVer |
| PING / PONG | 6 / 7 | 9 | both | tick u32 (sender's monotonic ms, echoed unchanged) |
| REJECT | 8 | 6 | receiver→sender | reason: 1 version, 2 bad PIN, 3 busy, 4 locked out |

## INPUT layout
`4:seq u16 · 6:tick u32 · 10:buttons u32 · 14:dpad u8 · 15:flags u8 · 16:LX i16 · 18:LY i16 · 20:RX i16 · 22:RY i16 · 24:LT u8 · 25:RT u8 · 26:steering i16 · 28:accel u8 · 29:brake u8 · 30:handbrake u8 · 31:gear u8 · 32:crc`

* Axes: i16, ±32767 = ±1.0 (−32768 decodes to −1.0); **+Y is up**. Triggers/pedals: u8, 255 = 1.0.
* Buttons (bit): A0 B1 X2 Y3 LB4 RB5 Select6 Start7 L3 8 R3 9 Nitro10 Guide11. Higher bits are masked.
* D-pad bits: Up1 Down2 Left4 Right8. Reserved bits (0xF0) → packet rejected. Opposing directions cancel.
* Flags: bit0 racing mode, bit1 calibrating. Gear: bit0 up, bit1 down, bit2 manual transmission; reserved bits → rejected.

## Validation (receiver)
Length, magic, type, CRC, version range, reserved bits, then 16-bit serial-number sequence filter (duplicates and out-of-order dropped, wrap-around handled), per-second rate limit (600 pkt/s), and the session/source check below.

## Session and security
1. Sender → HELLO (versions it supports). Receiver picks `min(senderMax, ownMax)` or REJECT(version).
2. Receiver → CHALLENGE; sender proves knowledge of the on-screen 6-digit PIN with AUTH. Compared in constant time.
3. After 5 wrong PINs the receiver locks out for 30 s. After ACCEPT the session is pinned to one source (BLE device id / UDP ip:port); input from any other source is ignored; other devices get REJECT(busy).
4. Session ends after 5 s without valid traffic. A separate fail-safe (default 500 ms, configurable) neutralises all controls earlier without ending the session.

**Not provided:** encryption. BLE relies on the OS link; UDP is plaintext and LAN-only. The PIN prevents *accidental/unauthorised control*, not eavesdropping.

## Timing
Sender emits on change (≤ configured rate: 30/60/90/120 Hz), refreshes held input every 100 ms and idle heartbeats every 200 ms. PING every 1 s; RTT = now − echoed tick (measured, not estimated; excludes receiver processing and game latency). No PONG for 4 s ⇒ link considered dead ⇒ bounded exponential backoff reconnect (0.5 s … 15 s, 8 attempts).

## BLE GATT
Service `7d0e0001-4e58-4d1c-9a3b-5a0e6a9c0001`; INPUT characteristic `…0002` (Write / Write-without-response); NOTIFY characteristic `…0003` (Notify). MTU ≥ 36 required (sender requests 64 and refuses smaller).
