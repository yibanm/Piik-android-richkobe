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
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}
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
  # 老 WebView 兼容（白屏根因修复）：上游 vite.config.ts 未设 build.target，
  # 产物含 Chrome 74（Android 9 出厂 WebView）无法解析的语法（?. / ?? / class
  # fields——acorn ES2019 校验 10 个 js 中 8 个解析失败），SPA 起不来即白屏。
  # 用上游 node_modules 自带的 esbuild 把 dist 里的 js 逐文件原地降级到
  # chrome61（对齐 Android 8 出厂 WebView，兑现「Android 8.0 及以上」承诺；
  # 零新增依赖）。只降语法不补运行时 API——缺的 globalThis / Promise.allSettled /
  # replaceAll 等由壳层 WebActivity.kt 的 OLD_WEBVIEW_POLYFILLS 在 onPageStarted 注入。
  echo "==> transpile dist js for old WebViews (esbuild --target=chrome61)"
  DIST=$PIIK/internal/server/webassets/dist
  for js in "$DIST"/assets/*.js; do
    [ -e "$js" ] || continue
    (cd "$PIIK" && ./node_modules/.bin/esbuild "$js" --target=chrome61 --format=esm --outfile="$js" --allow-overwrite)
  done
  (cd "$PIIK" && ./node_modules/.bin/esbuild "$DIST"/index.html --loader=html --target=chrome61 --outfile="$DIST/index.html" --allow-overwrite 2>/dev/null || true)
  # 门禁：转译后所有 js 必须能被 ES2019 解析器（≈Chrome 74 能力）接受，否则构建失败。
  # acorn 不在上游依赖里，缺失时 --no-save 临时装（不污染 package.json）。
  (cd "$PIIK" && node -e 'require("acorn")' 2>/dev/null || npm --prefix "$PIIK" i acorn --no-save --no-audit --no-fund >/dev/null)
  (cd "$PIIK" && node -e '
    const acorn = require("acorn"), fs = require("fs"), path = require("path");
    const dir = "internal/server/webassets/dist/assets";
    let fail = 0;
    for (const f of fs.readdirSync(dir).filter(f => f.endsWith(".js"))) {
      try { acorn.parse(fs.readFileSync(path.join(dir, f), "utf8"), { ecmaVersion: 2019, sourceType: "module" }); }
      catch (e) { fail++; console.error("ES2019 PARSE FAIL:", f, e.message); }
    }
    if (fail) { console.error("transpile gate: " + fail + " file(s) still unparsable by ES2019"); process.exit(1); }
    console.log("transpile gate: all dist js parsable as ES2019");
  ')
fi

# -checklinkname=0: pion's Android interface listing (wlynxg/anet) needs it.
LDFLAGS="-s -w -checklinkname=0 -X github.com/TNTcraftHIM/Piik/internal/app.BuildRevision=${PIIK_REF:0:12}-android"

# 找 NDK（armeabi-v7a / x86_64 的 cgo 链接需要它的 clang）。CI runner 预装并导出
# ANDROID_NDK_HOME；本机缺失时按 ANDROID_HOME/ndk/* 兜底探测。
find_ndk_dir() {
  local ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ]; then
    ndk=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
  fi
  [ -n "$ndk" ] || { echo "cgo ABI 需要 Android NDK（设置 ANDROID_NDK_HOME），未找到" >&2; exit 1; }
  echo "$ndk"
}

# 按 ABI 返回 NDK clang（API 26 对齐 minSdk）。
ndk_cc() {
  local ndk triple cc
  ndk=$(find_ndk_dir)
  case "$1" in
    armeabi-v7a) triple=armv7a-linux-androideabi26-clang ;;
    x86_64) triple=x86_64-linux-android26-clang ;;
    *) echo "ndk_cc: ABI $1 不需要 NDK clang" >&2; exit 1 ;;
  esac
  cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/"bin/$triple" 2>/dev/null | head -1)
  [ -n "$cc" ] && [ -x "$cc" ] || { echo "NDK clang 不存在：$ndk/toolchains/llvm/prebuilt/*/bin/$triple" >&2; exit 1; }
  echo "$cc"
}

# wlynxg/anet 对 android/arm 与 android/amd64 只有 cgo 实现（arm64 有纯 Go 路径），
# 纯 CGO_ENABLED=0 会报 "requires external (cgo) linking"。这两个 ABI 走 NDK cgo。
needs_cgo() {
  case "$1" in
    armeabi-v7a|x86_64) return 0 ;;
    *) return 1 ;;
  esac
}

