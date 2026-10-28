# Changelog

## 0.3.1 — 2026-10-28

Two user-reported fixes: a frozen UI while a model loads, and raw
`<think>`/```tool markup showing up as literal text in assistant messages.

### Fixed

- **UI no longer freezes while a model loads** ("the Chat button doesn't work
  until the app is restarted"): the whole load path — `dlopen` of the native
  runtime, llama.cpp backend init (Vulkan device enumeration included), the
  GGUF read itself and metadata parsing — now runs on `Dispatchers.Default`.
  Previously the first load executed backend init and metadata parsing on the
  main thread, so the navigation bar could stop responding for seconds at a
  time. `EngineState.Loading` is also published *before* the mutex and backend
  init, so the busy state appears immediately. The Chat screen shows a
  "Model is loading…" banner while this happens, and switching tabs stays
  responsive throughout.
- **Assistant messages render markup properly**: reasoning (`<think>…</think>`)
  collapses into a dim, italic "Thinking" section (expanded live while
  streaming, collapsed once finished), ```tool fences become compact
  "Tool call: <name>" cards (the raw JSON is one tap away), and ordinary
  ``` code fences render as monospace blocks with a language label on a dark
  surface. Parsing is tolerant: unterminated (mid-stream) blocks stream as
  open segments, unknown markup falls back to plain text, and everything is
  covered by 19 new unit tests (`AssistantTextTest`).
- **Stop is no longer a trap**: if a stopped turn fails to unwind (a native
  call wedged between tokens or a hung container exec), a 5-second watchdog
  force-releases the UI, restores the input field and posts a notice instead
  of leaving the chat stuck on the Stop button until a restart.

## 0.3.0 — 2026-10-27

Conversation persistence, sharded models and a real GPU backend: the three
long-standing gaps from the README's known-limitations list are closed, plus
releases now build and sign themselves on GitHub.

### Added

- **Chat history survives process death**: every completed turn is persisted
  atomically to `filesDir/chats/<id>.json` (strict USER/ASSISTANT turns for the
  prompt builder + the full UI transcript with tool calls and notices, tool
  text capped at 4 KiB). The newest conversation is restored on app start, so
  a killed process no longer wipes the dialogue. "New chat" keeps the outgoing
  conversation on disk before resetting; the KV prefix cache is dropped as
  before. Backed by a multi-conversation `ChatStore` (list/delete ready for a
  history picker later).
- **Sharded GGUF models** (`model-00001-of-00003.gguf`): previously skipped
  entirely, now grouped, downloaded part-by-part (each part keeps resume
  support) with one aggregated progress bar, stored with sidecars, shown as a
  single installed model and deleted as a whole. llama.cpp loads shard 1 and
  discovers the siblings automatically; the pre-load RAM estimate sums all
  parts. Complete groups only — a repo missing a part is not offered.
- **GPU offload via Vulkan**: release builds now ship the GGML Vulkan backend
  (`-PllamaVulkan=true`), statically linked into `liblocalai.so` with all
  shaders precompiled — no runtime shader compilation, no SDK needed on
  device. Devices without a usable Vulkan driver simply keep running on CPU;
  the existing "GPU layers" slider (Settings → Model) switches offload on.
  Default remains CPU-only (0 layers) until the user opts in.
- **Reproducible releases on GitHub**: the `Release` workflow builds portable
  and perf APKs on every `v*` tag with Vulkan enabled, signs them with the
  keystore stored in repository secrets and publishes the GitHub Release with
  extracted CHANGELOG notes — signing material never sits on a sandbox disk.

### Changed

- `scripts/fetch_deps.sh` now fetches five pinned, checksum-verified
  dependencies: llama.cpp, PRoot, Alpine rootfs, SPIRV-Headers and
  Vulkan-Headers (the last two only matter for Vulkan builds).
- CI builds moved from CMake 3.22.1 to 3.31.1 (FindVulkan's glslc component).
- The local build machine lost its signing keystore in an environment reset;
  0.3.0 starts a new signing key. **Upgrading from ≤ 0.2.4 requires uninstall
  first**; 0.3.0 and later upgrade over each other cleanly. The new keystore
  lives in GitHub Secrets, so this cannot happen again.

## 0.2.4 — 2026-10-27

Trust and convenience pass: the agent asks before doing anything destructive,
models are easier to pick, and the project now builds itself on every push.

### Added

- **Command approval in chat**: before `exec_shell` runs a command that is not
  provably read-only, the turn pauses and shows the exact command with
  Allow / Deny buttons. The classifier (`CommandRisk`) is quote-aware and
  splits on `&&`, `||`, `;` and `|`: read-only pipelines (`ls | wc -l`,
  `cd /workspace && cat f.txt`) run without friction, while anything that
  writes, mutates, installs, touches the network or spawns a shell
  (`rm`, `apk`, `wget`, `sh -c`, redirections, `$( )`, backticks, `find -delete`,
  `sed`, `awk`, `git`, ...) asks first. Command substitution and redirections
  inside *double* quotes are still detected — POSIX sh expands them there; only
  single quotes count as literal. Denying feeds the model an observation telling
  it to propose something safer; Stop/Deny also resolves a waiting turn. The
  hard deny-list (fork bombs, `mkfs`, `dd of=/dev/…`) is unchanged and runs
  before the approval step. Toggle: Settings → Container → "Ask before shell
  commands that are not read-only" (on by default).
- **Featured models on the Models screen**: one tap opens the file list of a
  curated, ungated GGUF repo (Qwen2.5 1.5B/3B, Llama 3.2 3B, DeepSeek-R1
  distill 1.5B, SmolLM2 1.7B, Phi-3.5 mini) — no search query needed.
- **Pre-download RAM fit badges**: every remote GGUF file shows fits / tight /
  risky, estimated from the file size plus a conservative per-token KV-cache
  heuristic against currently available RAM, so a 4 GB phone sees "risky" on a
  3 GB model before spending twenty minutes downloading it.
- **GitHub Actions CI**: every push to main and every pull request runs the
  full JVM test suite for both flavors, then builds the proot release APK twice
  (portable and `armv8.2-a+dotprod` perf variants) in parallel. CI artifacts
  are debug-signed with an ephemeral key — installable for side-load testing,
  clearly not release builds; release signing material never leaves the
  maintainer's machine.

### Changed

- `MemoryGuard` gained a pure, unit-testable size-only estimate path used for
  remote files (metadata-based estimates for local files are unchanged).
- **Anti-loop guard** (from the 0.2.2 field report): when the exact same tool
  call fails twice in a row, the observation now carries a system note telling
  the model to stop repeating itself — verify package names with
  `apk search`, try another tool, or report the blocker. Previously a wrong
  package name (e.g. `py3-pytz`, which does not exist in Alpine) aborted the
  whole `apk add` transaction and the model retried the identical command
  until the step limit burned out.
- The system prompt now documents the `apk` all-or-nothing transaction rule
  and that main + community repos are enabled (community arrived in 0.2.3;
  on 0.2.2 packages like `aircrack-ng` could not install at all).
- **Default max agent steps raised 6 → 12** and the Settings slider now goes
  to 24 (hard cap 32). Six steps were routinely too few for
  install → write → run → fix cycles; the 0.2.2 report hit the limit mid-task.

## 0.2.3 — 2026-10-27

Container quality pass: the shell you talk to now behaves like a real terminal.

### Added

- **Persistent shell session**: `exec_shell` (and the Container tab) now runs in a
  long-lived PRoot shell instead of spawning a fresh `sh -lc` for every command.
  The 150-300 ms ptrace startup disappears from every command after the first, and
  state survives between commands — `cd`, `export`, `umask`, aliases all persist,
  exactly like sitting in a terminal. The session is recreated transparently after
  a timeout, a Stop, a container reset, or 10 minutes of idleness. If the session
  shell dies mid-command, the command is reported as failed (never retried
  silently) and the next command starts a fresh session. The legacy one-shot path
  remains as an automatic fallback for hosts where the session protocol cannot
  start (no busybox `base64`). Commands travel base64-encoded with per-command
  random sentinels, so guest output can never desynchronize the protocol.
- **Honest rootfs install progress**: the installer scans the bundled tarball once
  to count entries, then reports real `1234 / 3801 entries` fractions. The old bar
  cycled 0→100% every 5000 files, which for the ~3.8k-entry Alpine minirootfs
  meant it never once showed a truthful value.
- **Persistent apk package cache**: a host-side directory is bind-mounted over
  `/etc/apk/cache`, so every package `apk add` downloads survives container
  resets and app restarts. Rebuilding a reset container installs previously used
  packages from local storage instead of re-downloading them.
- **Community repository enabled**: `/etc/apk/repositories` now lists the
  `v3.20/community` mirror next to `main`, so packages that live there (many CLI
  tools do) install without editing the file by hand.

### Changed

- **Snapshot create/restore no longer forces `workdir=/`**; the flag was already
  meaningless there (both commands operate with `tar -C /workspace`) and it now
  keeps the persistent session in the agent's working directory.

### Notes

- **Release signature changed**: the 0.2.3 APKs are signed with a new key because
  the previous signing keystore was lost with the old build machine. Install
  0.2.3 over 0.2.2 requires uninstalling the old version first (Android rejects
  signature mismatches). Save your `/workspace` and `/shared` files before
  uninstalling; the model files in the app's external files dir also need to be
  re-imported. Future releases will keep this key.
- 67/67 JVM unit tests green (7 new: shell protocol, session protocol against an
  in-JVM fake shell, output cap).

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
