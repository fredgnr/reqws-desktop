# ReqWS plugin verification certificate

`reqws-plugin-chain.crt` is the public verification consumer for the independent JetBrains plugin signing identity. It is not the macOS signing identity.

The authoritative encrypted private key, password, public key and certificate are backed up only in the private `fredgnr/reqws-secret` repository, under `reqws-desktop/jetbrains-plugin-signing/`. The initial verified backup commit is `5caab1579e63d7e38e5b169bd78a669cca558de0`. Recovery from that exact remote commit passed a real ZIP Signer signing/verification test before the public certificate and protected runtime Secrets were activated.

Do not place private material here. Certificate replacement requires deliberate identity maintenance, verified private backup/recovery, review and a new release tag. Release and upload jobs never clone the private repository. See [publishing operations](../../docs/changes/goland-plugin-marketplace/bootstrap-and-operations.md).
