import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { readFileSync } from 'node:fs'
import { PROTOCOL_VERSION } from '@superpi/protocol'
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
  MemoryFileResponse,
  MemorySnapshotResponse,
  MemoryWriteRequest,
  MemoryWriteResponse,
  PairCodeResponse,
  PairEntry,
  RevokeResponse,
} from '@superpi/protocol'
import type { RelayConfig } from './config.ts'
import { newDeviceId, newPairCode, randomToken, sha256, verifySecret } from './auth.ts'
import type { DeviceRecord, RelayStore } from './store.ts'
import { MemoryConflictError, MemoryRequestError, type MemoryStore } from './memory.ts'

export interface RelayServerOptions {
  config: RelayConfig
  store: RelayStore
  fingerprint: string | null
  entries: PairEntry[]
  caPem?: string | null
  memory?: MemoryStore | null
  onMemoryChanged?: (version: number) => void
  now?: () => number
  online?: (deviceId: string) => boolean
  botsOf?: (deviceId: string) => BotInfo[]
}

export interface RelayServer {
  server: Server
  listen(): Promise<{ host: string; port: number }>
  close(): Promise<void>
}

const PAIR_CODE_TTL_MS = 10 * 60 * 1000
const BODY_LIMIT = 64 * 1024

export function createRelayServer(options: RelayServerOptions): RelayServer {
  const now = options.now ?? (() => Date.now())
  const listener = (request: IncomingMessage, response: ServerResponse): void => {
    void handle(options, now, request, response)
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
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  try {
    const url = new URL(request.url ?? '/', 'http://relay.local')
    const method = request.method ?? 'GET'

    if (method === 'GET' && url.pathname === '/v1/health') {
      const body: HealthResponse = {
        ok: true,
        version: options.config.version,
        protocolVersion: PROTOCOL_VERSION,
        fingerprint: options.fingerprint,
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

    if (method === 'GET' && url.pathname === '/v1/memory/snapshot') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const body: MemorySnapshotResponse = memory.snapshot()
      sendJson(response, 200, body)
      return
    }

    if (method === 'GET' && url.pathname === '/v1/memory/file') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const requested = url.searchParams.get('path') ?? ''
      const body: MemoryFileResponse = memory.file(requested)
      sendJson(response, 200, body)
      return
    }

    if (method === 'POST' && url.pathname === '/v1/memory/write') {
      const device = authenticate(options, now(), request, response)
      if (!device || response.writableEnded) return
      const memory = requireMemory(options)
      const body = await readJson(request)
      if (typeof body !== 'object' || body === null) throw new HttpError(400, 'bad_request', 'body must be an object')
      const result = memory.write({ deviceId: device.id, deviceName: device.name }, body as MemoryWriteRequest, now())
      if (result.applied) options.onMemoryChanged?.(result.version)
      const responseBody: MemoryWriteResponse = { version: result.version }
      sendJson(response, 200, responseBody)
      return
    }

    if (method === 'POST' && url.pathname === '/v1/pair/enroll') {
      const body = await enroll(options, now(), request)
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
      const id = decodeURIComponent(url.pathname.slice('/v1/devices/'.length))
      if (id === '') throw new HttpError(400, 'bad_request', 'device id is required')
      if (!options.store.revokeDevice(id, now())) throw new HttpError(404, 'not_found', `unknown device: ${id}`)
      const body: RevokeResponse = { revoked: id }
      sendJson(response, 200, body)
      return
    }

    sendError(response, 404, 'not_found', `no route for ${method} ${url.pathname}`)
  } catch (error) {
    if (error instanceof HttpError) {
      sendError(response, error.status, error.code, error.message)
      return
    }
    if (error instanceof MemoryConflictError) {
      sendJson(response, 409, {
        code: 'memory_conflict',
        message: error.message,
        details: { currentVersion: error.currentVersion },
      })
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

async function enroll(options: RelayServerOptions, now: number, request: IncomingMessage): Promise<EnrollResponse> {
  const body = await readJson(request)
  if (typeof body !== 'object' || body === null) throw new HttpError(400, 'bad_request', 'body must be an object')
  const payload = body as Partial<EnrollRequest>
  if (typeof payload.deviceName !== 'string' || payload.deviceName.trim() === '') {
    throw new HttpError(400, 'bad_request', 'deviceName is required')
  }
  if (!isPlatform(payload.platform)) throw new HttpError(400, 'bad_request', 'platform is required')

  const account = options.store.account()
  if (!account) throw new HttpError(500, 'internal', 'account is not initialized')

  if (typeof payload.loginSecret === 'string' && payload.loginSecret !== '') {
    if (!verifySecret(payload.loginSecret, account.secretHash)) {
      throw new HttpError(401, 'unauthorized', 'invalid login secret')
    }
  } else if (typeof payload.pairCode === 'string' && payload.pairCode !== '') {
    const status = options.store.consumePairCode(payload.pairCode.toUpperCase(), now)
    if (status !== 'ok') {
      throw new HttpError(401, 'unauthorized', `pair code rejected: ${status}`)
    }
  } else {
    throw new HttpError(400, 'bad_request', 'loginSecret or pairCode is required')
  }

  const deviceId = newDeviceId()
  const token = randomToken()
  options.store.createDevice(
    {
      id: deviceId,
      name: payload.deviceName.trim(),
      platform: payload.platform,
      tokenHash: sha256(token),
      createdAt: now,
      lastSeen: now,
    },
    now,
  )

  return {
    deviceId,
    token,
    accountId: account.id,
    entries: options.entries,
    fingerprint: options.fingerprint,
  }
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
  return {
    deviceId: device.id,
    name: device.name,
    platform: device.platform as DevicePlatform,
    online: options.online?.(device.id) ?? false,
    lastSeen: device.lastSeen,
    bots: options.botsOf?.(device.id) ?? [],
  }
}

function readJson(request: IncomingMessage, limit = BODY_LIMIT): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let size = 0
    request.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > limit) {
        request.destroy()
        reject(new HttpError(413, 'bad_request', 'body too large'))
        return
      }
      chunks.push(chunk)
    })
    request.on('end', () => {
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
    request.on('error', reject)
  })
}

function isPlatform(value: unknown): value is DevicePlatform {
  return value === 'android' || value === 'desktop' || value === 'cli' || value === 'unknown'
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  })
  response.end(payload)
}

function sendError(response: ServerResponse, status: number, code: ErrorCode, message: string): void {
  const body: ApiErrorBody = { code, message }
  sendJson(response, status, body)
}

class HttpError extends Error {
  readonly status: number
  readonly code: ErrorCode

  constructor(status: number, code: ErrorCode, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}
