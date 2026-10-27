# Local AI Agent (Android)

[![CI](https://github.com/Shuguy99/localai-Android/actions/workflows/ci.yml/badge.svg)](https://github.com/Shuguy99/localai-Android/actions/workflows/ci.yml)

An offline-first Android app that runs a GGUF model on-device, discovers and downloads
models from Hugging Face, and lets the model act as a coding agent inside a PRoot Linux
container (simulated root, no host root required).

```
┌────────────────────────────── Android app (Kotlin, Compose) ──────────────────────────────┐
│                                                                                           │
│  Chat UI ──► AgentLoop ──► LlamaEngine ──► JNI ──► llama.cpp (mmap GGUF, ARM64)            │
│                 │                                                                         │
│                 ├──► ToolExecutor ──► ContainerManager ──► PRoot ──► Alpine /bin/sh         │
│                 │                        │                                                │
│                 │                        └── snapshots (tar of /workspace)                 │
│                 └──► ToolCallParser (fenced JSON, one call per step)                      │
│                                                                                           │
│  Models UI ──► HuggingFaceApi ──► OkHttp ──► huggingface.co/api                           │
│             └► ModelDownloader (Range resume) ──► getExternalFilesDir("models")           │
│                                                                                           │
│  DataStore (settings) · EncryptedSharedPreferences (HF token)                             │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

## What is implemented

| Area | Implementation |
|---|---|
| Inference | llama.cpp v0.7.0 via JNI, ARM64 CPU, mmap weights, persistent context with token-level KV prefix reuse, streaming tokens, cancellation, stop sequences, sampler chain (top-k/top-p/temp/penalties) |
| Prompting | Kotlin chat templates: ChatML/Qwen, Llama 3, Gemma, Mistral, Phi-3 + AUTO detection and manual override |
| Model discovery | `GET /api/models?search=…&filter=gguf&full=true`, detail lookup with `blobs=true` for file sizes |
| Downloads | Resumable `Range` downloads, `.part` files, progress flow, free-space check, GGUF magic + size verification, gated/401/403 handling |
| Container | PRoot + Alpine 3.20 minirootfs, `-0` simulated root, bind mounts, timeout, output cap, `ulimit -v`, per-turn `/workspace` snapshots with restore |
| Agent | 4 tools (`read_file`, `write_file`, `list_dir`, `exec_shell`), strict JSON protocol, step limit, path jail, soft command deny-list, tool status in UI |
| Settings | DataStore-backed model/container settings, reset-to-default, HF token in Android Keystore |
| Lifecycle | Foreground service while a model is loaded, all heavy work off the main thread |

## Decisions and why

**llama.cpp rather than MLC-LLM.** MLC needs a compiled model artifact per (model, phone)
pair and a heavier toolchain; GGUF files from Hugging Face are universally available and
llama.cpp's ARM64 kernels (dotprod/i8mm) are good enough for 2B–4B Q4_K_M on one device
class. It also gives direct control over `n_ctx`, `n_threads`, `n_gpu_layers`, mmap and
cancellation, which is exactly what this app exposes. MLC remains the better choice if you
later want maximum Adreno performance for one fixed model.

**PRoot rather than a VM or real root.** PRoot is a ptrace-based syscall interposer. It
provides a rootfs, simulated uid 0 (`-0`), bind mounts and path remapping without unlocking
the bootloader. It does **not** give kernel privileges: no modules, no raw devices, no
`/system` writes. The container is confined to app-private storage; host root is never
assumed.

**targetSdk 28 for the container flavor.** Android 10+ denies `execve()` on files written
into app data when an app targets API 29 or higher (W^X / `untrusted_app_29`). Every PRoot
app has to deal with this. This project ships two flavors:

| Flavor | targetSdk | Chat + models | Container | Distribution |
|---|---|---|---|---|
| `proot` | 28 | yes | yes | sideload / F-Droid style |
| `standard` | 35 | yes | disabled with an explanation | Google Play compatible |

The container code does not call `su`, does not touch `/system`, and declares the required
`INTERNET` and `FOREGROUND_SERVICE` permissions. Real host root is intentionally **not**
implemented; if you add it, gate it behind an explicit warning and a separate build.

**Persistent context with KV prefix reuse.** The JNI layer loads weights once and keeps one
`llama_context` per model session. It also tracks the exact token sequence currently resident
in the KV cache; on each `generate()` it computes the longest common prefix with the new
prompt, trims the cache past that point (`llama_memory_seq_rm`), and decodes only the tail.
Agent steps share the system prompt, conversation history and earlier tool observations, so
step N typically re-decodes a few hundred tokens instead of the whole multi-thousand-token
prompt — with a 2B–4B Q4_K_M model this is the difference between seconds and tens of
seconds per step. Correctness fallbacks are explicit: if the memory refuses a partial trim
(SWA-style caches may), or a decode fails, the cache is cleared and the request re-decodes
from scratch. When a new prompt is fully covered by the cache, the last prompt token is
re-decoded so the logits buffer always matches the prompt tail. **New Chat** drops the cache
via `LlamaBridge.resetContext`, so a discarded conversation never pollutes the next one.

## Repository layout

```
app/src/main/cpp/llama_jni.cpp          JNI bridge (UTF-8 safe, cancellation, sampler)
app/src/main/cpp/CMakeLists.txt         llama.cpp build config (CPU, optional Vulkan)
app/src/main/java/dev/localai/agent/
  container/                            rootfs installer, PRoot exec, snapshots
  data/hf/                              HF DTOs + API client
  data/model/                           GGUF metadata parser, memory estimate, downloader
  data/settings/                        DataStore settings + encrypted token
  inference/                            LlamaBridge, LlamaEngine
  agent/                                prompt templates, tool parser/executor, agent loop
  ui/                                   Compose screens + ViewModels
app/src/proot/assets/rootfs/            Alpine tarball (only packaged in the proot flavor)
app/src/proot/jniLibs/arm64-v8a/        PRoot static binary (only packaged in the proot flavor)
app/src/test/                           JVM unit tests (parser, templates, policy, GGUF reader)
scripts/fetch_deps.sh                   pinned llama.cpp, PRoot, Alpine
```

## Build

Requirements: Android Studio (or JDK 17 + Android SDK 35), NDK 27.0.12077973, CMake 3.22.1,
an ARM64 Android 10+ device. Host root is not required.

```bash
bash scripts/fetch_deps.sh          # pins llama.cpp v0.7.0, PRoot v5.3.0, Alpine 3.20.3
./gradlew testProotDebugUnitTest    # JVM tests (no device needed)
./gradlew assembleProotDebug        # container build (sideload)
# or
./gradlew assembleStandardDebug     # Play-compatible, container disabled
```

**Path caveat.** CMake treats `;` as a list separator, so a checkout whose absolute path
contains a semicolon (for example `/home/me/project;backup`) fails native configuration with
`file failed to open for writing (Is a directory)`. Move or copy the project to a path without
`;` before building. The Kotlin/Java layers are unaffected once CMake can configure.

On a low-RAM build host, the Gradle daemon can crash in the C2 JIT while compiling the large
Compose dependency graph. If that happens, cap the JIT and heap:

```bash
GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2560m -XX:TieredStopAtLevel=1" \
  ./gradlew --no-daemon --no-parallel assembleProotDebug
```

The same class of host can also make `clang++` crash with exit code 139 in the LLVM
register allocator while ninja compiles several large llama.cpp translation units at
once. The file that crashes changes between runs, and compiling it alone succeeds, so
this is a resource/concurrency failure rather than a code error. Limit native compile
parallelism with ninja job pools:

```bash
./gradlew assembleProotDebug -PlocalaiNativeJobs=2   # or =1 on a 4-core/8 GB box
```

The default (flag omitted) leaves ninja free to use every core.

Verified with AGP 8.7.3, Gradle 8.9, JDK 17, NDK 27.0.12077973, CMake 3.22.1 and SDK 35:
both flavors compile, 40 JVM tests pass, and `liblocalai.so` is linked with 16 KB `LOAD`
alignment (required by Android 15+ devices with 16 KB pages). The APK's native libraries are
`arm64-v8a` only; `libproot.so` is a static PRoot executable stored under a `.so` name so it is
extracted to `nativeLibraryDir` (the app copies it to app-private storage if that directory is
mounted `noexec`).

The project intentionally does not commit `llama.cpp/`, the rootfs tarball, `libproot.so`
or the Gradle wrapper JAR. If `./gradlew` is missing on your machine, run
`gradle wrapper --gradle-version 8.9` once or open the project in Android Studio.

The native library is built only for `arm64-v8a`. Vulkan offload is off by default; see
`llamaVulkan` in `gradle.properties` and the `GGML_VULKAN` option in CMake. Enabling it
also requires a shader compiler in the NDK build, which the stock NDK does not provide.

The CPU build targets ARMv8.0 for compatibility. Phones from roughly 2019 onward support the
ARMv8.2 dot-product extension, which is substantially faster for 4-bit quants:

```bash
./gradlew assembleProotDebug -PllamaArmArch=armv8.2-a+dotprod
```

Do not ship that build to ARMv8.0 devices (it will crash with `SIGILL`). Use it when you know
the target hardware, or keep the portable default.

### Release builds

Signing material is never committed. The build reads
`~/.localai-release/keystore.properties` by default, or the file passed with
`-PkeystoreProperties=/path/to/keystore.properties`:

```properties
storeFile=/home/me/.localai-release/localai-release.jks
storePassword=...
keyAlias=localai
keyPassword=...
```

Create the key once, then build:

```bash
mkdir -p ~/.localai-release && chmod 700 ~/.localai-release
keytool -genkeypair -keystore ~/.localai-release/localai-release.jks -alias localai \
  -keyalg RSA -keysize 4096 -validity 10000

./gradlew assembleProotRelease assembleStandardRelease
```

Without that file the release variants build but stay unsigned. Keep the keystore and
its passwords backed up: Android only accepts updates signed with the same key.

### Performance tuning

Three independent levers, all applied in the 0.2.1 release builds:

1. **q8_0 KV cache** — on by default (`Settings → Model → KV cache q8_0`). Halves the
   attention memory bandwidth; the JNI layer falls back to F16 automatically if a
   backend refuses the combination. Quality impact is negligible for K and V.
2. **Big-core threads** — the default `CPU threads` equals the number of cores within
   80% of the fastest core's frequency (sysfs), so decode never schedules onto the
   little cluster of a big.LITTLE SoC. Adjust manually if you know better.
3. **CPU-tuned `-perf` build** — compiled with `-march=armv8.2-a+dotprod`:
   ```bash
   ./gradlew assembleProotRelease -PllamaArmArch=armv8.2-a+dotprod
   ```
   Safe on every ARMv8.2 device (Snapdragon 855+, Tensor, Dimensity, Helio G-series,
   ~2019+). ARMv8.0 phones (2016–2018) must use the portable APK — the tuned build
   would crash with SIGILL. `+i8mm` can be appended for ARMv8.6 cores (2021+), but
   that build then requires those cores too.

Not yet wired: Vulkan GPU offload (`-PllamaVulkan=true` needs a glslc-capable build
image) — on-device decode for 2–4B quants is usually still CPU-bound, so the CPU
tuning above is the reliable path for now.


## First run

1. **Models** tab → search, e.g. `qwen2.5 coder 3b` or `llama 3.2 3b instruct`.
2. Open a repo, pick a single-file GGUF. `Q4_K_M` is the recommended default for 4–8 GB
   phones; `Q4_0`/`Q3_K_M` if RAM is tight. Sharded GGUFs are listed as unsupported in this
   MVP.
3. Download (resumable; survives going to background via the foreground service).
4. Tap **Load**. If the pre-load estimate is above 50% of *available* RAM you get a warning
   with the numbers and an explicit “Load anyway”.
5. **Container** tab → **Install Alpine**. This extracts the bundled minirootfs into
   app-private storage; then `echo hello && uname -a && id` should work. This tab is only
   functional in the `proot` flavor (the `standard` flavor shows an explanation).
6. **Chat** → try:
   > Write a shell script in /workspace that prints the first 20 primes, then run it and show me the output.

## Agent protocol

Small local models cannot be trusted with native function calling, so the app defines a
strict textual protocol. One call per assistant message:

````
```tool
{"name":"write_file","arguments":{"path":"/workspace/primes.sh","content":"#!/bin/sh\n..."}}
```
````

The model then stops and waits for the app to append a `TOOL RESULT` user message. The loop
runs at most `maxAgentSteps` (default 6) calls, then asks the user to continue. Tool tours
are shown as separate status cards in the chat; they never block the UI thread.

File tools are jailed to `/workspace` and `/shared`, resolving symlinks with
`canonicalPath` before every access. `exec_shell` is intentionally more powerful; it is
bounded by the configured timeout (default 60 s), output cap (32 KiB), a soft deny-list for
catastrophic commands, and a workspace snapshot taken once per turn.

## Memory model

```
weights ≈ GGUF file size × 1.02 + 128 MB runtime overhead
KV      = 2 × layers × n_ctx × kv_heads × (embedding / heads) × 2 bytes (f16)
```

`GgufMetaReader` reads `block_count`, `embedding_length`, `attention.head_count` and
`attention.head_count_kv` straight from the GGUF header, so the estimate is model-specific
before anything is loaded. The comparison is against `ActivityManager.MemoryInfo.availMem`
(not total RAM). Warning at 50%, danger at 70%. Weights are mmap'd; the app does not request
`largeHeap`.

The persistent context holds the full `n_ctx` KV allocation for as long as the model is
loaded — the same peak memory the per-request design allocated during generation, just kept
resident while idle. The foreground service already pins the app for the model's lifetime,
so the effective footprint is unchanged.

## Hugging Face notes

- `Authorization: Bearer <token>` is added only if you saved a token in Settings; the token
  is encrypted at rest with an Android Keystore master key and is never logged.
- 401 → “needs authentication”, 403 → “gated, accept the license”, 429/5xx → bounded retry
  with backoff. No token is ever hardcoded.
- Downloads go to `getExternalFilesDir("models")` (app-specific, no storage permission) and
  are verified for GGUF magic and expected byte count. A `.part` file is kept for resume.
- The default User-Agent identifies the app; please keep request volume modest (one search
  per action, no background polling).

## Third-party components and licenses

| Component | Version | License | Notes |
|---|---|---|---|
| llama.cpp / ggml | v0.7.0 | MIT | statically linked into `liblocalai.so` |
| PRoot | v5.3.0 | GPL-2.0-or-later | shipped as a separate executable (`libproot.so`), invoked as a subprocess rather than linked |
| Alpine Linux minirootfs | 3.20.3 | mixed per package (mostly MIT/BSD/GPL/Apache-2.0) | unmodified rootfs extracted at runtime; package licenses live in `/usr/share/licenses` inside the container |
| AndroidX, Compose, OkHttp, kotlinx | see `app/build.gradle.kts` | Apache-2.0 / MIT | Gradle dependencies |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | tar/gzip extraction |

The app source itself is provided as-is for the project. If you redistribute a built APK,
comply with the GPL-2.0-or-later terms for PRoot (including the offer of corresponding
source, available from the upstream project) and with the licenses of the Alpine packages
contained in the unbundled rootfs.

## Troubleshooting

- **PRoot fails with `ptrace` / `seccomp` errors.** Enable *Disable PRoot seccomp
  acceleration* in Settings → Container. It sets `PROOT_NO_SECCOMP=1`; tracing is slower but
  works on kernels where the seccomp fast path is blocked.
- **Container is “Unavailable” after installing.** Check that the installed APK is the
  `-proot` flavor (`targetSdk 28`). The `standard` flavor cannot exec guest binaries by design.
- **The app crashes with `SIGILL` right after loading a model.** The APK was built with
  `-PllamaArmArch` for a CPU feature this device does not have. Rebuild without it.
- **`/mnt/host` shows permission denied.** Enable the storage permission in Android app
  settings, or use `/shared` instead. See Known limitations.

## Verification checklist (physical ARM64 device)

- [ ] `scripts/fetch_deps.sh` completes; `llama.cpp`, `libproot.so`, Alpine tarball exist.
- [ ] `assembleProotDebug` installs and launches without ANR.
- [ ] Search returns GGUF repos; opening one lists files with sizes; a gated repo shows a
      clear message instead of a crash.
- [ ] Download a ~1–2 GB GGUF; kill the app mid-download; relaunch and confirm it resumes
      from the `.part` offset.
- [ ] Load the model; chat streams tokens and **Stop** returns within ~1s.
- [ ] Try a prompt that exceeds context: you get a clear “prompt too large” message, not a
      crash.
- [ ] Container: install, then `echo hello`, `id` (uid 0 simulated), `uname -a`, write and
      read a file under `/workspace`.
- [ ] Agent: “create /workspace/hello.sh, make it executable, run it” → one `write_file`,
      one `exec_shell`, correct output, no UI freeze.
- [ ] Snapshot: create a file, run the agent, restore the snapshot from the Container tab;
      the file is gone.
- [ ] Change temperature/context/threads; force-stop and relaunch; values persist.
- [ ] Change the HF token; verify it is used only after saving and is cleared by **Clear**.
- [ ] 10-minute chat with a 2B–4B Q4_K_M model on a 6–8 GB device: no OOM kill, no ANR,
      memory estimate stays in the same range.
- [ ] Airplane mode: chat still works (no network calls); model list shows an error only
      when you search.
- [ ] Prefix reuse: ask a question, then a follow-up in the same chat — the second prompt
      processes visibly faster (logcat shows a small decode tail, not the full prompt).
- [ ] New Chat → the next prompt re-decodes from scratch (no stale-cache artifacts in the
      answer) and the KV allocation starts compact again.

## Known limitations / next steps

- Sharded GGUF files are skipped (single-file models only).
- KV prefix reuse falls back to a full re-decode on SWA-style memories that refuse partial
  cache trims (correct, just slower for those architectures).
- GPU offload is plumbed through `n_gpu_layers`, but the default build is CPU-only.
- No chat history persistence beyond the process; settings do persist.
- The standard flavor cannot run the container by design (Android W^X).
- `exec_shell` is not a security boundary against a malicious model; the snapshot and
  deny-list are recovery/safety tools, not a sandbox.
- The optional `/mnt/host` mount of `/storage/emulated/0` needs Android's storage permission
  and is unreliable under Android 11+ scoped storage. Use `/shared` (app-private, always
  available) instead.
- Cancellation stops at token granularity; prompt processing checks cancellation per token
  batch, so a huge prompt may take a moment to abort.
