# Phase 3 Design: Player Traffic Relay

**Status: Design review — no implementation yet.**

This document specifies how player traffic reaches a game server running on a
Keeper that has no inbound connectivity. It covers the wire format, the
routing decisions, the multi-relay coordination layer, and the verification
plan.

Read this before any Phase 3 code is written.

---

## Goals and non-goals

**Goals:**

1. A Minecraft Java client typing `lowlight.mine.bz` in the server list reaches a
   container running on a Keeper behind a NAT.
2. Player traffic does not ride the JSON control channel.
3. The relay is a separate process from Central with its own identity,
   listeners, and state. Multiple relay nodes can run simultaneously.
4. A player arriving at any relay node reaches their Keeper, regardless of
   which relay that Keeper is connected to.
5. Backpressure handled end-to-end so slow Keepers don't cause Central-side
   memory unbounded growth.

**Non-goals for Phase 3:**

- Bedrock Edition (UDP). Separate phase.
- Session resume on Keeper disconnect. Players get kicked to the lobby; they
  reconnect manually.
- Packet-level inspection or per-packet rate limiting. The relay is a byte
  pipe; it does not decrypt Minecraft's post-handshake cipher stream.
- Dynamic discovery of relay peers. Relay peer list is static config in
  Phase 3; service discovery is Phase 8.

---

## Architecture at a glance

```
     ┌──────────────┐
     │   Player     │
     │   (Java      │
     │    client)   │
     └──────┬───────┘
            │ TCP :25565
            ▼
 ┌────────────────────────┐      ┌────────────────────────┐
 │      Relay Node A      │◄────►│      Relay Node B      │  mTLS RPC
 │  ┌──────────────────┐  │      │  ┌──────────────────┐  │  (peer-to-peer)
 │  │ Ingress TCP      │  │      │  │ Ingress TCP      │  │
 │  │ Handshake parser │  │      │  │ Handshake parser │  │
 │  │ Stream muxer     │  │      │  │ Stream muxer     │  │
 │  └──────────────────┘  │      │  └──────────────────┘  │
 └───────▲────────────────┘      └────────▲───────────────┘
         │                                │
         │ mTLS /ws/data                  │ mTLS /ws/data
         │ (binary subproto)              │ (binary subproto)
         │                                │
 ┌───────┴────────────┐          ┌────────┴───────────┐
 │     Keeper 1       │          │     Keeper 2       │
 │  (Lowlight SMP)    │          │  (Bravo Industries)│
 │  docker container  │          │  docker container  │
 │  127.0.0.1:49173   │          │  127.0.0.1:49181   │
 └────────────────────┘          └────────────────────┘

 Central is off to the side:
 - Holds the authoritative routing table (hostname → instance → keeper_id)
 - Publishes routing updates to all relay nodes over a streaming channel
 - Relays cache the table locally; Central never sits on the data path
```

Central remains the single source of truth for identity, instances, and
routing. It is NOT on the player-traffic data path.

---

## Binary sub-protocol

The existing control channel (JSON WebSocket, `/ws`) stays exactly as-is.
Player traffic uses a **separate** WebSocket at `/ws/data` speaking a binary
subprotocol.

### Connection setup

The Keeper opens a second mTLS WebSocket to its assigned relay. Subprotocol
negotiation at handshake:

```
GET /ws/data HTTP/1.1
Sec-WebSocket-Protocol: atgs-data-v1
```

The relay accepts and responds with the same subprotocol token. Any other
token is a fatal handshake failure.

### Frame format

Every message on `/ws/data` is a WebSocket binary frame with this layout:

```
 0       1       2       3       4       5       6       7
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|type |        stream_id (uint32, big-endian)         |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|     payload_length (uint32, big-endian)     | payload ...      |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
```

- `type` (1 byte): frame type. Values below.
- `stream_id` (4 bytes): unique per data-channel connection. 0 is reserved for
  connection-level control.
- `payload_length` (4 bytes): bytes of payload that follow. Hard cap 65536 to
  keep memory bounded; larger payloads use multiple DATA frames.
- `payload`: opaque bytes, interpreted per frame type.

Total header is 9 bytes. Payload cap is 64 KiB.

### Frame types

