# Build and artifact checks

Use after [explicit activation](../SKILL.md), for the build stage only; this reference does not authorize IDE installation.

## Resolve what cannot be safely inferred

Prefer the current checkout containing `integrations/goland/`. Preserve its branch and dirty worktree; never stash, reset, clean, switch branches, or delete output to simplify installation. Discover available targets and installed versions rather than asking the user to repeat known facts. Ask only when multiple GoLand targets remain ambiguous or an essential authorization is missing.

Use [the plugin README](../../../../integrations/goland/README.md) for the pinned build baseline and [the developer guide](../../../../docs/guides/development-guide.md) for affected checks. Read relevant sections, not a full documentation stack. macOS and a usable JDK 25 are required for the GO-262.9437.286 target.

Read-only preflight can include:

```bash
git status --short --branch
/usr/libexec/java_home -V
```

`./integrations/goland/gradlew -p integrations/goland --version` is useful, but the wrapper may download Gradle and write caches: it is not a guaranteed read-only probe. Obtain authorization for unavailable dependencies/toolchains and respect network/sandbox approvals before downloads or system installation; do not repeatedly request an approval already granted for the same scope. Never use `sudo`.

## Build the requested candidate

For a normal local package, run `npm run package:goland`. For a fully verified candidate, run `npm run check:goland`, then `npm run package:goland`. Use JDK 25 explicitly if another runtime would be selected. Do not run the complete target verification merely because the skill was loaded.

Fix build failures caused by changes that the user authorized you to make, then rerun affected checks. If the request is installation-only, do not silently expand it into source repair. A failed build blocks installation; preserve logs and the existing installed plugin, and never reuse a stale ZIP as a substitute.

## Pin the artifact

Resolve only the intended newly built regular ZIP under `integrations/goland/build/distributions/`. Reject symlinks, directories, ambiguous candidates, and similarly named files from Downloads, older builds, or other checkouts.

Inspect with `unzip -l`, inspect the packaged plugin identity/version, and calculate `shasum -a 256 <zip>`. Record the absolute path, size, version, SHA-256, source commit, and dirty status. Build output remains ignored and uncommitted.

Before proceeding to UI, load [installation and recovery](install.md). A build-only or planning-only request ends with its requested artifact/report, without installing or restarting anything.
