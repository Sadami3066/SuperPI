import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { readFileSync } from 'node:fs'
import { PROTOCOL_VERSION, RELAY_CAPABILITIES } from '@superpi/protocol'
import type {
  ApiErrorBody,
  BotInfo,
  DeviceInfo,
  DeviceListResponse,
  DevicePlatform,
  EnrollRequest,
  EnrollResponse,
  ErrorCode,
  HealthResponse,
  MemoryConsolidationLeaseReleaseResponse,
  MemoryConsolidationLeaseResponse,
  MemoryV3PullResponse,
  MemoryV3PushRequest,
  PairCodeResponse,
  PairEntry,
  RenameDeviceResponse,
  RevokeResponse,
} from '@superpi/protocol'
import type { RelayConfig } from './config.ts'
import { newDeviceId, newPairCode, randomToken, sha256, verifySecret } from './auth.ts'
import type { DeviceRecord, RelayStore } from './store.ts'
import { MemoryRequestError, type MemoryStore } from './memory.ts'

export interface EnrollRateLimitOptions {
  maxFailures?: number
  windowMs?: number
}

export interface RelayServerOptions {
  config: RelayConfig
  store: RelayStore
  fingerprint: string | null
  entries: PairEntry[]
  caPem?: string | null
  memory?: MemoryStore | null
  onMemoryChanged?: (version: number, sourceDeviceId?: string) => void
  onDeviceRevoked?: (deviceId: string) => void
  onDeviceRenamed?: (deviceId: string, name: string) => void
  enrollRateLimit?: EnrollRateLimitOptions
  now?: () => number
  online?: (deviceId: string) => boolean
  botsOf?: (deviceId: string) => BotInfo[]
  capabilitiesOf?: (deviceId: string) => string[] | undefined
}

export interface RelayServer {
  server: Server
  listen(): Promise<{ host: string; port: number }>
  close(): Promise<void>
}

const PAIR_CODE_TTL_MS = 10 * 60 * 1000
const BODY_LIMIT = 64 * 1024
const MEMORY_BODY_LIMIT = 4 * 1024 * 1024
const ENROLL_BODY_TIMEOUT_MS = 15_000
const DEFAULT_ENROLL_MAX_FAILURES = 10
const DEFAULT_ENROLL_WINDOW_MS = 15 * 60 * 1000
const MAX_FAILURE_KEYS = 4_096
const MAX_DEVICE_NAME_LENGTH = 128
const DEFAULT_CONSOLIDATION_LEASE_TTL_MS = 10 * 60 * 1000
const MIN_CONSOLIDATION_LEASE_TTL_MS = 30 * 1000
const MAX_CONSOLIDATION_LEASE_TTL_MS = 30 * 60 * 1000

export function createRelayServer(options: RelayServerOptions): RelayServer {
  const now = options.now ?? (() => Date.now())
  const rateLimiter = new FailureRateLimiter(
    options.enrollRateLimit?.maxFailures ?? DEFAULT_ENROLL_MAX_FAILURES,
    options.enrollRateLimit?.windowMs ?? DEFAULT_ENROLL_WINDOW_MS,
  )
  const listener = (request: IncomingMessage, response: ServerResponse): void => {
    void handle(options, now, rateLimiter, request, response)
  }
  const server = createListener(options.config, listener)

  return {
    server,
    listen() {
      return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(options.config.port, options.config.host, () => {
          server.removeListener('error', reject)
          const address = server.address()
          if (address === null || typeof address === 'string') {
            resolve({ host: options.config.host, port: options.config.port })
            return
          }
          resolve({ host: address.address, port: address.port })
        })
      })
    },
    close() {
      return new Promise((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()))
      })
    },
  }
}

function createListener(config: RelayConfig, listener: (request: IncomingMessage, response: ServerResponse) => void): Server {
  if (config.certPath && config.keyPath) {
    const secure = createHttpsServer(
      { cert: readFileSync(config.certPath), key: readFileSync(config.keyPath) },
      listener,
    )
    return secure as unknown as Server
  }
  return createServer(listener)
}

