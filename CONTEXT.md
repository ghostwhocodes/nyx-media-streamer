# Nyx Domain Context

## Playback delivery

Playback delivery is the core Module that turns a negotiated playback request or
existing playback session into adapter-ready delivery outcomes.

Important invariants:

- Playback delivery owns startup readiness, lifecycle interpretation, retry
  metadata, direct-file readiness, manifest readiness, startup failure handling,
  and delivery cleanup rules that are generic to Nyx playback.
- Playback delivery must not contain client-branded concepts such as Qloud.
- Fixed-client compatibility remains Adapter-owned. A compatibility Adapter may
  translate playback delivery outcomes into legacy response shapes, bridge
  tokens, playlist rewriting, or protocol quirks without moving those quirks
  into the core Module.
- Breaking Qloud compatibility is review-blocking while the Qloud Adapter is an
  active supported Adapter.

Related terms: playback session, stream representation, compatibility Adapter.

## Media client contract

Media client contract is the Adapter-owned policy that turns route-neutral media
catalog facts into an externally visible browse/listing payload for a specific
client surface.

Important invariants:

- The media catalog Module owns items, metadata, and capability facts. It must
  not build `/api/v1/*` route templates, externally visible origins, or
  client-specific links.
- A media client contract Adapter may choose payload shape, route templates,
  absolute URL origin policy, artwork links, audio links, browse links, and
  stream-entry links for its client surface.
- This is a greenfield pre-release project. Media browse/listing response shape
  may change when deepening this seam; do not add compatibility preservation for
  abandoned internal payload shapes unless an active external client requires it.
- The chosen long-term name is media client contract, not mobile client
  contract, so future clients can become additional Adapters behind the same
  seam.
- In the current HTTP implementation, `MediaClientContractAdapter` is the
  Adapter that turns route-neutral media catalog facts into the server media API
  browse, search, gallery, and audio response shapes.

Related terms: playback delivery, stream representation, Qloud compatibility
adapter.

## Qloud compatibility adapter

Qloud compatibility adapter is the fixed-client Adapter that translates Nyx
media, playback delivery, and stream representation behavior into the protocol
expected by the Qloud client.

Important invariants:

- Qloud compatibility remains one external Adapter. Do not split Qloud protocol
  behavior into public core Modules or generic app routes.
- The Adapter may contain internal policy Modules for auth session policy, HLS
  bridge policy, path translation, response shaping, recent media memory, and
  origin selection.
- The current internal Qloud policies are `QloudCompatibilitySessionPolicy`,
  `QloudHlsBridgePolicy`, `QloudPathPolicy`, `QloudResponseShape`,
  `QloudRecentMediaMemory`, and `QloudOriginPolicy`.
- Qloud client behavior is strict compatibility surface. Breaking Qloud request
  handling, response shape, auth/session behavior, tokenized bridge URLs,
  playlist rewriting, segment routing, seek, close, or externally visible origin
  behavior is review-blocking because the client source is not available.
- Qloud-specific names and legacy response concepts must not move into core
  playback delivery, stream representation, media catalog, or transcode Modules.

Related terms: media client contract, playback delivery, stream representation,
compatibility Adapter.

## Stream representation

Stream representation is the playback/transcode policy that chooses how an
output stream is represented for delivery: protocol, packaging, container,
manifest shape, artifact support, and command-output intent.

Important invariants:

- Stream representation must keep generic Nyx playback policy separate from
  fixed-client compatibility quirks. A compatibility Adapter may translate a
  selected representation into legacy response fields, but compatibility
  requirements must not influence the internal stream representation design.
- HLS fMP4, HLS MPEG-TS, DASH fMP4, and direct file delivery are distinct
  stream representations even when they share a streaming protocol.
- HLS MPEG-TS is the legacy Qloud-compatible packaging strategy and must not
  advertise unsupported adaptive DASH artifacts.
- Adaptive HLS MPEG-TS output is limited to a single representation unless a
  future stream representation policy explicitly changes that invariant. The
  stream representation policy declares this constraint; playback decision
  applies it using request/profile representation facts.
- Format aliases such as `hls`, `hls_ts`, `hls_mpegts`, and `hls-mpegts` should
  normalize through stream representation policy instead of drifting across
  callers.
- Unknown external request aliases preserve the characterized pre-refactor
  fallback to HLS+DASH fMP4 with no preferred protocol. This fallback is only
  for inbound external request names; storage-token parsing remains strict.
- All incoming seams that accept external stream representation names must call
  the same stream representation policy normalization method.
- Stream representation must be persisted as normalized typed values, not raw
  external aliases. This is a greenfield pre-release project: do not add
  migrations, legacy-format read paths, or backwards compatibility for old raw
  format strings.
- Transcode command construction consumes typed stream representation values,
  not raw job format strings.
- `StreamRepresentation` is the value. `StreamRepresentationPolicy` is the deep
  Module that owns normalization, external format mapping, artifact support,
  representation constraints, packaging traits, command-output mapping, and
  storage token mapping.
- Stream representation lives in a neutral contracts Module shared by playback
  and transcode, named `contracts-stream-representation`, not in playback
  contracts. The neutral Module owns streaming protocol values, stream
  representation values, stable storage tokens, representation traits,
  representation constraints, artifact kinds, and the default pure policy
  implementation. Do not introduce a runtime Adapter for stream representation
  unless a real dependency varies across the seam.
- Persisted representation uses stable storage tokens owned by stream
  representation policy, not Java enum names and not external aliases.
- Incoming transcode requests may keep an external `format` field at the route
  seam, but persisted transcode jobs should use a typed `representation` field
  so the model shift is explicit.
- Stream representation traits are low-level facts such as packaging, segment
  container, manifest kind, adaptive support, command-output intent, and
  artifact support. They are not client-branded or high-level compatibility
  labels.
- Artifact support is expressed as a set of artifact kinds, not a list of
  boolean flags.
- Direct file delivery is a stream representation with no manifest or segment
  artifacts, not a special case outside the policy.
- Invalid representation combinations fail as soon as the current layer has
  enough knowledge to prove invalid. Storage-token and artifact-name failures
  happen at the incoming seam. Representation/profile-dependent failures happen in
  playback decision after request and profile facts are known. Transcode must
  receive an already-valid typed representation decision, not re-evaluate
  playback policy.

Related terms: playback delivery, playback session, compatibility Adapter.

## Stream packaging

Stream packaging is a trait of stream representation. It describes the
container/manifest/segment packaging used by packaged outputs such as HLS fMP4,
HLS MPEG-TS, and DASH fMP4.

Important invariants:

- Stream packaging is not the top-level policy Module; stream representation is.
- Direct file delivery has no stream packaging.
- Persisted state should refer to stream representation storage tokens, not
  stream packaging tokens, Java enum names, or external aliases.

Related terms: stream representation, playback delivery.
