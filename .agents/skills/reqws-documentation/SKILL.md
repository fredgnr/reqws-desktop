---
name: reqws-documentation
description: Maintain ReqWS project documentation whenever work adds or changes product behavior, requirements, acceptance criteria, IPC/schema/state/security/packaging decisions, test plans or evidence, delivery scope, or document structure. Also use for documentation searches, audits, creation, moves, cleanup, and behavior-changing bug fixes; assess documentation impact even when the user does not explicitly request docs.
---

# ReqWS Documentation

Keep project knowledge discoverable without creating ceremonial documents. Treat the repository documentation standard as the source of truth and make documentation part of the same change as the behavior it explains.

## 1. Load the documentation context

Read these files completely before making task changes:

1. `docs/README.md`
2. `docs/standards/documentation-standard.md`
3. The relevant category and requirement `README.md` files
4. The active leaf documents linked by those indexes

Search indexes before full text:

```bash
rg -n -i --glob 'README.md' '<keyword|requirement-id|module>' docs
rg -n -i --glob '*.md' '<keyword|requirement-id|module>' docs
rg --files docs | sort
```

Use business terms together with implementation identifiers such as IPC channels, Zod schemas, service names, state fields, or error codes. `docs/reference/` is frozen historical input; consult it for provenance, never as the default current authority.

## 2. Decide the documentation impact

Before implementation, classify each of these as `create`, `update`, or `none`, with a short reason:

- requirements and acceptance criteria
- technical design and decisions
- test plan or verification evidence
- delivery, migration, rollback, and known limitations

Apply the decision table in `docs/standards/documentation-standard.md`. Do not create an empty four-document set. One document may cover adjacent concerns when they are reviewed and maintained together; record that coverage in the requirement index.

For a read-only explanation or review, inspect documents but do not mutate them unless the user also requested documentation changes.

## 3. Make documentation changes at the right time

- Update requirements and design before code when they determine implementation direction.
- Confirm test scope before verification; record commands, environment, results, and evidence gaps after execution.
- Create delivery notes only when there is a release, milestone, migration, rollback, or external handoff to record.
- Put lifecycle documents in `docs/changes/<requirement-id-or-kebab-topic>/` and use the templates under `docs/standards/templates/` selectively.
- Preserve unrelated working-tree edits. Do not rewrite frozen files under `docs/reference/` to match current behavior.

Use the required frontmatter, statuses, naming, concise opening sentence, and relative links from the documentation standard.

## 4. Keep the index chain complete

When adding, moving, renaming, deleting, or changing the status or one-line summary of a document:

1. Update the nearest `README.md`.
2. Update its parent index when a direct child or child summary changed.
3. Update `docs/README.md` only when top-level navigation or category summaries changed.
4. Search for stale inbound links using the old path, filename, and topic terms.

Every `docs/` subdirectory needs a `README.md`. Indexes should link only direct children and describe each in one sentence; avoid duplicating leaf content in parent indexes.

## 5. Verify and hand off

Run:

```bash
npm run docs:check
```

Then run task-relevant tests and `npm run check` before a final code handoff when feasible. Fix broken links, missing directory indexes, unindexed files, invalid frontmatter, and naming violations rather than bypassing the checker.

In the final response, list:

- documents created or updated
- indexes updated
- documentation types intentionally not produced and why
- documentation and project checks run