async function handle(
  options: RelayServerOptions,
  now: () => number,
  rateLimiter: FailureRateLimiter,
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  try {
    let url: URL
    try {
      url = new URL(request.url ?? '/', 'http://relay.local')
    } catch {
      throw new HttpError(400, 'bad_request', 'malformed request url')
    }
    const method = request.method ?? 'GET'

    if (method === 'GET' && url.pathname === '/v1/health') {
      const body: HealthResponse = {
        ok: true,
        version: options.config.version,
        protocolVersion: PROTOCOL_VERSION,
        fingerprint: options.fingerprint,
        capabilities: [...RELAY_CAPABILITIES],
      }
      sendJson(response, 200, body)
      return
    }

    if (method === 'GET' && url.pathname === '/v1/ca') {
      if (!options.caPem) {
        sendError(response, 404, 'not_found', 'no certificate authority configured')
        return
      }
      response.writeHead(200, {
        'content-type': 'application/x-pem-file; charset=utf-8',
        'content-length': Buffer.byteLength(options.caPem),
      })
      response.end(options.caPem)
      return
    }

    if (method === 'POST' && url.pathname === '/v3/memory/push') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const body = await readJson(request, MEMORY_BODY_LIMIT)
      const currentDevice = options.store.deviceByTokenHash(device.tokenHash)
      if (!currentDevice || currentDevice.revoked) {
        throw new HttpError(401, 'unauthorized', 'unknown or revoked device')
      }
      if (typeof body !== 'object' || body === null) throw new HttpError(400, 'bad_request', 'body must be an object')
      const writeNow = now()
      options.store.touchDevice(currentDevice.id, writeNow)
      const result = memory.push(currentDevice.id, body as MemoryV3PushRequest, writeNow)
      if (result.changed) options.onMemoryChanged?.(result.response.serverSeq, currentDevice.id)
      sendJson(response, 200, result.response)
      return
    }

    if (method === 'GET' && url.pathname === '/v3/memory/pull') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const rawSince = url.searchParams.get('since')
      const rawLimit = url.searchParams.get('limit')
      const since = rawSince !== null ? Number.parseInt(rawSince, 10) : 0
      const limit = rawLimit !== null ? Number.parseInt(rawLimit, 10) : 200
      if ((rawSince !== null && (!Number.isSafeInteger(since) || since < 0)) || (rawLimit !== null && (!Number.isSafeInteger(limit) || limit <= 0))) {
        throw new HttpError(400, 'bad_request', 'invalid since or limit')
      }
      const body: MemoryV3PullResponse = memory.pull(since, limit)
      sendJson(response, 200, body)
      return
    }

    if (method === 'POST' && url.pathname === '/v3/memory/consolidation-lease') {
      const currentNow = now()
      const device = authenticate(options, currentNow, request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const body = await readJson(request, BODY_LIMIT)
      if (body !== null && (typeof body !== 'object' || Array.isArray(body))) {
        throw new HttpError(400, 'bad_request', 'body must be an object')
      }
      const requestedTtl = (body as { ttlMs?: unknown } | null)?.ttlMs
      let ttlMs = DEFAULT_CONSOLIDATION_LEASE_TTL_MS
      if (requestedTtl !== undefined) {
        if (typeof requestedTtl !== 'number' || !Number.isFinite(requestedTtl) || requestedTtl <= 0) {
          throw new HttpError(400, 'bad_request', 'ttlMs must be a positive number')
        }
        ttlMs = Math.min(
          MAX_CONSOLIDATION_LEASE_TTL_MS,
          Math.max(MIN_CONSOLIDATION_LEASE_TTL_MS, Math.round(requestedTtl)),
        )
      }
      const requestedMinInterval = (body as { minIntervalMs?: unknown } | null)?.minIntervalMs
      let minIntervalMs = 0
      if (requestedMinInterval !== undefined) {
        if (typeof requestedMinInterval !== 'number' || !Number.isFinite(requestedMinInterval) || requestedMinInterval < 0) {
          throw new HttpError(400, 'bad_request', 'minIntervalMs must be a non-negative number')
        }
        minIntervalMs = Math.round(requestedMinInterval)
      }
      const lastConsolidationAt = memory.lastConsolidationAt()
      const lease = options.store.acquireLease(
        'consolidation',
        device.id,
        randomToken(),
        currentNow,
        ttlMs,
        minIntervalMs,
        lastConsolidationAt,
      )
      const result: MemoryConsolidationLeaseResponse = {
        ...lease,
        lastConsolidationAt,
        serverSeq: memory.version(),
      }
      sendJson(response, 200, result)
      return
    }

    if (method === 'DELETE' && url.pathname === '/v3/memory/consolidation-lease') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      requireMemory(options)
      const leaseId = url.searchParams.get('leaseId')?.trim() || undefined
      const released = options.store.releaseLease('consolidation', device.id, leaseId)
      const result: MemoryConsolidationLeaseReleaseResponse = { released }
      sendJson(response, 200, result)
      return
    }

    if (method === 'POST' && url.pathname === '/v1/pair/enroll') {
      const body = await enroll(options, now, rateLimiter, request)
      sendJson(response, 200, body)
      return
    }

    if (method === 'POST' && url.pathname === '/v1/pair/code') {
      authenticate(options, now(), request, response)
      if (response.writableEnded) return
      const code = newPairCode()
      const { expiresAt } = options.store.createPairCode(code, now(), PAIR_CODE_TTL_MS)
      const body: PairCodeResponse = {
        code,
        expiresAt,
        entries: options.entries,
        fingerprint: options.fingerprint,
      }
      sendJson(response, 200, body)
      return
    }

    if (method === 'GET' && url.pathname === '/v1/devices') {
      authenticate(options, now(), request, response)
      if (response.writableEnded) return
      const body: DeviceListResponse = {
        devices: options.store
          .listDevices()
          .filter((device) => !device.revoked)
          .map((device) => toDeviceInfo(device, options)),
      }
      sendJson(response, 200, body)
      return
    }

    if (method === 'DELETE' && url.pathname.startsWith('/v1/devices/')) {
      authenticate(options, now(), request, response)
      if (response.writableEnded) return
      let id: string
      try {
        id = decodeURIComponent(url.pathname.slice('/v1/devices/'.length))
      } catch {
        throw new HttpError(400, 'bad_request', 'malformed device id')
      }
      if (id === '' || id.includes('/') || id.includes('\\') || id === '.' || id === '..') {
        throw new HttpError(400, 'bad_request', 'invalid device id')
      }
      if (!options.store.revokeDevice(id, now())) throw new HttpError(404, 'not_found', `unknown device: ${id}`)
      options.onDeviceRevoked?.(id)
      const body: RevokeResponse = { revoked: id }
      sendJson(response, 200, body)
      return
    }

    if (method === 'PATCH' && url.pathname.startsWith('/v1/devices/')) {
      const currentNow = now()
      authenticate(options, currentNow, request, response)
      if (response.writableEnded) return
      let id: string
      try {
        id = decodeURIComponent(url.pathname.slice('/v1/devices/'.length))
      } catch {
        throw new HttpError(400, 'bad_request', 'malformed device id')
      }
      if (id === '' || id.includes('/') || id.includes('\\') || id === '.' || id === '..') {
        throw new HttpError(400, 'bad_request', 'invalid device id')
      }
      const payload = await readJson(request, BODY_LIMIT)
      if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
        throw new HttpError(400, 'bad_request', 'body must be an object')
      }
      const rawName = (payload as { name?: unknown }).name
      const cleanName = typeof rawName === 'string' ? sanitizeDeviceName(rawName) : ''
      if (cleanName === '') {
        throw new HttpError(400, 'bad_request', 'name must be a non-empty string up to 128 chars')
      }
      const updated = options.store.renameDevice(id, cleanName, currentNow)
      if (!updated) throw new HttpError(404, 'not_found', `unknown device: ${id}`)
      options.onDeviceRenamed?.(id, updated.name)
      const body: RenameDeviceResponse = { deviceId: id, name: updated.name }
      sendJson(response, 200, body)
      return
    }

    sendError(response, 404, 'not_found', `no route for ${method} ${url.pathname}`)
  } catch (error) {
    if (response.headersSent || response.writableEnded || response.destroyed) return
    if (error instanceof HttpError) {
      sendError(response, error.status, error.code, error.message, error.closeConnection)
      return
    }
    if (error instanceof MemoryRequestError) {
      sendError(response, 400, 'bad_request', error.message)
      return
    }
    console.error('relay request failed', error)
    sendError(response, 500, 'internal', 'request failed')
  }
}