| Code | Name | Direction | Meaning |
|------|------|-----------|---------|
| 0x01 | HELLO | Keeper→Relay | First frame on stream 0. Carries keeper_id + protocol version. |
| 0x02 | HELLO_ACK | Relay→Keeper | Relay accepts. Carries session_id. |
| 0x03 | STREAM_OPEN | Relay→Keeper | New player connection. Payload = instance_id (UUID bytes, 16B) + server_address (length-prefixed string). |
| 0x04 | STREAM_OPEN_ACK | Keeper→Relay | Keeper dialed the local container successfully. Zero payload. |
| 0x05 | STREAM_OPEN_ERR | Keeper→Relay | Keeper could not dial. Payload = error code (1B) + message. |
| 0x06 | DATA | Both | Opaque byte stream payload for the given stream_id. |
| 0x07 | STREAM_CLOSE | Both | The stream is ending. Payload = reason code (1B). |
| 0x08 | PING | Both | Connection-level liveness. Stream 0 only. |
| 0x09 | PONG | Both | Reply to PING. Stream 0 only. |

### Stream state machine (Keeper side)

```
                  STREAM_OPEN
    OPENING ────────────────────▶ DIAL_LOCAL
       │                              │
       │ dial ok                      │ dial fail
       ▼                              ▼
   ESTABLISHED ◀───────────      STREAM_OPEN_ERR
       │        STREAM_OPEN_ACK       │
       │                              ▼
       │ DATA frames both ways     CLOSED
       │
       │ STREAM_CLOSE from either side, OR local TCP close, OR player TCP close
       ▼
   DRAINING (flush pending frames)
       │
       ▼
   CLOSED
```

### Error codes (STREAM_OPEN_ERR and STREAM_CLOSE)

