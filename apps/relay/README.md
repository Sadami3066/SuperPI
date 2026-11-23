# SuperPI Remote

Relay, account, presence and shared-memory server for SuperPI devices.

A single self-hosted service that lets multiple SuperPI Android devices act as one assistant: it keeps the account, tracks which devices are online, routes a request from one device to a bot running on another, and holds the one shared memory all devices read and write.

## What it does

- **Account and devices** — one account per deployment, device tokens with revocation, pairing by short-lived codes or QR scan between phones.
- **Presence** — online state derived from the live connection, never self-reported.
- **Routing** — a device asks another device to run or manage one of its bots, transfer workspace attachments, or stream `present` rich media artifacts; events stream back over the same channel.
- **Shared memory** — item-level incremental Memory v2 sync (`/v2/memory/push`, `/v2/memory/pull`), canonical entity merging, and a cooldown-aware Deep Dream lease (`/v2/memory/dream-lease`) to coordinate multi-phone consolidation.
- **Transport** — HTTPS and WSS under a self-signed CA whose fingerprint is pinned at pairing time.

It does not run models, execute tools, or interpret conversations.

## Repository layout

```
packages/protocol/   wire protocol shared with SuperPI Android & Desktop clients, plus JSON test vectors
apps/relay/src/      HTTP and WebSocket server, configuration, entry point
apps/relay/test/     server tests
apps/relay/scripts/  deployment and certificate helpers
```

## Requirements

- Node.js 24 or newer
- A Linux host reachable by both devices (bare IP is fine)

## Run

```bash
pnpm install
SUPERPI_PORT=8443 SUPERPI_HOST=0.0.0.0 pnpm start
```

`GET /v1/health` returns the running version and protocol version. Configuration is read from environment variables: `SUPERPI_HOST`, `SUPERPI_PORT`, `SUPERPI_DATA_DIR`, `SUPERPI_CERT`, `SUPERPI_KEY`.

For production deployment with Docker or systemd and TLS certificates, see [Self-Hosting Relay](../../docs/user/self-hosting-relay.md).

## Development

```bash
pnpm typecheck
pnpm test
pnpm dev
```

## Protocol

The wire format lives in `packages/protocol`: frame types, error codes, memory write operations and HTTP payloads, with JSON vectors under `packages/protocol/vectors` used by every client implementation. The `@superpi/protocol` package is the canonical source.

## License

Apache-2.0. See [LICENSE](LICENSE).

## Local pairing

```bash
./scripts/dev-relay.sh
```

The script generates a self-signed certificate for the LAN address, starts the relay, and prints the pairing link with the certificate fingerprint embedded. On first start it also prints the account login secret once. Enter or scan the link in your SuperPI Android devices; the first device can use the login secret alone, later devices use a pairing code (or QR code) or the same login secret while the account is single-user.
