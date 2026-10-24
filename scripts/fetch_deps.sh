#!/usr/bin/env bash
# Fetches the three non-source dependencies. Idempotent; set FORCE=1 to redownload.
#
#   llama.cpp    v0.7.0 (pinned; the JNI layer is written against this API)
#   PRoot        v5.3.0 static aarch64 (upstream, supports -0 and --link2symlink)
#   Alpine       v3.20.3 minirootfs (~3.8 MB)
#
# Everything lands inside the project; no root, no Android SDK writes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_REF="${LLAMA_REF:-v0.7.0}"
PROOT_VERSION="${PROOT_VERSION:-v5.3.0}"
ALPINE_VERSION="${ALPINE_VERSION:-3.20.3}"

CPP_DIR="$ROOT/app/src/main/cpp/llama.cpp"
PROOT_DIR="$ROOT/app/src/proot/jniLibs/arm64-v8a"
ASSET_DIR="$ROOT/app/src/proot/assets/rootfs"

FORCE="${FORCE:-0}"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

need() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
}

need curl
need tar
need sha256sum

mkdir -p "$CPP_DIR" "$PROOT_DIR" "$ASSET_DIR"

# ---------------------------------------------------------------- llama.cpp
if [ "$FORCE" = "1" ] || [ ! -f "$CPP_DIR/CMakeLists.txt" ]; then
    log "Downloading llama.cpp $LLAMA_REF"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL "https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_REF}.tar.gz" \
        -o "$tmp/llama.tar.gz"
    rm -rf "$CPP_DIR"
    mkdir -p "$CPP_DIR"
    tar xzf "$tmp/llama.tar.gz" -C "$CPP_DIR" --strip-components=1
    log "llama.cpp ready at app/src/main/cpp/llama.cpp"
else
    log "llama.cpp already present (FORCE=1 to refresh)"
fi

# ---------------------------------------------------------------- PRoot
PROOT_URL="https://github.com/proot-me/proot/releases/download/${PROOT_VERSION}/proot-${PROOT_VERSION}-aarch64-static"
PROOT_SHA="fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"
PROOT_DEST="$PROOT_DIR/libproot.so"

if [ "$FORCE" = "1" ] || [ ! -f "$PROOT_DEST" ]; then
    log "Downloading PRoot ${PROOT_VERSION} (aarch64 static)"
    curl -fsSL "$PROOT_URL" -o "$PROOT_DEST.tmp"
    echo "$PROOT_SHA  $PROOT_DEST.tmp" | sha256sum -c - \
        || die "PRoot checksum mismatch. Review scripts/fetch_deps.sh before trusting the binary."
    mv "$PROOT_DEST.tmp" "$PROOT_DEST"
    chmod 0755 "$PROOT_DEST"
    log "PRoot ready at app/src/proot/jniLibs/arm64-v8a/libproot.so"
else
    log "PRoot already present (FORCE=1 to refresh)"
fi

# ---------------------------------------------------------------- Alpine rootfs
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-${ALPINE_VERSION}-aarch64.tar.gz"
ALPINE_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"
ALPINE_DEST="$ASSET_DIR/alpine-aarch64.tar.gz"

if [ "$FORCE" = "1" ] || [ ! -f "$ALPINE_DEST" ]; then
    log "Downloading Alpine ${ALPINE_VERSION} minirootfs"
    curl -fsSL "$ALPINE_URL" -o "$ALPINE_DEST.tmp"
    echo "$ALPINE_SHA  $ALPINE_DEST.tmp" | sha256sum -c - \
        || die "Alpine checksum mismatch."
    mv "$ALPINE_DEST.tmp" "$ALPINE_DEST"
    log "Alpine rootfs ready at app/src/proot/assets/rootfs/alpine-aarch64.tar.gz"
else
    log "Alpine rootfs already present (FORCE=1 to refresh)"
fi

cat <<'EOF'

Dependencies ready.

Next:
  1. Set sdk.dir in local.properties (or open the project in Android Studio).
  2. Build the proot flavor (targetSdk 28, container enabled):
       ./gradlew assembleProotDebug
     or the Play-compatible chat-only flavor:
       ./gradlew assembleStandardDebug

Note: the -proot APK targets SDK 28 because Android 10+ blocks execve() of
guest binaries from app data when targetSdk >= 29. Sideload it; Play policy
does not allow that targetSdk for new uploads.
EOF
