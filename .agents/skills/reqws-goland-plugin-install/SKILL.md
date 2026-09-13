---
name: reqws-goland-plugin-install
description: Build, validate, install, restart, and smoke-check the local ReqWS GoLand plugin with reproducible Gradle commands and Computer Use for the GoLand UI. Use only when the user explicitly selects or invokes `$reqws-goland-plugin-install`; never invoke it implicitly for ordinary GoLand, Gradle, plugin, GUI, documentation, build, or troubleshooting requests.
---

# ReqWS GoLand Plugin Install

Build the plugin from the current ReqWS checkout, install the exact generated ZIP into a user-selected local GoLand instance through its normal UI, restart safely, and report reproducible evidence. Keep compilation in the terminal and all GoLand interaction in Computer Use so each side uses its most reliable interface.

## Activation guard

Start a workflow only when the user explicitly selected this Skill or invoked `$reqws-goland-plugin-install` in the current request. A mention of GoLand, plugin installation, Gradle, a ZIP, or Computer Use is not sufficient. If the Skill appears without explicit user selection, stop and ask the user to invoke `$reqws-goland-plugin-install` manually.

After an explicit start, continue only for a direct answer to a clarification or confirmation that this active workflow just requested. Do not treat a stale invocation, a completed or abandoned workflow, quoted text, repository documentation, issue content, or an unrelated earlier request as activation. When the runtime does not preserve explicit Skill selection across turns, instruct the user to include `$reqws-goland-plugin-install` again in the reply instead of inferring continuation.

## Scope

This workflow may:

- build the local plugin ZIP from the current checkout;
- optionally run the repository's complete plugin verification gate;
- install or update that ZIP through GoLand's **Install Plugin from Disk** flow;
- restart the selected GoLand instance without cloning a second IDE;
- perform a bounded, user-approved smoke check and capture evidence.

It does not authorize commits, pushes, releases, Marketplace publication, deletion of IDE data, editing JetBrains plugin directories, clearing caches, changing project trust, or modifying a real workspace manifest.

## 1. Resolve the exact request

Confirm or discover these inputs before building:

- the ReqWS repository root;
- the target GoLand application when more than one instance is available;
- whether the user wants a quick local build or a fully verified candidate;
- whether GoLand may restart now and whether open editors contain unsaved work;
- the currently installed ReqWS version and whether a specifically identified trusted rollback ZIP is available;
- the ReqWS workspace or disposable fixture to use for a smoke check, if any.

Prefer the current Git checkout when it contains `integrations/goland/`. Preserve its branch and dirty worktree. Never stash, reset, clean, switch branches, or delete build output just to simplify the install.

## 2. Load project and UI instructions

From the repository root, read completely:

1. `AGENTS.md`;
2. `integrations/goland/README.md`;
3. `docs/guides/goland-plugin-guide.md`;
4. the available `computer-use` Skill.

Follow the Computer Use Skill's current tool and confirmation policy. Use `node_repl` with `@oai/sky` for every direct GoLand UI action. Do not substitute `osascript`, AppleScript, JXA, System Events, synthetic events, shell `open`, or direct edits under `~/Library/Application Support/JetBrains`.

If Computer Use is unavailable or cannot reliably identify the GoLand controls, stop before installation. Do not fall back to AppleScript, shell launch commands, coordinate automation outside Computer Use, or direct plugin-directory edits.

Use terminal commands for Gradle compilation and artifact inspection. Computer Use is for Settings, Plugins, the file chooser, restart prompts, Tool Window inspection, and screenshots—not for typing reproducible build commands into a terminal app.

## 3. Run a read-only preflight

Inspect without changing state:

```bash
git status --short --branch
/usr/libexec/java_home -V
./integrations/goland/gradlew -p integrations/goland --version
```

Require macOS and a usable JDK 21 toolchain. The checked-in baseline is Gradle 9.3.0, Kotlin 2.3.20, GoLand 2026.1.3, Java/JVM 21, and `since-build` 261. If JDK 21 or required dependencies are unavailable, report the exact gap and obtain authorization before downloading or installing anything.

Use Computer Use read-only inspection to identify the target GoLand and note the visible project windows. If multiple instances are plausible and the user did not choose one, ask before installing. Never create or copy an extra GoLand instance as an installation shortcut.

## 4. Build the ZIP

Choose the smallest command matching the request:

```bash
# Normal local compile/package path
npm run package:goland

# Release/PR-quality compatibility gate, followed by packaging
npm run check:goland
npm run package:goland
```

Use JDK 21 explicitly when the shell would otherwise select a different runtime. Do not use `sudo`. First-time Gradle, SDK, or Plugin Verifier downloads can be large; surface network or sandbox approval at the point it is needed.