| Code | Meaning |
|------|---------|
| 0x00 | normal close (peer finished) |
| 0x01 | local_dial_failed (keeper could not reach container) |
| 0x02 | instance_not_found (keeper doesn't own this instance) |
| 0x03 | container_not_running |
| 0x04 | protocol_error |
| 0x05 | timeout |
| 0x06 | relay_disconnect |
| 0x07 | keeper_disconnect |

### Liveness

PING every 15s on stream 0, drop after 45s without PONG. Separate from
control-channel liveness so one can fail without taking down the other.

### Test vectors

**HELLO frame** for keeper_id `bbbd01ed-041e-4647-8520-e9beee2ee1e9`,
session_id `aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee`, protocol version 1:

```
01                                      type=HELLO
00 00 00 00                             stream_id=0
00 00 00 28                             payload_length=40
bb bd 01 ed 04 1e 46 47 85 20 e9 be ee 2e e1 e9   keeper_id (16B)
aa aa aa aa bb bb cc cc dd dd ee ee ee ee ee ee   session_id (16B)
00 00 00 01                             protocol_version=1
00 00 00 00                             reserved for future use
```

The session_id is keeper-generated and included here so relay-side audit logs
can correlate a data-channel connection to a specific control-channel session.
Relays treat it as opaque.

**Relay-to-relay HELLO** uses the same 40-byte payload layout as the keeper
HELLO above, but the first 16 bytes carry the *relay_id* of the connecting
relay rather than a keeper_id. The frame type and stream_id are identical
(type=0x01, stream_id=0). Code uses separate Encode/Decode helpers
(`RelayHelloPayload`) to make the semantic distinction explicit.

**STREAM_OPEN** for instance `73945d9c-0d21-43fa-a01d-e7951feb0587`,
server_address `lowlight.mine.bz`, stream 7:

```
03                                      type=STREAM_OPEN
00 00 00 07                             stream_id=7
00 00 00 22                             payload_length=34
73 94 5d 9c 0d 21 43 fa a0 1d e7 95 1f eb 05 87   instance_id (16B)
00 10                                   server_address length = 16
6c 6f 77 6c 69 67 68 74 2e 6d 69 6e 65 2e 62 7a   "lowlight.mine.bz"
```

**DATA** carrying 32 bytes of Minecraft handshake on stream 7:

```
06                                      type=DATA
00 00 00 07                             stream_id=7
00 00 00 20                             payload_length=32
<32 bytes of minecraft traffic>
```

---

## Minecraft handshake parsing

Every Java client's first packet is the Handshake (packet ID 0x00) in the
Handshaking state:

```
VarInt  packet_length
VarInt  packet_id              (always 0x00 in this state)
VarInt  protocol_version
String  server_address         (VarInt-length-prefixed UTF-8; up to 255 bytes logically
                                but we accept up to 4096 to tolerate Velocity/Bungee
                                forwarding markers)
UShort  server_port
VarInt  next_state             (1 = status, 2 = login, 3 = transfer)
```

The relay's ingress handler reads exactly the handshake packet, extracts
`server_address`, and makes one routing decision. The handshake bytes are
NOT stripped — they are pushed into the stream as the first DATA frame so
the container sees them verbatim. Stripping would break Velocity/Bungee
forwarding which encodes real player IP into `server_address`.

Routing lookup: strip any trailing null + IP marker that BungeeCord appends
(format: `originalhost\0realip\0uuid`) before lookup, but remember it for
inclusion in the DATA frame. Compare normalized hostname case-insensitively
against the relay's cached routing table.

If no match: close the TCP connection immediately, do not open a stream.
(A future enhancement could send a disconnect packet back to the client,
but Phase 3 keeps it simple.)

---

## Central ↔ Relay sync channel

Each relay node authenticates to Central as a peer. Central issues relay
certs from the same internal CA as Keeper certs, with a different OU
(`ATGS Relay`) so handlers can distinguish them.

After mTLS handshake, the relay opens a persistent WebSocket to Central at
`/api/v1/relay-sync`. Frames are JSON (same shape as the control channel,
small volume, not on the hot path).

Sync flow:

1. Relay sends `relay.hello` with its relay_id and known_version (cached
   routing table version; 0 on first run).
2. Central replies with either a full snapshot (`routing.snapshot`) or a
   stream of deltas (`routing.delta`) bringing the relay up to the current
   version.
3. Central continues to push `routing.delta` on any change (instance
   created/deleted/hostname changed) until the connection drops.
4. On drop, relay reconnects, presents its known_version, resumes.

A routing table entry is a tuple:

```
(hostname, instance_id, keeper_id, hostname_port)
```

The relay caches the table in its local SQLite so a fresh start doesn't
immediately overwhelm Central with a full snapshot for every restart.

---

## Keeper ↔ Relay binding

At enrollment (Phase 1 flow, extended for Phase 3):

- Central includes a `relay_endpoints[]` list in the enrollment response.
  For Phase 3 this is statically configured on Central.
- Keeper writes that list to `<state>/relay.endpoints`.
- Keeper tries each endpoint in order. First successful mTLS WebSocket
  connection wins. Remaining endpoints are failover candidates.

At reconnect (e.g., relay restart):

- Keeper tries the last-successful endpoint first.
- On failure, iterates the full list with exponential backoff per endpoint.

Central can push an updated `relay_endpoints[]` over the control channel
later (new frame type `relay.endpoints.update`) without requiring re-enrollment.

---

## Relay ↔ Relay coordination

This is the part that the "split now" decision buys us. Player arrives on
Relay A, Keeper is connected to Relay B — we need to stitch them together.

### Peer discovery (Phase 3: static)

Each relay node has a config list of all other relays:

```
ATGS_RELAY_PEERS=relay-b.example.com:7443,relay-c.example.com:7443
```

Each relay opens a persistent mTLS gRPC-style connection to every peer at
startup and maintains it.

Phase 8 replaces this with service discovery (Consul, DNS SRV, or a
purpose-built registry). The interface stays the same.

### Keeper affinity tracking

When Relay B accepts a Keeper's data channel, Relay B publishes
`keeper_online(keeper_id=X, at_relay=relay-b)` to every peer via
inter-relay RPC. Peers cache this. If Relay B loses the Keeper, it
publishes `keeper_offline(keeper_id=X, from_relay=relay-b)`. If a Keeper
moves from Relay B to Relay A (relay failover), Relay A publishes
`keeper_online` and Relay B publishes `keeper_offline`; peers take the
newer message.

Each relay maintains a keeper-to-relay map. Lookup is O(1).

### Cross-relay stream forwarding

When Relay A accepts a player TCP connection targeting `lowlight.mine.bz`:

1. Routing table says `instance=73945d9c..., keeper_id=bbbd01ed...`
2. Keeper affinity map says keeper is currently at `relay-b`.
3. Relay A opens an inter-relay stream to Relay B over their persistent
   RPC connection, sending:

```
cross_relay.open_stream {
  remote_stream_id:  <relay-a-generated>
  instance_id:       73945d9c...
  server_address:    "lowlight.mine.bz"
}
```

4. Relay B opens a local stream on the Keeper's data channel (exactly as
   if it had been the ingress), and bridges inter-relay bytes ↔
   Keeper-data-channel bytes.
5. Bytes flow: player ↔ Relay A ↔ Relay B ↔ Keeper ↔ container.

One extra network hop. Acceptable for Phase 3. Phase 8's service discovery
can route players to the relay nearest their Keeper to minimize this.

### Inter-relay frame format

Same binary format as the Keeper data channel, but with two extra frame
types:

