import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export interface RelayConfig {
  host: string
  port: number
  dataDir: string
  certPath: string | null
  keyPath: string | null
  version: string
}

const DEFAULT_PORT = 8443

export function loadConfig(env: NodeJS.ProcessEnv = process.env): RelayConfig {
  const version = readVersion()
  const port = env.SUPERPI_PORT ? Number.parseInt(env.SUPERPI_PORT, 10) : DEFAULT_PORT
  if (!Number.isFinite(port) || port < 0 || port > 65535) {
    throw new Error(`invalid SUPERPI_PORT: ${env.SUPERPI_PORT}`)
  }
  return {
    host: env.SUPERPI_HOST ?? '0.0.0.0',
    port,
    dataDir: env.SUPERPI_DATA_DIR ?? fileURLToPath(new URL('../../data/', import.meta.url)),
    certPath: env.SUPERPI_CERT ?? null,
    keyPath: env.SUPERPI_KEY ?? null,
    version,
  }
}

function readVersion(): string {
  const raw = readFileSync(fileURLToPath(new URL('../../package.json', import.meta.url)), 'utf8')
  const parsed: unknown = JSON.parse(raw)
  if (typeof parsed === 'object' && parsed !== null && 'version' in parsed && typeof parsed.version === 'string') {
    return parsed.version
  }
  return '0.0.0'
}
