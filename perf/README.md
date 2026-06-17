# Nyx Media Streamer — Load Tests

Performance tests using [K6](https://k6.io/). Run these against a live Nyx
instance to measure throughput and latency baselines.

---

## Prerequisites

Install K6:
```bash
# macOS
brew install k6

# Ubuntu/Debian
sudo apt-get install -y gnupg
curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/k6.gpg
echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install -y k6
```

---

## Running tests locally

Start the Nyx server first:
```bash
./codex/mvnw.sh -pl :app -am -DskipTests package
./modules/application/app/target/app/bin/app
```

Then run a K6 script:
```bash
# Audio listing — 100 VUs, 30 s
k6 run perf/k6/audio.js

# Image listing — 50 VUs, 30 s
k6 run perf/k6/thumbnails.js

# Transcode job listing — 10 VUs, 60 s
k6 run perf/k6/transcode.js
```

---

## Configuration

| Env var           | Default                   | Description                         |
|-------------------|---------------------------|-------------------------------------|
| `NYX_BASE_URL`    | `http://localhost:8080`   | Base URL of the running server      |
| `NYX_AUTH_TOKEN`  | (empty)                   | Bearer token if auth is enabled     |

Example with a remote server:
```bash
NYX_BASE_URL=https://nyx.example.com NYX_AUTH_TOKEN=secret k6 run perf/k6/audio.js
```

---

## Scripts

| Script              | VUs | Duration | Threshold (p95) | What it tests            |
|---------------------|-----|----------|-----------------|--------------------------|
| `audio.js`          | 100 | 30 s     | < 2 s           | Audio file listing       |
| `thumbnails.js`     | 50  | 30 s     | < 2 s           | Image file listing       |
| `transcode.js`      | 10  | 60 s     | < 500 ms        | Transcode job listing    |

All scripts assert `http_req_failed < 1%` (error rate) and the p95 latency
threshold shown above.

---

## Nightly CI

The `.github/workflows/nightly.yml` workflow runs all three scripts against a
locally started server every night at 02:00 UTC. Results are uploaded as
artifacts with 30-day retention for trend analysis.
