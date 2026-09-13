# Installation and recovery contract

Use this only within the manual workflow defined by [the skill entry](../SKILL.md). Read the available Computer Use skill before interacting with GoLand, and consult the relevant [plugin user-guide section](../../../../docs/guides/goland-plugin-guide.md) when needed.

## Target and UI boundary

Use `node_repl` with `@oai/sky` for every direct GoLand UI action, following the current Computer Use tool/confirmation policy. Fetch fresh app state and re-derive accessibility indexes before actions. Compilation and archive inspection stay in the terminal. Do not use AppleScript, JXA, System Events, synthetic events, shell `open`, or direct writes under JetBrains support/plugin directories as substitutes. If reliable Computer Use is unavailable, stop before installation.

Discover the GoLand application path/version/build, installed ReqWS version, visible project windows, unsaved work, and any specifically identified trusted rollback ZIP. Ask only for unresolved choices, such as two plausible target instances or the allowed smoke fixture. Do not repeat already answered questions or require a rollback ZIP to exist; disclose when none is available. Do not clone a second IDE.

## Install and confirm once at the action boundary

Use Settings → Plugins → Install Plugin from Disk to select the exact artifact established by [the build contract](build.md). Stop before the first control that may submit installation, including the file chooser's Open/OK if a later confirmation is not guaranteed.

At that point, request explicit confirmation naming the absolute ZIP path, SHA-256, new/current plugin versions, target GoLand path/version/build, rollback availability, replacement of the current plugin, and the effect of restarting all project windows in that process. Explicit skill activation is not this confirmation. Ask for `$reqws-goland-plugin-install` plus confirmation of that exact artifact/target when selection will not persist across turns.

After confirmation, recheck the ZIP checksum/identity and fresh target/dialog/warning state. If unchanged, proceed without another redundant confirmation. Changed artifacts, targets, or unexpected signer/source/incompatibility messages block submission; obtain a new applicable decision rather than accepting unrelated permissions, terms, or security changes.

## Restart and bounded smoke

Use GoLand's own restart flow through Computer Use; never force-kill or discard unsaved edits. If an unsaved-work prompt appears, let the user decide. After restart, verify the intended instance, enabled ReqWS plugin, expected version, and visible startup errors.

Use only the user-approved workspace or disposable fixture. Without one, end after enabled/version verification and report smoke as not performed. An approved read-only smoke checks the manifest-backed workspace, ReqWS Tool Window, branch/repository/lifecycle rows, and visible errors. Safe Mode stays read-only and uses GoLand's native trust flow.

Do not click Sync Now, grant project trust, edit a manifest, disable other plugins, or mutate a real project unless that action was explicitly included. Authorized smoke uses the normal serial ReqWS workflow, not hidden project-file manipulation.

Capture screenshots only when requested or needed as installation evidence. Exclude unrelated projects, credentials, private URLs, personal paths, notifications, and other applications.

## Failure, rollback, and completion

Build failure or install rejection: preserve the current installation and report the failure. Restart failure: report visible state without forcing exit. Post-restart errors: preserve logs/artifact; disabling or uninstalling needs separate authorization. Rollback uses a specifically identified trusted previous ZIP through the same confirmed UI flow, never manual directory surgery. Do not delete IDE settings, caches, workspaces, repositories, or manifests to troubleshoot.

Report checkout/branch/commit/dirty status; commands and verification scope; ZIP path/size/version/SHA-256; target IDE identity; confirmation/restart result; observed smoke scope; reviewed screenshots/logs; and skipped checks or blockers. Continue the authorized workflow to this evidence-based endpoint, not a first-build checkpoint.

Do not label this a complete GUI GO. Exact-head Project/Search/Git/Go, lifecycle, Safe Mode, scale, and compatibility acceptance remains governed by [the plugin test plan](../../../../docs/changes/goland-plugin-support/testing/test-plan.md).
