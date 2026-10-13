# MeshCore TAK Bridge: Design

First written as a proposal on 11 October 2026. Revised 12 October 2026 to describe the app as built and tested on devices, and 13 October 2026 for the move to Colorado Mesh. Current progress and the to-do list are in [STATUS.md](STATUS.md).

## Summary

MeshCore TAK Bridge is an Android app that connects a phone to a MeshCore companion radio over Bluetooth and presents the mesh to ATAK as a local TAK server. ATAK connects to it the same way it connects to any server. Mesh nodes appear as map markers, and each mesh channel appears as an ATAK contact that carries that channel's chat in both directions.

It is the phone-side counterpart to the TAK server built into the Colorado Mesh desktop client, and the two share their behaviour towards ATAK. What this app learned on devices about chat addressing has been carried back to the desktop client (Colorado-Mesh/mesh-client pull request 1253).

The project was started under Bud's GitHub account and moved into the Colorado Mesh organisation on 13 October 2026, as [Colorado-Mesh/meshcore-tak-bridge](https://github.com/Colorado-Mesh/meshcore-tak-bridge). It is written from scratch, with no code taken from TAK-MESHCORE, and licensed GPL-3.0-or-later, the same as the Colorado Mesh desktop client.

## Background

There were two ways to get MeshCore into ATAK, and each left a gap.

| Option | What it does well | Where it falls short |
| --- | --- | --- |
| Colorado Mesh desktop client, TAK server | One radio serves every ATAK device on the network; channels become chat; nodes become markers | Needs a computer running the client and a shared network, so it does not work for a lone phone in the field |
| TAK-MESHCORE plugin | Phone connects straight to its own radio, no server | See the list below |

Problems seen with the TAK-MESHCORE plugin (v1.5.14), from use and from reading its source:

- It fails to connect on a Samsung Galaxy Tab Active 3 and sits on "Connecting" indefinitely. The connect code starts two Bluetooth operations at once and has no timeout.
- It reads only channel slots 0 to 7, although the radio reports how many it supports.
- It writes its own channel, `ATAK_DATA`, into slot 7 of the radio on every connect, and the channel stays there after disconnect.
- Channel chat lives in a cramped panel inside the plugin, not in ATAK's chat screen.
- It is built for ATAK 5.6.0 only, and an ATAK plugin has to be rebuilt as ATAK changes.
- It has no automated tests in about 36,000 lines of code.

A bridge app avoids the last two by design: it speaks only the standard TAK server protocol, so it is not tied to an ATAK version and needs no plugin signing. The Bluetooth design below answers the first three, and the bridge connects reliably on the Tab Active 3.

## Goals and non-goals

**Goals for version 1**

- Connect reliably to a MeshCore companion radio over Bluetooth on a range of Android devices, including older Samsung tablets.
- Show every mesh node that advertises a position as an ATAK map marker, styled by what it is: companion, repeater, room server or sensor.
- Show every channel on the radio in ATAK's own chat screen, two-way.
- Send and receive plain MeshCore channel text, readable by anyone on the mesh with any MeshCore app.
- Never change the radio's configuration. No channels, contacts, settings or clock are written, and nothing is left behind after disconnect.
- Work with any current ATAK version without being rebuilt.

**Not in version 1**

- Sending ATAK map points, routes or drawings between ATAK users over the mesh. This is what TAK-MESHCORE's `ATAK_DATA` tunnel does, and it is the main source of that plugin's complexity and airtime use.
- Its own encryption layer. MeshCore channels are already encrypted with the channel key.
- Meshtastic or Reticulum support.
- iPhone support. iTAK cannot be served this way from an Android app.
- Anything inside ATAK's interface: no custom tools, menus or buttons. That would need a plugin.

Direct messages with companion nodes were left out of the first pass and added on 12 October 2026 (see "Direct messages" below).

## Architecture

The app sits between the radio and ATAK on the same phone, and each part has one job.

```mermaid
flowchart LR
    radio["MeshCore radio<br/>LoRa mesh link"]
    subgraph app["MeshCore TAK Bridge app"]
        direction TB
        ble["Bluetooth link (app/ble)<br/>one step at a time, time limits, reconnect"]
        protocol["Companion client (protocol)<br/>command queue, frames encoded and decoded"]
        bridge["Mesh picture (app)<br/>nodes, channels, filters, styling"]
        server["Local TAK server (takserver, cot)<br/>CoT stream on 127.0.0.1"]
        ble --- protocol --- bridge --- server
    end
    atak["ATAK<br/>map markers, channel contacts"]
    radio <-- "Bluetooth LE" --> ble
    server <-- "TCP, loopback" --> atak
```

