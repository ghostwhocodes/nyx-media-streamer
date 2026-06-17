# Nyx Media Streamer

## Overview
Nyx Media Streamer is a self-hosted media backend for path-safe browsing, typed libraries, negotiated playback, image and audio delivery, local metadata control, a simple admin UI, and legacy-client compatibility. It is built with Java 25, Javalin, FFmpeg, SQLite, and Maven.

## Features
- Server-driven playback sessions that choose direct play, remux, audio-only transcode, video transcode, or subtitle burn-in from client capabilities and source media facts.
- HLS, DASH, and direct-file delivery backed by explicit stream-representation policy instead of route-local format strings.
- Adaptive FFmpeg job runtime with queued work, retries, circuit-breaker health, SSE progress, batch submit/cancel, segment serving, and webhook delivery.
- Typed libraries for movies, shows, music, photos, and generic video, with scan runs, durable media-object IDs, source-root membership, missing-file tracking, and admin repair/diagnostic endpoints.
- Library item interpretation for movie files, show/season/episode hierarchies, music album/track hierarchies, photos, generic videos, and unmatched items.
- Local library enrichment with manual metadata overrides, manual poster/background/thumbnail artwork, folder artwork discovery, and local NFO import.
- Per-user media and library state for resume position, watched status, favorites, rating, play count, last played time, and continue-watching views.
- Path-first browse and search across configured media roots, with virtual paths and path-security checks at API seams.
- Image gallery, thumbnail generation, EXIF extraction, privacy-stripped image serving, parameterized image transforms, cacheable video seek previews, and trickplay assets.
- Audio streaming with direct delivery, capability negotiation, AAC/Opus/MP3 transcode targets, session-backed playback reporting, and M3U playlist import/export.
- Extensible eForms metadata with schema versioning, arbitrary fields, FTS5 search, export/import, and relocation support for moved media.
- Chapter mark CRUD for local chapter sets independent of source files.
- Optional bearer-token and Basic authentication, runtime API-user management, per-user quota enforcement, rate limiting, CSRF guard, CORS configuration, and TLS.
- Simple admin UI served from the main listener for local server operation, alongside the HTTP API and Swagger docs.
- Operational APIs for health, readiness, liveness, Prometheus metrics, storage status, cache purge, SQLite vacuum, database backups, sanitized config, and user quotas.
- Pluggable cache storage through local filesystem or S3-compatible object storage.
- Dedicated Qloud compatibility listener for older third-party clients, including native hello/auth token bridging, `/proc/*` browse tolerance, proxy-origin handling, and optional legacy MPEG-TS HLS.

## Nyx vs. Jellyfin

This is a product-shape comparison, not a compatibility or performance benchmark. Jellyfin is a turnkey media server with a large client and plugin ecosystem. Nyx focuses on API-first local control: explicit playback decisions, custom metadata, compatibility adapters, and operator-visible runtime behavior.