async function enroll(
  options: RelayServerOptions,
  nowFn: () => number,
  rateLimiter: FailureRateLimiter,
  request: IncomingMessage,
): Promise<EnrollResponse> {
  const ip = clientIp(request)
  if (rateLimiter.isLimited(ip, nowFn())) {
    throw new HttpError(429, 'rate_limited', 'too many failed enrollment attempts')
  }
  const body = await readJson(request, BODY_LIMIT, ENROLL_BODY_TIMEOUT_MS)
  const checkNow = nowFn()
  if (rateLimiter.isLimited(ip, checkNow)) {
    throw new HttpError(429, 'rate_limited', 'too many failed enrollment attempts')
  }
  if (typeof body !== 'object' || body === null) throw new HttpError(400, 'bad_request', 'body must be an object')
  const payload = body as Partial<EnrollRequest>
  const deviceName = typeof payload.deviceName === 'string' ? sanitizeDeviceName(payload.deviceName) : ''
  if (deviceName === '') {
    throw new HttpError(400, 'bad_request', 'deviceName is required')
  }
  if (!isPlatform(payload.platform)) throw new HttpError(400, 'bad_request', 'platform is required')

  const account = options.store.account()
  if (!account) throw new HttpError(500, 'internal', 'account is not initialized')

  const currentNow = nowFn()
  if (typeof payload.loginSecret === 'string' && payload.loginSecret !== '') {
    if (!verifySecret(payload.loginSecret, account.secretHash)) {
      rateLimiter.recordFailure(ip, currentNow)
      throw new HttpError(401, 'unauthorized', 'invalid login secret')
    }
  } else if (typeof payload.pairCode === 'string' && payload.pairCode.trim() !== '') {
    const code = payload.pairCode.trim().toUpperCase()
    const status = options.store.consumePairCode(code, currentNow)
    if (status !== 'ok') {
      options.store.registerPairCodeFailure(code)
      rateLimiter.recordFailure(ip, currentNow)
      throw new HttpError(401, 'unauthorized', 'invalid or expired pair code')
    }
  } else {
    throw new HttpError(400, 'bad_request', 'loginSecret or pairCode is required')
  }

  const deviceId = newDeviceId()
  const token = randomToken()
  options.store.createDevice(
    {
      id: deviceId,
      name: deviceName,
      platform: payload.platform,
      tokenHash: sha256(token),
      createdAt: currentNow,
      lastSeen: currentNow,
    },
    currentNow,
  )

  return {
    deviceId,
    token,
    accountId: account.id,
    entries: options.entries,
    fingerprint: options.fingerprint,
  }
}

