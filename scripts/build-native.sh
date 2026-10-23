#!/usr/bin/env bash
# Builds the native executables the app bundles, from source:
#   libpiik.so         Piik's own App engine (cmd/piik-app) + the Android patch
#   libpiikcapture.so  the capture shim (native/capture-shim)
#   libcloudflared.so  Cloudflare's tunnel client, for Piik "Public invite" links
#
# They are ordinary Go executables named lib*.so so Android installs them into
# the app's native library directory, the one place apps may execute files.
#
# Requirements: git, Go >= 1.26, Node >= 22 with npm (Piik's web UI build).
# Usage:  scripts/build-native.sh            (arm64 phones)
#         ABIS="arm64-v8a x86_64" scripts/build-native.sh   (+ emulator)
set -euo pipefail

PIIK_REPO=${PIIK_REPO:-https://github.com/TNTcraftHIM/Piik}
# Pinned: the patch and the capture contract (protocol 7) were verified against it.
# v2.3.0 — 带房间聊天（文字/表情/语音）与完整中文语言包；probeProtocol 仍为 7，
# 补丁 0001-android-support.patch 经 git apply --check 验证可直接应用。
PIIK_REF=${PIIK_REF:-da7218bf446ed57907a3e95402fff3b4b5c37b5f}
CLOUDFLARED_REPO=${CLOUDFLARED_REPO:-https://github.com/cloudflare/cloudflared}
CLOUDFLARED_VERSION=${CLOUDFLARED_VERSION:-2026.8.3} # the version Piik ships
ABIS=${ABIS:-arm64-v8a}
SKIP_CLOUDFLARED=${SKIP_CLOUDFLARED:-0}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$ROOT/build/native}
JNI=$ROOT/app/src/main/jniLibs
mkdir -p "$WORK"

for tool in git go; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

goarch() {
  case "$1" in
    arm64-v8a) echo arm64 ;;
    x86_64) echo amd64 ;;
    *) echo "unsupported ABI $1" >&2; exit 1 ;;
  esac
}

fetch() { # repo ref dir
  if [ ! -d "$3/.git" ]; then
    git init -q "$3"
    git -C "$3" remote add origin "$1"
  fi
  git -C "$3" fetch -q --depth 1 origin "$2"
  git -C "$3" checkout -q --force FETCH_HEAD
  git -C "$3" clean -qfdx -e node_modules
}

# ---- Piik ----
PIIK=${PIIK_SRC:-$WORK/piik}
if [ -z "${PIIK_SRC:-}" ]; then
  echo "==> Piik $PIIK_REF"
  fetch "$PIIK_REPO" "$PIIK_REF" "$PIIK"
  for patch in "$ROOT"/native/piik-patches/*.patch; do
    echo "    applying $(basename "$patch")"
    git -C "$PIIK" apply "$patch"
  done
fi
if [ "${SKIP_WEB:-0}" != 1 ]; then
  command -v npm >/dev/null || { echo "missing: npm (Node.js)" >&2; exit 1; }
  echo "==> Piik web interface"
  (cd "$PIIK" && npm ci --no-audit --no-fund && npm run build:web)
fi

# -checklinkname=0: pion's Android interface listing (wlynxg/anet) needs it.
LDFLAGS="-s -w -checklinkname=0 -X github.com/TNTcraftHIM/Piik/internal/app.BuildRevision=${PIIK_REF:0:12}-android"

for abi in $ABIS; do
  arch=$(goarch "$abi")
  out=$JNI/$abi
  mkdir -p "$out"
  echo "==> $abi: libpiik.so"
  (cd "$PIIK" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "$LDFLAGS" -o "$out/libpiik.so" ./cmd/piik-app)
  echo "==> $abi: libpiikcapture.so"
  (cd "$ROOT/native/capture-shim" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$out/libpiikcapture.so" .)
done

# ---- cloudflared (optional: without it, Local and Site modes still work) ----
if [ "$SKIP_CLOUDFLARED" != 1 ]; then
  CFD=$WORK/cloudflared
  echo "==> cloudflared $CLOUDFLARED_VERSION"
  fetch "$CLOUDFLARED_REPO" "refs/tags/$CLOUDFLARED_VERSION" "$CFD"
  # Same DNS fix as Piik: pure-Go binaries on Android otherwise can't resolve names.
  cp "$ROOT/native/android_dns.go" "$CFD/cmd/cloudflared/android_dns.go"
  for abi in $ABIS; do
    arch=$(goarch "$abi")
    echo "==> $abi: libcloudflared.so"
    (cd "$CFD" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -mod=vendor -trimpath \
      -ldflags "-s -w -checklinkname=0 -X main.Version=$CLOUDFLARED_VERSION" \
      -o "$JNI/$abi/libcloudflared.so" ./cmd/cloudflared)
  done
fi

echo "==> done"
ls -la "$JNI"/*/
