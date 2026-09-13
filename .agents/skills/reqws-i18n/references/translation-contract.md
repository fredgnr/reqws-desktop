# Translation contract

Use this contract only for a ReqWS catalog translation delta; [SKILL.md](../SKILL.md) owns the scan/review/apply/check workflow.

## Model and ownership gate

The existing project policy requires a designated GPT-5.6 Sol/Pro translation subagent at reasoning `high` or above. Request `gpt-5.6-sol` or a currently exposed GPT-5.6 Pro model explicitly, with `reasoning_effort: high` or stronger. Do not rely on an inherited/default model, infer an API model ID, or silently substitute Astra or another tier. Changing this policy requires explicit user authorization, not a prompt-cleanup task.

Give the subagent read-only access and bounded context: repository path, requested keys in catalog order, current source/target values, relevant neighboring copy, terminology, and this output contract. Do not grant repository write ownership. Runtime configuration/metadata is the evidence for the actual model and reasoning level; the subagent's prose assertion alone does not establish it.

Stop translation writeback without changing `en-US.json` or `scripts/i18n-baseline.json` when the required model or reasoning cannot be selected/verified, the actual model/tier differs, or the response is still invalid after one correction attempt. Report that blocker; do not translate in the main agent or downgrade the model. Preserve unrelated work.

## Output

Return one JSON object, no Markdown fences or commentary:

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

Require exactly the requested keys, once each, in catalog order. `source` must match the current Chinese catalog byte-for-byte. `placeholders` is the sorted source placeholder multiset, including repetitions. English must be non-empty, natural, and preserve that exact multiset; copying Chinese into English is not a translation.

Submit the base key and every required `_one` / `_other` sibling together. Keep English singular/plural agreement meaningful. A changed source sentence under an unchanged key still needs review.

## Terminology

| Source concept | Preferred English |
|---|---|
| 工作区 | workspace |
| 仓库 | repository |
| 工作区清单 | workspace manifest |
| 工作区文件 | workspace file / `.code-workspace` file, according to context |
| 功能分支 | feature branch |
| 跟随系统 | Follow system |

Preserve product/technical names: ReqWS, Git, macOS, VS Code, Cursor, JSON, IPC, and `.code-workspace`. Use neighboring catalog text to resolve tone or context.

## Main-agent validation and writeback

Parse the JSON and verify locale identifiers, identical requested/returned key sets and order, no duplicates/extras, exact current source values, non-empty targets, repeated placeholders, complete plural families, English number agreement, and terminology. Reject stale responses if source values changed after delegation. Do not rewrite unrelated keys.

Only after validation may the main agent update the corresponding `en-US.json` values. Inspect the scoped catalog diff before `i18n:apply`, then run `i18n:check` and affected tests. The apply script checks keys, placeholders, runtime mappings, and catalog digests before acknowledging the pair; it does not prove a linguistic review occurred. A failed apply must leave the previous baseline intact; correct the defect and repeat gated review for any changed translations.