Data flows both ways along the same path. Radio frames come in over Bluetooth, are decoded, and update the bridge's picture of nodes and channels; the bridge turns that picture into CoT events for ATAK. A message typed in ATAK to a channel contact travels the other way and leaves the radio as a channel message.

`BridgeService`, a foreground service, owns all of these and holds the only state: the mesh picture, which markers ATAK currently has, and messages waiting for ATAK to connect.

## Radio link

The app talks to the radio with the published [MeshCore companion protocol](https://docs.meshcore.io/companion_protocol/) over Bluetooth Low Energy. The radio exposes a serial-style service with one characteristic the app writes to and one it receives notifications from; each write or notification carries exactly one protocol frame.

### Bluetooth

The Bluetooth layer (`GattLink`) is hand-written against Android's own API rather than built on a library, because the failures it has to avoid are about ordering and time limits, and those are easiest to guarantee directly. Its rules:

- **One step at a time.** Connect, discover services, request a larger packet size (512 bytes), turn on notifications. Each step starts only when the one before it has finished, and outgoing frames are written one at a time.
- **Every step has a time limit.** Connect 20 s, discovery 15 s, notifications 10 s, each write 5 s, pairing 60 s.
- **The packet size request may go unanswered.** After 4 s the link carries on at the default size rather than waiting. This is where some tablets were seen to stall.
- **Pairing.** When the radio asks for it, Android's PIN prompt appears and the link waits for the result.
- **Reconnect.** The chosen radio is remembered by address. A dropped link is retried after 3, 5, 10, 20, 30 and then every 60 seconds, and the last known mesh picture is kept so ATAK's view survives a short gap. The user can disconnect at any time, which stops retries and forgets the radio.

### Companion protocol

The `CompanionClient` in the `protocol` module runs the conversation once the link is ready. Commands wait in a queue, one is outstanding at a time, and each has a 6 s time limit.

1. App start: the reply gives the radio's name and radio settings.
2. Device query: the reply gives the firmware version, model and the number of channel slots.
3. Fetch contacts, then fetch each channel slot up to the number the radio reported (8 for firmware too old to say).
4. Fetch waiting messages, then react to the radio's own "messages waiting" and "advert heard" notices.

The client never sets the radio's clock, channels, contacts, paths or settings. Sending channel text and sending a direct message are the only commands that cause a transmission.

The protocol page is marked as still in development and does not document everything the app uses, such as the contact record layout. Those details come from the MeshCore firmware source, are checked against real radios, and are covered by tests with recorded frames.

### Channel kinds

Each programmed channel is classified by comparing its key with the fixed Public key and with the key a hashtag channel derives from its name (the first 16 bytes of SHA-256 of the name). The classification is kept; the key is not stored anywhere.

### Message length

MeshCore channel text is limited to 160 bytes, and the radio puts its own name and `": "` in front. The bridge works out the room left (at most 133 bytes) and refuses a longer message from ATAK, posting a note in that chat rather than cutting the text. Splitting into parts remains a possible later option.

## Local TAK server

The app runs a small TAK server on the phone that listens only on `127.0.0.1`, port 8087, and ATAK connects to it as a streaming TCP server. Because the connection never leaves the phone, it uses plain TCP with no certificates, which removes the truststore and data-package setup that caused trouble on the desktop client.

The server speaks Cursor on Target (CoT) XML. Each behaviour below was found necessary while testing against ATAK:

- **Bind to 127.0.0.1 explicitly.** Android's `InetAddress.getLoopbackAddress()` is `::1`, and ATAK refused to connect to a server bound there.
- **Bare events.** Each event is sent as an `<event>` element with no XML declaration in front. ATAK parsed the first event on a connection and dropped the rest when a declaration was present.
- **Keepalive replies.** ATAK sends a ping (`t-x-c-t`) when the connection is quiet and redials if nothing comes back. The server answers each with a pong (`t-x-c-t-r`).
- **Marker refresh.** A marker goes stale in ATAK 10 minutes after it was sent, so every current marker is re-sent every 5 minutes, and all of them are sent when ATAK connects.
- **Removal.** A node the filters drop is deleted from ATAK at once with a delete event, rather than left to go stale.
- **One connection.** The server accepts a single client, and the app shows when one is connected.

The bridge learns who the ATAK user is (callsign and uid) from the first position report ATAK sends, because chat is addressed to that user.

ATAK also asks a TAK server's web API on port 8080 for several things each time it connects. With nothing listening, it kept showing "Failed to get server contacts … connection to http://127.0.0.1:8080 refused". Once contacts were answered, it showed "Failed to get server groups … (error 404)" next. The bridge now runs a minimal responder on `127.0.0.1:8080` (`ApiServer`). It answers the requests ATAK makes on its own at connect, each with nothing, in the forms ATAK's open-source code parses:

| Request | Answer |
| --- | --- |
| `GET /Marti/api/clientEndPoints` (server contacts) | `{"type":"com.bbn.marti.remote.ClientEndpoint","data":[]}` |
| `GET /Marti/api/groups/all` (server groups, for ATAK's Channels feature) | `{"type":"com.bbn.marti.remote.groups.Group","data":[]}` |
| `GET /Marti/api/missions` (Data Sync missions, which newer ATAK calls "feeds") | `{"version":"3","type":"Mission","data":[],"nodeId":"meshcore-tak-bridge"}`, TAK Server's own wrapper for the list |
| `GET /Marti/api/device/profile/…` (settings profiles) | 204 No Content, which ATAK reads as "nothing new" |
| `GET /Marti/api/version` and `/version/config` | 404; ATAK only logs this, and no error showed on a device |

The contacts and channels themselves still arrive over the CoT stream. The contact-list answer has no `version` field, which is where ATAK reads the server's API version, so the bridge claims no TAK Server features it lacks. (The `version` in the mission list is that list's format version, which TAK Server puts on every mission reply.) The missions request is not in the public 4.x source; it came from the bridge's log on a device ("Failed to get feeds"). The other paths in ATAK's code (mission packages, certificate enrollment, CoT history) are used only when the user asks for them. They get 404, and the bridge logs each such path once, so a device test shows whether a newer ATAK wants more. The public ATAK source is version 4.x; the messages above came from a newer ATAK. If another app holds port 8080, the bridge carries on without the responder.

Setting up ATAK is one step: add a server with address `127.0.0.1`, port `8087`, protocol TCP.

## Chat: channels as contacts

The proposal had each channel as a GeoChat room. Testing on devices showed that does not work for replies:

- ATAK only sends chat to a contact it can reach, meaning a contact whose `<contact>` element has an endpoint such as `*:-1:stcp` ("through the server connection").
- ATAK addresses a reply to that contact, not to the room the message was read in. With rooms, the bridge could not tell which channel a reply was for.

So every channel is a contact:

- Its uid is `MESHCORE-CH-<slot>`, with endpoint `*:-1:stcp`. A contact must have a point, so it sits at 0, 0, away from any map in use.
- Its callsign starts with `#` and shows the channel's kind: `#Public`, `#name` for a hashtag channel, and `#🔒 name` for a private channel. ATAK lists contacts alphabetically. `#` sorts before digits and letters, so all channels sit together at the top, ahead of the nodes. The lock sorts after letters, so private channels follow the others within that group. Channels that share a name get their slot number added.
- The bridge posts nothing in a channel's chat until there is a message. The first version posted an opening line from each channel contact on every connect, so the conversation would exist in ATAK. Testing showed a reachable contact is enough to open a chat, and the opening lines marked every channel as unread each time ATAK connected, so they were removed.
- A channel message heard on the mesh arrives as a direct message from that channel's contact to the ATAK user. The mesh sender's name stays in the text, in the `Name: text` form MeshCore uses.
- A message the ATAK user sends to a channel contact is matched to its channel by the contact uid (or, failing that, the callsign) and sent once on that channel, under the radio's own name.
- Messages heard before ATAK connects and identifies itself are held, up to 50, and delivered when it does.
- If the radio is not connected, does not accept the message, or the message is too long, the bridge says so in that chat.

## Direct messages

Direct messages use the same pattern as channels: a contact ATAK can reach stands for each node, and ATAK's reply names that contact.

- **Who can be messaged.** Companion nodes only. A repeater or room server treats plain text from its admin as a command line, so the bridge never sends chat to one, and repeaters and room servers get no endpoint in ATAK.
- **Contacts.** A companion on the map has the `*:-1:stcp` endpoint. A companion with no position, or one the filters hide, becomes a contact at 0, 0 (like a channel) once a direct message has passed with it, so the conversation can go on. That list is cleared when a different radio is chosen.
- **Receiving.** The radio names the sender by the first 6 bytes of its public key. The bridge looks up the full contact and posts the text from that node's contact to the ATAK user. Messages heard before ATAK identifies itself are held with channel messages, up to 50 in all.
- **Duplicates.** When a sender's acknowledgement is lost, the sender tries again with the same timestamp and text. The radio delivers each try, so the bridge drops a repeat of a message from the same sender with the same timestamp and text in the last 15 minutes, and logs it.
- **Sending.** A message to a companion's contact is sent with the companion protocol's send-text command, addressed by the 6-byte key prefix, up to 160 bytes. The radio replies with the code the recipient's acknowledgement will carry and an estimate of how long it should take.
- **Acknowledgement and retries.** If no acknowledgement arrives within that estimate (kept between 2 and 60 seconds) plus 2 seconds, the message is sent again with the same timestamp, up to 3 tries in all. An acknowledgement of any try counts. The MeshCore apps also reset the contact's path before the last try; the bridge does not, because that would write to the radio's contacts.
- **Feedback in ATAK.** Delivery is logged. No acknowledgement after 3 tries, or a refusal by the radio (for example, a node it does not know), puts a note in that conversation. ATAK's own delivery receipts (`b-t-f-d`) are not sent yet.

Room server posts arrive as signed messages and are skipped. The radio's log says so once per connection.

## Echoes

A channel message sent from ATAK should never come back to ATAK. The firmware marks each packet it floods as already seen, so a repeater's rebroadcast is dropped, and sending a channel message produces no received-message frame. The bridge still checks. A received channel message with the same channel and text as one it sent in the last 15 minutes (`"<radio name>: <text>"`, the form the radio transmits) is dropped and logged as an echo, so a device test can show whether it ever happens. The check could also drop an identical message from a different node with the same name as this radio. That is unlikely, and it is logged.

## Data mapping

| On the mesh | In ATAK | Notes |
| --- | --- | --- |
| Companion node with a position | Team member marker (`a-f-G-U-C`), reachable for chat | Team colour chosen in the app, default Cyan; callsign is the node name |
| Companion node off the map, after a direct message | Team member contact at 0, 0, reachable for chat | Keeps the conversation going |
| Repeater with a position | Installation marker (`a-f-G-I`) | Optional icon from an ATAK icon set (GeoOps presets offered) |
| Room server with a position | Installation marker (`a-f-G-I`) | Its own optional icon, separate from repeaters |
| Sensor node with a position | Sensor marker (`a-f-G-E-S`) | |
| Node without a position | Not shown | MeshCore nodes only share location if set to |
| Channel on the radio | Contact, with a chat | See "Chat: channels as contacts" |
| Channel message heard | Message from that channel's contact | Sender's name kept in the text |
| Message sent to a channel contact in ATAK | Channel message from this radio | Goes out under the radio's own name |
| Direct message heard | Message from that node's contact | |
| Message sent to a companion's contact in ATAK | Direct message to that node | Acknowledged, or tried up to 3 times |

**Markers.** Each node's uid is built from its MeshCore public key (`MESHCORE-` and the first 16 hex digits), so it keeps its marker across restarts. The remarks give the node kind and its last advert time.

**Filters.** The user chooses which node types appear and how old a node's last advert may be (default: hide nodes not heard for 7 days). An advert dated in the future, from a sender with a wrong clock, counts as fresh. Changing a filter updates ATAK at once.

**Icons.** An icon is given as `<icon set uid>/<group>/<file>`. ATAK draws the standard symbol if it does not have the icon set. Paths are checked so they cannot break out of the XML attribute.

**Own position.** ATAK already shows the phone's own position, so the bridge does not create a marker for the user's own radio.

## Security and privacy

The app's exposure is small because nothing leaves the phone except what the radio transmits.

- **Loopback only.** The TAK server binds to `127.0.0.1`, and so does the web API responder on port 8080, so no other device on a network can reach either. The responder holds no data: it only ever answers with an empty contact list. Other apps on the same phone can, which is why the server accepts a single client and the app shows when one is connected.
- **Transmitting is the user's own act.** Only messages the user sends to a channel or companion contact are transmitted, from the user's own radio. Retries of an unacknowledged direct message are the only transmissions the bridge repeats on its own, and they stop after 3 tries.
- **No commands to infrastructure.** Chat is never sent to a repeater or room server, which could read it as an admin command.
- **No configuration writes.** The app cannot alter channels, keys, contacts, the clock or radio settings. Channel keys are read only to classify the channel and are not kept.
- **Untrusted input.** Everything from the radio and from ATAK is parsed defensively with length limits, and outgoing events are built from parsed fields with XML escaping, never by passing text through.
- **Rate limit.** At most 10 chat messages a minute are accepted from ATAK, and at most 5 can wait to be sent, so a misbehaving client cannot flood a slow shared channel.
- **Permissions.** Bluetooth connect and scan, notifications, and foreground service. Location is requested only on Android 11 and older, where Bluetooth scanning requires it. Android requires the internet permission for any network socket, including a loopback one, so the app declares it but makes no outside connections.
- **No telemetry.** The app collects nothing and connects to nothing beyond loopback.

## Technology and repository layout

The app is written in Kotlin, split so that most of the logic is plain code with no Android dependency and can be tested on any computer.

| Module | Contents | Depends on Android |
| --- | --- | --- |
| `protocol` | MeshCore companion frames, and the client that runs the command queue | No |
| `cot` | Build and parse CoT events: markers, contacts, GeoChat, deletes, ping and pong; stream framing | No |
| `takserver` | Loopback TCP server, inbound chat, rate limit; minimal web API answer on port 8080 | No |
| `app` | Bluetooth link and scanner (`ble/`), the mesh picture, the foreground service, the status and settings screen | Yes |

The proposal had separate `bridge` and `ble` modules. In practice the mapping (`MeshPicture`) and the Bluetooth code sit in `app`; the mapping could move into a plain module if it grows tests of its own.

Other choices:

- **Android versions:** Android 8 (API 26) or later, targeting API 34.
- **Background running:** a foreground service with a persistent notification. The Tab Active 3 held the radio link overnight without being stopped by power saving.
- **Interface:** built in code, with no layout resources.
- **Licence:** GPL-3.0-or-later, matching the Colorado Mesh desktop client.
- **Builds:** every push to `main` runs the tests and builds a debug APK in GitHub Actions.

## Testing

- **Unit tests.** `protocol` is tested with recorded byte frames and a scripted radio for the client's command queue; `cot` with expected events and parsing of what ATAK sends; `takserver` with a scripted client that connects, pings and exchanges chat.
- **On-device checks.** Bluetooth and ATAK behaviour are checked by hand on a Pixel 9 Pro and a Galaxy Tab Active 3.

Not yet in place: a simulated radio that runs the whole bridge end to end on a computer, and a written on-device test script for releases.

## Development plan

| Milestone | State |
| --- | --- |
| 0. Repository, build, tests on every change | Done |
| 1. ATAK spike: loopback TAK server with demo data | Done; ATAK accepts plain TCP on `127.0.0.1` |
| 2. Bluetooth spike | Done on both test devices, including reconnect |
| 3. Protocol | Done for what version 1 needs |
| 4. Nodes on the map | Done, with type and age filters, team colour and icons |
| 5. Channel chat | Done, with channels as contacts |
| 5a. Direct messages and echo check | Done; direct messages tested both ways, no channel echoes seen |
| 6. App shell | Mostly done: radio picker, connection status, map settings, background service, Colorado Mesh header and icon |
| 7. Field test and first release | Not started; application ID settled, signed APK still needed |

Later candidates, in rough order: replying to a sender on a channel (see STATUS.md for the open questions), ATAK delivery receipts for direct messages, the radio's battery in ATAK, room server posts, splitting long messages, and sharing the phone's position to the mesh.

## Open questions and risks

Settled by testing on devices:

- [x] **Does ATAK accept a plain TCP server on loopback?** Yes, when bound to `127.0.0.1` rather than `::1`.
- [x] **How does ATAK address a reply?** To a reachable contact, not to a room; hence channels as contacts.
- [x] **Will Android keep the bridge running?** Yes on both test devices, including overnight on the Tab Active 3.
- [x] **Does pairing work across devices?** Yes on both test devices, with the PIN prompt and the packet size request allowed to go unanswered.
- [x] **What is the application ID?** `org.coloradomesh.takbridge`, from the Colorado Mesh organisation's own domain, decided on 13 October 2026. It replaced `io.github.robertmtalbot.takbridge` before the first signed release, and the Kotlin packages use the same name. It must not change again, because Android treats a new ID as a different app.
- [x] **Does a sent channel message ever come back, so that it shows twice in ATAK?** No. The firmware source says not (see "Echoes"), and the bridge's echo log never fired in testing on 12 October 2026. The guard stays as a safety net.

Still open:

- [ ] **Do ATAK's delivery receipts work through this server?** Sending `b-t-f-d` for acknowledged direct messages would show them as delivered in ATAK, but has not been tried.
- [ ] **Which undocumented protocol details are stable?** The contact record and advert layouts come from firmware source, not the published page, and may change between firmware versions.
- [ ] **Should the phone's position be shared to the mesh?** It would mean writing the radio's advertised location, which conflicts with the rule of never changing radio settings.
- [ ] **Who keeps the release signing key?** Every release must be signed with the same key, or installed copies cannot update. The maintainers need to agree who holds it and where the backup lives.

Decided on 11 October 2026: the name is MeshCore TAK Bridge, and the licence is GPL-3.0-or-later.
