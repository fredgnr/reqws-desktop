import { z } from 'zod';

// The signed resource is the only production feed. Strict parsing rejects token,
// URL and provider overrides, even if they would otherwise be valid updater YAML.
export const updateConfigSchema = z.strictObject({
  provider: z.literal('github'),
  owner: z.literal('fredgnr'),
  repo: z.literal('reqws-desktop'),
  private: z.literal(false),
  updaterCacheDirName: z.literal('reqws-desktop-updater'),
});

export function parseUpdateConfig(contents: string) {
  // Our checked-in YAML is JSON, a YAML 1.2 subset; accepting only this subset
  // avoids a second configuration parser and YAML aliases/custom tags.
  return updateConfigSchema.parse(JSON.parse(contents) as unknown);
}
