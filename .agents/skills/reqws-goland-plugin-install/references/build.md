# Build and artifact contract

Commands are relative to the ReqWS checkout. Inspect the current checkout and relevant build configuration; use [the plugin README](../../../../integrations/goland/README.md) for setup details, not as a mandatory full-document read.

## Resolve only missing inputs

Prefer the current checkout containing `integrations/goland/`. Record branch, commit, and dirty status; preserve unrelated edits. Never stash, reset, clean, switch branches, copy an IDE, or delete output just to simplify installation.

Require macOS and JDK 21. Use read-only preflight as needed:

```bash
git status --short --branch
/usr/libexec/java_home -V
./integrations/goland/gradlew -p integrations/goland --version
```

The checked-in baseline is Gradle 9.3.0, Kotlin 2.3.20, GoLand 2026.1.3, Java/JVM 21, and `since-build` 261; verify against build configuration when it changes. Use JDK 21 explicitly if the shell selects another runtime. Missing toolchains or dependency downloads require the applicable network/installation approval; do not install them silently or use `sudo`.

Use the smallest requested build scope:

```bash
# Normal local package
npm run package:goland

# PR/release-quality candidate
npm run check:goland
npm run package:goland
```

Do not add the full verifier matrix to a quick-build request. For a verification candidate, do not omit that gate. A build failure blocks installation: preserve logs and the installed plugin, report the failing task, and do not substitute an older ZIP. A repair requires the requested scope to include it.

## Exact artifact evidence

Resolve only the intended freshly built regular ZIP under `integrations/goland/build/distributions/`. Reject symlinks, directories, ambiguous/stale output, Downloads copies, and other checkouts. Inspect with `unzip -l`, compute `shasum -a 256 <zip>`, and record absolute path, size, plugin version, commit, and dirty status. Exclude build output from commits.

Build-only completion is the commands/results and exact artifact record, with full verification included or explicitly not run. Continue to [installation](install.md) only within an active authorized installation request.
