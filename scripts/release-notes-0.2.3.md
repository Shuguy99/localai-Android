# Local AI Agent 0.2.3

Container quality pass: the shell the agent talks to now behaves like a real terminal.

## Downloads

| File | For |
|---|---|
| `localai-agent-0.2.3-proot-perf.apk` | **Recommended** — phones with big.LITTLE cores from 2019+ (Snapdragon 855/865/888/8 Gen 1+, Dimensity 800/1000+, Helio G-series, Tensor). Compiled with `-march=armv8.2-a+dotprod`, roughly 2x faster prompt processing. |
| `localai-agent-0.2.3-proot-portable.apk` | Older or unusual ARMv8.0 devices, or if the perf build crashes with `SIGILL` on startup. Same features, portable CPU code. |

Both builds are arm64-v8a, targetSdk 28 (sideload only — the container needs
`execve()` on app-private files, which Play policy forbids). The chat + model
download work without the container in either build.

**Signature changed in 0.2.3** (new signing key): Android will refuse to install
0.2.3 over 0.2.2. Uninstall the old version first; export anything you need from
the container's `/workspace` and `/shared` beforehand. Model files in
`Android/data/dev.localai.agent/` are removed by the uninstall as well.

## What's new

- **Persistent shell session** — `exec_shell` and the Container tab run in one
  long-lived PRoot shell. Every command after the first skips the 150-300 ms
  ptrace startup, and shell state persists between commands: `cd`, `export`,
  `umask` all stick, like sitting in a terminal. The session rebuilds itself
  transparently after a timeout, Stop, a container reset, or 10 idle minutes;
  the old one-command-per-process path stays as an automatic fallback.
- **Honest install progress** — the rootfs installer counts the archive's entries
  up front and reports real `1234 / 3801 entries`. The old bar cycled 0-100%
  every 5000 files and never showed a truthful value for this rootfs.
- **Persistent apk cache** — a host-side directory is bind-mounted over
  `/etc/apk/cache`, so packages downloaded with `apk add` survive container
  resets and app restarts. Rebuilding a reset container installs from local
  storage instead of the network.
- **Community repository enabled** — `v3.20/community` is listed next to `main`
  in `/etc/apk/repositories`, so more CLI tools install out of the box.

## Notes

- 67/67 JVM unit tests green (7 new: shell protocol, session protocol against an
  in-JVM fake shell, output cap).
- Restoring a snapshot no longer moves the session's working directory; both
  snapshot commands operate with `tar -C /workspace` anyway.
