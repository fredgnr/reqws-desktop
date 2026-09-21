---
name: reqws-documentation
description: Maintain ReqWS docs when documented behavior, acceptance criteria, developer workflows, or document organization changes, or when explicitly auditing documentation.
---

# ReqWS Documentation

Keep current documentation accurate and discoverable without manufacturing a document set for every edit. Read only the route relevant to the task:

| Need | Reference |
|---|---|
| Find a document or resolve its authority | [Documentation index](../../../docs/README.md); use it when the location or status is unclear. |
| Decide whether requirements, design, evidence, delivery, or a guide needs updating | [Documentation standard](../../../docs/standards/documentation-standard.md), especially the impact rules. |
| Create, move, rename, remove, or change a document's status/summary | The same standard's metadata, naming, and index rules, plus the affected directory's README. |
| Write or revise Chinese prose, onboarding, or screenshot mappings | [Chinese writing standard](../../../docs/standards/chinese-writing.md), based on the user-selected shuorenhua skill. Preserve facts, UI labels, conditions and completion status; distinguish prose edits from corrections verified against code or releases. |
| Choose checks or evaluate this skill | [Agent workflow guide](../../../docs/guides/agent-workflow.md). |

A known file can be opened directly. Read its relevant section and check status when authority matters; do not preload every linked document. Frozen `docs/reference/` material is historical evidence, not an editable current contract.

Update requirements/design before implementation only when they determine a changed contract. Document real behavior and evidence changes in the same work; a small correction can simply state that no other documentation is affected. Use lifecycle templates only when they add material value.

For structural changes, update the nearest README and stale inbound links; update parent indexes only when their direct entries or summaries change. Preserve unrelated edits and historical evidence. A read-only explanation or audit stays read-only unless edits were requested.

Keep user guides task-first: entry point, action, observable result, relevant limitation. Link detailed installation and internal design material instead of repeating it in the product overview. Mark planned screenshots in the handoff rather than linking nonexistent files; never present mockups or old screenshots as current runtime evidence. Ordinary Markdown editing does not authorize changing locale catalogs, installing plugins, restarting an IDE, or maintaining credentials.

Finish with `npm run docs:check` for documentation changes, or report why it could not run. Code checks follow the affected layer, not the mere activation of this skill. Report changed docs, relevant index updates, and genuine gaps without a mandatory five-category ledger for trivial work.
