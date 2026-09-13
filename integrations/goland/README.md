# ReqWS GoLand Plugin

This Gradle project builds the local-only ReqWS plugin that projects a Desktop-owned workspace manifest into GoLand, observes Git Root configuration, and never writes the manifest, VCS Directory Mappings, or Git lifecycle state.

Development and acceptance now follow the [language-agnostic plugin standard](../../docs/standards/ide-plugin-development-testing.md) and [cleanup plan](../../docs/changes/ide-plugin-language-decoupling/README.md). This change is documentation-only: the checked-in plugin still contains the Go Modules synchronization gate described below. The new target is not yet implemented or verified.

## Compatibility and toolchain

The checked-in build uses:

- plugin ID `com.reqws.workspace`, local default version `0.1.0`;
- IntelliJ Platform Gradle Plugin 2.18.1;
- Gradle wrapper 9.3.0;
- Kotlin 2.3.20;
- GoLand 2026.1.3 compile target;
- Java/JVM 21 and `since-build` 261, with no `until-build`;
- Plugin Verifier targets GoLand 2026.1.3 and GoLand 2026.2.

Use a JDK 21 environment for the reproducible command path. Gradle downloads platform dependencies and verifier IDEs on first use, so those commands require network access and substantial local disk space.

## Build and verify

For the pending language-decoupling work, open [the task index](../../docs/changes/ide-plugin-language-decoupling/tasks/README.md), then the assigned S1 or S2 document. Each document contains its own implementation steps, minimum regression and handoff. Run [the separate final acceptance plan](../../docs/changes/ide-plugin-language-decoupling/testing/final-acceptance.md) only after both are complete. Intermediate checkpoints require production/test compilation and directly affected test methods/classes, including affected safety and concurrency branches; they do not each require the complete Verifier matrix or a real IDE installation. The commands below are the final integrated candidate gates, not a checklist to repeat after every small edit. Existing CI still runs normally on any intermediate push.

From the repository root:

```bash
npm run check:goland
npm run package:goland
```

Or run the wrapper directly:

```bash
cd integrations/goland
./gradlew test verifyForbiddenProductionSymbols verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew verifyForbiddenProductionSymbols buildPlugin
```

`verifyForbiddenProductionSymbols` scans every `src/main` file and every class in the composed plugin JAR. It currently rejects private Go tracker/scheduler APIs and VCS Directory Mapping mutation/internal implementation symbols while leaving test fixtures outside the production scan. The cleanup plan extends this existing gate to all Go API references; that extension has not run or been implemented in this documentation change. Both root npm commands run the current gate automatically. `verifyPlugin` runs the configured Plugin Verifier matrix. The ZIP is written under `integrations/goland/build/distributions/`; Gradle caches, IDE sandboxes and build output are ignored and must not be committed.

The root `npm run check` and Desktop `package:macos` remain independent of Gradle. GitHub Actions retains a separate `goland-plugin` CI job and all existing checks. CI passes the Desktop project version with `-PreleaseVersion`; tag Release builds pass the validated tag version. This sets the actual plugin descriptor version, not just the archive name, while commands without the property retain the local default. Both workflows run `scripts/prepare-goland-release.py` to check the ZIP/JAR integrity, unique plugin ID/version and staged SHA-256.

Release publishes `ReqWS-<version>-goland-plugin.zip` as a separate unsigned asset beside the arm64 app and `SHA256SUMS`. It does not embed the plugin in Electron, install it automatically or publish to Marketplace. All Desktop and plugin gates must pass before publication. The [Release delivery guide](../../docs/changes/github-actions-ci-release/delivery.md) describes download and disk installation.

CI and Release share `.github/actions/setup-goland/action.yml`. Gradle task/dependency caching remains enabled, with extracted IDEs separately cached under `.intellijPlatform/ides` using OS/architecture/toolchain keys. Sandboxes, release outputs and plaintext configuration-cache state are not part of this IDE cache; cache misses never waive checks.

## Run and install locally

For an isolated development IDE:

```bash
cd integrations/goland
./gradlew runIde
```

For the real application, build the ZIP and in GoLand choose Settings → Plugins → the gear menu → Install Plugin from Disk, select the generated ZIP, and restart when prompted. The plugin is unsigned and is not published to JetBrains Marketplace or a custom repository. Agent-driven installation or restart requires the existing explicit authorization; a documentation or build request does not authorize it.