| Area | Nyx today | Jellyfin today |
|---|---|---|
| Core shape | Modular Java/Javalin backend for local media APIs, negotiated delivery, and compatibility shims. | Turnkey media server with existing clients, admin UI, discovery, plugins, and broad deployment docs. |
| Library model | Typed libraries for movies, shows, music, photos, and generic video, with scan runs, durable object IDs, path history, content-hash correlation, missing-file tracking, and repair diagnostics. | Virtual libraries with multiple paths and documented media types such as movies, shows, music, books, photos, and mixed libraries. |
| Metadata | eForms for custom schemas and FTS5 search, local NFO import, manual metadata/artwork overrides, folder artwork discovery, and relocation-aware metadata. | Built-in metadata providers, local NFO read/write, provider identifiers, and additional metadata/artwork providers through plugins. |
| Playback | Server-driven sessions choose direct play, remux, audio-only transcode, full transcode, or subtitle burn-in; outputs include HLS, DASH, and direct files. | Mature client-oriented playback stack with direct play/transcoding behavior and documented hardware acceleration support. |
| Image and trickplay | Privacy-stripped image serving, thumbnails, transforms, seek previews, trickplay manifests/assets, and chapter marks. | Server tasks and clients cover image handling, chapter images, and key-frame workflows. |
| User state | Authenticated per-user resume, watched, favorite, rating, play-count, and continue-watching state on media objects and library items. | Multi-user library experience exposed through Jellyfin users and clients. |
| Extensibility | eForms plus internal extension seams for library metadata providers, artwork providers, collection builders, scan hooks, and scheduled jobs. | Public plugin catalog and plugin repositories, with plugins able to add integrations and scheduled tasks. |
| Operations | Health/readiness/liveness, Prometheus metrics, per-user quotas, backups, storage reporting, local/S3 cache backends, webhooks, runtime config, and API-user admin. | Full dashboard-driven administration with scheduled tasks, plugin management, monitoring integrations, and established operator workflows. |
| Compatibility | Dedicated Qloud compatibility listener isolated from normal Nyx routes, preserving old client request and HLS behavior. | Broad Jellyfin client ecosystem rather than a Nyx-style fixed-client compatibility shim. |
| Best fit | Building custom local-first clients, precise playback behavior, privacy-aware media tooling, custom metadata, and legacy-client bridge work. | Operating a home media library with existing apps, plugins, and metadata providers. |

## Requirements
- JDK 25
- FFmpeg 6+ (with ffprobe)
- Maven 3.9+ via the repository launcher (`./codex/mvnw.sh`)

## Quick Start

```bash
# Build and test
./codex/mvnw.sh verify

# Package the runnable distribution
./codex/mvnw.sh -pl :app -am -DskipTests package

# Run
./modules/application/app/target/app/bin/app
# Server starts on http://localhost:8080
# Admin UI is served at http://localhost:8080/ when frontend assets are packaged

# Or use Make
make build
make run
```

For a local admin UI build and login example, see [QUICKSTART.md](/home/nos/Projects/Nyx/nyx-media-streamer/QUICKSTART.md).

## Configuration

Config via `application.conf` (HOCON) with environment variable overrides:

| Variable | Default | Description |
|---|---|---|
| NYX_HOST | 0.0.0.0 | Bind address |
| NYX_PORT | 8080 | Listen port |
| NYX_CORS_ORIGINS | [] | Allowed CORS origins |
| NYX_QLOUD_COMPAT_ENABLED | false | Enable the dedicated Qloud compatibility listener |
| NYX_QLOUD_COMPAT_HOST | NYX_HOST | Qloud compatibility bind address |
| NYX_QLOUD_COMPAT_PORT | NYX_PORT + 1 | Qloud compatibility bind port |
| NYX_QLOUD_HANDSHAKE_UPSTREAM | | Optional upstream Qloud server used to bridge native hello/auth tokens |
| NYX_QLOUD_LEGACY_TS_HLS | false | Serve Qloud playback as MPEG-TS HLS for very old clients |
| NYX_MEDIA_ROOT | (required) | Comma-separated media root paths |
| NYX_DATABASE_DIR | data/db | Database directory |
| NYX_FFMPEG_PATH | ffmpeg | FFmpeg binary path |
| NYX_FFPROBE_PATH | ffprobe | ffprobe binary path |
| NYX_MAX_CONCURRENT_JOBS | 2 | Maximum active FFmpeg transcode jobs |
| NYX_MAX_QUEUED_JOBS | 8 | Queue depth for waiting transcode jobs |
| NYX_MAX_CONCURRENT_MEDIA_PROCESSES | 4 | Maximum concurrent media helper processes for thumbnails, previews, and probes |
| NYX_AUTH_ENABLED | false | Enable bearer token auth |
| NYX_AUTH_TOKEN | | Auth token value |
| NYX_QUOTA_ENABLED | false | Enable per-user job, request-rate, and storage quotas |
| NYX_RATE_LIMIT_ENABLED | false | Enable global request rate limiting |
| NYX_CSRF_ENABLED | false | Enable CSRF guard |
| NYX_WEBHOOKS_ENABLED | false | Enable transcode webhook subscriptions and delivery history |
| NYX_BACKUP_ENABLED | false | Enable database backup service |
| NYX_BACKUP_DIR | database backups dir | Optional backup destination |
| NYX_BACKUP_INTERVAL_MINUTES | 0 | Scheduled backup interval; `0` disables scheduling |
| NYX_TLS_ENABLED | false | Enable TLS listener |
| NYX_TLS_PORT | 8443 | TLS listener port |
| NYX_STORAGE_BACKEND | local | Cache object backend (`local` or `s3`) |
| NYX_STORAGE_LOCAL_CACHE_DIR | data/cache | Local cache directory when `NYX_STORAGE_BACKEND=local` |
| NYX_S3_BUCKET | | S3-compatible bucket when `NYX_STORAGE_BACKEND=s3` |
| NYX_S3_ENDPOINT | | Optional S3-compatible endpoint |
| NYX_S3_REGION | us-east-1 | S3 region |
| NYX_S3_PREFIX | | Optional object key prefix |