# Android Bionic 无 getloadavg(3)：开 cgo 后 go-osstat/loadavg 的 cgo 文件引用
# C.getloadavg 导致编译失败（arm64 纯 Go 路径跳过该文件，不受影响）。
# 注意：CGO_CFLAGS 注入存根头文件的方案已实测失败（stub.h 里的 #include <stdlib.h>
# 让 NDK 头文件在 cgo 的错误上下文里被重新解析，报 unexpected token）。改用
# vendor 覆盖：go mod vendor 后把 loadavg_unix.go 换成直接返回错误的纯 Go 实现，
# 整个包里不再有 C.getloadavg 引用。loadavg 仅用于系统负载统计，读取失败不影响
# 核心投屏。操作幂等，多个 cgo ABI 重复执行无副作用。
prepare_vendor() {
  echo "==> vendoring deps and stubbing go-osstat/loadavg"
  (cd "$PIIK" && go mod vendor)
  cat > "$PIIK/vendor/github.com/mackerelio/go-osstat/loadavg/loadavg_unix.go" <<'STUB'
//go:build !windows && cgo

package loadavg

import "errors"

// Android Bionic 无 getloadavg(3)，原 cgo 实现编不过；覆盖为始终失败。
func get() (*Stats, error) {
	return nil, errors.New("getloadavg is not available on Android (Bionic)")
}
STUB
}

for abi in $ABIS; do
  arch=$(goarch "$abi")
  out=$JNI/$abi
  mkdir -p "$out"
  if needs_cgo "$abi"; then
    CC_NDK=$(ndk_cc "$abi")
    prepare_vendor
    # GOARM=7 只对 32 位 ARM 有意义，面向主流 32 位机。
    if [ "$arch" = arm ]; then
      echo "==> $abi: libpiik.so (cgo, CC=$CC_NDK)"
      (cd "$PIIK" && CGO_ENABLED=1 GOOS=android GOARCH=$arch GOARM=7 CC="$CC_NDK" go build -mod=vendor -trimpath -ldflags "$LDFLAGS" -o "$out/libpiik.so" ./cmd/piik-app)
      echo "==> $abi: libpiikcapture.so (cgo)"
      (cd "$ROOT/native/capture-shim" && CGO_ENABLED=1 GOOS=android GOARCH=$arch GOARM=7 CC="$CC_NDK" go build -trimpath -ldflags "-s -w" -o "$out/libpiikcapture.so" .)
    else
      echo "==> $abi: libpiik.so (cgo, CC=$CC_NDK)"
      (cd "$PIIK" && CGO_ENABLED=1 GOOS=android GOARCH=$arch CC="$CC_NDK" go build -mod=vendor -trimpath -ldflags "$LDFLAGS" -o "$out/libpiik.so" ./cmd/piik-app)
      echo "==> $abi: libpiikcapture.so (cgo)"
      (cd "$ROOT/native/capture-shim" && CGO_ENABLED=1 GOOS=android GOARCH=$arch CC="$CC_NDK" go build -trimpath -ldflags "-s -w" -o "$out/libpiikcapture.so" .)
    fi
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
    arch=$(goarch "$abi")
    if needs_cgo "$abi"; then
      # 与 libpiik 同理：android/arm 与 android/amd64 均强制 cgo 外部链接，
      # 用对应 ABI 的 NDK clang（API 26 对齐 minSdk）。
      CC_NDK=$(ndk_cc "$abi")
      if [ "$arch" = arm ]; then
        echo "==> $abi: libcloudflared.so (cgo, CC=$CC_NDK)"
        (cd "$CFD" && CGO_ENABLED=1 GOOS=android GOARCH=$arch GOARM=7 CC="$CC_NDK" go build -mod=vendor -trimpath \
          -ldflags "-s -w -checklinkname=0 -X main.Version=$CLOUDFLARED_VERSION" \
          -o "$JNI/$abi/libcloudflared.so" ./cmd/cloudflared)
      else
        echo "==> $abi: libcloudflared.so (cgo, CC=$CC_NDK)"
        (cd "$CFD" && CGO_ENABLED=1 GOOS=android GOARCH=$arch CC="$CC_NDK" go build -mod=vendor -trimpath \
          -ldflags "-s -w -checklinkname=0 -X main.Version=$CLOUDFLARED_VERSION" \
          -o "$JNI/$abi/libcloudflared.so" ./cmd/cloudflared)
      fi
    else
      echo "==> $abi: libcloudflared.so"
      (cd "$CFD" && GOOS=android GOARCH=$arch CGO_ENABLED=0 go build -mod=vendor -trimpath \
        -ldflags "-s -w -checklinkname=0 -X main.Version=$CLOUDFLARED_VERSION" \
        -o "$JNI/$abi/libcloudflared.so" ./cmd/cloudflared)
    fi
  done
fi

echo "==> done"
ls -la "$JNI"/*/
