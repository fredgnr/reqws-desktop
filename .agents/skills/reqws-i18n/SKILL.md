---
name: reqws-i18n
description: Synchronize ReqWS UI translations when copy, locale keys, placeholders, plurals, or UI-facing mappings change, or translation checks report drift.
---

# ReqWS Internationalization

Simplified Chinese is the source catalog; English needs independent translation review. CSS/layout-only changes and internal refactors with unchanged UI copy and mappings do not trigger this skill.

## Route by the actual delta

Inspect the affected keys, callers, and neighboring copy rather than loading unrelated namespaces. Run `npm run i18n:scan` to distinguish a translation delta from structural defects. A changed source sentence under an existing key is still a delta.

For translation work, read [the translation contract](references/translation-contract.md) before delegation or catalog writes. Preserve the existing policy: a designated GPT-5.6 Sol/Pro subagent with explicit reasoning `high` or above returns structured JSON without editing files; the main agent validates and writes the reviewed values.

Do not change `en-US.json` or `scripts/i18n-baseline.json` when the model/reasoning gate or output validation fails. Report that translation blocker and continue only independent, safe work; do not silently substitute another model or main-agent translation.

Complete `i18n:scan` → gated review → main-agent validation → `i18n:apply` → `i18n:check`, plus affected tests. If only mappings need repair and there is no translation delta, fix and check those mappings without fabricating a translation or baseline update. Report the changed keys and actual gate/check evidence.
