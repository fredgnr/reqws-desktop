# Repository Guidelines

## Scope and orientation

ReqWS is a macOS Electron/React/TypeScript app with an independent Kotlin GoLand plugin in `integrations/goland/`. Main owns privileged services; preload exposes the typed bridge; renderer owns UI; shared owns cross-process contracts. Tests mirror those boundaries.

Use [the development guide](docs/guides/development-guide.md) for commands and affected-layer contracts, and [the plugin README](integrations/goland/README.md) for its pinned toolchain and public 261 API baseline. Plugin development and acceptance follow [the IDE plugin development/testing standard](docs/standards/ide-plugin-development-testing.md); the [language-decoupling plan](docs/changes/ide-plugin-language-decoupling/README.md) defines the pending cleanup, not completed implementation. Read relevant sections, not the whole repository. Use [the documentation index](docs/README.md) when the location or authority of a document is unclear; `docs/reference/` is frozen history, not current requirements.

## Boundaries that must survive every change

- Preserve renderer sandboxing, context isolation, typed preload APIs, and main-process Zod validation. Spawn Git/editor commands with argument arrays and `shell: false`. Never store credentials in URLs or fixtures, weaken path/symlink containment, or automatically delete user workspaces.
- Desktop is the only `.reqws/workspace.json` and repository-lifecycle writer. Keep TypeScript/Kotlin manifest acceptance aligned through shared fixtures and the credential-free HTTPS/SSH URL policy. The plugin must not access repository URLs, perform Git lifecycle operations, delete directories, modify user language/build configuration, or mutate VCS Directory Mappings in any mode.
- Keep ReqWS language-agnostic: the manifest selects Git repositories, not `go.mod` or another build file. New plugin synchronization must not query Go registries, wait for language services, or use language-toolchain readiness as a success gate. Retain required Workspace Model/ProjectFileIndex directory-boundary verification. Existing Go-specific code is a pending cleanup, not a pattern to extend or replace with optional/no-op adapters.
- The plugin may mutate only verifiably ReqWS-owned project-model entries after trust. Use public 261 APIs, never JetBrains `@Internal`, `@Experimental`, reflection, or private APIs. Pre-release `.idea/reqws-vcs-ownership.json` and matching locks are inert: never treat them as authority, migrate them, or remove them automatically.
- Preserve unrelated edits. Do not edit or commit generated dependencies, caches, bundles, coverage, or Gradle/sandbox output. Do not reset, stash, clean, or change branches just to simplify a task.

## Route only the work that needs a skill

- Use [reqws-documentation](.agents/skills/reqws-documentation/SKILL.md) when documented behavior, acceptance criteria, developer workflows, or document organization changes, or for an explicit documentation audit. A read-only lookup or spelling fix does not require a lifecycle-document exercise.
- Use [reqws-i18n](.agents/skills/reqws-i18n/SKILL.md) for UI copy, catalog keys/placeholders/plurals, localized error/status/message mappings, or stale translation checks. Its gated, read-only translation subagent and validated writeback are mandatory; do not bypass the gate or acknowledge an unreviewed baseline. Ordinary code refactoring or Markdown prose is not a translation delta.
- [reqws-goland-plugin-install](.agents/skills/reqws-goland-plugin-install/SKILL.md) is manual-only. A GoLand code, documentation, or build request does not activate installation. Exact-artifact installation confirmation and the Computer Use boundary remain in force.

## Work and verification

Use two-space indentation, single quotes, semicolons, trailing commas, strict TypeScript, and existing naming conventions; ESLint is authoritative. Keep Kotlin packages under `com.reqws.goland`. For IPC changes, update shared schemas/types/channels, preload, main handlers, and contract tests together.

Within the requested scope, continue through implementation, affected checks, fixes for regressions introduced by the change, and a final diff review. Disposable local fixture tests may be run and rerun without asking at each step, subject to runtime permissions. This does not authorize use of real userData/workspaces, IDE installation/restart, system-tool installation, or publishing.

Choose checks by impact: docs-only changes use `npm run docs:check`; Desktop code changes use affected tests and `npm run check` before handoff when the environment supports it; plugin code/build work uses production/test compilation and directly affected test methods/classes at intermediate task checkpoints, then `npm run check:goland` on the final integrated candidate; shared manifest changes need both affected layers. Use ordinary-text Git fixtures for plugin acceptance. Do not repeat native IDE Git, Go SDK/modules, code-reference, run/debug or user-project `go test` checks, or manually rescan symbols at every GUI step. Retain plugin Kotlin tests, affected integration checks, safety regressions and compatibility verification. Existing CI/release workflows remain unchanged; the new standard replaces the old Go-specific GUI acceptance requirements. Do not build/install software for prose edits.

For the language-decoupling cleanup, start from [the task index](docs/changes/ide-plugin-language-decoupling/tasks/README.md) and read the assigned S1 or S2 document: each owns its implementation steps and minimum regression. Use [the separate final acceptance plan](docs/changes/ide-plugin-language-decoupling/testing/final-acceptance.md) only after integrated development; do not reconstruct task instructions from the master plans. A checkpoint is not a release candidate: keep atomic signature/call-site/test changes together, test directly affected safety/concurrency behavior immediately, and reserve full retained-plugin regression, compatibility and required GUI checks for final integration. Record real test selectors and counts; zero tests or all-skipped tests are not a pass. Do not split into separate PRs merely to mirror task numbers, suppress existing CI, or carry old evidence across changed runtime inputs/artifacts.

When a permission, required model, or environment is unavailable, stop the affected operation, preserve state, and report the exact blocker; complete independent safe work where possible. Never turn unrun checks, a ZIP build, a limited smoke screenshot or an old verification report into a new candidate's full GUI `GO`.

## Handoff and remote actions

Update materially affected docs and their nearest indexes in the same change. Keep requirements/design authoritative before implementing a changed contract; distinguish target behavior from currently shipped behavior. Preserve historical evidence. Use the [documentation standard](docs/standards/documentation-standard.md) for metadata and indexing, not as a mandatory reading itinerary.

Report the outcome, verification actually performed, remaining gaps, and documentation impact. Use short imperative commit subjects. A request to create a branch and PR authorizes the necessary scoped commits and push to that new branch; it does not authorize merging, tags/releases, force-push, or unrelated remote changes. Read-only review requests do not authorize edits. Task-prompt examples and skill evaluation guidance are in [the agent workflow guide](docs/guides/agent-workflow.md).
