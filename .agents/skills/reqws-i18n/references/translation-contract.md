# Translation contract

Use this contract for a ReqWS catalog translation delta, not for unrelated UI work. Catalog paths and commands below are relative to the repository root.

## Delta and model gate

Update affected source copy in `src/renderer/locales/zh-CN.json` first, including required plural siblings and placeholders; inspect the matching namespace in `src/renderer/locales/en-US.json` and its callers. User-visible JSX uses catalog keys. Run `npm run i18n:scan`. Changed catalog digests or missing English keys can explain an expected non-zero scan before review; malformed JSON, placeholder mismatches, missing runtime keys, and missing mappings must be corrected, not acknowledged with `i18n:apply`.

Delegate translation to a designated subagent with an explicit `gpt-5.6-sol` override or a currently exposed GPT-5.6 Pro model, and `reasoning_effort: high` or stronger. Do not inherit an unspecified default. Supply only the relevant repository/catalog paths, requested keys, source values, neighboring copy, terminology, and output contract.

Do not change English or the baseline if that model is unavailable, the reasoning floor cannot be set, the returned model/tier differs, or structured output remains invalid after one correction attempt. Model self-identification alone does not prove runtime configuration: record the actual configured model/reasoning metadata available from the tool, and disclose missing evidence. An unverifiable gate is not permission to fall back. This policy is retained, not silently migrated to Astra by a documentation refactor.

## Translator output

The translator must not edit files. Return one JSON object, with no Markdown fences or commentary:

```json
{
  "sourceLocale": "zh-CN",
  "targetLocale": "en-US",
  "translations": [
    {
      "key": "namespace.example",
      "source": "包含 {{count}} 个项目",
      "target": "Contains {{count}} items",
      "placeholders": ["count"]
    }
  ]
}
```

Return exactly the requested keys once each, in catalog order. Include each base key and all required `_one` / `_other` siblings for a plural family. `source` must match the current Chinese catalog exactly; `placeholders` is the sorted source placeholder multiset, including repeats. Targets must be natural English with the same placeholders, not untranslated Chinese or empty placeholders. English singular/plural number agreement must match the count.

| Concept | Preferred English |
|---|---|
| 工作区 | workspace |
| 仓库 | repository |
| 工作区清单 | workspace manifest |
| 工作区文件 | workspace file / `.code-workspace` file, according to context |
| 功能分支 | feature branch |
| 跟随系统 | Follow system |

Keep ReqWS, Git, macOS, VS Code, Cursor, JSON, IPC, and `.code-workspace` unchanged.

## Main-agent validation and writeback

Parse the response. Verify exact locale IDs, exact requested key coverage without duplicates/extras, byte-for-byte source values, non-empty targets, exact placeholder multisets, complete plural families, number agreement, and surrounding terminology. Do not rewrite unrelated keys. Reject malformed or stale candidates before writing.

Only after validation, update the corresponding English values and review both catalog diffs. Run `npm run i18n:apply`, then `npm run i18n:check` and affected Renderer/contract tests. Apply checks keys, placeholders, runtime mappings, and both catalog digests before acknowledging the pair in `scripts/i18n-baseline.json`; it is not a translation reviewer. On failure, preserve the previous baseline, correct the defect, and repeat gated review when translations change.

The handoff identifies changed keys, actual subagent model/reasoning evidence, commands/results, and any blocker. Static checks or plausible English alone never establish independent linguistic review.