| Code | Name | Meaning |
|------|------|---------|
| 0x10 | XR_STREAM_OPEN | Relay A asks Relay B to open a stream to keeper_id on A's behalf |
| 0x11 | XR_STREAM_OPEN_ACK | Relay B acknowledges, confirms it has the keeper |
| 0x12 | XR_STREAM_OPEN_ERR | Relay B doesn't have the keeper anymore |
| 0x13 | XR_KEEPER_ONLINE | Announcement: this relay now has this keeper |
| 0x14 | XR_KEEPER_OFFLINE | Announcement: this relay lost this keeper |

DATA, STREAM_CLOSE, PING, PONG frames reused with same semantics.

### Split-brain scenarios

Two relays could both believe they have the same Keeper if affinity
announcements race. Resolution: every keeper affinity entry carries a
monotonic `since_unix_nano` timestamp from the announcing relay. Readers
always prefer the entry with the highest timestamp. A lost announcement
on reconnect resolves within one heartbeat.

If a stream-open RPC hits a relay that no longer has the keeper, it
returns `XR_STREAM_OPEN_ERR(keeper_not_here)` with its current affinity
belief, and the originating relay re-routes. Player sees a brief hang (one
RPC round-trip) then connects.

---

## Backpressure

Bounded channels everywhere.

**Ingress side:** when the relay reads from the player's TCP socket, it
pushes bytes into a per-stream `chan []byte` of depth 16 (16 * 64 KiB = 1
MiB per stream max buffer). If the channel is full, the relay stops
reading from the TCP socket. The player's OS advertises a zero TCP
window; Minecraft client handles this gracefully.

**Egress side:** each data-channel WebSocket has a single writer goroutine
consuming from a single `chan []byte` of depth 256. If full, incoming DATA
frames on any stream back up to their per-stream channel, which backs up
the TCP reader.

**Cross-relay:** same pattern at each hop.

Reject-if-too-slow policy: if a stream's per-stream channel stays full for
more than 5 seconds, close the stream with code `timeout` (0x05). This
prevents a single dead Keeper or slow peer from occupying memory
indefinitely.

---

## Security model

- Relay ↔ Central: mTLS, relay cert OU = `ATGS Relay`, client cert required.
- Keeper ↔ Relay: mTLS, existing keeper cert, client cert required.
- Relay ↔ Relay: mTLS with relay certs, both sides authenticated. Each relay
  has an allowlist of peer relay_ids.
- Central ↔ Central: N/A (single node).

A compromised relay can:
- Read all traffic it relays (already encrypted by Minecraft post-handshake).
- Deny service to its keepers and players.
- Lie about keeper affinity to cause wrong routing decisions.

A compromised relay cannot:
- Forge identity to Central (relay cert binds to relay_id).
- Impersonate a keeper to another relay.
- Modify the routing table (only Central writes it).

A compromised Central would own everything. This is acknowledged and
unchanged from Phase 1 — Central is the trust anchor.

---

## Instance model changes

New column on `instances`:

```sql
ALTER TABLE instances ADD COLUMN hostname TEXT UNIQUE;
```

Create-instance API adds an optional `hostname` field. Values must match
`[a-z0-9][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)+` and be globally unique across
all non-deleted instances. Deleting an instance frees its hostname
immediately.

One instance, one hostname, for Phase 3. Multi-hostname (aliases) is a
trivial follow-up.

### Host port discovery

The Keeper already picks a free host port when creating a container
(Phase 2: `HostPort: "0"`). After `ContainerCreate` returns, the Keeper
needs to inspect the container to learn the actual port chosen and persist
it in `localstore`. New field on `localstore.Instance`:
`HostPort int`.

The task result for `instance.create` is extended (backward-compatible) to
include `host_port`:

```go
type InstanceCreateResult struct {
    InstanceID  string
    ContainerID string
    HostPort    int  // new in v1.1
}
```

Central stores this on the instance row (new column `host_port INT`). The
relay reads this from the routing table when opening a stream, so the
`STREAM_OPEN` frame payload now also carries the target host port:

Updated STREAM_OPEN payload:

```
instance_id (16B) + host_port (uint16, 2B) + server_address (length-prefixed)
```

This is a bump of the data-channel subprotocol to `atgs-data-v1.1` (no
breaking change at the binary level — adding fields into a length-prefixed
envelope is forward-compatible with older Keepers that just ignore the
port and use their local lookup. But v1.0 Keepers would NOT exist at this
point, so the bump is academic.)

---

## Module layout

New top-level module:

