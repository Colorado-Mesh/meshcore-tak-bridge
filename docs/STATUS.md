# Project status

Where MeshCore TAK Bridge stands, for anyone picking the work up. Last updated 2026-10-13.

**Repository:** moved from RobertMTalbot/meshcore-tak-bridge to [Colorado-Mesh/meshcore-tak-bridge](https://github.com/Colorado-Mesh/meshcore-tak-bridge) on 2026-10-13. GitHub redirects the old address, but clones should point their remote at the new one.

**Release candidate:** CI build #43 (commit `71b00f3`), on the Tab Active 3 since the evening of 13 October with no problems. It is the first build with the final application ID, `org.coloradomesh.takbridge`, and the Colorado Mesh icon and header, and the first that connects to a radio on the first tap. What stands between it and a first release is the signing key (see "Still to do").

Changes since the last overnight burn-in (build #31, 12–13 October): the application ID, the radio connect and tap fixes, and the branding. Apart from the connection retries, none changes how the bridge talks to the radio or to ATAK.

## What works (tested on a Pixel 9 Pro and a Galaxy Tab Active 3)

- **TAK server for ATAK** on `127.0.0.1:8087`, plain TCP, loopback only, one client.
- **Bluetooth link to a MeshCore companion radio**, with an opening handshake that reads the radio's name, settings, firmware and channel capacity.
  - Every step is queued with a time limit.
  - The packet size request may go unanswered without stalling the link.
  - **A radio connects on the first tap** (checked on build #43, 13 October). See "Design decisions learned on devices" for why it used to need several.
  - Connecting waits a moment after a scan and tries up to 3 times, 12 s each, since Android often fails a first try with status 133. Tapping the radio already being connected does not start over, and the scan list is cleared once a connection is under way.
- **Remembers the chosen radio** and reconnects after a drop, retrying after 3, 5, 10, 20, 30, then every 60 seconds.
  - The Tab Active 3 held the link overnight with no power-saving problems, on an early build and again on build #31.
- **Answers ATAK's connect-time web requests** on `127.0.0.1:8080` (server contacts, groups, feeds, profiles) with empty replies, so ATAK shows no server errors. Only the two version requests stay unanswered, by design.
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
- **Stale contacts break direct messages silently, in both directions.** Channels share a key, so they keep working. A direct message is encrypted between the two nodes' own keys, so if either radio holds an old contact for the other, nothing gets through. The signs in the log are that every try is "flooded" (no path is ever learned) and no "Mesh, direct from" line appears. A flood advert from each radio fixed it in the first test. The bridge itself does not advert, since that is a radio action the user should choose.
- **Android silently ignores frequent Bluetooth scans.** An app that starts more than five scans in 30 seconds gets scans that report nothing, with no error. Combined with a scan button that gave no feedback, this made it seem to need two or three taps. The button now shows that a scan is running, and a second tap stops the scan instead of restarting it. The scanner also counts its starts and says how long to wait.
- **A list redrawn while it is being tapped loses the tap.** During a scan the radio's advert arrives several times a second, and the app redrew the whole list of radios for each one. A tap is a press and a release; when the list was redrawn in between, the button pressed was gone, so nothing happened, and a radio needed several taps. Each radio now keeps one button for the whole scan and only its text changes. Retrying Bluetooth connections (status 133) did not fix this on its own: build #41 had the retries and still lost taps; build #43 fixed the list.

## How work is delivered

- Changes go into `main` through pull requests. Each pull request runs CI (tests and a debug build) and CodeQL; merge once they pass.
- Every push to `main` builds again. The debug APK is the `meshcore-tak-bridge-debug-apk` artifact on each run, and the build number in this file is the CI run number.
- A newer push to `main` cancels a CI run still going on an older one, so merging two pull requests in quick succession leaves one cancelled run and one complete build with both.
- Each CI build is signed with a new temporary debug key, so a debug build will not install over a different one: uninstall first. A signed release will fix this.
- The `cot`, `takserver` and `protocol` modules are plain Kotlin with JUnit tests. `app` is the Android app, built in code; its only resources are the icons.

## Still to do

1. **A signed release APK**, version 0.1.0 from build #43's code.
   - The maintainers need to agree who creates the release signing key and who holds the encrypted backup (see DESIGN.md's open questions). Every later release must be signed with the same key.
   - Then: an org admin adds the keystore and its passwords as GitHub secrets, ideally in a `release` environment that needs a maintainer's approval, and a release workflow builds, signs and publishes a GitHub Release when a `v*` tag is pushed.
   - The application ID is settled: `org.coloradomesh.takbridge` (2026-10-13). Builds from before that used `io.github.robertmtalbot.takbridge` and install as a separate app, so uninstall them once.
   - While writing the release workflow: GitHub is moving actions off Node 20 (`actions/upload-artifact@v4` and `gradle/actions/setup-gradle@v4` warn about it), and `ubuntu-latest` moves to Ubuntu 26 from 19 October 2026.
2. Possibly: ATAK delivery receipts (`b-t-f-d`) for acknowledged direct messages, so ATAK shows them as delivered; and room server posts, which arrive as signed messages and are skipped for now.
3. **Reply to a sender on a channel**, if it can be done well. Requested 2026-10-12. To work out:
   - The reply form is `@[Name]` at the start of the text, as the MeshCore apps write it (confirmed by use, 2026-10-12). The firmware gives it no special handling: channel text is sent exactly as written, so this is a convention between apps, and the bridge only has to write the text that way.
   - ATAK's chat has no reply action, and adding one would need a plugin. The likely route is a typed convention: a message from ATAK that starts with `@Name` (or `@"Two Words"`) goes out as `@[Name] …`, so MeshCore users see a normal reply.
   - Matching the name: offer only names heard on that channel recently, ignore case, and send the text unchanged when the name does not match, so nothing is altered by surprise.
   - Received replies: show `@[Name]` as it is, or tidy it to `@Name`. Possibly mark one addressed to this radio's name.
4. Later: radio battery in ATAK.

In Claude cloud sessions, `./gradlew` cannot fetch Gradle or dependencies. The plain modules' tests can still be run with the Kotlin compiler and JUnit bundled in a local Gradle install. The app is only compiled in CI. Claude cannot push to the Colorado Mesh organisation yet (the Claude GitHub App is not installed there), so its changes come as patch files that a maintainer applies with `git am` and opens as a pull request.

## Related work in Colorado-Mesh/mesh-client

- PR #1253 (`fix/tak-chat-contacts` on RobertMTalbot/mesh-client) applies the channels-as-contacts finding to mesh-client's TAK chat bridge.
  - CodeRabbit's findings and a main-bundle size failure have been addressed.
  - Still open on 2026-10-13, last changed 12 October. It needs one approving review to merge, and has not been tested against ATAK.
  - mesh-client's main bundle is about 600 bytes under its 640 KiB budget.