Open a directory that contains `.reqws/workspace.json`, or use ReqWS Desktop's GoLand action. In Safe Mode the plugin only reads and displays diagnostics and does not publish a project-roots event. The ReqWS Tool Window provides status, repository rows, Sync Now, Open Manifest and redacted diagnostics. Missing or stale Git Roots remain a manual GoLand setting; Sync Now replays project projection and rechecks them. A VCS configuration event triggers the read-only check automatically.

To configure Git Roots, open Settings → Version Control → Directory Mappings, add each active repository directory as `Git`, and remove a retained repository mapping only when that matches the user's intent. Apply the settings, then wait for the Tool Window to refresh or choose Sync Now. The plugin never changes unrelated mappings or `rootSettings`.

The illustrated [GoLand plugin user guide](../../docs/guides/goland-plugin-guide.md) explains the currently delivered installation and behavior. Its Go-specific diagnostics describe the old implementation, not additional acceptance requirements for the cleanup target.

## Current behavior versus cleanup target

At the documented baseline, trusted synchronization verifies Workspace Model, live `ProjectFileIndex` and the public Go Modules registry before committing a clean candidate. The last check selects top-level regular `go.mod` files, may publish an additional ordinary roots event and wait for Go registry convergence, and can report `PROJECT_CONTENT_NOT_CONVERGED` with a Go-registry diagnostic. This remains current code, not a recommended pattern.

The target deletes that Go-specific chain. `Synced` will describe the manifest-driven repository view and managed directory projection only; `Active` will not promise language-module readiness. Required platform model updates and PFI directory-boundary checks remain. Removing the old compensation may expose delayed native Go module/run-configuration refresh; the target does not promise that those configurations immediately recover, nor that logical removal makes retained code impossible to run. Do not claim this target is delivered until implementation and the [targeted test plan](../../docs/changes/ide-plugin-language-decoupling/testing/test-plan.md) have actual results.

## Contract and safety

- ReqWS Desktop is the only manifest and repository-lifecycle writer; the plugin treats the manifest as untrusted, read-only input.
- Manifest schema v1 and safe paths stay aligned through shared golden fixtures; TypeScript and Kotlin both consume the versioned repository URL safety corpus.
- The selected project-model strategy preserves the existing workspace-root Content Root. Every plugin-created target exclude for `.reqws` or a retained Git repository has a virtual companion marker exclude plus a verified relative-path/token claim; deletion requires the unique state claim, target and marker to agree. Existing equivalent excludes are borrowed.
- Preserve trust/dispose gates, stable path handling, atomic ownership storage, cancellation, latest-wins coordination and recovery. A real Workspace Model/PFI boundary failure remains a synchronization failure; language decoupling must not hide it.
- Keep normal platform notifications and bounded external project-range reconciliation. Delete the Go-registry-driven extra notification/wait loop, not required model updates. Any retained additional notifier needs a demonstrated language-independent purpose.
- VCS Directory Mappings are entirely user/GoLand owned. Production code only reads them and listens for configuration changes; it never invokes a mapping mutation API or writes `.idea/vcs.xml`. GoLand may still apply its own native auto-detection policy, which the plugin neither invokes nor suppresses.
- Any `.idea/reqws-vcs-ownership.json` or matching lock file left by an unpublished development build is inert. The plugin neither consults nor migrates it and does not remove it automatically.
- The plugin does not clone, fetch, checkout, delete directories, modify user language/build configuration, access repository URLs, or directly launch external processes. Normal IDE reactions are distinct from ReqWS calls; do not promise that the entire trusted IDE has no process or network activity.
- Production code must not use JetBrains `@Internal`, `@Experimental`, reflection or private APIs. New code must not query Go registries or inspect language build files to decide repository membership or synchronization success.

## Acceptance scope

Use local Git repositories containing ordinary text files. Test the plugin's own manifest contract, automatic/manual synchronization, managed directory boundary, VCS diagnostics and configuration protection; retain Kotlin/platform regression tests and the existing compatibility matrix. Do not gate acceptance on Go SDK/modules, completion, code references, user-project `go test`, run/debug or native Git's complete functionality. Symbol auditing belongs in the build gate, not in every GUI step.

The [original GoLand support package](../../docs/changes/goland-plugin-support/README.md) preserves delivered implementation and historical evidence. The new standard and cleanup plan selectively replace its Go-specific success criteria. Historical GO reports and old ZIP hashes do not validate a new cleanup candidate; documentation-only checks do not establish functional acceptance.
