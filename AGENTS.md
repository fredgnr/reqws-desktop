# Repository Guidelines

## Project Structure & Module Organization

ReqWS is a macOS-only Electron app written in TypeScript and React. `src/main/` owns lifecycle, IPC, security, and privileged Git/state/workspace services. `src/preload/` exposes the typed bridge used by `src/renderer/`, which contains pages, components, and `styles/app.css`. Cross-process types, Zod schemas, IPC channels, errors, and pure utilities belong in `src/shared/`.

Tests mirror these boundaries under `tests/unit/`, `tests/integration/`, and `tests/renderer/`; setup is in `tests/setup.ts`. Build/install tooling lives in `scripts/`. `docs/reference/` contains historical designs and prototype assets. Do not edit generated `node_modules/`, `.vite/`, `out/`, `dist/`, or `coverage/` content.

## Build, Test, and Development Commands

- `nvm use && npm ci`: select Node 24 and install locked dependencies.
- `npm start`: run Electron Forge with Vite for local development.
- `npm run check`: run TypeScript checks, ESLint, and the complete Vitest suite.
- `npm run test:unit`, `npm run test:integration`, or `npm run test:renderer`: run one layer; use `npm run test:watch` while iterating.
- `npm run package:macos`: create `out/ReqWS-darwin-<arch>/ReqWS.app` without installing it.
- `npm run install:macos` (or `make install`): clean, check, package, and install locally. Never run the whole command with `sudo`.

## Coding Style & Naming Conventions

Follow two-space indentation, single quotes, semicolons, and trailing commas. TypeScript is strict. Use kebab-case filenames, PascalCase for React components and types, and camelCase for functions and variables. ESLint is authoritative; no formatter is configured. When changing IPC, update shared schemas/types/channels, preload exposure, main handlers, and contract tests together.

## Testing Guidelines

Use Vitest and name files `*.test.ts` or `*.test.tsx`. Write behavior-focused `describe` blocks and sentence-style `it` cases. Renderer tests use jsdom and Testing Library; integration tests use temporary local Git fixtures. Add regression coverage for behavior changes and run `npm run check` before review.

## Commit & Pull Request Guidelines

Git history and repository-specific templates are unavailable in this checkout. Use short, imperative commit subjects and keep each commit focused. Pull requests should explain the change and affected layers, link relevant issues, and list verification performed. Include screenshots for renderer changes and packaging/install evidence when modifying macOS delivery behavior.

## Security & Configuration

Preserve renderer sandboxing, context isolation, typed preload APIs, and main-process Zod validation. Spawn Git and editor commands with argument arrays and `shell: false`. Never store credentials in repository URLs or fixtures, weaken path/symlink containment, or automatically delete user workspaces.
