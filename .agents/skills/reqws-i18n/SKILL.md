---
name: reqws-i18n
description: Keep ReqWS Simplified Chinese and English UI copy synchronized. Use this skill whenever user-visible copy, src/renderer/locales/zh-CN.json, src/renderer/locales/en-US.json, an i18n key, placeholder, plural form, error code, workspace status, or operation message changes, or when npm reports stale translations. Automatically delegate translation review to a gated GPT-5.6 Sol/Pro subagent before updating English resources.
---

# ReqWS Internationalization

Keep Simplified Chinese as the reviewed source catalog and English as its complete, independently reviewed translation. Static checks catch structural drift; the translation subagent supplies the linguistic review that those checks cannot provide.

## 1. Establish the translation delta

Read the changed Renderer code and both catalogs completely enough to understand the affected namespace:

```text
src/renderer/locales/zh-CN.json
src/renderer/locales/en-US.json
```

Search for each changed key and its callers. User-visible JSX must use catalog keys rather than inline single-language text.

Update the Chinese source copy first, including every required i18next plural sibling and placeholder. Then run:

```bash
npm run i18n:scan
```

Before the baseline is updated, this command may exit non-zero solely because catalogs changed or English keys are missing. Treat any other finding—placeholder mismatch, missing runtime key, missing error/status/message mapping, or malformed catalog—as a defect that must be fixed. Never run `i18n:apply` merely to silence the scan.

## 2. Enforce the translation-agent gate

Translation work must use a designated subagent; do not translate the catalog only in the main agent.

Spawn the subagent with an explicit model override of `gpt-5.6-sol` (or a currently exposed GPT-5.6 Pro model) and `reasoning_effort: high` or stronger. Use a bounded context fork or no fork and include the repository path, changed keys, source/target catalog paths, terminology, and output contract in the task. Do not rely on an inherited/default model.

Stop without changing `en-US.json` or the baseline when:

- neither GPT-5.6 Sol nor GPT-5.6 Pro is available;
- the requested reasoning effort cannot be set to at least `high`;
- the spawned agent reports a different model/tier;
- the response does not satisfy the structured output contract after one correction attempt.

Report the model gate as the blocker instead of silently falling back to another model or translating in the main agent.

## 3. Require structured translations only

Tell the translation subagent not to edit files and to return one JSON object with no Markdown fences or commentary:

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

The response must contain exactly the requested keys, once each, in catalog order. `source` must exactly match the current Chinese catalog. `placeholders` must list the source placeholder multiset in sorted order, including repeats. `target` must be a natural English translation, preserve all placeholders exactly, and must not be an untranslated Chinese placeholder.

Give the subagent enough neighboring copy to preserve tone and these established terms:

| Source concept | Preferred English |
|---|---|
| 工作区 | workspace |
| 仓库 | repository |
| 工作区清单 | workspace manifest |
| 工作区文件 | workspace file / `.code-workspace` file, according to context |
| 功能分支 | feature branch |
| 跟随系统 | Follow system |

Keep product and technical names unchanged: ReqWS, Git, macOS, VS Code, Cursor, JSON, IPC, and `.code-workspace`.

For plural families, submit the base key and every `_one` / `_other` sibling together. The English singular and plural must be grammatically distinct where the count changes meaning. A changed source sentence is a translation delta even when its key already exists.

## 4. Validate before writing English

The main agent owns validation and file updates. Parse the JSON response and reject it unless:

1. the locale identifiers are exactly `zh-CN` and `en-US`;
2. requested and returned key sets are identical, with no duplicates or extras;
3. every returned `source` matches the current Chinese catalog byte-for-byte;
4. each target is non-empty and preserves the exact placeholder multiset;
5. plural siblings are complete and English number agreement is sensible;
6. terminology matches the surrounding catalog and the table above;
7. no unrelated key is rewritten.

After validation, update only the corresponding values in `en-US.json`. Review the two catalog diffs together, then run the required sequence:

```bash
npm run i18n:apply
npm run i18n:check
```

`i18n:apply` validates keys, placeholders, runtime mappings, and both catalog digests before acknowledging the reviewed pair in `scripts/i18n-baseline.json`. If it fails, leave the previous baseline intact, correct the catalogs, and repeat the gated review as needed.

Run the relevant Renderer and contract tests after the catalog check. In the final handoff, identify the changed keys, the gated subagent model and reasoning level, and the commands that passed.
