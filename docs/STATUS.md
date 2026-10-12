# Project status

Where MeshCore TAK Bridge stands, for anyone picking the work up. Last updated 2026-10-12.

## What works (tested on a Pixel 9 Pro and a Galaxy Tab Active 3)

- **TAK server for ATAK** on `127.0.0.1:8087`, plain TCP, loopback only, one client.
- **Bluetooth link to a MeshCore companion radio**, with an opening handshake that reads the radio's name, settings, firmware and channel capacity.
  - Every step is queued with a time limit.
  - The packet size request may go unanswered without stalling the link.
- **Remembers the chosen radio** and reconnects after a drop, retrying after 3, 5, 10, 20, 30, then every 60 seconds.
  - The Tab Active 3 held the link overnight with no power-saving problems.
- **Nodes on the map:** contacts that share a position become markers.
  - Companions are team members in a chosen team colour (default Cyan).
  - Repeaters and room servers are installations, each with an optional icon-set icon (GeoOps presets).
  - Sensors use the sensor symbol.
  - Filters by node type and by last-heard age (default: hide nodes not heard for 7 days); dropped nodes are deleted from ATAK at once.
- **Every channel the radio reports is a contact in ATAK.**
  - Names: `#name` for hashtag channels, a lock for private channels, plain `Public` for the default channel.
  - The kind is worked out by comparing the channel key with the Public key and the key a hashtag name gives; the key is never stored.
  - Channel text arrives as messages from that contact; a message sent to it goes out on that channel. Messages received before ATAK connects are held (up to 50).
- **Never writes to the radio's settings, channels, contacts or clock.**

## Built, not yet tested on a device

- **Direct messages** between the ATAK user and companion nodes, both ways.
  - Companions on the map are reachable ATAK contacts. A companion with no position becomes a contact at 0, 0 once a direct message has passed with it.
  - Only companions: a repeater or room server reads plain text from its admin as a command, so chat addressed to one is never sent.
  - A sent message waits for the recipient's acknowledgement and is tried up to 3 times with its first timestamp. The path is never reset, since that would write to the radio's contacts. No acknowledgement after 3 tries puts a note in that conversation; so does a refusal.
  - Up to 160 bytes. Held, like channel text, until ATAK connects.
  - A retried copy of a received message (same sender, timestamp and text) is dropped and logged.
- **Echo check for channels.** A channel message received with the same channel and text as one just sent from ATAK (`"<radio name>: <text>"`, within 15 minutes) is dropped and logged as `Echo check: …`.
  - None is expected. The firmware marks its own flooded packets as seen, so a repeater's rebroadcast is ignored, and sending never produces a received frame.

### Device test for these

1. Pair the bridge's radio and a second radio running the standard MeshCore app. Put both near a repeater, and the second one far enough away that its traffic goes through the repeater if possible.
2. **Direct, mesh to ATAK:** from the second radio, message the bridge's radio. It should appear in ATAK from that node's contact. If the node has no position, its contact should appear in ATAK's contact list at that moment.
3. **Direct, ATAK to mesh:** reply in that conversation. The second radio should show it, and the bridge log should show `delivered`. Switch the second radio off and send again: after about three tries there should be a "Not confirmed" note in the conversation.
4. **A repeater is safe:** a repeater on the map should have no chat option in ATAK.
5. **Echo:** send several messages on Public and on a hashtag channel from ATAK. Each should show once in ATAK. Look in the bridge's log for `Echo check:`. If it never appears, echoes do not happen, and that result should be recorded here.

## Design decisions learned on devices

- **Channels are contacts, not GeoChat rooms.** ATAK only sends chat to a contact it can reach (`<contact endpoint="*:-1:stcp">`), and it addresses a reply to that contact, not to the room it was read in. With rooms, the bridge could not tell which channel a reply was for.
- **Bind to 127.0.0.1 explicitly.** `InetAddress.getLoopbackAddress()` is `::1` on Android, and ATAK refused to connect to it.
- **Events are bare `<event>` elements** with no XML declaration, and pings get `t-x-c-t-r` pongs.

## How work is delivered

- Pushes to `main` build in GitHub Actions. The debug APK is the `meshcore-tak-bridge-debug-apk` artifact on each run.
- The `cot`, `takserver` and `protocol` modules are plain Kotlin with JUnit tests. `app` is the Android app, built in code with no layout resources.

## Still to do

1. Run the device test above for direct messages and the echo check, and record the results.
2. A signed release APK and a settled application ID (currently `io.github.robertmtalbot.takbridge`).
3. Possibly: ATAK delivery receipts (`b-t-f-d`) for acknowledged direct messages, so ATAK shows them as delivered; and room server posts, which arrive as signed messages and are skipped for now.
4. Later: radio battery in ATAK, and moving the repository to Colorado Mesh if they agree.

In Claude cloud sessions, `./gradlew` cannot fetch Gradle or dependencies. The plain modules' tests can still be run with the Kotlin compiler and JUnit bundled in a local Gradle install. The app is only compiled in CI.

## Related work in Colorado-Mesh/mesh-client

- PR #1253 (`fix/tak-chat-contacts` on RobertMTalbot/mesh-client) applies the channels-as-contacts finding to mesh-client's TAK chat bridge.
  - CodeRabbit's findings and a main-bundle size failure have been addressed.
  - Not yet tested against ATAK; waiting on a maintainer review.
  - mesh-client's main bundle is about 600 bytes under its 640 KiB budget.
