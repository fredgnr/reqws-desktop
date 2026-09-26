import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { log } from 'node:console';
import * as fs from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath, URL } from 'node:url';
import vm from 'node:vm';

// Historical defect probes, not acceptance tests for the current application.
const baseline = '30ea9432efe7b9062ed68a7aa803543288f45abd';
const repositoryRoot = fileURLToPath(new URL('../../../', import.meta.url));
assert.equal(Number(process.versions.node.split('.')[0]), 24, 'Use Node.js 24.');
assert.ok(['darwin', 'linux'].includes(process.platform), 'Use macOS or Linux.');
assert.notEqual(process.getuid(), 0, 'Run as a non-root user for the EACCES probe.');

function baselineSource(file) {
  return execFileSync('git', ['show', `${baseline}:${file}`], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    shell: false,
  });
}

function between(source, start, end) {
  const first = source.indexOf(start);
  assert.notEqual(first, -1, `Missing baseline marker: ${start}`);
  const last = source.indexOf(end, first + start.length);
  assert.notEqual(last, -1, `Missing baseline marker: ${end}`);
  return source.slice(first, last);
}

const names = baselineSource('src/shared/repository-utils.ts');
const nameSource = names.slice(0, names.indexOf('\n')) + '\n'
  + between(names, 'export function normalizeRepositoryName', 'function hasControlCharacter');
const isValidRepositoryName = vm.runInNewContext(
  stripTypeScriptTypes(nameSource).replaceAll('export function ', 'function ')
    + '\nisValidRepositoryName;',
);
const redactGitOutput = vm.runInNewContext(
  stripTypeScriptTypes(between(
    baselineSource('src/main/services/git-runner.ts'),
    'export function redactGitOutput',
    'function hasUnsafeGitArgumentCharacters',
  )).replace('export function ', 'function ') + '\nredactGitOutput;',
);
const pathExistsSource = stripTypeScriptTypes(between(
  baselineSource('src/main/services/workspace-service.ts'),
  'async function pathExists',
  'function isNodeError',
));
const loadDataSource = stripTypeScriptTypes(between(
  baselineSource('src/renderer/App.tsx'),
  'const loadData = useCallback',
  '\n  useEffect(',
));

const fixtureRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'reqws-review-probes-'));
try {
  const fixtureHome = path.join(fixtureRoot, 'home');
  await fs.mkdir(fixtureHome);
  // Do not inherit Git config, hooks, credentials or repository overrides.
  const env = {
    PATH: process.env.PATH,
    HOME: fixtureHome,
    XDG_CONFIG_HOME: fixtureHome,
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_CONFIG_GLOBAL: '/dev/null',
    GIT_TERMINAL_PROMPT: '0',
    GIT_ALLOW_PROTOCOL: 'file',
    LC_ALL: 'C',
  };
  function git(args, cwd = fixtureRoot) {
    return execFileSync('git', args, { cwd, env, encoding: 'utf8', shell: false });
  }
  log(`baseline = ${baseline}`);
  log(`runtime = ${process.platform}/${process.arch}, Node ${process.version}, ${git(['--version']).trim()}`);

  const seed = path.join(fixtureRoot, 'seed');
  const origin = path.join(fixtureRoot, 'origin.git');
  git(['init', '--initial-branch=main', seed]);
  git(['config', 'user.name', 'Probe Fixture'], seed);
  git(['config', 'user.email', 'probe@example.invalid'], seed);
  await fs.writeFile(path.join(seed, 'workspace.json'), '{"userOwned":true}\n');
  git(['add', 'workspace.json'], seed);
  git(['commit', '-m', 'Create disposable fixture'], seed);
  git(['clone', '--bare', seed, origin]);

  // R01 models only name acceptance, clone placement and atomic replacement.
  const workspaceRoot = path.join(fixtureRoot, 'workspace');
  await fs.mkdir(workspaceRoot);
  const repositoryPath = path.join(workspaceRoot, '.reqws');
  assert.equal(isValidRepositoryName('.reqws'), true);
  git(['clone', origin, repositoryPath]);
  const repositoryFile = path.join(repositoryPath, 'workspace.json');
  const manifestPath = path.join(workspaceRoot, '.reqws', 'workspace.json');
  assert.equal(repositoryFile, manifestPath);
  const before = (await fs.readFile(repositoryFile, 'utf8')).trim();
  const replacement = '{"schemaVersion":1,"id":"review-workspace"}';
  const pending = path.join(repositoryPath, 'workspace.json.tmp');
  await fs.writeFile(pending, replacement + '\n');
  await fs.rename(pending, manifestPath);
  const after = (await fs.readFile(repositoryFile, 'utf8')).trim();
  const status = git(['status', '--porcelain'], repositoryPath).trim();
  assert.equal(before, '{"userOwned":true}');
  assert.equal(after, replacement);
  assert.equal(status, 'M workspace.json');
  assert.equal((await fs.readFile(path.join(seed, 'workspace.json'), 'utf8')).trim(), before);
  log(`R01: accepted=true, samePath=true, before=${before}, after=${after}, status=${status}`);

  // The SSH URI is written/read locally; GIT_ALLOW_PROTOCOL forbids networking.
  const expectedOrigin = 'ssh://git@example.invalid/team/repo.git';
  git(['remote', 'set-url', 'origin', expectedOrigin], repositoryPath);
  const rawOrigin = git(['remote', 'get-url', 'origin'], repositoryPath).trim();
  const returnedOrigin = redactGitOutput(rawOrigin);
  assert.equal(rawOrigin, expectedOrigin);
  assert.equal(returnedOrigin, 'ssh://<redacted>@example.invalid/team/repo.git');
  assert.notEqual(returnedOrigin, expectedOrigin);
  log(`R02: raw=${rawOrigin}, returned=${returnedOrigin}, matches=false`);

  const featureClone = path.join(fixtureRoot, 'feature-clone');
  git(['clone', origin, featureClone]);
  git(['switch', '-c', 'feature/review', 'origin/main'], featureClone);
  assert.equal(git(['config', '--get', 'branch.feature/review.remote'], featureClone).trim(), 'origin');
  assert.equal(git(['config', '--get', 'branch.feature/review.merge'], featureClone).trim(), 'refs/heads/main');
  const push = spawnSync('git', ['push', '--dry-run'], {
    cwd: featureClone, env, encoding: 'utf8', shell: false,
  });
  if (push.error) throw push.error;
  assert.notEqual(push.status, 0);
  assert.match(push.stderr, /upstream branch of your current branch does not match/u);
  log('R03: remote=origin, merge=refs/heads/main, push --dry-run rejects the upstream name mismatch');

  const blockedParent = path.join(fixtureRoot, 'blocked');
  await fs.mkdir(blockedParent);
  const existingFile = path.join(blockedParent, 'exists.txt');
  await fs.writeFile(existingFile, 'existing file');
  await fs.chmod(blockedParent, 0o000);
  try {
    const output = execFileSync(process.execPath, ['--input-type=module', '-e', `
      import assert from 'node:assert/strict';
      import * as fs from 'node:fs/promises';
      ${pathExistsSource}
      assert.notEqual(process.getuid(), 0);
      const target = process.argv[1];
      let code;
      try { await fs.access(target); } catch (error) { code = error.code; }
      const exists = await pathExists(target);
      assert.equal(code, 'EACCES');
      assert.equal(exists, false);
      console.log('R05: fs.access error.code=' + code + ', pathExists=' + exists);
    `, existingFile], { env, encoding: 'utf8', shell: false });
    log(output.trim());
  } finally {
    await fs.chmod(blockedParent, 0o700);
  }

  // Run the baseline callback with plain setters; this does not mount React.
  const oldRepositories = Promise.withResolvers();
  const repositoryResponses = [oldRepositories.promise, Promise.resolve(['new'])];
  const workspaceResponses = [Promise.resolve(['old']), Promise.resolve(['new'])];
  const state = {};
  const loadData = vm.runInNewContext(loadDataSource + '\nloadData;', {
    useCallback: (callback) => callback,
    api: {
      repositories: { list: () => repositoryResponses.shift() },
      workspaces: { list: () => workspaceResponses.shift() },
      editors: { getAvailability: async () => ({}) },
    },
    setRepositories: (value) => { state.repositories = value; },
    setWorkspaces: (value) => { state.workspaces = value; },
    setAvailability: (value) => { state.availability = value; },
    setLoading: (value) => { state.loading = value; },
    setRefreshing: (value) => { state.refreshing = value; },
    toastError: (error) => { throw error; },
  });
  const requestA = loadData(true);
  await loadData();
  assert.deepEqual(state.repositories, ['new']);
  assert.deepEqual(state.workspaces, ['new']);
  log('R06 after B: repositories=new, workspaces=new');
  oldRepositories.resolve(['old']);
  await requestA;
  assert.deepEqual(state.repositories, ['old']);
  assert.deepEqual(state.workspaces, ['old']);
  log('R06 after A: repositories=old, workspaces=old');
  log('Confirmed 5 historical defect probes (R01, R02, R03, R05, R06).');
} finally {
  // Only remove the directory this invocation created with mkdtemp.
  await fs.rm(fixtureRoot, { recursive: true, force: true });
}
