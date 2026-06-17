#!/usr/bin/env bash
# Cleanroom test runner — builds a fresh Docker image, then runs the full test
# suite in an ephemeral container. Tests execute at `docker run` time so
# Docker layer caching never skips them.
#
# Usage: ./docker-test.sh [maven args...]
# Examples:
#   ./docker-test.sh                                                    # all tests
#   ./docker-test.sh -Dtest=com.nyx.transcode.FFmpegProcessTest test    # single class
#   ./docker-test.sh -Dtest='*MediaFileServiceTest' test -X             # with extra flags
set -euo pipefail

IMAGE="nyx-test-runner"

echo "==> Building test image..."
docker build -t "$IMAGE" -f Dockerfile.test .

echo "==> Running tests..."
docker run --rm "$IMAGE" "$@"
