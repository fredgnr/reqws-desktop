# ReqWS GoLand Plugin

This Gradle project builds the local-only ReqWS plugin that projects a Desktop-owned workspace manifest into GoLand without writing the manifest or running Git lifecycle commands.

## Compatibility and toolchain

The checked-in build uses:

- plugin ID `com.reqws.workspace`, version `0.1.0`;
- IntelliJ Platform Gradle Plugin 2.18.1;
- Gradle wrapper 9.3.0;
- Kotlin 2.3.20;
- GoLand 2026.1.3 compile target;
- Java/JVM 21 and `since-build` 261, with no `until-build`;
- Plugin Verifier targets GoLand 2026.1.3 and GoLand 2026.2.

Use a JDK 21 environment for the reproducible command path. Gradle downloads platform dependencies and verifier IDEs on first use, so those commands require network access and substantial local disk space.

## Build and verify

From the repository root:

```bash
npm run check:goland
npm run package:goland
```

Or run the wrapper directly:

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
```

`verifyPlugin` runs the configured Plugin Verifier matrix. The ZIP is written under `integrations/goland/build/distributions/`; Gradle caches, IDE sandboxes and build output are ignored and must not be committed.

The root `npm run check` and Desktop `package:macos` remain independent of Gradle. GitHub Actions uses a separate `goland-plugin` job so a plugin failure is visible without coupling Gradle artifacts into the Electron app or its Release assets.

## Run and install locally

For an isolated development IDE:

```bash
cd integrations/goland
./gradlew runIde
```

For the real application, build the ZIP and in GoLand choose Settings → Plugins → the gear menu → Install Plugin from Disk, select the generated ZIP, and restart when prompted. The plugin is unsigned and is not published to JetBrains Marketplace or a custom repository.

Open a directory that contains `.reqws/workspace.json`, or use ReqWS Desktop's GoLand action. In Safe Mode the plugin only reads and displays diagnostics. After the user trusts the project through GoLand's native flow, the plugin performs one synchronization. The ReqWS Tool Window provides status, repository rows, Sync Now, Open Manifest and redacted diagnostics.

The illustrated [GoLand plugin user guide](../../docs/guides/goland-plugin-guide.md) explains installation, project trust, every Tool Window region and action, lifecycle states, normal add/remove/re-add behavior, and safe troubleshooting.

## Contract and safety

- ReqWS Desktop is the only manifest writer; the plugin treats the file as untrusted, read-only input.
- Manifest schema v1 and safe paths stay aligned through shared golden fixtures; TypeScript and Kotlin both consume the versioned repository URL safety corpus.
- The selected project-model strategy preserves the existing workspace-root Content Root. Every plugin-created target exclude for `.reqws` or a retained Git repository has a virtual companion marker exclude plus a state-v2 relative-path/token claim; deletion requires the unique state claim, target and marker to agree. Existing equivalent excludes are borrowed.
- Existing equivalent VCS mappings are borrowed; only mappings created and still provably owned by ReqWS may be removed.
- The plugin does not clone, fetch, checkout, delete directories, modify `go.work`, access repository URLs, or launch external processes.
- Production code must not use JetBrains `@Internal`, `@Experimental`, reflection or private APIs.

The active requirements, design, remaining evidence gaps and GUI matrix are maintained in the [GoLand support requirement package](../../docs/changes/goland-plugin-support/README.md). Do not report compatibility or a GUI `GO` result until a dated exact-head verification record includes both verifier targets and the real GoLand run.
