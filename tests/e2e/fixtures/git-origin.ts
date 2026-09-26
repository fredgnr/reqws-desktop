import { execFile, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { registerOwnedProcess } from './process-registry';
import { constants } from 'node:fs';
import { access, appendFile, chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import type { ServerResponse } from 'node:http';
import { createServer } from 'node:https';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import type { Duplex } from 'node:stream';
import { promisify } from 'node:util';

import { GitRunner, redactGitOutput } from '../../../src/main/services/git-runner';
import { isolatedGitSpawn, type Isolation } from './isolation';

const runExecutable = promisify(execFile);
const PROCESS_TIMEOUT_MS = 15_000;
const HEADER_LIMIT_BYTES = 64 * 1024;
const REQUEST_LIMIT_BYTES = 1024 * 1024;
const OUTPUT_LIMIT_BYTES = 1024 * 1024;
const REPOSITORY_NAME = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/u;

export interface GitOrigin {
  addRepository(name: string): Promise<{ url: string; barePath: string }>;
  url(name: string): string;
  setUnavailable(value: boolean): void;
  close(): Promise<void>;
}

function assertRepositoryName(name: string): void {
  if (name.length > 64 || !REPOSITORY_NAME.test(name)) {
    throw new Error('Git fixture names must be single lowercase ASCII slugs.');
  }
}

async function opensslPath(environment: Record<string, string>): Promise<string> {
  const candidates = [
    '/opt/homebrew/opt/openssl@3/bin/openssl',
    '/usr/bin/openssl',
    ...(environment.PATH ?? '').split(path.delimiter)
      .filter((directory) => path.isAbsolute(directory))
      .map((directory) => path.join(directory, 'openssl')),
  ];
  for (const candidate of new Set(candidates)) {
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Use an already available executable; never install system tools.
    }
  }
  throw new Error('The HTTPS Git fixture requires an existing OpenSSL executable.');
}

async function createCertificates(isolation: Isolation): Promise<{
  caPath: string;
  key: Buffer;
  cert: Buffer;
}> {
  const directory = path.join(isolation.originRoot, '.tls');
  await mkdir(directory, { mode: 0o700 });
  const configPath = path.join(directory, 'openssl.cnf');
  const caKey = path.join(directory, 'ca.key');
  const caPath = path.join(directory, 'ca.pem');
  const serverKey = path.join(directory, 'server.key');
  const requestPath = path.join(directory, 'server.csr');
  const certificatePath = path.join(directory, 'server.pem');
  await writeFile(configPath, [
    '[req]',
    'distinguished_name = dn',
    'prompt = no',
    '[dn]',
    'CN = ReqWS E2E Temporary CA',
    '[ca_ext]',
    'basicConstraints = critical,CA:TRUE',
    'keyUsage = critical,keyCertSign,cRLSign',
    'subjectKeyIdentifier = hash',
    '[server_ext]',
    'basicConstraints = critical,CA:FALSE',
    'keyUsage = critical,digitalSignature,keyEncipherment',
    'extendedKeyUsage = serverAuth',
    'subjectAltName = IP:127.0.0.1',
    '',
  ].join('\n'), { flag: 'wx', mode: 0o600 });

  const executable = await opensslPath(isolation.env);
  const run = (args: readonly string[]) => runExecutable(executable, [...args], {
    cwd: directory,
    env: isolation.env,
    shell: false,
    timeout: PROCESS_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: OUTPUT_LIMIT_BYTES,
  });
  await run([
    'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes',
    '-keyout', caKey, '-out', caPath, '-days', '1',
    '-config', configPath, '-extensions', 'ca_ext',
  ]);
  await run([
    'req', '-new', '-newkey', 'rsa:2048', '-sha256', '-nodes',
    '-keyout', serverKey, '-out', requestPath,
    '-config', configPath, '-subj', '/CN=127.0.0.1',
  ]);
  await run([
    'x509', '-req', '-in', requestPath,
    '-CA', caPath, '-CAkey', caKey, '-CAcreateserial',
    '-out', certificatePath, '-days', '1', '-sha256',
    '-extfile', configPath, '-extensions', 'server_ext',
  ]);
  await chmod(caKey, 0o600);
  await chmod(serverKey, 0o600);
  return {
    caPath,
    key: await readFile(serverKey),
    cert: await readFile(certificatePath),
  };
}

function endResponse(response: ServerResponse, status: number, message: string): void {
  if (response.destroyed || response.writableEnded) return;
  if (response.headersSent) response.destroy();
  else {
    response.writeHead(status, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    response.end(message);
  }
}

function writeCgiHeaders(response: ServerResponse, raw: Buffer): void {
  let status = 200;
  const headers: Record<string, string> = {};
  for (const line of raw.toString('ascii').split(/\r?\n/u)) {
    const separator = line.indexOf(':');
    if (separator <= 0) throw new Error('Invalid Git CGI response header.');
    const name = line.slice(0, separator).toLowerCase();
    const value = line.slice(separator + 1).trim();
    if (name === 'status') {
      if (!/^[2-5][0-9]{2}(?: |$)/u.test(value)) throw new Error('Invalid Git CGI status.');
      status = Number(value.slice(0, 3));
    } else if (['content-type', 'content-length', 'cache-control', 'pragma', 'expires'].includes(name)) {
      headers[name] = value;
    }
  }
  response.writeHead(status, headers);
}

export async function createGitOrigin(isolation: Isolation): Promise<GitOrigin> {
  const certificates = await createCertificates(isolation);
  const git = await GitRunner.create(isolatedGitSpawn(isolation));
  const repositories = new Set<string>();
  const preparing = new Set<string>();
  const backendEnvironment: NodeJS.ProcessEnv = {
    PATH: isolation.env.PATH,
    HOME: isolation.home,
    XDG_CONFIG_HOME: isolation.env.XDG_CONFIG_HOME,
    TMPDIR: isolation.env.TMPDIR,
    LANG: isolation.env.LANG,
    LC_ALL: isolation.env.LC_ALL,
  };
  const sockets = new Set<Duplex>();
  const backends = new Map<ChildProcessWithoutNullStreams, Promise<void>>();
  const logWrites: Promise<void>[] = [];
  const logErrors: unknown[] = [];
  const terminationErrors: unknown[] = [];
  const terminationSignals = new WeakMap<ChildProcessWithoutNullStreams, Set<NodeJS.Signals>>();
  let unavailable = false;
  let closed = false;
  let closePromise: Promise<void> | undefined;
  let sequence = 0;

  const terminate = (child: ChildProcessWithoutNullStreams, signal: NodeJS.Signals): void => {
    if (child.pid === undefined) return;
    const signals = terminationSignals.get(child) ?? new Set<NodeJS.Signals>();
    if (signals.has(signal)) return;
    signals.add(signal);
    terminationSignals.set(child, signals);
    try {
      // Each backend owns a new process group, including its upload-pack child.
      process.kill(-child.pid, signal);
    } catch (error) {
      if (!(error instanceof Error && 'code' in error && error.code === 'ESRCH')) {
        terminationErrors.push(error);
        // Keep event handlers from throwing and leaving the direct child alive.
        // A group failure is still reported by close; it is not a cleanup pass.
        try { child.kill(signal); } catch (failure) { terminationErrors.push(failure); }
      }
    }
  };

  const server = createServer({
    key: certificates.key,
    cert: certificates.cert,
    minVersion: 'TLSv1.2',
  }, (request, response) => {
    if (closed || unavailable) {
      endResponse(response, 503, 'Git fixture unavailable.');
      return;
    }
    const target = request.url ?? '';
    const queryStart = target.indexOf('?');
    const pathname = queryStart < 0 ? target : target.slice(0, queryStart);
    const query = queryStart < 0 ? '' : target.slice(queryStart + 1);
    const route = /^\/([a-z][a-z0-9-]*)\.git\/(info\/refs|git-upload-pack)$/u.exec(pathname ?? '');
    const name = route?.[1];
    const endpoint = route?.[2];
    const discovery = request.method === 'GET' && endpoint === 'info/refs' && query === 'service=git-upload-pack';
    const upload = request.method === 'POST' && endpoint === 'git-upload-pack' && query === ''
      && request.headers['content-type'] === 'application/x-git-upload-pack-request';
    if (!name || !repositories.has(name) || (!discovery && !upload) || (request.url ?? '').includes('#')) {
      endResponse(response, 404, 'Unknown Git fixture endpoint.');
      return;
    }
    const protocol = request.headers['git-protocol'];
    if (protocol !== undefined && (typeof protocol !== 'string' || !/^version=[12]$/u.test(protocol))) {
      endResponse(response, 400, 'Unsupported Git protocol header.');
      return;
    }
    if (request.headers['content-encoding'] !== undefined) {
      endResponse(response, 400, 'Encoded fixture requests are not supported.');
      return;
    }

    const child = spawn(git.gitPath, ['http-backend'], {
      cwd: isolation.originRoot,
      env: {
        ...backendEnvironment,
        GIT_CONFIG_NOSYSTEM: '1',
        GIT_PROJECT_ROOT: isolation.originRoot,
        PATH_INFO: pathname,
        QUERY_STRING: query,
        REQUEST_METHOD: request.method,
        CONTENT_TYPE: request.headers['content-type'] ?? '',
        CONTENT_LENGTH: request.headers['content-length'] ?? '',
        REMOTE_ADDR: '127.0.0.1',
        ...(protocol ? { HTTP_GIT_PROTOCOL: protocol } : {}),
      },
      shell: false,
      stdio: 'pipe',
      detached: true,
    });
    registerOwnedProcess(child);
    const requestNumber = ++sequence;
    let header = Buffer.alloc(0);
    let headersWritten = false;
    let inputBytes = 0;
    let stderr = '';
    let resolveClosed: () => void = () => undefined;
    const completion = new Promise<void>((resolve) => { resolveClosed = resolve; });
    backends.set(child, completion);
    let killTimer: NodeJS.Timeout | undefined;
    const stop = (): void => {
      if (!backends.has(child)) return;
      terminate(child, 'SIGTERM');
      if (!killTimer) {
        killTimer = setTimeout(() => terminate(child, 'SIGKILL'), 2_000);
        killTimer.unref();
      }
    };
    const timer = setTimeout(() => {
      endResponse(response, 504, 'Git fixture backend timed out.');
      stop();
    }, PROCESS_TIMEOUT_MS);
    timer.unref();

    child.stdout.on('data', (chunk: Buffer) => {
      try {
        let body = chunk;
        if (!headersWritten) {
          header = Buffer.concat([header, chunk]);
          let separator = header.indexOf('\r\n\r\n');
          let width = 4;
          if (separator < 0) {
            separator = header.indexOf('\n\n');
            width = 2;
          }
          if (separator < 0) {
            if (header.length > HEADER_LIMIT_BYTES) throw new Error('Git CGI headers exceeded the limit.');
            return;
          }
          if (separator > HEADER_LIMIT_BYTES) throw new Error('Git CGI headers exceeded the limit.');
          writeCgiHeaders(response, header.subarray(0, separator));
          body = header.subarray(separator + width);
          header = Buffer.alloc(0);
          headersWritten = true;
        }
        if (body.length > 0 && !response.destroyed && !response.write(body)) {
          child.stdout.pause();
          response.once('drain', () => child.stdout.resume());
        }
      } catch {
        endResponse(response, 502, 'Invalid Git fixture backend response.');
        stop();
      }
    });
    child.stderr.on('data', (chunk: Buffer) => {
      if (stderr.length < OUTPUT_LIMIT_BYTES) stderr += chunk.toString('utf8').slice(0, OUTPUT_LIMIT_BYTES - stderr.length);
    });
    child.stdin.on('error', () => {
      endResponse(response, 502, 'Git fixture request stream failed.');
      stop();
    });
    child.once('error', () => {
      endResponse(response, 502, 'Unable to start Git fixture backend.');
      stop();
    });
    child.once('close', (code) => {
      clearTimeout(timer);
      if (killTimer) clearTimeout(killTimer);
      backends.delete(child);
      if (code !== 0 || !headersWritten) endResponse(response, 502, 'Git fixture backend failed.');
      else if (!response.destroyed && !response.writableEnded) response.end();
      logWrites.push(appendFile(
        path.join(isolation.logs, 'git-http-backend.log'),
        `${requestNumber} ${request.method} ${pathname} exit=${code}\n${redactGitOutput(stderr)}`,
        { mode: 0o600 },
      ).catch((error: unknown) => { logErrors.push(error); }));
      resolveClosed();
    });
    request.once('aborted', stop);
    request.once('error', stop);
    response.once('close', () => { if (!response.writableFinished) stop(); });
    request.on('data', (chunk: Buffer) => {
      inputBytes += chunk.length;
      if (inputBytes > REQUEST_LIMIT_BYTES) {
        request.unpipe(child.stdin);
        endResponse(response, 413, 'Git fixture request exceeded the limit.');
        stop();
      }
    });
    request.pipe(child.stdin);
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });

  const close = (): Promise<void> => {
    if (closePromise) return closePromise;
    closed = true;
    closePromise = (async () => {
      const closing = new Promise<void>((resolve, reject) => {
        server.close((error) => {
          if (error && !('code' in error && error.code === 'ERR_SERVER_NOT_RUNNING')) reject(error);
          else resolve();
        });
      });
      for (const socket of sockets) socket.destroy();
      const pending = [...backends.entries()];
      for (const [child] of pending) terminate(child, 'SIGTERM');
      const killTimer = setTimeout(() => {
        for (const [child] of pending) if (backends.has(child)) terminate(child, 'SIGKILL');
      }, 2_000);
      killTimer.unref();
      try {
        await Promise.all([closing, ...pending.map(([, completion]) => completion)]);
      } finally {
        clearTimeout(killTimer);
      }
      await Promise.all(logWrites);
      if (logErrors.length > 0) throw new AggregateError(logErrors, 'Unable to save Git fixture backend logs.');
      if (terminationErrors.length > 0) throw new AggregateError(terminationErrors, 'Unable to terminate the owned Git fixture process group.');
    })();
    return closePromise;
  };

  try {
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', () => {
        server.removeListener('error', reject);
        resolve();
      });
    });
    const address = server.address() as AddressInfo;
    const baseUrl = `https://127.0.0.1:${address.port}`;
    await writeFile(path.join(isolation.home, '.gitconfig'), [
      `[http "${baseUrl}/"]`,
      `  sslCAInfo = ${JSON.stringify(certificates.caPath)}`,
      '  sslVerify = true',
      '',
    ].join('\n'), { flag: 'wx', mode: 0o600 });
    const url = (name: string): string => {
      assertRepositoryName(name);
      return `${baseUrl}/${name}.git`;
    };
    const runGit = async (args: readonly string[], cwd?: string): Promise<void> => {
      const result = await git.run(args, { cwd, timeoutMs: PROCESS_TIMEOUT_MS });
      if (result.timedOut || result.exitCode !== 0) {
        throw new Error(`Git fixture setup failed: ${redactGitOutput(result.stderr)}`);
      }
    };
    return {
      url,
      setUnavailable: (value) => { unavailable = value; },
      close,
      addRepository: async (name) => {
        assertRepositoryName(name);
        if (closed) throw new Error('The Git fixture is closed.');
        if (repositories.has(name) || preparing.has(name)) throw new Error('The Git fixture repository already exists.');
        preparing.add(name);
        try {
          const barePath = path.join(isolation.originRoot, `${name}.git`);
          const seedPath = path.join(isolation.originRoot, '.seeds', name);
          await mkdir(seedPath, { recursive: true, mode: 0o700 });
          await runGit(['init', '--bare', '--initial-branch=main', barePath]);
          await runGit(['init', '--initial-branch=main'], seedPath);
          await writeFile(path.join(seedPath, 'README.txt'), `ReqWS Git fixture: ${name}\n`, { flag: 'wx', mode: 0o600 });
          await runGit(['add', '--', 'README.txt'], seedPath);
          await runGit([
            '-c', 'user.name=ReqWS Test', '-c', 'user.email=reqws@example.invalid',
            'commit', '-m', 'Create Git fixture',
          ], seedPath);
          await runGit(['push', '--', barePath, 'HEAD:refs/heads/main'], seedPath);
          await runGit(['config', 'http.receivepack', 'false'], barePath);
          await runGit(['config', 'http.getanyfile', 'false'], barePath);
          await writeFile(path.join(barePath, 'git-daemon-export-ok'), '', { flag: 'wx', mode: 0o600 });
          repositories.add(name);
          return { url: url(name), barePath };
        } finally {
          preparing.delete(name);
        }
      },
    };
  } catch (error) {
    await close();
    throw error;
  }
}
