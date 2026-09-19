import { createHash, X509Certificate } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export type BuildProfile = 'local' | 'personal-release';

export const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const releaseCertificatePath = path.join(repositoryRoot, 'build/certificates/reqws-signing.cer');

export function readTrustedSigningIdentities(keychain: string): string {
  return execFileSync('/usr/bin/security', ['find-identity', '-v', '-p', 'codesigning', keychain], {
    encoding: 'utf8', shell: false, stdio: ['ignore', 'pipe', 'pipe'], timeout: 15_000,
  });
}

export function assertTrustedSigningIdentity(
  identity: string,
  keychain: string,
  query: typeof readTrustedSigningIdentities = readTrustedSigningIdentities,
  allowOtherIdentities = false,
) {
  if (!/^[A-F0-9]{40}$/u.test(identity)) throw new Error('A certificate hash is required for signing.');
  const output = query(keychain);
  const identities = [...output.matchAll(/^\s*\d+\) ([A-F0-9]{40}) "[^"\r\n]+"\s*$/gmu)].map((match) => match[1]);
  if (identities.filter((value) => value === identity).length !== 1
    || (!allowOtherIdentities && identities.length !== 1)) {
    throw new Error('The pinned identity must be uniquely trusted for Code Signing in the selected keychain.');
  }
}

export function buildProfile(environment: NodeJS.ProcessEnv = process.env): BuildProfile {
  const profile = environment.REQWS_BUILD_PROFILE ?? 'local';
  if (profile !== 'local' && profile !== 'personal-release') {
    throw new Error('Unknown REQWS_BUILD_PROFILE; expected local or personal-release.');
  }
  return profile;
}

export function validateReleaseCertificate(
  bytes: Buffer,
  expectedPin: string | undefined,
  now = Date.now(),
): { identity: string; expiresAt: number } {
  if (!expectedPin || !/^[A-F0-9]{64}$/u.test(expectedPin)) {
    throw new Error('MAC_SIGNING_CERT_SHA256 must be an uppercase SHA-256 certificate fingerprint.');
  }
  const certificate = new X509Certificate(bytes);
  if (!bytes.equals(certificate.raw)) throw new Error('The public signing certificate must be DER.');
  if (createHash('sha256').update(bytes).digest('hex').toUpperCase() !== expectedPin) {
    throw new Error('The public signing certificate does not match MAC_SIGNING_CERT_SHA256.');
  }
  const expiresAt = Date.parse(certificate.validTo);
  if (now < Date.parse(certificate.validFrom) || now >= expiresAt) {
    throw new Error('The signing certificate is not currently valid.');
  }
  if (!certificate.checkIssued(certificate) || !certificate.verify(certificate.publicKey)) {
    throw new Error('The personal signing certificate must be self-signed.');
  }
  if (!certificate.keyUsage?.includes('1.3.6.1.5.5.7.3.3')) {
    throw new Error('The signing certificate must explicitly allow Code Signing.');
  }
  if (certificate.publicKey.asymmetricKeyType !== 'rsa'
    || (certificate.publicKey.asymmetricKeyDetails?.modulusLength ?? 0) < 4096) {
    throw new Error('The personal signing certificate must use RSA with at least 4096 bits.');
  }
  return { identity: createHash('sha1').update(bytes).digest('hex').toUpperCase(), expiresAt };
}

export function releaseSigningConfiguration(
  environment: NodeJS.ProcessEnv = process.env,
  certificateFile = releaseCertificatePath,
  query: typeof readTrustedSigningIdentities = readTrustedSigningIdentities,
) {
  const certificate = validateReleaseCertificate(
    readFileSync(certificateFile), environment.MAC_SIGNING_CERT_SHA256,
  );
  if (environment.REQWS_SIGNING_IDENTITY !== certificate.identity) {
    throw new Error('REQWS_SIGNING_IDENTITY must be the exact pinned certificate identity, not its name.');
  }
  const keychain = environment.REQWS_SIGNING_KEYCHAIN;
  if (!keychain || !path.isAbsolute(keychain)) {
    throw new Error('REQWS_SIGNING_KEYCHAIN must be an absolute keychain path.');
  }
  const status = lstatSync(keychain);
  if (!status.isFile() || status.isSymbolicLink()) throw new Error('The signing keychain must be a regular file.');
  assertTrustedSigningIdentity(certificate.identity, keychain, query);
  return {
    identity: certificate.identity,
    keychain,
    // osx-sign 1.3.3 searches the default X.509 policy. Enforce the correct
    // Code Signing policy above, then bypass only that redundant discovery.
    identityValidation: false,
    continueOnError: false,
    preAutoEntitlements: false,
    preEmbedProvisioningProfile: false,
    optionsForFile: () => ({
      hardenedRuntime: true,
      timestamp: 'none',
      entitlements: path.join(repositoryRoot, 'build/entitlements.personal-release.plist'),
    }),
  };
}