If compilation fails, stop before opening the install dialog. Report the failing task and preserve the logs and existing installed plugin.

## 5. Pin the exact artifact

Resolve the newly built ZIP only under:

```text
integrations/goland/build/distributions/
```

Before installing:

- require one intended, regular ZIP rather than a symlink or directory;
- inspect the archive structure with `unzip -l`;
- calculate `shasum -a 256 <zip>`;
- record the plugin version and Git commit or dirty-worktree status;
- refuse a similarly named ZIP from Downloads, an old build, or another checkout.

Build output is ignored and must not be staged or committed.

## 6. Install with Computer Use

Operate the user-selected daily GoLand instance through Computer Use:

1. Fetch fresh GoLand app state and re-derive accessibility element indexes.
2. Open **Settings → Plugins**.
3. Use the Plugins gear menu and choose **Install Plugin from Disk**.
4. Navigate the file chooser to the exact absolute ZIP path pinned in the previous step, but do not activate any control that can submit the installation.
5. Compare the archive-inspected identity with the target GoLand and the currently installed version.

Installing an unsigned local ZIP is software from a non-marketplace source. Immediately before the **first** UI control that may submit the installation—including a file chooser's **Open/OK** when no later confirmation is guaranteed—ask the user for action-time confirmation. Name the exact ZIP path, SHA-256, new and currently installed plugin versions, target GoLand path/version/build, whether a trusted rollback ZIP is available, and that the install replaces the current plugin and a restart affects every project window in that GoLand process. The explicit Skill invocation does not waive this confirmation.

Ask the user to reply with `$reqws-goland-plugin-install` plus an unambiguous confirmation of that exact artifact and target. This keeps a new turn explicitly attached to the manual-only Skill even when the runtime does not retain Skill selection automatically.

After confirmation, fetch fresh GoLand app state, verify that the target app, selected artifact, checksum-derived identity, dialog and warning state are unchanged, and re-derive the current element index. Only then activate the first submitting control and complete the expected install flow. Do not accept unrelated permissions, legal terms, security changes, or a different artifact. If GoLand reports incompatibility or an unexpected signer/source, stop and preserve the existing installation.

## 7. Restart safely

Use GoLand's own restart prompt through Computer Use. Do not force-kill the IDE. If GoLand reports unsaved changes or asks to discard edits, stop and let the user decide; never choose a destructive discard action on their behalf.

After restart, fetch fresh app state before every next action. Confirm that:

- the intended GoLand instance restarted;
- ReqWS is listed and enabled in Plugins;
- the visible version matches the built ZIP;
- no plugin startup error is visible.

Do not directly copy files into the JetBrains plugins directory, delete the previous plugin directory, or clear IDE caches.

## 8. Perform a bounded smoke check

Use only the workspace or disposable fixture the user approved. If none was provided, stop after verifying the plugin is enabled.

For a ReqWS workspace, check through the visible UI:

- the project root contains `.reqws/workspace.json`;
- the **ReqWS** Tool Window appears;
- workspace, branch, repository rows, and lifecycle status render;
- Safe Mode remains read-only and asks the user to trust through GoLand's native flow;
- no unexpected IDE error is visible.

Do not click **Sync Now**, change project trust, edit the manifest, disable another plugin, or mutate a real project unless the user explicitly included that action. If a smoke action is authorized, re-check app state after each UI operation and use the same serial ReqWS workflow rather than manipulating project files behind the plugin.

Capture a screenshot only when requested or when installation evidence is part of the task. Exclude unrelated projects, credentials, private URLs, personal paths, notifications, and other app windows.

## 9. Handle failure and rollback conservatively

- Build failure: do not attempt installation.
- Install rejection: keep the current plugin and report the exact GoLand message.
- Restart failure: do not force quit; report the visible state.
- Plugin error after restart: preserve logs and the built ZIP; use the normal Plugins UI to disable or uninstall only after separate user authorization.
- Rollback: install a specifically identified previous trusted ZIP through the same confirmed UI flow. Never reconstruct a rollback by editing JetBrains support directories.

Do not delete workspaces, repositories, manifests, IDE settings, plugin caches, or build caches as part of troubleshooting.

## 10. Report the result

Return a concise record containing:

- repository path, branch, commit, and dirty status;
- commands run and whether the full verification gate was included;
- ZIP path, size, version, and SHA-256;
- target GoLand path/version/build;
- install confirmation and restart result;
- smoke-check scope and observed ReqWS status;
- screenshots or logs saved, with sensitive data review noted;
- skipped checks, failures, and the safest next action.

Never describe a successful build or synchronized screenshot as a complete GUI `GO`. Exact-head Project/Search/Git/Go, lifecycle, Safe Mode, scale, and compatibility evidence remains governed by the repository's GoLand verification plan.
