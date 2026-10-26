# Changelog

## 0.2.2 — 2026-10-26

Bug-fix pass over the whole codebase: one native correctness fix, one safety fix in
the container tooling, and several smaller correctness/accuracy improvements.

### Fixed

- **Stop sequences of exactly the longest length never fired** (native): the
  forward-only scan window started one byte too far, so a marker whose length equalled
  `max_stop_len` ("[INST]" for Mistral, "<|im_start|>" for ChatML, "<|end_of_text|>"
  for Llama 3) was never matched. It leaked into the visible output and the model kept
  generating past its turn boundary. The scan now starts at `size - max_stop_len`.
- **Restoring a corrupt snapshot destroyed the workspace** (Container tab): the old
  restore command wiped `/workspace` before validating the archive. `tar -tzf` now
  verifies the snapshot first and fails with a clear message, leaving the workspace
  untouched.
- **Tool-call candidate order** (parser): fenced blocks and `<tool>`-style tags were
  tried per-shape instead of by position, so an earlier tag could shadow a later
  fence. Candidates are now sorted by position; the last call in the text wins.
- **CPU-threads slider upper bound** (Settings): now uses the detected big-core count
  (`CpuTopology`), matching the 0.2.1 changelog instead of `availableProcessors()`.
- **Generation with no stream consumer aborted after one token** (native): `emit()`
  treated a missing token sink as a failure. A null sink is now a no-op and generation
  continues.
- **Memory estimate ignored the q8_0 KV cache**: the pre-load estimate now halves the
  KV part when the q8_0 cache setting is on (34 bytes per 32 elements instead of
  F16's 2 bytes per element), removing the systematic 2x over-count for default
  configurations.
- **`generate()` return value**: an immediate EOG reported "1 token"; it now reports
  the actual count. Also guards `NewString(data, 0)` against a null pointer when
  converting empty chunks.

### Tests

- 60/60 JVM unit tests green (2 new parser-ordering tests).

## 0.2.1 — 2026-10-26

Faster generation on the same model: less memory bandwidth, smarter threads, and an
optional CPU-tuned build.

### Added

- **q8_0 KV cache** (on by default, toggle in Settings → Model): the K/V projections are
  stored as 8-bit instead of F16, halving the attention memory bandwidth that dominates
  token-by-token decode on phones. Long chats benefit the most. If the backend cannot
  serve a quantized V cache, the JNI layer transparently retries with the stock F16
  cache, so the setting can never break model loading.
- **Big-core thread auto-detection** (`CpuTopology`): the default thread count is now the
  number of cores running at 80%+ of the fastest core's frequency, read from sysfs.
  On big.LITTLE phones this avoids spawning decode threads on the little cluster, where
  every sync step waits for the slowest core (e.g. Snapdragon 865: 4 threads instead of
  a naive 8 or 4-by-luck). Falls back to the old cores/2 heuristic when sysfs is
  unavailable. Covered by unit tests.
- **`-perf` APK** (`localai-agent-*-proot-perf.apk`): the same app compiled with
  `-march=armv8.2-a+dotprod`, which roughly doubles prompt processing on every SoC from
  2019 onwards (Snapdragon 855+, Dimensity 1000+, Helio G-series, Tensor). Install this
  one if your phone is 2019+; the portable APK remains for older devices.
- `kvCacheQ8` model setting persisted in DataStore with a Settings switch.

### Changed

- Default thread count and the "CPU threads" slider upper bound derive from the detected
  big-core count instead of `availableProcessors()`.
- Model load log line includes the KV cache mode for easier on-device debugging.

## 0.2.0 — 2026-10-25

Performance and reliability pass over the inference core and the agent tooling.

### Added

- Persistent `llama_context` with token-level KV prefix reuse in the JNI layer. Each
  `generate()` computes the longest common prefix between the cached tokens and the new
  prompt, trims the KV cache past that point (`llama_get_memory` + `llama_memory_seq_rm`)
  and decodes only the tail. Agent steps that share the system prompt and history now
  re-decode hundreds of tokens instead of the whole prompt.
- Correctness fallbacks for the cache: a full clear + re-decode when a memory refuses a
  partial trim (SWA-style caches) or when a decode fails mid-flight; the last prompt token
  is re-decoded when the cache fully covers the prompt so logits always match the prompt tail.
- `LlamaBridge.resetContext` + `LlamaEngine.resetConversation()`: **New Chat** drops the
  native KV prefix cache so a discarded conversation never pollutes the next one.
- OpenAI-style tool calls (`{"function":{"name":...,"arguments":{...}}}`) are now accepted
  by `ToolCallParser` alongside the fenced/tag/bare shapes.
- `<tool>` is accepted as a tag variant in addition to `<tool_call>`.
- Unit tests: OpenAI-style and `<tool>` parsing, bare-JSON start offsets, and a new
  `GuestPathsTest` covering the path jail (relative paths, `..` escapes, symlink escapes,
  `/shared` mapping).

### Changed

- Repeat-penalty sampler is seeded with the prompt tail (last 64 tokens), matching the
  upstream llama.cpp server, so penalties behave the same across cached prefixes.
- Stop-sequence scan uses a forward-only search window instead of re-scanning the whole
  generated text every token (O(window) instead of O(length) per token).
- Cancelled generations now flush the pending UTF-8 holdback, so the partial text shown in
  the UI is complete instead of missing its last few bytes.
- README: the "Per-request llama_context" decision note is replaced by the persistent
  context / prefix-reuse design, including its fallbacks and memory profile.

### Fixed

- `modelInfo` and `tokenCount` read the model/vocab without holding the session mutex,
  racing `freeModel()` — a use-after-free window. Both now lock the session.
- Sessions are owned by a `shared_ptr` registry instead of the intentional ~100-byte
  per-unload leak; an in-flight `generate()` keeps the session alive until it returns.
- `read_file` issued a single `read()` that could return fewer bytes than available and
  report a bogus truncation on slow storage; it now loops until the buffer is full or the
  stream ends.
- Bare-JSON tool calls reported a wrong `startIndex` (and thus wrong "visible text before
  the call") when the same object text appeared earlier in the message; the offset is now
  tracked during the scan.

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
