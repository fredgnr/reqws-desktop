const INVALID_REPOSITORY_NAMES = new Set(['', '.', '..']);
const CREDENTIAL_PARAMETER = /^(?:access[_-]?token|api[_-]?key|auth|authorization|credential|oauth[_-]?token|password|private[_-]?key|private[_-]?token|secret|token)$/iu;
const AUTHORITY_URI = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//u;
const ALLOWED_GIT_PROTOCOLS = new Set(['https:', 'ssh:']);
const LEGACY_SCP_LIKE_REMOTE = /^(?:[^@/:\s]+@)?[^@/:\s]+:.+$/u;
const ASCII_HOST_CHARACTER = /^[A-Za-z0-9._~-]$/u;
const DECIMAL_HOST_LABEL = /^[0-9]+$/u;
const HEX_HOST_LABEL = /^0x[0-9A-Fa-f]+$/u;
const IPV6_HOST_CHARACTERS = /^[0-9A-Fa-f:.]+$/u;
const HOST_DOT = /[.\u3002\uff0e\uff61]/u;
const PERCENT_ESCAPE = /%([0-9A-Fa-f]{2})/gu;

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

function hasWhitespaceCharacter(value: string): boolean {
  return Array.from(value).some((character) => /\s/u.test(character));
}

function decodePercentEscapes(value: string): string {
  return value.replace(PERCENT_ESCAPE, (_escape, hexadecimal: string) => (
      String.fromCharCode(Number.parseInt(hexadecimal, 16))
  ));
}

