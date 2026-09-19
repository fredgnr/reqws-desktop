import { z } from 'zod';
import { updateErrorCodes, updatePhases, updateReasons } from './update-types';

export const updateNoArgumentsSchema = z.tuple([]);
export const updateStateSchema = z.strictObject({
  revision: z.number().int().nonnegative().safe(),
  phase: z.enum(updatePhases),
  currentVersion: z.string().min(1).max(80),
  nextVersion: z.string().regex(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u).max(80).optional(),
  percent: z.number().finite().min(0).max(100).optional(),
  reason: z.enum(updateReasons).optional(),
  errorCode: z.enum(updateErrorCodes).optional(),
  releaseNotes: z.string().max(8_000).optional(),
});
