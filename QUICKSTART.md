# Quickstart

These commands assume you are in the project root.

## Admin UI

Build the admin UI and package it into the application:

```bash
cd admin-ui
npm install
npm run build
cd ..

./codex/mvnw.sh -pl :app -am -DskipTests package
```

Create local development directories:

```bash
mkdir -p /tmp/nyx-media-root /tmp/nyx-admin-db /tmp/nyx-admin-cache
```

Start the application with local auth enabled:

```bash
env NYX_HOST=127.0.0.1 \
    NYX_PORT=8090 \
    NYX_MEDIA_ROOT=/tmp/nyx-media-root \
    NYX_DATABASE_DIR=/tmp/nyx-admin-db \
    NYX_STORAGE_LOCAL_CACHE_DIR=/tmp/nyx-admin-cache \
    NYX_AUTH_ENABLED=true \
    NYX_AUTH_TOKEN=nyx-dev-token \
    ./modules/application/app/target/app/bin/app
```

Open the UI:

```text
http://127.0.0.1:8090/
```

Login with:

```text
Mode: Bearer Token
Token: nyx-dev-token
```

The API health endpoint is:

```text
http://127.0.0.1:8090/api/v1/health
```

## Qloud Compatibility

The Qloud compatibility listener is separate from the admin UI. The admin UI is served by `NYX_PORT`; legacy Qloud clients should connect to the Qloud compatibility port or to a debugging proxy in front of it.

Build the app:

```bash
./codex/mvnw.sh -pl :app -am -DskipTests package
```

Create local runtime directories:

```bash
mkdir -p /tmp/nyx-qloud-media /tmp/nyx-qloud-db /tmp/nyx-qloud-cache
```

Start Nyx with Qloud compatibility enabled:

```bash
env NYX_HOST=127.0.0.1 \
    NYX_PORT=8890 \
    NYX_QLOUD_COMPAT_ENABLED=true \
    NYX_QLOUD_COMPAT_HOST=127.0.0.1 \
    NYX_QLOUD_COMPAT_PORT=8891 \
    NYX_QLOUD_HANDSHAKE_UPSTREAM=http://127.0.0.1:8888 \
    NYX_QLOUD_LEGACY_TS_HLS=true \
    NYX_MEDIA_ROOT=/tmp/nyx-qloud-media \
    NYX_DATABASE_DIR=/tmp/nyx-qloud-db \
    NYX_STORAGE_LOCAL_CACHE_DIR=/tmp/nyx-qloud-cache \
    NYX_AUTH_ENABLED=false \
    ./modules/application/app/target/app/bin/app
```

`NYX_QLOUD_HANDSHAKE_UPSTREAM` is required for older third-party Qloud clients that validate the native hello/auth token handshake. Run a local Qloud server on that upstream URL before starting Nyx, or omit the setting only when testing clients that accept Nyx's built-in handshake tokens.

`NYX_QLOUD_LEGACY_TS_HLS=true` keeps the Qloud shim on the old MPEG-TS HLS mode. Older Qloud clients may browse successfully but fail playback if this is omitted, because the default modern HLS path uses fMP4/CMAF segments.

For debugging with mitmproxy, keep `NYX_QLOUD_COMPAT_HOST=127.0.0.1` and expose a LAN-facing proxy. This keeps the legacy shim reachable only through the proxy while still letting mitmproxy capture the client traffic:

```bash
uvx --from mitmproxy mitmdump \
  --listen-host 0.0.0.0 \
  --listen-port 8889 \
  --mode reverse:http://127.0.0.1:8891 \
  --set keep_host_header=true \
  --flow-detail 3 \
  -w debug/qloud-mitm/qloud-$(date +%Y%m%d-%H%M%S).mitm
```

Point the Qloud client at:

```text
<LAN_IP>:8889
```

The proxy listen port can be any free LAN-facing port. For example, use `--listen-port 8091` if you want the client to connect to `<LAN_IP>:8091`.

Without mitmproxy, expose `NYX_QLOUD_COMPAT_HOST` on an address reachable by the client and point the client at:

```text
<LAN_IP>:8891
```

Do not point another device directly at `8891` while `NYX_QLOUD_COMPAT_HOST=127.0.0.1`; that listener is intentionally loopback-only and will appear unreachable from the client.

## Troubleshooting

If port `8090` is already in use, either stop the process using it or change `NYX_PORT`.

```bash
lsof -iTCP:8090 -sTCP:LISTEN -n -P
```

If `/api/v1/health` works but `/` is blank, the backend is running but the browser is probably not loading the compiled frontend assets. Rebuild the UI, repackage the app, and restart the process:

```bash
cd admin-ui
npm run build
cd ..

./codex/mvnw.sh -pl :app -am -DskipTests package
```

Then confirm the root page references the built assets:

```bash
curl -s http://127.0.0.1:8090/ | rg 'assets|script|stylesheet'
```

And confirm a JavaScript asset returns `200`:

```bash
curl -s http://127.0.0.1:8090/ \
  | rg -o '/assets/[^"]+\.js' \
  | head -n 1 \
  | xargs -I{} curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8090{}
```

If a legacy Qloud client says the server is unreachable immediately, first confirm that `NYX_QLOUD_HANDSHAKE_UPSTREAM` points at a running Qloud server. Then run the mitmproxy command above and check whether the client reaches `/proc/hello`, `/proc/auth`, and `/proc/list`.

If the client reaches `/proc/list` but fails after choosing a recent or bookmarked file, capture the request body. Older clients can send file paths back to `/proc/list`; the compatibility shim should treat those as containing-folder browse requests while the normal Nyx browse API remains strict.

If the client can browse and starts playback but immediately stops, confirm Nyx was started with `NYX_QLOUD_LEGACY_TS_HLS=true`. In the mitmproxy log, the old-client path should fetch a master playlist, a variant playlist, and `.ts` segment URLs with `video/mp2t` responses.

Use a real-length media file when testing playback. Very short clips can end before the old client has settled playback, and some versions report that as a remote-server reachability error even when the proxy and segment requests succeeded.
