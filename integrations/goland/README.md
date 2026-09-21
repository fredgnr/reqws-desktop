# ReqWS GoLand Plugin

The plugin reads Desktop-owned workspace membership and loading selections, projects selected repositories into one managed module, hides the dedicated IDE entry, and observes user-owned Git configuration.

This checkout implements the [dedicated-entry design](../../docs/changes/goland-workspace-loading/technical-design.md). See the [implementation record](../../docs/changes/goland-workspace-loading/implementation-2026-09-19.md) for completed checks and remaining acceptance work. Historical acceptance reports apply only to their original candidates.

## Target and toolchain

- Plugin ID: `com.reqws.workspace`.
- Compatibility policy: GoLand from platform branch **262**, with neither `until-build` nor `strict-until-build`. Compile SDK: **GO 2026.2 / 262.8665.270**; fixed process/UI environment: **GO 2026.2.1.1 / 262.9437.286**. API targets are frozen from the official stable catalog for each run.
- [Compatibility development record](../../docs/changes/ide-plugin-compatibility-automation/implementation-2026-09-21.md): implementation is present; compilation, tests and acceptance were explicitly not run in this development iteration. This is not a claim that all 262 versions have passed.
- IntelliJ Platform Gradle Plugin 2.18.1, Kotlin 2.3.20, Gradle Wrapper 9.3.0, Java/JVM 25.
- The Wrapper is pinned by its official HTTPS distribution URL. Do not add `distributionSha256Sum` or duplicate it elsewhere. Existing Wrapper JAR, lockfile and release verification remain required.

## Build and verify

From the repository root:

```bash
npm run check:goland
npm run package:goland
```

Intermediate work uses `compileKotlin compileTestKotlin` and affected `test --tests ...` selectors. `check:goland` retains baseline tests and structural checks, builds once, freezes the complete stable API matrix, then verifies the exact ZIP against every target. Gradle needs network access on the first dependency download. `exportPluginArchivePath` writes the exact selected ZIP path to `build/release/plugin-archive.txt`; do not pick from `distributions/*.zip`. ZIPs are under `integrations/goland/build/distributions/`; caches, sandboxes and outputs must not be committed.

To use an existing **GO 2026.2** compilation SDK, pass `-PreqwsGoLandSdkPath=/absolute/path/GoLand.app` to Gradle, or set `ORG_GRADLE_PROJECT_reqwsGoLandSdkPath`. Structured ProductInfo checks reject the wrong product, release or Java requirement. This option does not install or start that IDE and never changes the descriptor floor. Direct `verifyPlugin` without a snapshot retains the baseline and fixed representative checks; it is not the complete candidate gate. Offline diagnostic verification retains API/dependency checks but does not replace the fresh online CI/release matrix.

Complete-IDE Starter/Driver scenarios run **locally only**. CI retains production/test compilation (including the Starter/Driver host), all unit and Light/Heavy platform tests (including `HeavyPlatformTestCase`), forbidden API checks, structure/artifact policy checks and the frozen cross-version Verifier matrix. PR, Release and weekly workflows never start a complete GoLand or request IDE license credentials. Their `ci-api` evidence explicitly says local integration was not run.

API verification downloads official Maven SDK distributions without OS installers. For local API checks, `ORG_GRADLE_PROJECT_reqwsVerifierSdkPaths` may contain a JSON array of existing GoLand SDK paths. A matching SDK is reused read-only only when its product, version and build match the selected target; duplicates or mismatched builds fail. Other targets still download normally. This option neither changes the compilation SDK nor launches the installed IDE or copies its account/configuration state.

From the repository root, prepare a dedicated persistent test profile, then run the unchanged automated scenario groups against an explicit ZIP:

```bash
npm run prepare:goland:authorization -- --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1"
npm run check:goland:integration -- --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" --archive /absolute/path/candidate.zip --version 0.1.5
```