```
atgs/
├── central/        (existing)
├── keeper/         (existing, extended)
├── relay/          NEW
│   ├── cmd/relay/main.go
│   └── internal/
│       ├── config/        (env loading)
│       ├── ingress/       (player TCP listener, handshake parse)
│       ├── datachannel/   (keeper /ws/data server)
│       ├── peering/       (inter-relay RPC + affinity map)
│       ├── routing/       (cached routing table, central sync client)
│       └── streams/       (per-stream state machine, backpressure)
└── shared/
    └── relayproto/        NEW (binary frame codec, shared between relay and keeper)
```

The binary frame encoder/decoder lives in `shared/relayproto` so both relay
and keeper implementations use the same code. There is intentionally only
one parser to prevent wire-format drift.

---

## Phase 3 implementation order

1. `shared/relayproto`: frame codec, test vectors as Go tests.
2. `relay/internal/config` + `relay/cmd/relay`: skeleton with listener.
3. Relay certs: extend Central's CA to issue relay certs, new subcommand
   `central mint-relay-cert`.
4. Central's `/api/v1/relay-sync` endpoint + relay's routing cache client.
5. Keeper's `/ws/data` client: opens data channel, speaks subprotocol,
   replies to HELLO_ACK. No streams yet.
6. Relay's `/ws/data` server: accepts keepers, maintains affinity map,
   publishes `keeper_online` to peers.
7. Inter-relay RPC: establish persistent connections between configured
   peers. Affinity announcements. Test with two relays.
8. Relay's player ingress listener: accept TCP, parse handshake, look up
   routing entry, open stream via data channel or cross-relay.
9. Keeper's stream handler: receive STREAM_OPEN, dial local container,
   bidirectional byte pipe with backpressure.
10. Instance hostname field + API endpoints.
11. Keeper reports host port; Central stores it.
12. End-to-end test: two relays, one Keeper connected to relay B, player
    connects to relay A, handshake parsed, cross-relay stream established,
    bytes round-trip through a fake Minecraft server on the Keeper side.

---

## Verification plan

**Single-relay path (simpler case):**

- Start Central, one relay, one Keeper. Keeper connects to the relay.
- Create an instance with hostname `lowlight.test.local`.
- Test harness opens TCP to the relay's ingress port, sends a crafted
  Minecraft handshake packet targeting `lowlight.test.local`.
- Verify the Keeper receives STREAM_OPEN, dials the fake local server
  (a simple echo server listening on the container's host port), and
  bytes round-trip.

**Two-relay path (the reason we're splitting now):**

- Same, but with two relay nodes configured as peers. Keeper connects to
  relay B. Player test harness connects to relay A.
- Verify affinity announcement reached relay A before the player
  connected (or that the player's stream waits briefly for affinity).
- Verify `XR_STREAM_OPEN` and `XR_STREAM_OPEN_ACK` round-trip.
- Verify end-to-end byte flow through two relays.

**Keeper-failover path:**

- Kill the relay the Keeper is connected to mid-session. Verify:
  - All active streams close with `relay_disconnect` (0x06).
  - Keeper reconnects to the second configured relay within backoff.
  - New affinity announcement propagates.
  - A new player connection succeeds.

**Split-brain resolution:**

- Force a stale affinity entry by simulating a dropped `keeper_offline`.
  Verify that a stream_open to the stale relay returns
  `XR_STREAM_OPEN_ERR(keeper_not_here)` and the originating relay reroutes.

**Backpressure:**

- Unit test: fill a per-stream channel, verify TCP reads stop and the
  player's socket drains, then resume drains, verify flow resumes.

---

## What I'm NOT building in Phase 3

Noted explicitly so neither of us is surprised later:

- Relay-side metrics or dashboards.
- Live bandwidth caps per keeper or per stream.
- Geographic routing decisions.
- Relay graceful-drain (tell keepers to migrate before shutdown).
- Keeper-to-multiple-relays redundancy (active/active).
- UDP anything.
- Protocol-aware features (kick-on-ban, whitelist enforcement at the relay).

These are all natural Phase 7/8 extensions. The architecture supports them.

---

## Sign-off

Before any code is written, confirm:

1. Frame format looks right.
2. Frame types cover every case you expect.
3. Multi-relay coordination approach is acceptable (affinity announcements
   + timestamps, static peer list, cross-relay byte bridging with one hop).
4. Instance hostname model is OK (one-to-one, unique, freed on delete).
5. Scope exclusions above are acceptable.
6. Module layout acceptable.

Call out anything that looks wrong and I'll revise before writing code.