function decodeHostPercentEscapes(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

function decodeParameterKey(value: string): string {
  return decodePercentEscapes(value.replaceAll('+', ' '));
}

function hasCredentialParameterText(rawParameters: string | null): boolean {
  if (!rawParameters) return false;
  return rawParameters.split('&').some((entry) => CREDENTIAL_PARAMETER.test(
    decodeParameterKey(entry.split('=', 1)[0] ?? ''),
  ));
}

function hasCredentialParameters(value: string): boolean {
  const fragmentStart = value.indexOf('#');
  const queryStart = value.indexOf('?');
  const query = queryStart >= 0 && (
    fragmentStart < 0 || queryStart < fragmentStart
  )
    ? value.slice(
      queryStart + 1,
      fragmentStart < 0 ? value.length : fragmentStart,
    )
    : null;
  const fragment = fragmentStart < 0 ? null : value.slice(fragmentStart + 1);
  return hasCredentialParameterText(query) || hasCredentialParameterText(fragment);
}

function rawAuthority(value: string): string {
  const authorityStart = value.indexOf('://') + 3;
  const tailStart = value.slice(authorityStart).search(/[/?#]/u);
  return tailStart < 0
    ? value.slice(authorityStart)
    : value.slice(authorityStart, authorityStart + tailStart);
}

function isValidPort(value: string): boolean {
  if (!/^[0-9]*$/u.test(value)) return false;
  if (!value) return true;
  const significantDigits = value.replace(/^0+/u, '') || '0';
  return significantDigits.length < 5 || (
    significantDigits.length === 5 && significantDigits <= '65535'
  );
}

function parseIpv4Number(value: string): number | null {
  let digits = value;
  let radix = 10;
  if (/^0[xX]/u.test(digits)) {
    digits = digits.slice(2);
    radix = 16;
    if (!/^[0-9A-Fa-f]+$/u.test(digits)) return null;
  } else if (digits.length >= 2 && digits.startsWith('0')) {
    digits = digits.slice(1);
    radix = 8;
    if (!/^[0-7]+$/u.test(digits)) return null;
  } else if (!/^[0-9]+$/u.test(digits)) {
    return null;
  }
  const parsed = Number.parseInt(digits, radix);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function isValidIpv4(value: string): boolean {
  const parts = value.split('.');
  if (parts.at(-1) === '') parts.pop();
  if (parts.length < 1 || parts.length > 4) return false;
  const numbers = parts.map(parseIpv4Number);
  if (numbers.some((number) => number === null)) return false;
  const values = numbers as number[];
  if (values.slice(0, -1).some((number) => number > 255)) return false;
  return values.at(-1)! < 256 ** (5 - values.length);
}

function isForbiddenHostCodePoint(codePoint: number): boolean {
  return (
    codePoint >= 0xd800 && codePoint <= 0xdfff
  ) || (
    codePoint >= 0x80 && codePoint <= 0x9f
  ) || codePoint === 0x200c || codePoint === 0x200d;
}

function hasValidDnsHost(
  value: string,
  enforceSpecialSchemeIpv4 = true,
): boolean {
  if (!value) return false;
  const decodedValue = decodeHostPercentEscapes(value);
  if (decodedValue === null || decodedValue.includes('%')) return false;
  const comparableValue = HOST_DOT.test(decodedValue.at(-1) ?? '')
    ? decodedValue.slice(0, -1)
    : decodedValue;
  const labels = comparableValue.split(HOST_DOT);
  const lastLabel = labels.at(-1) ?? '';
  if (
    enforceSpecialSchemeIpv4
    && (DECIMAL_HOST_LABEL.test(lastLabel) || HEX_HOST_LABEL.test(lastLabel))
  ) {
    return isValidIpv4(comparableValue);
  }
  return labels.every((label) => Array.from(label).every((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint > 0x7f
      ? !isForbiddenHostCodePoint(codePoint) && !hasWhitespaceCharacter(character)
      : ASCII_HOST_CHARACTER.test(character);
  }));
}

function isValidEmbeddedIpv4(value: string): boolean {
  const parts = value.split('.');
  return parts.length === 4 && parts.every((part) => (
    /^[0-9]+$/u.test(part) &&
    (part === '0' || !part.startsWith('0')) &&
    part.length <= 3 &&
    Number(part) <= 255
  ));
}

function isValidIpv6(value: string): boolean {
  if (!IPV6_HOST_CHARACTERS.test(value)) return false;
  let comparableValue = value;
  if (value.includes('.')) {
    const ipv4Separator = value.lastIndexOf(':');
    if (ipv4Separator < 0 || !isValidEmbeddedIpv4(value.slice(ipv4Separator + 1))) {
      return false;
    }
    // An embedded IPv4 address occupies the final two 16-bit IPv6 groups.
    comparableValue = `${value.slice(0, ipv4Separator)}:0:0`;
  }

  const firstCompression = comparableValue.indexOf('::');
  if (firstCompression !== comparableValue.lastIndexOf('::')) return false;
  if (firstCompression < 0) {
    const groups = comparableValue.split(':');
    return groups.length === 8 && groups.every((group) => (
      group.length >= 1 && group.length <= 4
    ));
  }
  const left = comparableValue.slice(0, firstCompression);
  const right = comparableValue.slice(firstCompression + 2);
  if (left.endsWith(':') || right.startsWith(':')) return false;
  const groups = [
    ...(left ? left.split(':') : []),
    ...(right ? right.split(':') : []),
  ];
  return groups.length < 8 && groups.every((group) => group.length >= 1 && group.length <= 4);
}

function hasValidHostAndPort(
  value: string,
  enforceSpecialSchemeIpv4: boolean,
): boolean {
  if (value.startsWith('[')) {
    const closingBracket = value.indexOf(']');
    if (closingBracket <= 1) return false;
    if (!isValidIpv6(value.slice(1, closingBracket))) return false;
    const remainder = value.slice(closingBracket + 1);
    return remainder === '' || (
      remainder.startsWith(':') && isValidPort(remainder.slice(1))
    );
  }

  if ((value.match(/:/gu) ?? []).length > 1) return false;
  const separator = value.lastIndexOf(':');
  const host = separator < 0 ? value : value.slice(0, separator);
  const port = separator < 0 ? null : value.slice(separator + 1);
  return hasValidDnsHost(host, enforceSpecialSchemeIpv4)
    && (port === null || isValidPort(port));
}

function hasValidAuthority(
  value: string,
  protocol: string,
): boolean {
  const authority = rawAuthority(value);
  if (!authority) return false;

  // WHATWG URL parsing treats the final literal `@` as the authority
  // separator and percent-encodes earlier `@` characters into the username.
  const userInfoSeparator = authority.lastIndexOf('@');
  const hasUserInfo = userInfoSeparator >= 0;
  const userInfo = hasUserInfo ? authority.slice(0, userInfoSeparator) : '';
  const hostAndPort = hasUserInfo ? authority.slice(userInfoSeparator + 1) : authority;
  // WHATWG applies numeric-host/IPv4 coercion to special HTTPS URLs but not
  // to the non-special ssh scheme. Preserve that legacy persisted-state
  // boundary while keeping both implementations parser-independent.
  if (!hasValidHostAndPort(hostAndPort, protocol === 'https:')) return false;

  // Preserve legacy empty HTTPS userinfo (`https://@host`) and the equivalent
  // empty username/password boundary (`https://:@host`) without accepting any
  // non-empty HTTPS credential text.
  if (protocol === 'https:') {
    return !hasUserInfo || !userInfo || userInfo === ':';
  }
  if (!hasUserInfo) return true;

  // SSH usernames are identity selectors rather than persisted credentials.
  // A literal colon creates the password boundary. The legacy parser also
  // accepted a single trailing colon because that password is empty; preserve
  // that credential-free representation along with encoded-colon usernames.
  const firstColon = userInfo.indexOf(':');
  return firstColon < 0 || (
    firstColon === userInfo.length - 1
    && firstColon === userInfo.lastIndexOf(':')
  );
}

function isSafeScpLikeRemote(value: string): boolean {
  const firstColon = value.indexOf(':');
  if (firstColon <= 0 || firstColon === value.length - 1) return false;
  const identity = value.slice(0, firstColon);
  const suffix = value.slice(firstColon + 1);
  const firstAt = identity.indexOf('@');
  if (firstAt !== identity.lastIndexOf('@')) return false;
  const username = firstAt < 0 ? null : identity.slice(0, firstAt);
  const host = firstAt < 0 ? identity : identity.slice(firstAt + 1);
  if (
    !host ||
    host.includes('/') ||
    hasWhitespaceCharacter(host) ||
    !hasValidDnsHost(host, false)
  ) return false;
  if (username !== null && (
    !username ||
    /[:@/]/u.test(username) ||
    hasWhitespaceCharacter(username)
  )) return false;
  return !suffix.includes('@') && !hasCredentialParameters(suffix);
}

/**
 * Git remotes are persisted in clear text, so reject credential-bearing URLs
 * and input that can become a command option. SSH usernames such as `git@` are
 * identity selectors rather than secrets and remain supported.
 */
export function isSafeRepositoryUrl(input: string): boolean {
  const value = input.normalize('NFC').trim();
  if (
    !value ||
    value.startsWith('-') ||
    hasControlCharacter(value)
  ) return false;
  // HTTPS parsers historically treat backslashes as path separators. Apply
  // that normalization only for structural validation; the original value is
  // still passed as one argument and never through a shell.
  const structuralValue = value.replaceAll('\\', '/');

  const authorityUri = AUTHORITY_URI.exec(structuralValue);
  if (authorityUri) {
    const protocol = `${authorityUri[1]?.toLocaleLowerCase('en-US')}:`;
    if (!ALLOWED_GIT_PROTOCOLS.has(protocol)) return false;
    if (!hasValidAuthority(structuralValue, protocol)) return false;
    return !hasCredentialParameters(structuralValue);
  }

  if (structuralValue.includes('://')) return false;
  // Git's remote-helper syntax (for example ext::) can launch an executable.
  if (structuralValue.includes('::')) return false;
  if (isSafeScpLikeRemote(structuralValue)) return true;

  return false;
}

function legacyHasCredentialParameter(url: URL): boolean {
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
 * Exact pre-GoLand policy used only to keep an existing state.v1 catalog
 * readable. New/updated repositories and every workspace manifest still use
 * [isSafeRepositoryUrl], so legacy-only values are quarantined until edited.
 */
export function isLegacySafePersistedRepositoryUrl(input: string): boolean {
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
    if (protocol === 'https:' && (parsed.username || parsed.password)) return false;
    if (protocol === 'ssh:' && parsed.password) return false;
    return !legacyHasCredentialParameter(parsed);
  }

  if (value.includes('://') || value.includes('::')) return false;
  if (!LEGACY_SCP_LIKE_REMOTE.test(value)) return false;
  const firstColon = value.indexOf(':');
  return (
    !value.slice(firstColon + 1).includes('@')
    && !/[?#](?:[^#]*)(?:token|password|secret|credential)=/iu.test(value)
  );
}
