# ReqWS GoLand Plugin

Open the repositories you need for a ReqWS task together in one GoLand project. Keep workspace creation and repository management in Desktop; use this plugin to load the selected repositories and check their IDE status.

For example, a workspace can contain `api`, `web`, and `shared`, while GoLand loads only `api` and `shared`. The other repository stays on disk and remains a workspace member. This selection does not change Git Log/Commit filters or user-owned Directory Mappings.

## Install and use

The current target is **macOS GoLand 2026.2.1.1 / GO-262.9437.286**. Installing a release ZIP does not require a separate build toolchain.

1. Create a workspace in ReqWS Desktop using the [Desktop guide](../../docs/guides/user-guide.md).
2. Download the separate `ReqWS-<version>-goland-plugin.zip` from a trusted [Release](https://github.com/fredgnr/reqws-desktop/releases). Install it through **Settings → Plugins → Install Plugin from Disk**, then follow the IDE's restart prompt. The plugin is not bundled into Desktop.
3. In Desktop's workspace details, choose **All by default** or **Selected repositories**, then **Save and open GoLand**. Do not open the ordinary workspace root instead.
4. Trust the project only after checking its origin. Open the **ReqWS** tool window and expand the selected repositories in the normal **Project** panel to confirm the result.
5. When the plugin reports missing Git roots, configure them in **Settings → Version Control → Directory Mappings**. The plugin only checks these mappings; it never changes them.

The [GoLand user guide](../../docs/guides/goland-plugin-guide.md) covers the exact steps, loading choices, buttons, status messages and troubleshooting. Desktop updates do not update the plugin. Marketplace availability depends on actual review, not build or release success.

The rest of this page is for plugin development. This checkout implements the [dedicated-entry design](../../docs/changes/goland-workspace-loading/technical-design.md). See the [implementation record](../../docs/changes/goland-workspace-loading/implementation-2026-09-19.md) for completed checks and remaining acceptance work. Historical reports apply only to their original candidates.

## Target and toolchain

- Plugin ID: `com.reqws.workspace`.
- Sole target: GoLand 2026.2.1.1 / **GO-262.9437.286**. Both descriptor bounds and Plugin Verifier use this target.
- IntelliJ Platform Gradle Plugin 2.18.1, Kotlin 2.3.20, Gradle Wrapper 9.3.0, Java/JVM 25.
- The Wrapper is pinned by its official HTTPS distribution URL. Do not add `distributionSha256Sum` or duplicate it elsewhere. Existing Wrapper JAR, lockfile and release verification remain required.

## Build and verify

From the repository root:

```bash
npm run check:goland
npm run package:goland
```

Intermediate work uses `compileKotlin compileTestKotlin` and affected `test --tests ...` selectors. Final integration runs the full plugin gate once after fixes. Gradle needs network access on the first dependency download. `exportPluginArchivePath` writes the exact selected ZIP path to `build/release/plugin-archive.txt`; do not pick from `distributions/*.zip`. ZIPs are under `integrations/goland/build/distributions/`; caches, sandboxes and outputs must not be committed.

To use an already installed **exact target** SDK for both compilation and verification, pass `-PreqwsGoLandSdkPath=/absolute/path/GoLand.app` to Gradle, or set `ORG_GRADLE_PROJECT_reqwsGoLandSdkPath` for the npm commands. The build checks the SDK build number. This option does not install or start that IDE. When Marketplace access is unavailable, `-PreqwsVerifierOffline=true` (or `ORG_GRADLE_PROJECT_reqwsVerifierOffline=true`) enables Verifier’s offline mode while retaining API and dependency checks against that SDK. CI uses online verification by default.

`verifyForbiddenProductionSymbols` scans sources and the composed JAR. It rejects Go APIs, VCS mutation, external-process/reflection/private API symbols, the retired exclude adapter/ledger, and the disallowed Experimental notification path. Plugin Verifier retains target API/dependency checks. Production uses public `WorkspaceModel.update`, standard entity sources and a bounded public roots-change event around actual shell policy changes.

Desktop `npm run check` stays independent of Gradle. CI/Release keep their separate plugin checks, version injection and ZIP integrity verification. Formal releases require an independently signed plugin ZIP. The Release workflow submits those exact bytes to Marketplace in automatic mode; bootstrap and paused modes report their explicit non-submission state. Ordinary CI builds unsigned test candidates and receives no production secrets. Marketplace review and IDE installation are separate from build success. See the [publishing operations](../../docs/changes/goland-plugin-marketplace/bootstrap-and-operations.md).

## Use the dedicated entry

In Desktop, open a ready workspace's details and choose **All by default** or **Selected repositories**. An empty selection intentionally loads no managed repositories. **Save selection** writes configuration; **Save and open GoLand** also opens the dedicated entry. A save is not proof that the plugin is installed or synchronized.

Every Desktop GoLand launch prepares or reuses `<workspace>/.reqws/ide/goland/reqws-project.json`, then opens that shell. The workspace root is no longer a ReqWS plugin entry. Unknown or partially created shell directories are preserved and reported as conflicts. Do not delete user data to retry.

The plugin reads both `.reqws/workspace.json` and the shell binding/selection. It does not write either business file. In initial Safe Mode it only reads and displays; projection requires trust. The normal Project view shows loaded repositories and user content, while the exact shell is excluded and filtered. The Tool Window distinguishes loaded, not loaded, missing and user-root coverage. Sync Now forces a complete check; it does not change the loading selection.

Agent-driven installation or IDE restart requires separate exact-artifact authorization through the manual-only installation skill.

## Ownership and safety

The native shell module/root is preserved. A stable ReqWS module owns only roots backed by the current binding, the new `.idea/reqws-loaded-roots.json` journal and a matching virtual companion exclude marker. User roots are borrowed without claiming them. Source roots, extra excludes and unknown child relationships prevent destructive root deletion. Empty selections preserve the managed module and all user roots.

The journal uses stable directory handles, atomic replacement and an inode-bound writer lock. Ambiguous/partial recovery fails closed; it never treats a missing marker or an old path list as deletion authority. Old `reqws-managed-project-model.json` and VCS ownership files are inert and are neither migrated nor removed. Runtime digests are not source-identity ledgers.

The plugin remains language-agnostic, never accesses repository URLs, executes Git lifecycle commands, deletes repository directories, writes VCS mappings or changes language/build configuration. Mapping diagnostics concern loaded repositories; unloaded members and extra mappings remain user configuration.

Final acceptance uses plain-text Git fixtures, platform/PFI assertions and the normal Project panel. It does not repeat Go SDK, language-service, run/debug or native Git acceptance. See the [test plan](../../docs/changes/goland-workspace-loading/test-plan.md) and [plugin standard](../../docs/standards/ide-plugin-development-testing.md).