function sanitizeDeviceName(raw: string): string {
  return raw
    .replace(/[\x00-\x1f\x7f]/g, ' ')
    .replaceAll('-->', '-- >')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_DEVICE_NAME_LENGTH)
}

function authenticate(
  options: RelayServerOptions,
  now: number,
  request: IncomingMessage,
  response: ServerResponse,
): DeviceRecord | null {
  const header = request.headers.authorization
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
    sendError(response, 401, 'unauthorized', 'missing bearer token')
    return null
  }
  const token = header.slice('Bearer '.length).trim()
  if (token === '') {
    sendError(response, 401, 'unauthorized', 'missing bearer token')
    return null
  }
  const device = options.store.deviceByTokenHash(sha256(token))
  if (!device || device.revoked) {
    sendError(response, 401, 'unauthorized', 'unknown or revoked device')
    return null
  }
  options.store.touchDevice(device.id, now)
  return device
}

function requireMemory(options: RelayServerOptions): MemoryStore {
  if (!options.memory) throw new HttpError(404, 'not_found', 'memory is not configured')
  return options.memory
}

function toDeviceInfo(device: DeviceRecord, options: RelayServerOptions): DeviceInfo {
  const capabilities = options.capabilitiesOf?.(device.id)
  const info: DeviceInfo = {
    deviceId: device.id,
    name: device.name,
    platform: device.platform as DevicePlatform,
    online: options.online?.(device.id) ?? false,
    lastSeen: device.lastSeen,
    bots: options.botsOf?.(device.id) ?? [],
  }
  if (capabilities) info.capabilities = capabilities
  return info
}

