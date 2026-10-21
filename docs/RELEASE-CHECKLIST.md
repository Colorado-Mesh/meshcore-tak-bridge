# Release test checklist

Run this on every release candidate before tagging it, as step 1 of [RELEASING.md](RELEASING.md) says. It takes about 15 to 20 minutes, and checks on a real device everything the unit tests cannot: Bluetooth, ATAK, and the mesh itself.

Copy the list into the pull request that sets the new version, tick it as you go, and note anything odd. A box that cannot be ticked is either a bug to fix before the release or a known problem to name in the release notes.

## What you need

- The candidate: the debug APK from the CI build of `main` you mean to release (the `meshcore-tak-bridge-debug-apk` artifact). Debug builds are signed with a different key, so uninstall the release first, or use a second device.
- ATAK, with a TAK server at `127.0.0.1`, port `8087`, TCP, SSL/TLS off.
- Your companion radio, and a **second companion radio** in range, where you can read and send messages (in the MeshCore app on another phone, say).
- On the second radio, for tracking: its GPS on, and base and location telemetry allowed for your radio. If the two radios have not swapped adverts recently, send a flood advert from each first; an old contact on either side makes direct messages and tracking fail silently.

## Record

| | |
| --- | --- |
| Version and build | 0.x.y, CI build # , commit |
| Device and Android version | |
| ATAK version | |
| Radio and firmware | (the bridge's log shows the firmware at connect) |
| Date and who ran it | |

## 1. Start and connect

- [ ] Start the bridge. The notification appears and the screen says "Running on 127.0.0.1:8087, Waiting for ATAK".
- [ ] Scan, then tap your radio once. It connects on the **first** tap, and the scan list clears.
- [ ] The radio line shows its name, the number of channels, and nodes on the map.
- [ ] Within a few seconds, "Battery: about N% (x.xx V)" appears under the radio, and the notification shows the radio's battery too.
- [ ] The log shows the firmware, contacts and channels, with no errors.

## 2. ATAK

- [ ] ATAK connects to the server (green), and the bridge says "ATAK connected".
- [ ] For a minute after connecting, ATAK shows no server errors: no "Failed to get server contacts", nothing about invitations, groups or missions.

## 3. The map

- [ ] Nodes appear: companions as team members in the chosen team colour, repeaters and room servers as installations (with the chosen icon, if one is set), sensors as sensors.
- [ ] Untick a node type: its markers leave the map within a few seconds. Tick it again: they come back.
- [ ] Change "Hide nodes not heard for", and the map follows.

## 4. Channels

- [ ] Every channel on the radio is in ATAK's contacts, starting with `#`, private channels with a lock.
- [ ] A message sent on a channel from the second radio arrives in that channel's ATAK chat, with the sender's name.
- [ ] A message sent from ATAK to a channel contact arrives on the second radio, and appears only once in ATAK.
- [ ] A message longer than the limit (paste in a long paragraph) is not sent, and a "Not sent: too long for the mesh" note appears in the chat.

## 5. Replies

- [ ] In a channel where the second radio has spoken, `@` and the first few letters of its name, then a message, arrive on the second radio as `@[Full Name] message`.
- [ ] `@@ thanks` replies to whoever spoke last on that channel.
- [ ] `@` with letters that match more than one recent name sends nothing, and a "Not sent: @… matches …" note lists them.

## 6. Direct messages

- [ ] A message from ATAK to the second radio's contact arrives there, and in ATAK it gets the **delivered** check mark beside its time.
- [ ] A direct message from the second radio arrives in ATAK, in a chat with that companion.
- [ ] With the second radio switched off, a direct message from ATAK ends, after 3 tries (up to a minute or two), with a "Not confirmed: no acknowledgement …" note. Switch it back on.

## 7. Tracking

- [ ] Under Tracking, choose the second radio. Its line shows "at lat, lon (time)" within a few seconds, and its marker moves to that position.
- [ ] Moved with ATAK's breadcrumbs on for that contact, it leaves a track (or check, over a longer run, that the position updates each interval).
- [ ] Disconnect ATAK (or close it). The screen says "Paused until ATAK connects". Reconnect: the node is not asked again at once, only when its interval comes round.
- [ ] Untick the node, and its line goes.

## 8. Staying up

- [ ] Switch your radio off. The radio line says "Lost …" and "Trying again in N s", and the battery line goes. Switch it on: the bridge reconnects by itself, and ATAK's picture is still there.
- [ ] Turn the screen off for 5 minutes, or switch to another app. The bridge is still running and connected when you come back.
- [ ] Stop the bridge. ATAK shows the server as disconnected, and the notification goes.

The overnight run that picks the candidate (RELEASING.md, step 1) covers longer use. The low-battery alert is not checked here; it shows the first time a radio runs down to 20%.

## 9. After tagging: the signed release

- [ ] The release run's summary shows the certificate SHA-256 `f59a5a05f3924b8e0dd2cc24aeb4f11405875d966d68bc25c2bbae9d38a7e554`.
- [ ] Uninstall the debug build, reinstall the previous release, set up a radio and a tracked node, then install the new release from the draft over it. It installs as an update, and the radio, map settings and tracked list are all still there.
- [ ] It connects to the radio, and ATAK shows the mesh, as in steps 1 and 2.

Then publish (RELEASING.md, step 7).