This table covers the common runtime knobs. See `modules/application/app/src/main/resources/application.conf` for the full configuration surface, including audio bitrates, retry settings, webhook delivery limits, database pool settings, quota defaults, and TLS key material.

`NYX_DATABASE_DIR` is the canonical database directory override. `NYX_DB_DIR`
is still accepted as a compatibility alias when `NYX_DATABASE_DIR` is unset; if
both are present, `NYX_DATABASE_DIR` wins.

### Runtime Cache Storage

With the default local storage backend, Nyx writes generated cache objects under
`NYX_STORAGE_LOCAL_CACHE_DIR`. The default value, `data/cache`, is intentionally
relative, so it resolves beneath the process working directory. For example, if
the app is launched from `modules/application/app`, generated cache files appear
under `modules/application/app/data/cache/`.

The cache contains runtime artifacts such as generated thumbnails, EXIF-stripped
image copies, image transform outputs, and their `.meta` sidecar files. These are
not source files and are safe to purge; they should not be committed.

For production local storage, set `NYX_STORAGE_LOCAL_CACHE_DIR` to an absolute,
writable, persistent cache path outside the repository, such as
`/var/cache/nyx`. Treat it as cache data rather than user media or database
state. If `NYX_STORAGE_BACKEND=s3` is used, cache objects are stored in S3
instead of the local cache directory.

### Compatibility Listeners

Nyx keeps the admin UI, static assets, health, metrics, and normal API routes on
the main listener at `NYX_PORT`.

Qloud compatibility is opt-in in the packaged `application.conf`. When you set
`NYX_QLOUD_COMPAT_ENABLED=true`, the app binds a second same-JVM listener. If
you do not also set `NYX_QLOUD_COMPAT_PORT`, the app computes it as
`NYX_PORT + 1`. Qloud clients must connect to that compatibility port for
`/proc/*` calls and root-level HLS bridge URLs such as `/{qloudToken}/...`.
For very old third-party Qloud clients, set `NYX_QLOUD_LEGACY_TS_HLS=true` so
the shim returns legacy `httplive` metadata and MPEG-TS HLS segments instead of
modern fMP4 HLS.

This isolation is intentional: brittle legacy clients keep their root-level route
shape on the compatibility port, while the main listener keeps serving the admin
UI and normal Nyx APIs without route shadowing.

Future legacy shims should follow the same pattern: give each shim its own
dedicated compatibility listener port instead of registering root routes on the
main Nyx listener.

## API Reference

