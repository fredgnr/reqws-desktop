import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const localeDirectory = path.join(repositoryRoot, 'src', 'renderer', 'locales');
const rendererDirectory = path.join(repositoryRoot, 'src', 'renderer');
const sharedTypesPath = path.join(repositoryRoot, 'src', 'shared', 'types.ts');
const sharedErrorsPath = path.join(repositoryRoot, 'src', 'shared', 'errors.ts');
const baselinePath = path.join(repositoryRoot, 'scripts', 'i18n-baseline.json');
const mode = process.argv[2];

if (mode && mode !== '--scan' && mode !== '--apply') {
  throw new Error(`Unknown option: ${mode}`);
}

function digest(value) {
  return createHash('sha256').update(value).digest('hex');
}

function catalogDigest(catalog) {
  return digest(JSON.stringify([...catalog.entries()].sort(([left], [right]) =>
    left.localeCompare(right),
  )));
}

function flatten(value, prefix = '', result = new Map()) {
  for (const [key, child] of Object.entries(value)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    if (typeof child === 'string') result.set(fullKey, child);
    else if (child && typeof child === 'object' && !Array.isArray(child)) {
      flatten(child, fullKey, result);
    } else {
      throw new Error(`${fullKey} must be a string or an object.`);
    }
  }
  return result;
}

function placeholders(value) {
  return [...value.matchAll(/\{\{\s*([\w.-]+)(?:\s*,[^}]*)?\s*\}\}/gu)]
    .map((match) => match[1])
    .sort();
}

function unionMembers(source, typeName) {
  const declaration = new RegExp(`export type ${typeName}\\s*=([\\s\\S]*?);`, 'u')
    .exec(source)?.[1] ?? '';
  return [...declaration.matchAll(/['"]([^'"]+)['"]/gu)].map((match) => match[1]);
}

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await sourceFiles(target));
    else if (/\.(?:ts|tsx)$/u.test(entry.name)) files.push(target);
  }
  return files;
}

const zhSource = await readFile(path.join(localeDirectory, 'zh-CN.json'), 'utf8');
const enSource = await readFile(path.join(localeDirectory, 'en-US.json'), 'utf8');
const zhCN = flatten(JSON.parse(zhSource));
const enUS = flatten(JSON.parse(enSource));
const failures = [];

for (const key of zhCN.keys()) {
  if (!enUS.has(key)) failures.push(`en-US is missing key: ${key}`);
}
for (const key of enUS.keys()) {
  if (!zhCN.has(key)) failures.push(`zh-CN is missing key: ${key}`);
}
for (const [key, source] of zhCN) {
  const translated = enUS.get(key);
  if (translated === undefined) continue;
  if (JSON.stringify(placeholders(source)) !== JSON.stringify(placeholders(translated))) {
    failures.push(`placeholder mismatch for ${key}`);
  }
}

for (const file of await sourceFiles(rendererDirectory)) {
  const source = await readFile(file, 'utf8');
  for (const match of source.matchAll(/\bt\(\s*['"]([^'"]+)['"]/gu)) {
    const key = match[1];
    if (!zhCN.has(key)) {
      failures.push(`${path.relative(repositoryRoot, file)} uses missing key: ${key}`);
    }
  }
  for (const match of source.matchAll(
    /['"]((?:app|common|confirmDialog|createWorkspace|errors|navigation|operation|repositories|repositoryDialog|settings|shell|workspaceDetail|workspaces)(?:\.[\w-]+)+)['"]/gu,
  )) {
    const key = match[1];
    if (!zhCN.has(key)) {
      failures.push(`${path.relative(repositoryRoot, file)} references missing key: ${key}`);
    }
  }
}

const sharedTypes = await readFile(sharedTypesPath, 'utf8');
for (const kind of unionMembers(sharedTypes, 'OperationKind')) {
  if (!zhCN.has(`operation.titles.${kind}`)) {
    failures.push(`operation title is missing for kind: ${kind}`);
  }
}
for (const stage of unionMembers(sharedTypes, 'OperationStage')) {
  if (!zhCN.has(`operation.stages.${stage}`)) {
    failures.push(`operation stage is missing: ${stage}`);
  }
}
for (const status of unionMembers(sharedTypes, 'WorkspaceStatus')) {
  if (!zhCN.has(`common.status.${status}`)) {
    failures.push(`workspace status is missing: ${status}`);
  }
}

const sharedErrors = await readFile(sharedErrorsPath, 'utf8');
const errorCodeDeclaration = /export const reqwsErrorCodes\s*=\s*\[([\s\S]*?)\]\s*as const/u
  .exec(sharedErrors)?.[1] ?? '';
for (const match of errorCodeDeclaration.matchAll(/['"]([^'"]+)['"]/gu)) {
  if (!zhCN.has(`errors.codes.${match[1]}`)) {
    failures.push(`localized error message is missing for code: ${match[1]}`);
  }
}

let baseline;
try {
  baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
} catch {
  if (mode !== '--apply') {
    failures.push('i18n baseline is missing or invalid; run npm run i18n:apply after reviewing both locales.');
  }
}

const current = {
  version: 1,
  zhCN: catalogDigest(zhCN),
  enUS: catalogDigest(enUS),
};
const sourceChanged = baseline?.zhCN !== current.zhCN;
const translationChanged = baseline?.enUS !== current.enUS;

if (baseline && sourceChanged && !translationChanged) {
  failures.push('en-US translations are stale because zh-CN changed without a corresponding English update.');
} else if (baseline && mode !== '--apply' && (sourceChanged || translationChanged)) {
  failures.push('locale resources changed; review them and run npm run i18n:apply to acknowledge the synchronized catalog.');
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else if (mode === '--apply') {
  await writeFile(baselinePath, `${JSON.stringify(current, null, 2)}\n`, 'utf8');
  console.log(`i18n baseline updated (${zhCN.size} keys).`);
} else if (mode === '--scan') {
  console.log(`i18n scan found no stale translations (${zhCN.size} keys).`);
} else {
  console.log(`i18n resources are consistent (${zhCN.size} keys).`);
}
