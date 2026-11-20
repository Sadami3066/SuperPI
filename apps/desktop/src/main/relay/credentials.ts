import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export interface RelayCredentials {
  origin: string
  caPem: string | null
  deviceId: string
  token: string
  deviceName: string
  accountId: string
  enrolledAt: number
}

export class RelayCredentialStore {
  private readonly homeDir: string
  private readonly file: string

  constructor(homeDir: string) {
    this.homeDir = homeDir
    this.file = path.join(homeDir, 'relay.json')
  }

  read(): RelayCredentials | null {
    if (!existsSync(this.file)) return null
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf8')) as Partial<RelayCredentials>
      if (
        typeof parsed.origin !== 'string' ||
        typeof parsed.deviceId !== 'string' ||
        typeof parsed.token !== 'string' ||
        typeof parsed.deviceName !== 'string' ||
        typeof parsed.accountId !== 'string' ||
        typeof parsed.enrolledAt !== 'number'
      ) {
        return null
      }
      return {
        origin: parsed.origin,
        caPem: typeof parsed.caPem === 'string' ? parsed.caPem : null,
        deviceId: parsed.deviceId,
        token: parsed.token,
        deviceName: parsed.deviceName,
        accountId: parsed.accountId,
        enrolledAt: parsed.enrolledAt,
      }
    } catch {
      return null
    }
  }

  save(credentials: RelayCredentials): void {
    mkdirSync(this.homeDir, { recursive: true })
    writeFileSync(this.file, `${JSON.stringify(credentials, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
    chmodSync(this.file, 0o600)
  }

  clear(): void {
    rmSync(this.file, { force: true })
  }
}
