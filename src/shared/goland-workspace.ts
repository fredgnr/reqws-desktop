import { z } from 'zod';

export const GOLAND_PROJECT_FILE = 'reqws-project.json';
export const GOLAND_PROJECT_MAX_BYTES = 1024 * 1024;
export const GOLAND_SHELL_SEGMENTS = ['.reqws', 'ide', 'goland'] as const;

const id = z.string().trim().min(1).max(200);
const bindingId = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu);
const revision = z.number().int().positive().max(Number.MAX_SAFE_INTEGER);

export const goLandSelectionSchema = z.discriminatedUnion('mode', [
  z.strictObject({ mode: z.literal('all') }),
  z.strictObject({
    mode: z.literal('selected'),
    repositoryIds: z.array(id).refine((ids) => new Set(ids).size === ids.length, {
      message: 'Repository IDs must be unique.',
    }),
  }),
]);

export const goLandProjectSchema = z.strictObject({
  schemaVersion: z.literal(1),
  adapterProtocol: z.literal(1),
  workspaceId: id,
  bindingId,
  revision,
  selection: goLandSelectionSchema,
  updatedAt: z.iso.datetime(),
});

export const prepareGoLandWorkspaceSchema = z.strictObject({
  workspaceId: id,
  selection: goLandSelectionSchema.optional(),
});

export const saveGoLandSelectionSchema = z.strictObject({
  workspaceId: id,
  selection: goLandSelectionSchema,
  expectedBindingId: bindingId,
  expectedRevision: revision,
});

export type GoLandSelection = z.infer<typeof goLandSelectionSchema>;
export type GoLandProject = z.infer<typeof goLandProjectSchema>;
export type PrepareGoLandWorkspaceInput = z.infer<typeof prepareGoLandWorkspaceSchema>;
export type SaveGoLandSelectionInput = z.infer<typeof saveGoLandSelectionSchema>;

export interface GoLandWorkspaceState {
  project: GoLandProject | null;
  shellPath: string;
}

/** IDs are membership references only; manifest order determines projection order. */
export function resolveGoLandSelection<T extends { catalogRepositoryId: string }>(
  members: readonly T[],
  selection: GoLandSelection,
): T[] {
  if (selection.mode === 'all') return [...members];
  const selected = new Set(selection.repositoryIds);
  return members.filter((member) => selected.has(member.catalogRepositoryId));
}