function readJson(request: IncomingMessage, limit = BODY_LIMIT, timeoutMs = 30_000): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let size = 0
    let settled = false
    const timer =
      timeoutMs > 0
        ? setTimeout(() => {
            if (settled) return
            settled = true
            request.pause()
            reject(new HttpError(408, 'bad_request', 'request body timeout', true))
          }, timeoutMs)
        : null
    timer?.unref?.()

    const cleanup = (): void => {
      if (timer) clearTimeout(timer)
    }

    request.on('data', (chunk: Buffer) => {
      if (settled) return
      size += chunk.length
      if (size > limit) {
        settled = true
        cleanup()
        request.pause()
        reject(new HttpError(413, 'bad_request', 'body too large', true))
        return
      }
      chunks.push(chunk)
    })
    request.on('end', () => {
      if (settled) return
      settled = true
      cleanup()
      if (chunks.length === 0) {
        resolve(null)
        return
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(new HttpError(400, 'bad_request', 'body is not valid JSON'))
      }
    })
    request.on('error', (error) => {
      if (settled) return
      settled = true
      cleanup()
      reject(error)
    })
  })
}

function isPlatform(value: unknown): value is DevicePlatform {
  return value === 'android' || value === 'desktop' || value === 'cli' || value === 'unknown'
}

function sendJson(response: ServerResponse, status: number, body: unknown, closeConnection = false): void {
  if (response.headersSent || response.writableEnded || response.destroyed) return
  const payload = JSON.stringify(body)
  const headers: Record<string, string | number> = {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  }
  if (closeConnection) headers.connection = 'close'
  response.writeHead(status, headers)
  response.end(payload, () => {
    if (closeConnection) response.socket?.destroy()
  })
}

function sendError(
  response: ServerResponse,
  status: number,
  code: ErrorCode,
  message: string,
  closeConnection = false,
): void {
  const body: ApiErrorBody = { code, message }
  sendJson(response, status, body, closeConnection)
}

class HttpError extends Error {
  readonly status: number
  readonly code: ErrorCode
  readonly closeConnection: boolean

  constructor(status: number, code: ErrorCode, message: string, closeConnection = false) {
    super(message)
    this.status = status
    this.code = code
    this.closeConnection = closeConnection
  }
}

function clientIp(request: IncomingMessage): string {
  const remote = request.socket.remoteAddress ?? 'unknown'
  return remote.startsWith('::ffff:') ? remote.slice('::ffff:'.length) : remote
}

class FailureRateLimiter {
  private readonly maxFailures: number
  private readonly windowMs: number
  private readonly failures = new Map<string, number[]>()

  constructor(maxFailures: number, windowMs: number) {
    this.maxFailures = maxFailures
    this.windowMs = windowMs
  }

  isLimited(key: string, now: number): boolean {
    return this.prune(key, now).length >= this.maxFailures
  }

  recordFailure(key: string, now: number): void {
    const timestamps = this.prune(key, now)
    timestamps.push(now)
    this.failures.delete(key)
    this.failures.set(key, timestamps)
    if (this.failures.size > MAX_FAILURE_KEYS) {
      for (const entryKey of [...this.failures.keys()]) {
        if (this.failures.size <= MAX_FAILURE_KEYS) break
        this.prune(entryKey, now)
      }
      while (this.failures.size > MAX_FAILURE_KEYS) {
        const oldestKey = this.failures.keys().next().value
        if (oldestKey === undefined) break
        this.failures.delete(oldestKey)
      }
    }
  }

  private prune(key: string, now: number): number[] {
    const existing = this.failures.get(key)
    if (!existing) return []
    const cutoff = now - this.windowMs
    const recent = existing.filter((timestamp) => timestamp > cutoff)
    if (recent.length === 0) this.failures.delete(key)
    else if (recent.length !== existing.length) this.failures.set(key, recent)
    return recent
  }
}
