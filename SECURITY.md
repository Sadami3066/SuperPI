# Security Policy

## Scope

SuperPI-Remote handles device pairing codes, bearer device tokens, WebSocket routing between paired devices, and shared memory storage over self-signed TLS (HTTPS/WSS).

## Reporting a Vulnerability

If you discover a security vulnerability in authentication, device revocation, TLS certificate pinning, rate limiting, or frame routing:

- **Do not open a public GitHub issue.**
- Report the issue privately via GitHub Security Advisories on the repository or contact the maintainers directly through GitHub.
- Do not commit or share private keys (`*.key`, `*.pem`), bearer tokens, or production SQLite databases.
