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
#   版本取自上游 v2.5.0 的 go.mod（go 1.27.3）与 package.json（engines.node ">=24.0.0 <27"）；
#   低于此版本 Go 会因工具链版本不足直接拒绝构建。
# Usage:  scripts/build-native.sh            (arm64 phones)
#         ABIS="arm64-v8a x86_64" scripts/build-native.sh   (+ emulator)
set -euo pipefail

PIIK_REPO=${PIIK_REPO:-https://github.com/TNTcraftHIM/Piik}
# Pinned: the patch and the capture contract (protocol 7) were verified against it.
# v2.5.0 — Android 共享与触控控制进入共享运行时（#504）、悬浮工具输入焦点修复（#507）。
# 已核对：probeProtocol 仍为 7；Capabilities 仅新增 remoteInput/captureBorderControl
# 两个 omitempty 字段（Kotlin probe JSON 不传即兼容，DisallowUnknownFields 不受影响）；
# 采集参数集不变（--probe/--protocol-v7/--capture-video/--encoded-video 均在）；
# go.mod 仍 go 1.27.3、engines node >=24<27（工具链要求不变）；vite 仍无 build.target
# （dist 转译+门禁链路继续有效）；版本变量搬家：BuildRevision 从 cmd/piik-app 移至
# internal/app/buildinfo 包的 Revision（见下方 LDFLAGS）；上游新增 mobile/ 目录为官方
# 版自己的桥，与 cmd/piik-app+capture-shim 路线不冲突。
# 补丁 0001-android-support.patch 已在纯净 v2.5.0 克隆上 git apply --check 验证可干净应用，
# 其新建的 anet_android.go / dns_android.go 上游仍不存在（无撞车），依赖的 wlynxg/anet 在 go.mod 中。
PIIK_REF=${PIIK_REF:-499789671c7530b7364818b9c07123f3f290ebad}
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
  # 老 WebView 兼容（白屏根因修复）：上游 vite.config.ts 未设 build.target，
  # 产物含 Chrome 74（Android 9 出厂 WebView）无法解析的语法（?. / ?? / 逻辑赋值等，
  # acorn 校验 10 个 js 中 8 个解析失败），SPA 起不来即白屏。
  # 用上游 node_modules 自带的 esbuild 把 dist 里的 js 逐文件原地降级。
  # target 必须=chrome74（要覆盖的最老可运行内核），**不能更低**：chrome61 会让
  # esbuild 把 import.meta 替换成空对象（61 不支持），new URL(import.meta.url)
  # 变 new URL(undefined) 直接 Invalid URL——连现代内核一起炸（2026-10-27 实测）。
  # chrome74 原生支持 import.meta / 动态 import / 类字段，esbuild 只降级它缺的
  # ?./??/逻辑赋值/数字分隔符等，两代内核都正常。只降语法不补运行时 API——缺的
  # Promise.allSettled / replaceAll / Object.hasOwn 等由壳层 WebActivity.kt 的
  # assets/old-webview.js 在 onPageStarted 注入。
  echo "==> transpile dist js for old WebViews (esbuild --target=chrome74)"
  DIST=$PIIK/internal/server/webassets/dist
  for js in "$DIST"/assets/*.js; do
    [ -e "$js" ] || continue
    (cd "$PIIK" && ./node_modules/.bin/esbuild "$js" --target=chrome74 --format=esm --outfile="$js" --allow-overwrite)
  done
  # 门禁：转译后不得残留 chrome74 缺的语法（AST 精确扫描，不用整档 ecmaVersion——
  # 类字段/import.meta 在 chrome74 是合法的，ES2019 档会误杀）。acorn 不在上游
  # 依赖里，缺失时 --no-save 临时装（不污染 package.json）。
  (cd "$PIIK" && node -e 'require("acorn")' 2>/dev/null || npm --prefix "$PIIK" i acorn --no-save --no-audit --no-fund >/dev/null)
  (cd "$PIIK" && node -e '
    const acorn = require("acorn"), fs = require("fs"), path = require("path");
    const dir = "internal/server/webassets/dist/assets";
    const BAD_OPS = new Set(["??=", "||=", "&&="]);
    function walk(n, cb) {
      if (!n || typeof n !== "object") return;
      if (Array.isArray(n)) { n.forEach(x => walk(x, cb)); return; }
      if (typeof n.type === "string") cb(n);
      for (const k of Object.keys(n)) if (k !== "type") walk(n[k], cb);
    }
    let fail = 0;
    for (const f of fs.readdirSync(dir).filter(f => f.endsWith(".js"))) {
      const code = fs.readFileSync(path.join(dir, f), "utf8");
      let ast;
      try { ast = acorn.parse(code, { ecmaVersion: 2022, sourceType: "module" }); }
      catch (e) { fail++; console.error("PARSE FAIL:", f, e.message); continue; }
      const hits = new Set();
      walk(ast, n => {
        if (n.type === "ChainExpression") hits.add("optional chaining (?.)");
        else if (n.type === "LogicalExpression" && n.operator === "??") hits.add("nullish (??)");
        else if (n.type === "AssignmentExpression" && BAD_OPS.has(n.operator)) hits.add("logical assignment " + n.operator);
      });
      // 数字分隔符 1_000 是 chrome75+，chrome74 解析不了；esbuild 应已降级，正则兜底。
      if (/\d_\d/.test(code)) hits.add("numeric separator");
      if (hits.size) { fail++; console.error("CHROME74 SYNTAX LEFT:", f, [...hits].join(", ")); }
    }
    if (fail) { console.error("transpile gate: " + fail + " file(s) bad"); process.exit(1); }
    console.log("transpile gate: all dist js clean for chrome74 syntax subset");
  ')
fi

# -checklinkname=0: pion's Android interface listing (wlynxg/anet) needs it.
# v2.5.0: revision var moved to internal/app/buildinfo.Revision (was internal/app.BuildRevision).
LDFLAGS="-s -w -checklinkname=0 -X github.com/TNTcraftHIM/Piik/internal/app/buildinfo.Revision=${PIIK_REF:0:12}-android"

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
# 上游 v2.5.0 新增 mobile/（官方 Android 桥），本移植版不用它（走 cmd/piik-app +
# capture-shim + 内置 WebView 路线）。但它没有独立 go.mod，属主 module 的一部分，
# 而 mobile/cloudflared/fallback_test.go 引用了一批只有测试才需要的包
# （cloudflare/cloudflared 的 connection/retry、rs/zerolog，且 quic-go 缺 go.sum 条目）——
# go mod vendor 会解析 module 内所有包的测试依赖，于是 vendor 直接失败，cgo ABI 全挂。
# 全仓库无任何包 import Piik/mobile（已 grep 核验），删掉它对 ./cmd/piik-app 构建零影响。
prepare_vendor() {
  echo "==> vendoring deps and stubbing go-osstat/loadavg"
  rm -rf "$PIIK/mobile" # 官方桥：依赖残缺会让 vendor 失败，且本移植版不使用
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
