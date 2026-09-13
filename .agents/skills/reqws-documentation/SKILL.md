---
name: reqws-documentation
description: Create, restructure, or audit ReqWS docs; use when requirements, design decisions, or acceptance criteria need documentation changes.
---

# ReqWS Documentation

Keep current decisions discoverable in the same change as the behavior they describe. Do not create a document set merely because code changed.

## Choose the relevant route

- Known document or small correction: read the affected section and its status directly. Check the nearest index only if its entry or navigation is affected.
- Unknown requirement or authority: start at [the documentation index](../../../docs/README.md), then follow the relevant category or search by business term, requirement ID, or code identifier.
- New behavior, design decision, acceptance criteria, or document structure: use the impact and metadata rules in [the documentation standard](../../../docs/standards/documentation-standard.md). Read only the applicable sections and active documents.

An ordinary read-only code explanation, formatting edit, or typo does not require this workflow. A read-only documentation audit may use it, but does not authorize edits. Historical reference files remain frozen.

## Completion contract

Update the material requirements, decisions, verification guidance, or user/developer instructions affected by the request. Settle decisions that drive implementation before coding; record actual evidence after verification. Use templates only for documents that need independent maintenance.

Keep nearest indexes, affected parent entries, and inbound links consistent when paths, status, or summaries change. Run `npm run docs:check` after documentation edits; select other checks by the actual code changes, not by this skill. Fix introduced defects and finish the documentation update rather than stopping at an outline.

Report changed documents and meaningful evidence gaps. A small change may state that no additional lifecycle documents were needed; do not require a five-category report for every edit.