Preparation opens a projectless GoLand 2026.2.1.1 where the user may sign in through JetBrains Account and then quit normally. `JETBRAINS_LICENSE_SERVER` is optional. The dedicated config is reused in place; personal licenses/tokens/settings are never copied or assumed inherited. Every run gets fresh business fixtures, project state, system/plugins/log directories and a profile lock. The launcher never rebuilds the production plugin. Reports bind the actual ZIP, plugin version, IDE version/build and results; a signed artifact needs its own exact-byte evidence. Missing authorization, graphics or permissions blocks the run; preparation/CI success is not a UI pass. See [local integration instructions](../../docs/changes/ide-plugin-compatibility-automation/local-integration.md). See the [verification record](../../docs/changes/ide-plugin-compatibility-automation/verification-2026-09-21.md) for actual checks and outstanding local authorization/UI evidence. Packaging disables searchable-option indexing because ReqWS has no Settings configurables; it must not implicitly launch an IDE.

`verifyForbiddenProductionSymbols` scans sources and the composed JAR. It rejects Go APIs, VCS mutation, external-process/reflection/private API symbols, the retired exclude adapter/ledger, and the disallowed Experimental notification path. Plugin Verifier retains target API/dependency checks. Production uses public `WorkspaceModel.update`, standard entity sources and a bounded public roots-change event around actual shell policy changes.

Desktop `npm run check` stays independent of Gradle. CI/Release keep their separate plugin checks, version injection and ZIP integrity verification. Formal releases require an independently signed plugin ZIP. The Release workflow submits those exact bytes to Marketplace in automatic mode; bootstrap and paused modes report their explicit non-submission state. Ordinary CI builds unsigned test candidates and receives no production secrets. Marketplace review and IDE installation are separate from build success. See the [publishing operations](../../docs/changes/goland-plugin-marketplace/bootstrap-and-operations.md).

## Use the dedicated entry

In Desktop, open a ready workspace's details and choose **All by default** or **Selected repositories**. An empty selection intentionally loads no managed repositories. **Save selection** writes configuration; **Save and open GoLand** also opens the dedicated entry. A save is not proof that the plugin is installed or synchronized.

Every Desktop GoLand launch prepares or reuses `<workspace>/.reqws/ide/goland/reqws-project.json`, then opens that shell. The workspace root is no longer a ReqWS plugin entry. Unknown or partially created shell directories are preserved and reported as conflicts. Do not delete user data to retry.

The plugin reads both `.reqws/workspace.json` and the shell binding/selection. It does not write either business file. In initial Safe Mode it only reads and displays; projection requires trust. The normal Project view shows loaded repositories and user content, while the exact shell is excluded and filtered. The Tool Window distinguishes loaded, not loaded, missing and user-root coverage. Sync Now forces a complete check; it does not change the loading selection.

Use [the user guide](../../docs/guides/goland-plugin-guide.md) for installation and troubleshooting. Agent-driven installation or IDE restart requires separate exact-artifact authorization through the manual-only installation skill.

## Ownership and safety

The native shell module/root is preserved. A stable ReqWS module owns only roots backed by the current binding, the new `.idea/reqws-loaded-roots.json` journal and a matching virtual companion exclude marker. User roots are borrowed without claiming them. Source roots, extra excludes and unknown child relationships prevent destructive root deletion. Empty selections preserve the managed module and all user roots.

The journal uses stable directory handles, atomic replacement and an inode-bound writer lock. Ambiguous/partial recovery fails closed; it never treats a missing marker or an old path list as deletion authority. Old `reqws-managed-project-model.json` and VCS ownership files are inert and are neither migrated nor removed. Runtime digests are not source-identity ledgers.

The plugin remains language-agnostic, never accesses repository URLs, executes Git lifecycle commands, deletes repository directories, writes VCS mappings or changes language/build configuration. Mapping diagnostics concern loaded repositories; unloaded members and extra mappings remain user configuration.

Final acceptance uses plain-text Git fixtures, platform/PFI assertions and the normal Project panel. It does not repeat Go SDK, language-service, run/debug or native Git acceptance. See the [test plan](../../docs/changes/goland-workspace-loading/test-plan.md) and [plugin standard](../../docs/standards/ide-plugin-development-testing.md).
