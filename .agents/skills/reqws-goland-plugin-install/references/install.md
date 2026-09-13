# Installation and recovery

Use only inside the [explicitly activated workflow](../SKILL.md), after [build and artifact checks](build.md) identify a successful exact candidate.

## Prepare the authorized UI path

Load the available Computer Use skill and follow its current tool/confirmation policy. Use `node_repl` with `@oai/sky` for all direct GoLand UI actions; compilation stays in the terminal. If the required runtime is unavailable or controls cannot be reliably identified, stop before installation. Do not substitute AppleScript, JXA, System Events, synthetic events, shell launch commands, coordinate automation outside Computer Use, or edits under `~/Library/Application Support/JetBrains`.

Discover the selected GoLand path/version/build, installed ReqWS version, visible project windows/unsaved work, and whether a specifically identified trusted rollback ZIP is available. Resolve an ambiguous IDE target with the user; never clone/copy another IDE as a shortcut. Read [the plugin user guide](../../../../docs/guides/goland-plugin-guide.md) only for the relevant UI or status information.

With fresh app state and current accessibility indexes, open Settings → Plugins → gear menu → Install Plugin from Disk. Select the pinned absolute ZIP path, but stop before any control that could submit installation.

## Action-time confirmation

An unsigned local ZIP is a non-marketplace install. Immediately before the **first** potentially submitting control (including file-chooser Open/OK if no later confirmation is guaranteed), ask for confirmation naming:

- exact ZIP path, SHA-256, packaged version and currently installed version;
- target GoLand path/version/build and trusted rollback availability;
- replacement of the current plugin and the effect of restart on every project window in that process.

The original skill invocation does not waive this confirmation. Ask for `$reqws-goland-plugin-install` plus unambiguous confirmation of this exact artifact/target when the runtime does not retain explicit skill selection across turns. Only a direct reply in the still-active workflow can continue it; stale/quoted invocations never suffice.

After valid confirmation, fetch fresh app state, revalidate artifact/checksum identity, target, dialog, warnings, and accessibility indexes, then proceed without redundant confirmation while those facts remain unchanged. Any material change requires a new confirmation. Do not accept unrelated permissions, legal terms, security changes, or a different artifact. Stop on incompatibility or unexpected signer/source; preserve the current installation.

## Restart and bounded smoke

Use GoLand's own restart prompt through Computer Use. Never force-kill or discard unsaved edits. If unsaved work or a discard prompt appears, stop that action and let the user decide.

After restart, use fresh app state to confirm the intended instance, ReqWS listed/enabled, matching version, and absence of a visible plugin startup error. Do not copy into plugin directories, delete the previous plugin directory, or clear IDE caches.

Use only the approved workspace/disposable fixture for smoke. With none approved, finish after verifying the plugin is enabled. In an approved ReqWS workspace, inspect manifest presence, the ReqWS Tool Window, workspace/branch/repository rows, lifecycle status, Safe Mode's read-only behavior and native trust prompt, and visible IDE errors. Do not click Sync Now, change trust, edit manifests, disable another plugin, or mutate a real project without explicit authorization. Recheck UI state after each authorized operation; do not manipulate project files behind the plugin.

Capture screenshots only when requested or installation evidence is part of the task. Exclude unrelated projects/windows, credentials, private URLs, personal paths, and notifications.

## Failure and recovery

Build failure blocks installation; install rejection preserves the current plugin. Record restart failure without force quitting. After a plugin startup error, preserve logs and the ZIP; disabling/uninstalling requires separate user authorization through the normal Plugins UI. Rollback uses a specifically identified previous trusted ZIP through the same confirmed UI flow, never support-directory reconstruction.

Do not delete workspaces, repositories, manifests, IDE settings, plugin caches, or build caches as troubleshooting. Report source/dirty status, commands and verification scope, ZIP path/size/version/hash, target, confirmation/restart results, observed smoke, saved evidence, and skipped/failed checks. The [test plan](../../../../docs/changes/goland-plugin-support/testing/test-plan.md) still governs exact-head Project/Search/Git/Go, lifecycle, Safe Mode, scale, and compatibility acceptance; this workflow cannot turn partial evidence into full `GO`.
