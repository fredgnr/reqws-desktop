---
name: reqws-i18n
description: Synchronize ReqWS zh-CN/en-US UI catalogs when copy, keys, placeholders, plurals, or localized mappings change, or translation checks fail.
---

# ReqWS Internationalization

`src/renderer/locales/zh-CN.json` is the source catalog; `en-US.json` is its independently reviewed translation. This workflow is for catalog/runtime-copy changes, not ordinary Markdown translation, unrelated renderer logic, or internal error/status refactoring with unchanged localized mappings.

## Route by the actual delta

Inspect affected keys, callers, and enough neighboring copy for context. Existing keys with changed source text are deltas too; submit complete plural families. Do not read unrelated namespaces just because they share a catalog.

Update Chinese source copy and its callers, then run `npm run i18n:scan`. Before acknowledgement, source/catalog drift or missing English keys may explain a non-zero scan; malformed catalogs, placeholders, missing runtime keys, and error/status/message mapping failures are defects, not expected drift.

For a translation delta, load [the translation contract](references/translation-contract.md) before delegation or English/baseline writeback. It is the authority for the existing explicit model/reasoning gate, JSON shape, terminology, and validation. Automatically use the required read-only translator; the main agent validates and writes only reviewed English values. Do not translate in the main agent as a fallback.

After validating the response, review both catalog diffs, then run:

```bash
npm run i18n:apply
npm run i18n:check
```

Never apply merely to silence a scan. If the model/reasoning gate or output validation fails, leave English and the baseline unchanged and report the blocker; independent non-translation work can continue. Do not infer a model upgrade from the main agent's identity.

Finish with affected renderer/contract tests and report changed keys, actual translator model/reasoning evidence, and check results. Structural checks are not linguistic review. No translation delta means no translator and no baseline acknowledgement.
