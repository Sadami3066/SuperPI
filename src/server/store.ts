import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { DatabaseSync } from 'node:sqlite'

export interface AccountRecord {
  id: string
  secretHash: string
  createdAt: number
}

export interface DeviceRecord {
  id: string
  name: string
  platform: string
  tokenHash: string
  createdAt: number
  lastSeen: number
  revoked: boolean
}

export type PairCodeStatus = 'ok' | 'missing' | 'used' | 'expired' | 'too_many_attempts'

const PAIR_CODE_ATTEMPTS = 5

export class RelayStore {
  private readonly db: DatabaseSync

  constructor(dataDir: string) {
    mkdirSync(dataDir, { recursive: true })
    this.db = new DatabaseSync(path.join(dataDir, 'relay.db'))
    this.db.exec('PRAGMA journal_mode = WAL')
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS accounts (
        id TEXT PRIMARY KEY,
        secret_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        platform TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        created_at INTEGER NOT NULL,
        last_seen INTEGER NOT NULL,
        revoked INTEGER NOT NULL DEFAULT 0
      );
      CREATE TABLE IF NOT EXISTS pair_codes (
        code TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at INTEGER,
        attempts INTEGER NOT NULL DEFAULT 0
      );
      CREATE TABLE IF NOT EXISTS memory_leases (
        name TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        lease_id TEXT NOT NULL,
        expires_at INTEGER NOT NULL
      );
    `)
  }

  close(): void {
    this.db.close()
  }

  get database(): DatabaseSync {
    return this.db
  }

  account(): AccountRecord | null {
    const row = this.db.prepare('SELECT id, secret_hash, created_at FROM accounts LIMIT 1').get() as
      | { id: string; secret_hash: string; created_at: number }
      | undefined
    if (!row) return null
    return { id: row.id, secretHash: row.secret_hash, createdAt: row.created_at }
  }

  createAccount(id: string, secretHash: string, now: number): AccountRecord {
    this.db.prepare('INSERT INTO accounts (id, secret_hash, created_at) VALUES (?, ?, ?)').run(id, secretHash, now)
    return { id, secretHash, createdAt: now }
  }

  createDevice(device: Omit<DeviceRecord, 'revoked'>, now: number): DeviceRecord {
    this.db
      .prepare('INSERT INTO devices (id, name, platform, token_hash, created_at, last_seen, revoked) VALUES (?, ?, ?, ?, ?, ?, 0)')
      .run(device.id, device.name, device.platform, device.tokenHash, device.createdAt, device.lastSeen)
    return { ...device, revoked: false }
  }

  deviceByTokenHash(tokenHash: string): DeviceRecord | null {
    const row = this.db
      .prepare('SELECT id, name, platform, token_hash, created_at, last_seen, revoked FROM devices WHERE token_hash = ?')
      .get(tokenHash) as DeviceRow | undefined
    return row ? toDevice(row) : null
  }

  listDevices(): DeviceRecord[] {
    const rows = this.db
      .prepare('SELECT id, name, platform, token_hash, created_at, last_seen, revoked FROM devices ORDER BY created_at')
      .all() as unknown as DeviceRow[]
    return rows.map(toDevice)
  }

  revokeDevice(id: string, now: number): boolean {
    const result = this.db.prepare('UPDATE devices SET revoked = 1, last_seen = ? WHERE id = ?').run(now, id)
    if (Number(result.changes) > 0) {
      this.db.prepare('DELETE FROM memory_leases WHERE device_id = ?').run(id)
      return true
    }
    return false
  }

  renameDevice(id: string, name: string, now: number): DeviceRecord | null {
    const result = this.db
      .prepare('UPDATE devices SET name = ?, last_seen = ? WHERE id = ? AND revoked = 0')
      .run(name, now, id)
    if (Number(result.changes) === 0) return null
    const row = this.db
      .prepare('SELECT id, name, platform, token_hash, created_at, last_seen, revoked FROM devices WHERE id = ?')
      .get(id) as DeviceRow | undefined
    return row ? toDevice(row) : null
  }

  touchDevice(id: string, now: number): void {
    this.db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?').run(now, id)
  }

  createPairCode(code: string, now: number, ttlMs: number): { expiresAt: number } {
    const expiresAt = now + ttlMs
    this.db
      .prepare('INSERT INTO pair_codes (code, created_at, expires_at, used_at, attempts) VALUES (?, ?, ?, NULL, 0)')
      .run(code, now, expiresAt)
    return { expiresAt }
  }

  consumePairCode(code: string, now: number): PairCodeStatus {
    const row = this.db
      .prepare('SELECT code, expires_at, used_at, attempts FROM pair_codes WHERE code = ?')
      .get(code) as { code: string; expires_at: number; used_at: number | null; attempts: number } | undefined
    if (!row) return 'missing'
    if (row.used_at !== null) return 'used'
    if (row.attempts >= PAIR_CODE_ATTEMPTS) return 'too_many_attempts'
    if (row.expires_at < now) return 'expired'
    this.db.prepare('UPDATE pair_codes SET used_at = ? WHERE code = ?').run(now, code)
    return 'ok'
  }

  registerPairCodeFailure(code: string): void {
    this.db.prepare('UPDATE pair_codes SET attempts = attempts + 1 WHERE code = ?').run(code)
  }

  acquireLease(
    name: string,
    deviceId: string,
    leaseId: string,
    now: number,
    ttlMs: number,
  ): { acquired: boolean; leaseId?: string; holderDeviceId?: string; expiresAt: number } {
    this.db.exec('BEGIN IMMEDIATE')
    try {
      const existing = this.db
        .prepare('SELECT device_id, expires_at FROM memory_leases WHERE name = ?')
        .get(name) as { device_id: string; expires_at: number } | undefined
      if (existing && existing.expires_at > now && existing.device_id !== deviceId) {
        const holder = this.db
          .prepare('SELECT revoked FROM devices WHERE id = ?')
          .get(existing.device_id) as { revoked: number } | undefined
        if (holder && holder.revoked === 0) {
          this.db.exec('COMMIT')
          return {
            acquired: false,
            holderDeviceId: existing.device_id,
            expiresAt: existing.expires_at,
          }
        }
      }
      const expiresAt = now + ttlMs
      this.db
        .prepare('INSERT OR REPLACE INTO memory_leases (name, device_id, lease_id, expires_at) VALUES (?, ?, ?, ?)')
        .run(name, deviceId, leaseId, expiresAt)
      this.db.exec('COMMIT')
      return { acquired: true, leaseId, expiresAt }
    } catch (error) {
      try {
        this.db.exec('ROLLBACK')
      } catch {}
      throw error
    }
  }

  releaseLease(name: string, deviceId: string, leaseId?: string): boolean {
    const result = leaseId
      ? this.db
          .prepare('DELETE FROM memory_leases WHERE name = ? AND device_id = ? AND lease_id = ?')
          .run(name, deviceId, leaseId)
      : this.db.prepare('DELETE FROM memory_leases WHERE name = ? AND device_id = ?').run(name, deviceId)
    return Number(result.changes) > 0
  }
}

interface DeviceRow {
  id: string
  name: string
  platform: string
  token_hash: string
  created_at: number
  last_seen: number
  revoked: number
}

function toDevice(row: DeviceRow): DeviceRecord {
  return {
    id: row.id,
    name: row.name,
    platform: row.platform,
    tokenHash: row.token_hash,
    createdAt: row.created_at,
    lastSeen: row.last_seen,
    revoked: row.revoked !== 0,
  }
}
