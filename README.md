# MeshCore TAK Bridge

An Android app that connects a phone to a [MeshCore](https://docs.meshcore.io/) companion radio over Bluetooth and presents the mesh to [ATAK](https://tak.gov/) as a local TAK server. Mesh nodes appear as map markers, and mesh channels appear as ordinary ATAK chat rooms.

It is the phone-side counterpart to the TAK server in the [Colorado Mesh desktop client](https://github.com/Colorado-Mesh/mesh-client). The full design, goals and plan are in [docs/DESIGN.md](docs/DESIGN.md).

## Status

**Early development. There is no radio support yet.**

What exists today is the ATAK half, built to answer one question first: will ATAK connect to a plain TCP server on the same phone, show its markers, and exchange chat with it? The app serves three fixed demo markers in central Denver and one chat room that echoes what you send.

| Milestone | State |
| --- | --- |
| 0. Repository, build, tests | Written; the first CI build has not run yet |
| 1. ATAK spike: local TAK server with demo data | Built, awaiting a test on a device |
| 2. Bluetooth spike | Not started |
| 3 to 7 | See the [development plan](docs/DESIGN.md#development-plan) |

Not yet tested on a device: everything in the Android app. The `cot` and `takserver` modules have automated tests.

## Try it

1. Download the debug APK from the latest run of the **CI** workflow under the repository's Actions tab (artifact `meshcore-tak-bridge-debug-apk`), or build it yourself (below).
2. Install it on a phone that has ATAK, open it, and tap **Start bridge**.
3. In ATAK, open Settings, Network Preferences, TAK Servers, and add a server with address `127.0.0.1`, port `8087`, protocol TCP. Leave SSL/TLS off.
4. Three demo markers appear in central Denver, and a chat room called "Bridge Test" appears with a welcome message.
5. Reply in that room. The bridge answers "Bridge heard: ..." and shows the message in its log.

Each CI build is signed with a different temporary debug key, so uninstall the previous build before installing a new one.

## Build and test

Requires JDK 17 (Gradle fetches it if missing) and, for the app, the Android SDK.

```bash
./gradlew test            # unit tests for the plain-Kotlin modules
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

## Layout

| Module | Contents |
| --- | --- |
| `cot` | Builds and parses the Cursor on Target events ATAK exchanges: markers, GeoChat, ping and pong, and the stream framer. Plain Kotlin. |
| `takserver` | The loopback TCP server: one client, keepalive replies, inbound chat, rate limit. Plain Kotlin. |
| `app` | The Android app: a foreground service that runs the server, and a status screen. |

The `protocol`, `bridge` and `ble` modules described in the design arrive with the radio work.

## Design rules

These come from problems seen in practice, and changes should keep to them.

- Never write to the radio's configuration. No channels are created or altered.
- One Bluetooth operation at a time, and every operation has a timeout.
- Use every channel the radio reports.
- Send plain MeshCore channel text, readable by any MeshCore app.
- Listen on the loopback address only.
- Events sent to ATAK are bare `<event>` elements with no XML declaration.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

This project is not affiliated with MeshCore, the TAK Product Center, or TAK-MESHCORE. It contains no code from TAK-MESHCORE.
