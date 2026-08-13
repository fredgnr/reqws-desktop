const INVALID_REPOSITORY_NAMES = new Set(['', '.', '..']);
const CREDENTIAL_PARAMETER = /^(?:access[_-]?token|api[_-]?key|auth|authorization|credential|oauth[_-]?token|password|private[_-]?key|private[_-]?token|secret|token)$/iu;
const AUTHORITY_URI = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//u;
const ALLOWED_GIT_PROTOCOLS = new Set(['https:', 'ssh:']);
const SCP_LIKE_REMOTE = /^(?:[^@/:\s]+@)?[^@/:\s]+:.+$/u;

export function deriveRepositoryName(url: string): string {
  const normalized = url.trim().replace(/\/+$/, '');
  const segment = normalized.split(/[/:]/u).pop() ?? '';
  return segment.replace(/\.git$/iu, '');
}

export function normalizeRepositoryName(name: string): string {
  return name.normalize('NFC').trim();
}

export function repositoryNameKey(name: string): string {
  return normalizeRepositoryName(name).toLocaleLowerCase('en-US');
}

export function isValidRepositoryName(name: string): boolean {
  const normalized = normalizeRepositoryName(name);
  return (
    !INVALID_REPOSITORY_NAMES.has(normalized) &&
    !normalized.includes('/') &&
    !normalized.includes('\\') &&
    !normalized.includes('\0')
  );
}

function hasControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 0x20 || codePoint === 0x7f;
  });
}

function hasCredentialParameter(url: URL): boolean {
  for (const key of url.searchParams.keys()) {
    if (CREDENTIAL_PARAMETER.test(key)) return true;
  }
  if (url.hash) {
    const fragment = new URLSearchParams(url.hash.slice(1));
    for (const key of fragment.keys()) {
      if (CREDENTIAL_PARAMETER.test(key)) return true;
    }
  }
  return false;
}

/**
 * Git remotes are persisted in clear text, so reject credential-bearing URLs
 * and input that can become a command option. SSH usernames such as `git@` are
 * identity selectors rather than secrets and remain supported.
 */
export function isSafeRepositoryUrl(input: string): boolean {
  const value = input.normalize('NFC').trim();
  if (!value || value.startsWith('-') || hasControlCharacter(value)) return false;

  const authorityUri = AUTHORITY_URI.exec(value);
  if (authorityUri) {
    let parsed: URL;
    try {
      parsed = new URL(value);
    } catch {
      return false;
    }

    const protocol = parsed.protocol.toLocaleLowerCase('en-US');
    if (!ALLOWED_GIT_PROTOCOLS.has(protocol)) return false;
    if (protocol === 'http:' || protocol === 'https:') {
      if (parsed.username || parsed.password) return false;
    } else if (protocol === 'ssh:') {
      // ssh://git@host is supported; ssh://git:secret@host is not.
      if (parsed.password) return false;
    } else if (parsed.username || parsed.password) {
      return false;
    }
    return !hasCredentialParameter(parsed);
  }

  if (value.includes('://')) return false;
  // Git's remote-helper syntax (for example ext::) can launch an executable.
  if (value.includes('::')) return false;
  if (SCP_LIKE_REMOTE.test(value)) {
    const firstColon = value.indexOf(':');
    return (
      !value.slice(firstColon + 1).includes('@') &&
      !/[?#](?:[^#]*)(?:token|password|secret|credential)=/iu.test(value)
    );
  }

  return false;
}
