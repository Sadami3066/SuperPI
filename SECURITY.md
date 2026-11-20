# Security Policy

## Architecture & Trust Boundaries

1. **Local Execution & Linux Environment**:
   - In Normal and Wireless ADB modes, the bundled Alpine environment runs inside `proot` user space (not a security sandbox).
   - In Root mode, commands execute directly in a `chroot` / root shell with full device privileges.
2. **External `.ddp` Plugins**:
   - External plugins are loaded into the SuperPI process only after verifying their Ed25519 signature (`META-INF/plugin-signature.json`) and pinning the publisher `keyId` (Trust-On-First-Use or verified market `publisherKeyId`).
   - Plugins share the host process and UID; install only plugins from trusted publishers.
3. **Self-Hosted Multi-Device Relay (`SuperPI-Remote`)**:
   - Connections to `SuperPI-Remote` use HTTPS/WSS with pinned CA certificate SHA-256 fingerprints (`RelayTls`) and revocable per-device bearer tokens.
4. **Miniapps & Local File Views**:
   - Local file and HTML previews enforce canonical path boundary checks and disallow arbitrary JavaScript bridge privilege escalation.

## Reporting a Vulnerability

If you discover a security vulnerability in SuperPI:

- **Do not open a public GitHub issue.**
- Report it privately via GitHub Security Advisories on the repository or contact the maintainers directly through GitHub.
- Do not include private signing keys, model API keys, or personal session transcripts in your report.
