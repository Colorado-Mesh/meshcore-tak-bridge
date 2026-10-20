# Project status

Where MeshCore TAK Bridge stands, for anyone picking the work up. Last updated 2026-10-19.

**Repository:** moved from RobertMTalbot/meshcore-tak-bridge to [Colorado-Mesh/meshcore-tak-bridge](https://github.com/Colorado-Mesh/meshcore-tak-bridge) on 2026-10-13. GitHub redirects the old address, but clones should point their remote at the new one.

**Latest release: [0.1.1](https://github.com/Colorado-Mesh/meshcore-tak-bridge/releases/tag/v0.1.1), published 17 October 2026.** Version code 2, tag `v0.1.1` on commit `2b45e8d`, built and signed by the release workflow (run 39063380535). It installs over 0.1.0 and keeps the radio and map settings. Signed with the same certificate as 0.1.0 (SHA-256 `f59a5a05f3924b8e0dd2cc24aeb4f11405875d966d68bc25c2bbae9d38a7e554`), read independently from the published APK, whose checksum also matches. Since 0.1.0:
- Replies on a channel, `@name` and `@@` (checked on a device, 2026-10-17).
- An answer for ATAK's mission-invitations request, so "Failed to get invitations from all … (404)" no longer appears at connect (checked on a device, 2026-10-17).
- The setup text on the bridge's screen: no more line breaks mid-sentence, and it now covers direct messages and replies.
- The node-type checkboxes are tinted (Colorado Mesh yellow when ticked, light grey when not); they were hard to see on the dark background.

**Earlier releases:**
- [0.1.0](https://github.com/Colorado-Mesh/meshcore-tak-bridge/releases/tag/v0.1.0), 14 October 2026: version code 1, tag `v0.1.0` on `48a2501` (run 38587020807). Its app code was that of CI build #43 (`71b00f3`), run on the Tab Active 3 from the evening of 13 October with no problems. The first signed release; the certificate was checked against the key holder's own copy. Before it, the last overnight burn-in was build #31 (12–13 October).

## What works (tested on a Pixel 9 Pro and a Galaxy Tab Active 3)

- **TAK server for ATAK** on `127.0.0.1:8087`, plain TCP, loopback only, one client.
- **Bluetooth link to a MeshCore companion radio**, with an opening handshake that reads the radio's name, settings, firmware and channel capacity.
  - Every step is queued with a time limit.
  - The packet size request may go unanswered without stalling the link.
  - **A radio connects on the first tap** (checked on build #43, 13 October). See "Design decisions learned on devices" for why it used to need several.
  - Connecting waits a moment after a scan and tries up to 3 times, 12 s each, since Android often fails a first try with status 133. Tapping the radio already being connected does not start over, and the scan list is cleared once a connection is under way.
- **Remembers the chosen radio** and reconnects after a drop, retrying after 3, 5, 10, 20, 30, then every 60 seconds.
  - The Tab Active 3 held the link overnight with no power-saving problems, on an early build and again on build #31.
- **Answers ATAK's connect-time web requests** on `127.0.0.1:8080` (server contacts, groups, feeds, mission invitations, profiles) with empty replies, so ATAK shows no server errors. Only the two version requests stay unanswered, by design.
  - Mission invitations were added 2026-10-16 after 0.1.0 showed "Failed to get invitations from all … (404)" for a while after ATAK connected. Checked on a device, 2026-10-17.
- **Nodes on the map:** contacts that share a position become markers.
  - Companions are team members in a chosen team colour (default Cyan).
  - Repeaters and room servers are installations, each with an optional icon-set icon (GeoOps presets).
  - Sensors use the sensor symbol.
  - Filters by node type and by last-heard age (default: hide nodes not heard for 7 days); dropped nodes are deleted from ATAK at once.
- **Every channel the radio reports is a contact in ATAK.**
  - Names: every channel starts with `#` so they group together at the top of ATAK's contact list: `#Public`, `#name` for hashtag channels, `#🔒 name` for private channels (changed 2026-10-12; on a device, earlier names put private channels among the nodes).
  - The kind is worked out by comparing the channel key with the Public key and the key a hashtag name gives; the key is never stored.
  - Channel text arrives as messages from that contact; a message sent to it goes out on that channel. Messages received before ATAK connects are held (up to 50).
- **Direct messages** between the ATAK user and companion nodes, both ways, with acknowledgements logged as delivered (tested 2026-10-12).
  - Companions on the map are reachable ATAK contacts. A companion with no position, or one the filters hide, becomes a contact at 0, 0 once a direct message has passed with it. Checked on a device: with companions filtered off the map, a direct message from one brought its contact back and a reply got through.
  - Only companions: a repeater or room server reads plain text from its admin as a command, so chat addressed to one is never sent. Checked on a device: a repeater on the map has no chat option in ATAK.
  - A sent message waits for the recipient's acknowledgement and is tried up to 3 times with its first timestamp. The path is never reset, since that would write to the radio's contacts. No acknowledgement after 3 tries puts a note in that conversation; so does a refusal. Checked on a device: with the other radio off, the "Not confirmed" note appears.
  - Up to 160 bytes. Held, like channel text, until ATAK connects.
  - A retried copy of a received message (same sender, timestamp and text) is dropped and logged.
  - **Delivery receipts** (checked on a device, 2026-10-19, build #73): when the mesh acknowledges a direct message sent from ATAK, the bridge sends ATAK a `b-t-f-d` receipt for it, so ATAK marks the message delivered. Built like ATAK's own receipts (`GeoChatService.sendStatusMessage` in ATAK's source): the receipt's event uid is ATAK's id for the message, read from the chat ATAK sent. Channel messages get none, since MeshCore channel messages are flooded without acknowledgement. In ATAK it shows as a check mark beside the time of the latest message sent in that conversation, not as a new message or notification.
- **Replies on a channel** (checked on a device, 2026-10-17). MeshCore has no reply-to-message feature; its apps start a reply with `@[Name]`, which the firmware sends like any text (confirmed by use, 2026-10-12). ATAK's chat has no reply action, so a reply is typed in a channel's chat:
  - `@silver are you on?` goes out as `@[Silver Eagle] are you on?` when exactly one person heard on that channel in the last 24 hours has a name starting "silver". Case, spaces, emoji and punctuation are ignored, so `@w0rmt` finds `🔥 W0RMT 03`. An exact match wins over longer names it starts; `@"Two Words"` matches a whole name.
  - `@@ thanks` replies to whoever spoke last on that channel.
  - Several matches: nothing is sent, and a note in the chat lists them (up to 5). `@@` on a quiet channel: nothing is sent, with a note. No match: the text goes out exactly as typed, so a message that just starts with "@" is not changed by surprise. Text already in the `@[Name]` form is left alone.
  - Senders are learned from channel messages as they arrive (up to 50 per channel, by this phone's clock), forgotten when another radio is chosen, and never include this radio's own name. The logic is `ChannelReplies` in the `protocol` module, with tests.
- **Radio battery** (checked on a device, 2026-10-19, build #73: on the screen and in the notification; the low-battery alert has not yet been seen on a device). MeshCore reports the battery only as a voltage, when asked, so the bridge asks once start-up is done and every 5 minutes after (`CMD_GET_BATTERY`, 0x14).
  - The bridge's screen shows it under the radio, for example "Battery: about 72% (3.92 V)", and so does its notification, which can be pulled down over ATAK.
  - The percentage is estimated from a typical lithium cell's discharge curve (`RadioBattery` in the `protocol` module). It reads high while the radio charges and a little low while it transmits.
  - It differs from the MeshCore app's. At 3.88 V the bridge showed 69% and the MeshCore app 73% (2026-10-19). The MeshCore app appears to scale evenly from 3.0 V to 4.2 V, which fits that reading exactly. Kept the curve (decided 2026-10-19): it reads lower as the battery runs down (at 3.5 V, about 20% against 42%), which is closer to the charge really left, so the alerts come while there is still time to charge.
  - As the battery falls to 20%, and again to 10%, a heads-up alert ("Radio battery low") appears over whatever app is open. Each level alerts once, and again only after the battery has climbed 5 points above it, so a wobbling reading does not alert repeatedly.
  - A radio that reports 0 V cannot measure its battery: the screen says so and no alert is raised. Firmware that refuses the request is not asked again.
  - Nothing new appears in ATAK itself (decided 2026-10-19): the radio is not a marker, and a note in every channel's chat would mark them all unread.
  - The log notes the battery at connect and when it has moved 5 points.
- **Tracking** (checked on a device, 2026-10-19, build #77: markers moved with the tracked radio, and ATAK's breadcrumbs drew its track). For companions whose owners have agreed to share their location.
  - Up to 10 companions are chosen on the bridge's screen. While ATAK is connected, each is asked in turn for its position with MeshCore's telemetry request, about every 5 minutes (2, 5, 10, 15 or 30 can be chosen), one request at a time.
  - An answer with a position moves the node's marker in ATAK, which can draw its track. A tracked node shows even if the filters would hide it.
  - The other radio needs a GPS that is on, and its owner must allow base and location telemetry for everyone or for this radio. It sends nothing back otherwise, so "no answer" may mean not allowed or out of range. The screen says which kind of answer each node gave.
  - **A stale contact gives "no answer" every time** (found 2026-10-19). Like a direct message, a request is encrypted between the two radios' own keys, and a node only answers contacts it knows; with an old contact for this radio, the tracked radio ignores every request. An advert from each radio fixed it. The "no answer" line and the Tracking help on the screen now say so.
  - The firmware also answers only when its "base" telemetry permission is allowed, not just location; worth checking first when a node never answers.
  - Decided 2026-10-19: ask only nodes chosen in advance, and leave drawing the track to ATAK. Trails built from adverts were set aside (companions advert only when their owner chooses), and sharing this phone's position was set aside too (it would mean writing the radio's advert location).
- **No channel echoes** (checked 2026-10-12). A channel message sent from ATAK did not come back: the `Echo check:` log line never appeared. This matches the firmware, which marks its own flooded packets as seen. The guard stays in place: a received channel message matching one just sent from ATAK (`"<radio name>: <text>"`, same channel, within 15 minutes) is dropped and logged.
- **Never writes to the radio's settings, channels, contacts or clock.**
- **Colorado Mesh branding** (checked on a device, 13 October):
  - App icon: the Colorado Mesh mark with its arc closed into a map crosshair, with a themed-icon version and a matching status bar icon. No ATAK or TAK artwork. Drawn by `docs/icon/make_icon.py`.
  - A navy header with the icon, name, "A Colorado Mesh project" and the version.
  - An About section with the licence and buttons for the source code and coloradomesh.org.

## Design decisions learned on devices

- **Channels are contacts, not GeoChat rooms.** ATAK only sends chat to a contact it can reach (`<contact endpoint="*:-1:stcp">`), and it addresses a reply to that contact, not to the room it was read in. With rooms, the bridge could not tell which channel a reply was for.
- **Bind to 127.0.0.1 explicitly.** `InetAddress.getLoopbackAddress()` is `::1` on Android, and ATAK refused to connect to it.
- **Events are bare `<event>` elements** with no XML declaration, and pings get `t-x-c-t-r` pongs.
- **Stale contacts break direct messages and tracking silently, in both directions.** Channels share a key, so they keep working. A direct message is encrypted between the two nodes' own keys, so if either radio holds an old contact for the other, nothing gets through. The signs in the log are that every try is "flooded" (no path is ever learned) and no "Mesh, direct from" line appears. A flood advert from each radio fixed it in the first test, and again when tracking got no answers (19 October). The bridge itself does not advert, since that is a radio action the user should choose.
- **Android silently ignores frequent Bluetooth scans.** An app that starts more than five scans in 30 seconds gets scans that report nothing, with no error. Combined with a scan button that gave no feedback, this made it seem to need two or three taps. The button now shows that a scan is running, and a second tap stops the scan instead of restarting it. The scanner also counts its starts and says how long to wait.
- **A list redrawn while it is being tapped loses the tap.** During a scan the radio's advert arrives several times a second, and the app redrew the whole list of radios for each one. A tap is a press and a release; when the list was redrawn in between, the button pressed was gone, so nothing happened, and a radio needed several taps. Each radio now keeps one button for the whole scan and only its text changes. Retrying Bluetooth connections (status 133) did not fix this on its own: build #41 had the retries and still lost taps; build #43 fixed the list.

## How work is delivered

- Changes go into `main` through pull requests. Each pull request runs CI (tests and a debug build) and CodeQL, and gets a CodeRabbit review (from 2026-10-18; settings in `.coderabbit.yaml`, which spells out the design rules for it). Merge once they pass.
- Every push to `main` builds again. The debug APK is the `meshcore-tak-bridge-debug-apk` artifact on each run, and the build number in this file is the CI run number.
- A newer push to `main` cancels a CI run still going on an older one, so merging two pull requests in quick succession leaves one cancelled run and one complete build with both.
- Releases: pushing a `v*` tag runs the release workflow, which waits for a maintainer's approval, then builds, signs and drafts a GitHub Release. [RELEASING.md](RELEASING.md) has the procedure; Bud holds the signing key (decided 2026-10-14).
- Each CI build is signed with a new temporary debug key, so a debug build will not install over a different one: uninstall first. Signed releases share one key, so each installs over the last.
- CI and the release workflow use action versions that run on Node 24 (`upload-artifact` v7, `setup-gradle` v6 with its open-source cache). Every action is pinned to a full commit rather than a tag, as CodeQL asks, since the release job can read the signing key; Dependabot proposes updates monthly as pull requests.
- The `cot`, `takserver` and `protocol` modules are plain Kotlin with JUnit tests. `app` is the Android app, built in code; its only resources are the icons.

## Still to do

1. Possibly: room server posts, which arrive as signed messages and are skipped for now.
2. **Replies, next steps** (see "Replies on a channel" above): tidy received `@[Name]` to `@Name` in ATAK, and possibly mark a reply addressed to this radio's name so it stands out on a busy channel. Per-person reply contacts in ATAK were considered and set aside (2026-10-17): too much clutter in the contact list.
3. Housekeeping: the release run notes that Gradle 8.11.1 is out of date. Update the wrapper and the Android Gradle plugin together, in their own pull request, and check a debug build on a device.

In Claude cloud sessions, `./gradlew` cannot fetch Gradle or dependencies. The plain modules' tests can still be run with the Kotlin compiler and JUnit bundled in a local Gradle install. The app is only compiled in CI. Claude cannot push to the Colorado Mesh organisation yet (the Claude GitHub App is not installed there), so its changes come as patch files that a maintainer applies with `git am` and opens as a pull request.

## Related work in Colorado-Mesh/mesh-client

- PR #1253 (`fix/tak-chat-contacts` on RobertMTalbot/mesh-client) applies the channels-as-contacts finding to mesh-client's TAK chat bridge.
  - CodeRabbit's findings and a main-bundle size failure have been addressed.
  - Still open on 2026-10-13, last changed 12 October. It needs one approving review to merge, and has not been tested against ATAK.
  - mesh-client's main bundle is about 600 bytes under its 640 KiB budget.
