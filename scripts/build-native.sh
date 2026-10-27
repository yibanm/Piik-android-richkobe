#!/usr/bin/env bash
# Builds the native executables the app bundles, from source:
#   libpiik.so         Piik's own App engine (cmd/piik-app) + the Android patch
#   libpiikcapture.so  the capture shim (native/capture-shim)
#   libcloudflared.so  Cloudflare's tunnel client, for Piik "Public invite" links
#
# They are ordinary Go executables named lib*.so so Android installs them into
# the app's native library directory, the one place apps may execute files.
#
# Requirements: git, Go >= 1.27.3, Node >= 24 (<27) with npm (Piik's web UI build).
#   版本取自上游 v2.4.1 的 go.mod（go 1.27.3）与 package.json（engines.node ">=24.0.0 <27"）；
#   低于此版本 Go 会因工具链版本不足直接拒绝构建。
# Usage:  scripts/build-native.sh            (arm64 phones)
#         ABIS="arm64-v8a x86_64" scripts/build-native.sh   (+ emulator)
set -euo pipefail

PIIK_REPO=${PIIK_REPO:-https://github.com/TNTcraftHIM/Piik}
# Pinned: the patch and the capture contract (protocol 7) were verified against it.
# v2.4.1 — 远程同乐（共用键鼠/手柄）、分享声音与房间控制改进、连接恢复与采集兼容性修复；
# 完整中文语言包（zh.ts 1049 行）。已核对：probeProtocol 仍为 7、Capabilities 字段未变、
# 传给采集进程的参数集合与 v2.3.0 完全一致（含 --protocol-v7）、go.mod/go.sum 与 v2.3.0
# md5 相同（无新依赖、无 cgo 风险）、工具链要求 go 1.27.3 / node >=24 <27 未变；
# 补丁 0001-android-support.patch 经 git apply --check 验证可干净应用，其新建的
# anet_android.go / dns_android.go 上游仍不存在（无撞车），依赖的 wlynxg/anet v0.0.5 在 go.mod 中。
PIIK_REF=${PIIK_REF:-066e218d84a237db41d455975c89a002eb3d0d79}
CLOUDFLARED_REPO=${CLOUDFLARED_REPO:-https://github.com/cloudflare/cloudflared}
CLOUDFLARED_VERSION=${CLOUDFLARED_VERSION:-2026.8.3} # the version Piik ships
ABIS=${ABIS:-"arm64-v8a armeabi-v7a"}
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
    armeabi-v7a) echo arm ;;
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

# 找 NDK（armeabi-v7a 的 cgo 链接需要它的 clang）。CI runner 预装并导出
# ANDROID_NDK_HOME；本机缺失时按 ANDROID_HOME/ndk/* 兜底探测。
find_ndk() {
  local ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ]; then
    ndk=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
  fi
  [ -n "$ndk" ] || { echo "armeabi-v7a 需要 Android NDK（设置 ANDROID_NDK_HOME），未找到" >&2; exit 1; }
  local cc
  cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/armv7a-linux-androideabi26-clang 2>/dev/null | head -1)
  [ -n "$cc" ] && [ -x "$cc" ] || { echo "NDK clang 不存在：$ndk/toolchains/llvm/prebuilt/*/bin/armv7a-linux-androideabi26-clang" >&2; exit 1; }
  echo "$cc"
}

for abi in $ABIS; do
  arch=$(goarch "$abi")
  out=$JNI/$abi
  mkdir -p "$out"
  if [ "$abi" = "armeabi-v7a" ]; then
    # Go 对 android/arm（32 位）强制 cgo 外部链接：纯 CGO_ENABLED=0 会报
    # "android/arm requires external (cgo) linking, but cgo is not enabled"。
    # 用 NDK clang 作 CC（API 26 对齐 minSdk），GOARM=7 面向主流 32 位机。
    CC7=$(find_ndk)
    echo "==> $abi: libpiik.so (cgo, CC=$CC7)"
    (cd "$PIIK" && CGO_ENABLED=1 GOOS=android GOARCH=$arch GOARM=7 CC="$CC7" go build -trimpath -ldflags "$LDFLAGS" -o "$out/libpiik.so" ./cmd/piik-app)
    echo "==> $abi: libpiikcapture.so (cgo)"
    (cd "$ROOT/native/capture-shim" && CGO_ENABLED=1 GOOS=android GOARCH=$arch GOARM=7 CC="$CC7" go build -trimpath -ldflags "-s -w" -o "$out/libpiikcapture.so" .)
  else
    echo "==> $abi: libpiik.so"
    (cd "$PIIK" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "$LDFLAGS" -o "$out/libpiik.so" ./cmd/piik-app)
    echo "==> $abi: libpiikcapture.so"
    (cd "$ROOT/native/capture-shim" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$out/libpiikcapture.so" .)
  fi
done

# ---- cloudflared (optional: without it, Local and Site modes still work) ----
if [ "$SKIP_CLOUDFLARED" != 1 ]; then
  CFD=$WORK/cloudflared
  echo "==> cloudflared $CLOUDFLARED_VERSION"
  fetch "$CLOUDFLARED_REPO" "refs/tags/$CLOUDFLARED_VERSION" "$CFD"
  # Same DNS fix as Piik: pure-Go binaries on Android otherwise can't resolve names.
  cp "$ROOT/native/android_dns.go" "$CFD/cmd/cloudflared/android_dns.go"
  for abi in $ABIS; do
    # cloudflared 只为 64 位构建：其 android/arm（32 位）交叉编译未经上游验证，
    # 强行编译失败会连累整个 CI（连 arm64 包都出不来）。壳层 PiikService 对
    # libcloudflared.so 是「存在才启用隧道」，故 32 位设备缺它只是没有「公开邀请」
    # 模式，Local / Site 两种共享照常可用。
    if [ "$abi" = "armeabi-v7a" ]; then
      echo "==> $abi: skip libcloudflared.so (32-bit tunnel not built)"
      continue
    fi
    arch=$(goarch "$abi")
    echo "==> $abi: libcloudflared.so"
    (cd "$CFD" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -mod=vendor -trimpath \
      -ldflags "-s -w -checklinkname=0 -X main.Version=$CLOUDFLARED_VERSION" \
      -o "$JNI/$abi/libcloudflared.so" ./cmd/cloudflared)
  done
fi

echo "==> done"
ls -la "$JNI"/*/
