# Security Policy

## Architecture & Trust Boundaries

1. **Local Execution & Linux Environment (`apps/android/shell`)**:
   - In Normal and Wireless ADB modes, the bundled Alpine environment runs inside `proot` user space (not a security sandbox).
   - In Root mode, commands execute directly in a `chroot` / root shell with full device privileges.
2. **External `.ddp` Plugins**:
   - External plugins load into the SuperPI Android process only after verifying their Ed25519 signature (`META-INF/plugin-signature.json`) and pinning the publisher `keyId` (TOFU or verified market `publisherKeyId`).
   - Plugins share the host process and UID; install only plugins from trusted publishers.
3. **Self-Hosted Multi-Device Relay (`apps/relay`)**:
   - Connections use HTTPS/WSS with pinned CA certificate SHA-256 fingerprints and revocable per-device bearer tokens.
4. **Miniapps & Workspace File Access**:
   - File tools, workspace transfer handlers, and miniapp viewers enforce canonical path boundary checks.

## Reporting a Vulnerability

If you discover a security vulnerability in any part of SuperPI:

- **Do not open a public GitHub issue.**
- Report it privately via GitHub Security Advisories on [`Sadami3066/SuperPI`](https://github.com/Sadami3066/SuperPI/security/advisories/new) or contact the maintainer directly.
- Never attach private signing keys, `.keystore` files, model API keys, or personal session transcripts.
