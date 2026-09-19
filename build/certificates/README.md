# Personal release certificate

`reqws-signing.cer` is the persistent **public DER certificate only** for
`ReqWS Personal Code Signing`, initialized on 2026-09-19 as described in the
[self-update design](../../docs/changes/macos-self-update/technical-design.md#4-一次性生成密钥与证书).

The certificate uses RSA 4096, SHA-256, and Code Signing EKU. It is valid from
2026-09-19 through 2036-09-16. The `macos-release` environment stores its SHA-256
pin in `MAC_SIGNING_CERT_SHA256`; `personal-release` validates the DER against
that pin and requires the matching identity in an explicit signing keychain.
The encrypted P12 and its password are separate environment Secrets. Private
keys, P12 files and passwords must remain outside this source repository and all
release assets. The maintainer explicitly selected a separate private credential
repository for the complete backup, including passwords; see the design's
storage policy before restoring or changing that backup.

Use the project [reqws-signing-maintenance skill](../../.agents/skills/reqws-signing-maintenance/SKILL.md)
for identity audits, credential repair, packaging-password changes or migration.
Restore private material only temporarily. Changing a P12 or PEM password keeps
this DER and the environment pin unchanged; replacing or renewing the certificate
requires a reviewed identity migration.

Reuse this exact identity for subsequent releases. Reissuing a certificate with
the same name is an identity change and requires an explicit migration; never
replace it with a generated fixture or silently update the pin. See the
[configuration and verification record](../../docs/changes/macos-self-update/implementation-2026-09-19.md#4-长期身份与-github-environment-初始化)
for the local signing probe, environment checks and remaining acceptance gates.

The release profile enables Hardened Runtime with JIT and disabled library
validation for the self-signed Electron runtime. This is not Developer ID signing
or notarization; actual signed startup and native updating remain acceptance gates.
