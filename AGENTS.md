# ReqWS Agent Guide

ReqWS is a macOS Electron/TypeScript/React app; `integrations/goland/` is an independent Kotlin/Gradle plugin. Main owns privileged services and IPC validation, preload exposes the typed bridge, renderer owns UI, and shared owns cross-process contracts.

## Work within the request

Complete the requested change, affected verification, and handoff rather than stopping at the first implementation. Read only the files and documentation needed for the task. For an unknown area, use [the documentation index](docs/README.md); for a known path, go directly there and check its status. Frozen `docs/reference/` inputs are not current requirements.

Within the requested scope, inspect files, make local edits, and run or rerun relevant checks using disposable test fixtures without asking at each step. Preserve unrelated edits. If an environment or tool blocks one step, continue safe independent work and report the unverified part; do not claim success or bypass the gate.

Commits, pushes, PRs, installation, releases, and changes to real user data require authorization in the request or the applicable workflow. Permission for one does not imply permission for the others. Do not merge or publish merely because checks pass.

## Task-specific guidance

- Documentation creation, restructuring, or requirement/design/acceptance changes: use [reqws-documentation](.agents/skills/reqws-documentation/SKILL.md). A typo or read-only code explanation does not require a lifecycle-document checklist.
- UI copy, locale keys/placeholders/plurals, UI-facing error/status/message mappings, or stale translation findings: use [reqws-i18n](.agents/skills/reqws-i18n/SKILL.md). Layout-only changes do not trigger translation. The existing GPT-5.6 Sol/Pro translation-subagent gate at reasoning `high` or above remains in force; never update English or the baseline through an ungated fallback.
- GoLand development: consult [the plugin guide](integrations/goland/README.md) and [the support requirement index](docs/changes/goland-plugin-support/README.md) for the affected contract. The [installation skill](.agents/skills/reqws-goland-plugin-install/SKILL.md) is manual-only, not a prerequisite for plugin code or build tasks.
- Agent instruction maintenance and task-prompt examples: [agent workflow guide](docs/guides/agent-workflow.md). General setup, conventions, and detailed commands: [development guide](docs/guides/development-guide.md).

## Verification and handoff

Use Node 24 (`nvm use`, then `npm ci` for locked dependencies); plugin checks require JDK 21. Choose checks by the changed behavior, not by the presence of a skill:

| Change | Verification |
|---|---|
| Documentation/instructions only | `npm run docs:check`; validate changed skill references, metadata, and eval JSON. No local application build solely for prose edits. |
| Desktop behavior or build configuration | Affected tests while iterating; `npm run check` before code handoff. |
| GoLand code or build configuration | `npm run check:goland`; package the candidate when ZIP evidence is required. Keep Gradle separate from Desktop checks. |
| Shared manifest/IPC contracts | Update affected types, schemas, handlers, preload/callers, and contract tests together; run both language gates for manifest changes. |
| UI or macOS delivery behavior | Add the relevant screenshots or package/install smoke evidence; source checks do not prove GUI behavior. |

The existing CI/release gates still apply. Reuse a completed check only while its relevant inputs are unchanged; fix failures caused by the change and rerun affected checks. Report commands, results, skipped checks, and relevant documentation impact. Keep commits focused and PR descriptions explicit about scope and evidence.

## Non-negotiable project boundaries

Preserve renderer sandboxing, context isolation, typed preload APIs, and main-process Zod validation. Spawn Git/editor commands with argument arrays and `shell: false`. Never store credentials in URLs, fixtures, or logs; weaken path/symlink containment; or automatically delete user workspaces. Never run the whole macOS install command with `sudo`.

Desktop is the only `.reqws/workspace.json` writer. Keep TypeScript and Kotlin validation aligned through shared fixtures, including credential-free HTTPS/SSH URLs. The GoLand plugin must not access repository URLs, perform Git lifecycle operations, delete directories, modify `go.work`, or mutate VCS Directory Mappings. It may mutate only verifiably ReqWS-owned project-model entries after project trust. Pre-release `.idea/reqws-vcs-ownership.json` and matching locks remain inert: do not use them as authority, migrate, or automatically remove them.

Use public 261 APIs for plugin production code, never JetBrains `@Internal`, `@Experimental`, reflection, or private APIs. A successful unit suite or ZIP build is not an exact-head macOS GoLand Project/Search/Git/Go GUI acceptance record.

Do not edit or commit generated `node_modules/`, `.vite/`, `out/`, `dist/`, `coverage/`, or GoLand `.gradle/`, `.intellijPlatform/`, `.kotlin/`, and `build/` output. When documentation paths, status, or summaries change, update the nearest index and affected inbound links in the same change.
