#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import {
  existsSync,
  readFileSync,
  readdirSync,
  realpathSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REQUIRED_NODE_MAJOR = 24;
const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = path.resolve(SCRIPT_DIRECTORY, '..');
const INSTALL_SCRIPT = path.join(SCRIPT_DIRECTORY, 'install-macos.mts');

function addCandidate(candidates, candidate) {
  if (!candidate) return;
  const resolved = path.resolve(candidate);
  if (!existsSync(resolved)) return;
  let canonical;
  try {
    canonical = realpathSync(resolved);
  } catch {
    return;
  }
  if (!candidates.includes(canonical)) candidates.push(canonical);
}

function addNvmCandidates(candidates) {
  const nvmDirectory = process.env.NVM_DIR
    ? path.resolve(process.env.NVM_DIR)
    : path.join(os.homedir(), '.nvm');
  const versionsDirectory = path.join(nvmDirectory, 'versions', 'node');

  try {
    const requestedVersion = readFileSync(
      path.join(REPOSITORY_ROOT, '.nvmrc'),
      'utf8',
    ).trim();
    if (requestedVersion) {
      addCandidate(
        candidates,
        path.join(versionsDirectory, requestedVersion, 'bin', 'node'),
      );
    }
  } catch {
    // A missing .nvmrc is handled by the remaining candidates.
  }

  try {
    const installedVersions = readdirSync(versionsDirectory, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((left, right) => right.localeCompare(left, 'en', { numeric: true }));
    for (const version of installedVersions) {
      addCandidate(candidates, path.join(versionsDirectory, version, 'bin', 'node'));
    }
  } catch {
    // nvm is optional.
  }
}

function nodeVersion(candidate) {
  const result = spawnSync(
    candidate,
    ['-p', 'process.versions.node'],
    {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      timeout: 5_000,
    },
  );
  if (result.status !== 0) return undefined;
  const version = result.stdout.trim();
  return /^\d+\.\d+\.\d+(?:[-+].*)?$/u.test(version) ? version : undefined;
}

function nodeWithNpm(candidate) {
  const version = nodeVersion(candidate);
  if (!version || Number(version.split('.')[0]) !== REQUIRED_NODE_MAJOR) return undefined;

  const npmExecutable = path.join(path.dirname(candidate), 'npm');
  let npmCli;
  try {
    npmCli = realpathSync(npmExecutable);
  } catch {
    return undefined;
  }
  const npmResult = spawnSync(
    candidate,
    [npmCli, '--version'],
    {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      timeout: 5_000,
    },
  );
  if (npmResult.status !== 0 || !/^\d+\.\d+\.\d+(?:[-+].*)?$/u.test(npmResult.stdout.trim())) {
    return undefined;
  }
  return {
    executable: candidate,
    npmCli,
    npmVersion: npmResult.stdout.trim(),
    version,
  };
}

function findNode24() {
  const candidates = [];
  if (process.env.REQWS_NODE24 !== undefined) {
    const requested = process.env.REQWS_NODE24;
    if (!requested || !path.isAbsolute(requested)) {
      throw new Error('REQWS_NODE24 must be an absolute path to a Node.js 24 executable.');
    }
    if (!existsSync(requested)) {
      throw new Error(`REQWS_NODE24 does not exist: ${requested}`);
    }
    let canonical;
    try {
      canonical = realpathSync(requested);
    } catch (error) {
      throw new Error(`REQWS_NODE24 cannot be resolved: ${requested}`, { cause: error });
    }
    const selected = nodeWithNpm(canonical);
    if (!selected) {
      const version = nodeVersion(canonical);
      throw new Error(
        `REQWS_NODE24 must point to Node.js 24 with npm in the same bin directory; `
        + `found ${version ? `v${version}` : 'an unusable executable'} at ${requested}.`,
      );
    }
    return selected;
  }

  addCandidate(candidates, process.execPath);
  addNvmCandidates(candidates);
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (directory) addCandidate(candidates, path.join(directory, 'node'));
  }
  addCandidate(candidates, '/opt/homebrew/opt/node@24/bin/node');
  addCandidate(candidates, '/usr/local/opt/node@24/bin/node');
  addCandidate(candidates, '/opt/homebrew/bin/node');
  addCandidate(candidates, '/usr/local/bin/node');

  for (const candidate of candidates) {
    const selected = nodeWithNpm(candidate);
    if (selected) return selected;
  }
  return undefined;
}

function main() {
  const selected = findNode24();
  if (!selected) {
    throw new Error(
      `Node.js ${REQUIRED_NODE_MAJOR}.x is required, but the current runtime is ${process.version}. `
      + `Run "nvm install ${REQUIRED_NODE_MAJOR} && nvm use ${REQUIRED_NODE_MAJOR}", or set `
      + 'REQWS_NODE24 to the absolute path of a Node.js 24 executable.',
    );
  }

  if (path.resolve(selected.executable) !== path.resolve(process.execPath)) {
    console.log(
      `ReqWS: current runtime is ${process.version}; using Node v${selected.version} `
      + `from ${selected.executable}.`,
    );
  }

  const environment = { ...process.env };
  environment.NODE = selected.executable;
  environment.npm_execpath = selected.npmCli;
  environment.npm_node_execpath = selected.executable;
  environment.PATH = [
    path.dirname(selected.executable),
    process.env.PATH ?? '',
  ].filter(Boolean).join(path.delimiter);

  const result = spawnSync(
    selected.executable,
    [INSTALL_SCRIPT, ...process.argv.slice(2)],
    { env: environment, stdio: 'inherit' },
  );
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}

try {
  main();
} catch (error) {
  console.error(`ReqWS launcher failed: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
