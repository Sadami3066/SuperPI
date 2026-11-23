# Self-Hosting the Relay (`apps/relay`)

`apps/relay` is a lightweight Node.js ≥ 24 service that lets your SuperPI Android phones and Desktop clients discover each other, route bot sessions, transfer files, and synchronize shared **Memory v2** state. It never runs models, executes tools, or reads provider keys.

## 1. Run with Docker (Recommended)

Container images are published to GitHub Container Registry on every `relay-v*` tag:

```bash
docker run -d \
  --name superpi-relay \
  --restart unless-stopped \
  -p 8443:8443 \
  -v superpi-relay-data:/data \
  ghcr.io/sadami3066/superpi-relay:latest
```

Inspect container logs on first startup to copy the one-time **account login secret**:

```bash
docker logs superpi-relay
```

For internet-facing deployments, either terminate TLS behind a reverse proxy (Caddy/Nginx) and supply cert/CA environment variables, or mount generated self-signed certs via `SUPERPI_CERT`, `SUPERPI_KEY`, and `SUPERPI_CA`.

## 2. Run from Source (Node.js ≥ 24)

```bash
git clone https://github.com/Sadami3066/SuperPI.git
cd SuperPI
pnpm install

# Quick LAN setup: generates self-signed TLS certs, starts relay, prints pairing URL + login secret
./apps/relay/scripts/dev-relay.sh
```

Or invoke directly with explicit environment variables:

```bash
SUPERPI_HOST=0.0.0.0 \
SUPERPI_PORT=8443 \
SUPERPI_DATA_DIR=/var/lib/superpi-relay \
SUPERPI_CERT=/etc/superpi-relay/server.crt \
SUPERPI_KEY=/etc/superpi-relay/server.key \
SUPERPI_CA=/etc/superpi-relay/ca.crt \
pnpm --filter @superpi/relay start
```

### Generate LAN / Custom TLS Certificates

```bash
SUPERPI_CERT_IPS="192.168.1.50" ./apps/relay/scripts/gen-cert.sh ./certs
```

`gen-cert.sh` creates `ca.crt`, `server.crt`, `server.key`, and `fingerprint.txt` containing the pinned SHA-256 CA fingerprint expected by SuperPI pairing links.

## 3. Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `SUPERPI_HOST` | `0.0.0.0` | Listen address |
| `SUPERPI_PORT` | `8443` | Listen port (`0` for ephemeral test port) |
| `SUPERPI_DATA_DIR` | `./data` | SQLite (`relay.db`) storage directory |
| `SUPERPI_CERT` | unset | Server TLS certificate PEM path (plain HTTP when cert/key omitted) |
| `SUPERPI_KEY` | unset | Server TLS private key PEM path |
| `SUPERPI_CA` | unset | CA certificate PEM returned at `GET /v1/ca` for fingerprint pinning |
| `SUPERPI_PUBLIC_ORIGIN` | unset | Public `https://...` origin advertised in pairing links |
| `SUPERPI_LAN_ORIGIN` | unset | LAN `https://...` origin advertised in pairing links |

## 4. Health & Pairing

- Health check: `GET /v1/health` returns running relay version and wire protocol version.
- Pair your first device using the `https://<host>:<port>/#fp=<sha256>` link (or QR code) plus the one-time **login secret**.
- Subsequent phones/desktops can scan a **short-lived pairing code** generated from any already-paired device without exposing the master secret.
- On Android, if you enable **Allow remote control of this phone**, exempt SuperPI from battery optimization so WebSocket connections survive screen-off idle.