| Category | Endpoints | Description |
|---|---|---|
| Health | GET /api/v1/health, /api/v1/health/live, /api/v1/health/ready | Runtime health, liveness, and readiness checks |
| Metrics | GET /api/v1/metrics | Prometheus metrics |
| Docs | GET /api/v1/openapi.json, /api/v1/swagger | OpenAPI JSON and Swagger UI |
| Client discovery | GET /api/v1/client/capabilities | Client feature and route-template discovery |
| Browse/search | GET /api/v1/browse, /api/v1/search/files | Path-safe browse and file search |
| Libraries | /api/v1/libraries/* | Typed libraries, library items, local metadata/artwork, collections, and per-user library state |
| Media state | /api/v1/media/objects/*/state, /api/v1/media/state/* | Per-user object state, favorites, and continue-watching |
| Playback | /api/v1/playback/sessions/*, /api/v1/media/sessions/*/report | Negotiated playback sessions, manifests, segments, simple streams, lifecycle, and reporting |
| Transcode | /api/v1/transcode/* | Job management, manifests, segments, SSE progress, batch operations, and compatibility submission |
| Images and video previews | /api/v1/images/* | Gallery, thumbnails, EXIF, privacy-stripped files, transforms, previews, and trickplay |
| Audio | /api/v1/audio/* | Browsing, streaming, negotiated audio sessions, playback reporting, and playlists |
| Chapters | /api/v1/chapters* | Local chapter sets and chapter marks |
| eForms | /api/v1/forms, /api/v1/metadata, /api/v1/search | Form definitions, metadata CRUD, FTS5 search, import/export, and relocation |
| Webhooks | /api/v1/transcode/webhooks/* | Transcode webhook subscriptions and delivery history |
| Config/auth | /api/v1/config, /api/v1/auth/users | Sanitized runtime config and API-user management |
| Admin | /api/v1/admin/* | Cache purge, vacuum, storage info, quotas, backups, library scans, diagnostics, and repairs |
| Qloud compatibility | /proc/* and /{qloudToken}/* on the compatibility listener | Legacy Qloud browse, info, preview, video, and HLS bridge routes |

## Architecture

```
HTTP Request -> Javalin Routes -> Services -> FFmpeg / SQLite / Filesystem
```

Key packages under `modules/*/*/src/main/java/com/nyx/`:
- `playback/` - Playback decisions, sessions, lifecycle, delivery outcomes, media-session reporting
- `stream/representation/` - Typed stream representation, packaging traits, storage tokens, artifact policy
- `transcode/` - Job runtime, persistence, segment delivery, batch operations, webhooks, FFmpeg execution modes
- `ffmpeg/` - Command construction, probing, preview generation, trickplay generation, subtitle extraction
- `media/` - Browse/search, media objects, libraries, scans, thumbnails, images, audio, playlists, chapters, user state
- `eforms/` - Extensible metadata forms, schema versioning, FTS5 search, export/import, relocation
- `storage/` - Local and S3-compatible cache object backends
- `admin/` - Health, metrics, backups, quotas, storage info, config/user admin, library diagnostics
- `qloud/` - Legacy Qloud compatibility adapter and response/route policies
- `common/`, `config/`, `http/` - Path security, error contracts, HOCON config, routing, auth, and OpenAPI support

## Development

```bash
# Run tests
make test

# Run single test class
make test-single TEST=com.nyx.transcode.FFmpegProcessTest

# Check the mobile-facing OpenAPI contract snapshot
./codex/mvnw.sh -pl :app -Dtest=ApplicationTest#getOpenapiJsonEndpointMatchesMobileContractSnapshot test

# Generate coverage reports and enforce the 90% line threshold
make coverage
# Reports are written under modules/*/*/target/site/jacoco/
```

The mobile/client OpenAPI snapshot lives at
`modules/application/app/src/test/resources/openapi/mobile-client-openapi-snapshot.json`.
Intentional changes to the release-facing client routes should update that
snapshot together with the code and `ai/design/api/mobile-client-contract.md`.

## Docker

```bash
# Build image
docker build -t nyx-media-streamer .

# Run with docker-compose
docker-compose up

# Or run directly
docker run -p 8080:8080 -v /path/to/media:/media:ro nyx-media-streamer
```

## License

Except where otherwise noted, the project-authored code and documentation in this repository are Copyright (C) 2025-2026 Nos Doughty.

Nyx Media Streamer is licensed under the GNU Affero General Public License v3.0 only (`AGPL-3.0-only`). See [LICENSE](LICENSE) for the full text and [COPYRIGHT](COPYRIGHT) for the repository notice. This program is distributed without any warranty.
