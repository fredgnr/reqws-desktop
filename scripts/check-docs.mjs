import { lstat, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const docsRoot = path.join(repositoryRoot, 'docs');
const allowedTypes = new Set([
  'delivery',
  'governance',
  'requirements',
  'technical-design',
  'test-plan',
  'test-report',
]);
const allowedStatuses = new Set(['active', 'archived', 'draft', 'superseded']);
const failures = [];

async function exists(target) {
  try {
    await lstat(target);
    return true;
  } catch {
    return false;
  }
}

async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const directories = [directory];
  const files = [];

  for (const entry of entries) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      const nested = await walk(target);
      directories.push(...nested.directories);
      files.push(...nested.files);
    } else if (entry.isFile()) {
      files.push(target);
    } else if (entry.isSymbolicLink()) {
      failures.push(`${path.relative(repositoryRoot, target)} must not be a symbolic link`);
    }
  }

  return { directories, files };
}

function withoutCodeFences(source) {
  let inFence = false;
  return source
    .split('\n')
    .filter((line) => {
      if (/^\s{0,3}(?:```|~~~)/u.test(line)) {
        inFence = !inFence;
        return false;
      }
      return !inFence;
    })
    .join('\n');
}

function linkTargets(source) {
  const targets = [];
  const content = withoutCodeFences(source);
  for (const match of content.matchAll(/!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+['"][^'"]*['"])?\)/gu)) {
    targets.push(match[1].replace(/^<|>$/gu, ''));
  }
  return targets;
}

function tableLinkTargets(source) {
  return withoutCodeFences(source)
    .split('\n')
    .filter((line) => /^\s*\|.*\|\s*$/u.test(line))
    .flatMap((line) => linkTargets(line));
}

function localTarget(sourceFile, rawTarget) {
  if (!rawTarget || rawTarget.startsWith('#') || rawTarget.startsWith('//')) return undefined;
  if (/^[a-z][a-z0-9+.-]*:/iu.test(rawTarget)) return undefined;

  const pathPart = rawTarget.split('#', 1)[0].split('?', 1)[0];
  if (!pathPart) return undefined;

  try {
    return path.resolve(path.dirname(sourceFile), decodeURIComponent(pathPart));
  } catch {
    failures.push(`${path.relative(repositoryRoot, sourceFile)} has an invalid link: ${rawTarget}`);
    return undefined;
  }
}

function isInsideRepository(target) {
  const relative = path.relative(repositoryRoot, target);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

async function hasExactCase(target) {
  const relative = path.relative(repositoryRoot, target);
  if (!isInsideRepository(target)) return false;
  if (!relative) return true;

  let current = repositoryRoot;
  for (const segment of relative.split(path.sep)) {
    const names = await readdir(current);
    if (!names.includes(segment)) return false;
    current = path.join(current, segment);
  }
  return true;
}

function parseFrontmatter(source) {
  if (!source.startsWith('---\n')) return undefined;
  const end = source.indexOf('\n---\n', 4);
  if (end < 0) return undefined;

  const metadata = new Map();
  for (const line of source.slice(4, end).split('\n')) {
    const match = /^([a-z][a-z-]*):\s*(.+)$/u.exec(line);
    if (!match) continue;
    const rawValue = match[2].trim();
    const value = /^(?:"([^"]*)"|'([^']*)')$/u.exec(rawValue);
    metadata.set(match[1], value ? value[1] ?? value[2] : rawValue);
  }
  return metadata;
}

function usesManagedMetadata(file) {
  const relative = path.relative(docsRoot, file);
  return file.endsWith('.md')
    && path.basename(file) !== 'README.md'
    && !relative.startsWith(`reference${path.sep}`)
    && !relative.startsWith(`standards${path.sep}templates${path.sep}`);
}

function usesManagedName(target) {
  const relative = path.relative(docsRoot, target);
  return relative !== 'reference' && !relative.startsWith(`reference${path.sep}`);
}

function isRealDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day;
}

function indexStatus(source, sourceFile, expectedTarget) {
  for (const line of withoutCodeFences(source).split('\n')) {
    if (!/^\s*\|.*\|\s*$/u.test(line)) continue;
    const lineTargets = linkTargets(line)
      .map((target) => localTarget(sourceFile, target))
      .filter(Boolean)
      .map((target) => path.normalize(target));
    if (!lineTargets.includes(path.normalize(expectedTarget))) continue;
    const cells = line.split('|').slice(1, -1).map((cell) => cell.trim());
    return cells.find((cell) => allowedStatuses.has(cell));
  }
  return undefined;
}

const { directories, files } = await walk(docsRoot);
const markdownFiles = files.filter((file) => file.endsWith('.md'));
const linkSourceFiles = [
  path.join(repositoryRoot, 'README.md'),
  path.join(repositoryRoot, 'AGENTS.md'),
  ...markdownFiles,
];

for (const directory of directories) {
  const relative = path.relative(repositoryRoot, directory);
  const readme = path.join(directory, 'README.md');
  if (!await exists(readme)) failures.push(`${relative} is missing README.md`);

  if (directory !== docsRoot && usesManagedName(directory) && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(path.basename(directory))) {
    failures.push(`${relative} must use kebab-case`);
  }
}

for (const file of files) {
  const relative = path.relative(repositoryRoot, file);
  const basename = path.basename(file);
  if (usesManagedName(file) && basename !== 'README.md') {
    const stem = basename.slice(0, basename.length - path.extname(basename).length);
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(stem)) failures.push(`${relative} must use kebab-case`);
  }

  if (!usesManagedMetadata(file)) continue;
  const source = await readFile(file, 'utf8');
  const metadata = parseFrontmatter(source);
  if (!metadata) {
    failures.push(`${relative} is missing YAML frontmatter`);
    continue;
  }

  for (const field of ['title', 'type', 'status', 'updated']) {
    if (!metadata.has(field)) failures.push(`${relative} frontmatter is missing ${field}`);
  }
  if (metadata.has('type') && !allowedTypes.has(metadata.get('type'))) {
    failures.push(`${relative} has invalid type: ${metadata.get('type')}`);
  }
  if (metadata.has('status') && !allowedStatuses.has(metadata.get('status'))) {
    failures.push(`${relative} has invalid status: ${metadata.get('status')}`);
  }
  if (metadata.has('updated') && !isRealDate(metadata.get('updated'))) {
    failures.push(`${relative} has invalid updated date: ${metadata.get('updated')}`);
  }
  if (metadata.get('status') === 'superseded') {
    const successor = metadata.get('superseded-by');
    if (!successor) {
      failures.push(`${relative} is superseded but has no superseded-by link`);
    } else {
      const target = localTarget(file, successor);
      if (!target || !isInsideRepository(target) || !await exists(target)) {
        failures.push(`${relative} has invalid superseded-by target: ${successor}`);
      }
    }
  }
}

for (const directory of directories) {
  const readme = path.join(directory, 'README.md');
  if (!await exists(readme)) continue;

  const source = await readFile(readme, 'utf8');
  const indexedTargets = new Map(
    tableLinkTargets(source)
      .map((target) => localTarget(readme, target))
      .filter(Boolean)
      .map((target) => [path.normalize(target), target]),
  );
  const entries = await readdir(directory, { withFileTypes: true });

  for (const entry of entries) {
    if (entry.name === 'README.md') continue;
    const expected = entry.isDirectory()
      ? path.join(directory, entry.name, 'README.md')
      : path.join(directory, entry.name);
    if (!indexedTargets.has(path.normalize(expected))) {
      failures.push(`${path.relative(repositoryRoot, expected)} is not indexed by ${path.relative(repositoryRoot, readme)}`);
      continue;
    }

    if (entry.isFile() && usesManagedMetadata(expected)) {
      const metadata = parseFrontmatter(await readFile(expected, 'utf8'));
      const listedStatus = indexStatus(source, readme, expected);
      if (!listedStatus) {
        failures.push(`${path.relative(repositoryRoot, expected)} has no status in ${path.relative(repositoryRoot, readme)}`);
      } else if (metadata?.get('status') && listedStatus !== metadata.get('status')) {
        failures.push(`${path.relative(repositoryRoot, expected)} status (${metadata.get('status')}) does not match ${path.relative(repositoryRoot, readme)} (${listedStatus})`);
      }
    }
  }
}

for (const sourceFile of linkSourceFiles) {
  if (!await exists(sourceFile)) continue;
  const source = await readFile(sourceFile, 'utf8');
  for (const rawTarget of linkTargets(source)) {
    const target = localTarget(sourceFile, rawTarget);
    if (!target) continue;

    const sourceRelative = path.relative(repositoryRoot, sourceFile);
    if (!isInsideRepository(target)) {
      failures.push(`${sourceRelative} link escapes the repository: ${rawTarget}`);
    } else if (!await exists(target)) {
      failures.push(`${sourceRelative} has a broken link: ${rawTarget}`);
    } else if (!await hasExactCase(target)) {
      failures.push(`${sourceRelative} has a case-mismatched link: ${rawTarget}`);
    }
  }
}

const uniqueFailures = [...new Set(failures)].sort();
if (uniqueFailures.length > 0) {
  console.error(uniqueFailures.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`Documentation is consistent (${directories.length} indexes, ${files.length} files).`);
}
