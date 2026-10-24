# Changelog

## 0.1.0 — 2026-10-24

First public build. Runtime behaviour is **not yet verified on a physical ARM64
device**; see "Known limitations" below.

### Added

- Offline GGUF chat through llama.cpp v0.7.0 compiled for `arm64-v8a` with JNI,
  mmap loading, configurable context size, thread count and GPU layers.
- Hugging Face model search, metadata view and resumable downloads with `Range`
  requests, `.part` recovery and optional `HF_TOKEN` support.
- Optional PRoot v5.3.0 / Alpine 3.20.3 coding container exposing `read_file`,
  `write_file`, `list_dir` and `exec_shell` tools to the model.
- Streaming chat UI with turn cancellation, tool status and workspace snapshots.
- Separate model and container settings with per-section reset.
- RAM estimate before loading; warning above 50–60 % of available memory.
- Foreground service with ref-counted start/stop and a cancellation latch.
- Two flavors: `proot` (targetSdk 28, container enabled, sideload) and
  `standard` (targetSdk 35, container disabled).
- 40 JVM tests covering the tool-call parser, prompt builder, command policy,
  GGUF metadata and Hugging Face model parsing.

### Security / build

- Pinned and SHA-256-verified third-party archives in `scripts/fetch_deps.sh`.
- Never commits `llama.cpp/`, the Alpine rootfs, PRoot binaries or signing keys.
- Path jail based on canonical paths for the file tools; output caps, timeouts
  and a soft command deny-list for `exec_shell`.
- Optional `-PlocalaiNativeJobs=N` ninja job-pool cap for low-RAM build hosts.
- Optional release signing through `~/.localai-release/keystore.properties`.

### Known limitations

- No on-device validation yet: inference, downloads, rootfs extraction, PRoot
  execution, snapshots and 10-minute stability are unverified at runtime.
- The `proot` flavor targets SDK 28 and is sideload-only; it cannot be published
  on Google Play.
- PRoot confines damage to app-private storage but is not a security sandbox.
- PRoot is GPL-2.0; redistributing an APK that contains `libproot.so` requires
  GPL compliance.
- `androidx.security:security-crypto` is an alpha dependency.
- Model integrity is checked by GGUF magic and size, not by the Hugging Face
  LFS SHA-256.
