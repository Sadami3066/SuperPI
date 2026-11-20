# Security Policy

## Execution Model

External `.ddp` plugins run inside the SuperPI Android process with the same UID and Android permissions as the host app. Installing a plugin requires trusting its publisher `keyId`.

## Reporting a Vulnerability

If you discover a security issue in the packaging scripts, signature verification tasks, or plugin contract documentation:

- **Do not open a public GitHub issue.**
- Report it privately via GitHub Security Advisories on the repository or contact the maintainers directly through GitHub.
- Never post private signing keys (`.pem`), tokens, or exploit payloads publicly.
