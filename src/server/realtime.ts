import type { Server } from 'node:http'
import type { Duplex } from 'node:stream'
import { WebSocketServer, WebSocket } from 'ws'
import {
  PROTOCOL_VERSION,
  RELAY_CAPABILITIES,
  RouteValidationError,
  UnknownRouteMethodError,
  decodeFrameLine,
  encodeFrame,
  type BotInfo,
  type ClientFrame,
  type DeviceInfo,
  type HelloFrame,
  type HelloOkFrame,
  type InboundRouteFrame,
  type PresenceFrame,
  type RouteErrorFrame,
  type ServerFrame,
  type SyncEventFrame,
} from '@superpi/protocol'
import type { RelayStore } from './store.ts'
import { sha256 } from './auth.ts'

export interface RealtimeHub {
  attach(server: Server): void
  close(): Promise<void>
  isOnline(deviceId: string): boolean
  botsOf(deviceId: string): BotInfo[]
  capabilitiesOf(deviceId: string): string[] | undefined
  broadcastMemoryChanged(version: number): void
  disconnectDevice(deviceId: string): void
}

export interface RealtimeHubOptions {
  heartbeatIntervalMs?: number
  heartbeatTimeoutMs?: number
}

interface Connection {
  deviceId: string
  bots: BotInfo[]
  capabilities: string[]
  socket: WebSocket
  state: 'pending' | 'active' | 'closed'
  lastActivityAt: number
  lastHelloDigest: string
  lastHelloBroadcastAt: number
  syncEventTimestamps: number[]
}

interface PendingRoute {
  from: string
  to: string
  method: InboundRouteFrame['method']
  sessionKey?: string
  at: number
  createdAt: number
  streaming: boolean
  accepted: boolean
}

const HELLO_TIMEOUT_MS = 10_000
const ROUTE_TTL_MS = 30 * 60 * 1000
const MAX_ROUTE_LIFETIME_MS = 2 * 60 * 60 * 1000
const DEFAULT_HEARTBEAT_INTERVAL_MS = 25_000
const DEFAULT_HEARTBEAT_TIMEOUT_MS = 10_000
const MAX_WS_PAYLOAD_BYTES = 4 * 1024 * 1024
const MAX_PENDING_SOCKETS = 256
const MAX_PENDING_PER_DEVICE = 8
const MAX_PENDING_ROUTES = 2048
const MAX_ROUTES_PER_DEVICE = 64
const MIN_IDENTICAL_HELLO_INTERVAL_MS = 1_000
const SYNC_EVENT_WINDOW_MS = 1_000
const MAX_SYNC_EVENTS_PER_WINDOW = 30
const SHUTDOWN_TERMINATE_MS = 300

export function createRealtimeHub(
  store: RelayStore,
  memoryVersion: () => number = () => 0,
  now: () => number = Date.now,
  options: RealtimeHubOptions = {},
): RealtimeHub {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_WS_PAYLOAD_BYTES })
  const connections = new Map<string, Connection>()
  const pendingSockets = new Map<WebSocket, string>()
  const pendingRoutes = new Map<string, PendingRoute>()
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_INTERVAL_MS
  const heartbeatTimeoutMs = options.heartbeatTimeoutMs ?? DEFAULT_HEARTBEAT_TIMEOUT_MS
  const heartbeatDeadlineMs = heartbeatIntervalMs + heartbeatTimeoutMs

  const sweeper = setInterval(() => {
    const current = now()
    const idleCutoff = current - ROUTE_TTL_MS
    const hardCutoff = current - MAX_ROUTE_LIFETIME_MS
    for (const [id, pending] of pendingRoutes) {
      if (pending.at > idleCutoff && pending.createdAt > hardCutoff) continue
      pendingRoutes.delete(id)
      if (pending.streaming && pending.accepted && pending.at <= idleCutoff) continue
      const requester = connections.get(pending.from)
      if (requester) {
        send(requester, { type: 'route_error', id, code: 'internal', message: 'request timed out' })
      }
    }
  }, 60_000)
  sweeper.unref?.()

  const heartbeat = setInterval(() => {
    const current = now()
    for (const connection of [...connections.values()]) {
      if (current - connection.lastActivityAt > heartbeatDeadlineMs) {
        connection.state = 'closed'
        if (connections.get(connection.deviceId) === connection) {
          connections.delete(connection.deviceId)
          dropDevice(connection.deviceId, `device ${connection.deviceId} timed out`)
        }
        try {
          connection.socket.terminate()
        } catch {}
        continue
      }
      if (connection.socket.readyState === WebSocket.OPEN) {
        try {
          connection.socket.ping()
        } catch {}
      }
    }
  }, heartbeatIntervalMs)
  heartbeat.unref?.()

  function deviceList(): DeviceInfo[] {
    return store
      .listDevices()
      .filter((device) => !device.revoked)
      .map((device) => {
        const connection = connections.get(device.id)
        const info: DeviceInfo = {
          deviceId: device.id,
          name: device.name,
          platform: device.platform as DeviceInfo['platform'],
          online: connection !== undefined,
          lastSeen: device.lastSeen,
          bots: connection?.bots ?? [],
        }
        if (connection) info.capabilities = connection.capabilities
        return info
      })
  }

  function send(connection: Connection, frame: ServerFrame): void {
    if (connection.state === 'closed') return
    if (connection.socket.readyState === WebSocket.OPEN) {
      connection.socket.send(encodeFrame(frame))
    }
  }

  function dropDevice(deviceId: string, reason: string): void {
    const seenAt = now()
    try {
      store.touchDevice(deviceId, seenAt)
    } catch {}
    const offline: PresenceFrame = { type: 'presence', deviceId, online: false, lastSeen: seenAt }
    for (const entry of connections.values()) send(entry, offline)
    for (const [id, pending] of pendingRoutes) {
      if (pending.to === deviceId || pending.from === deviceId) {
        pendingRoutes.delete(id)
        const requester = connections.get(pending.from)
        if (requester && pending.to === deviceId) {
          send(requester, {
            type: 'route_error',
            id,
            code: 'device_offline',
            message: reason,
          })
        }
      }
    }
  }

  function broadcastPresence(deviceId: string, online: boolean, bots?: BotInfo[], capabilities?: string[]): void {
    const device = store.listDevices().find((entry) => entry.id === deviceId)
    const frame: PresenceFrame = {
      type: 'presence',
      deviceId,
      online,
      lastSeen: device?.lastSeen ?? now(),
    }
    if (bots) frame.bots = bots
    if (capabilities) frame.capabilities = capabilities
    for (const connection of connections.values()) {
      if (connection.deviceId !== deviceId) send(connection, frame)
    }
  }

  function handleHello(connection: Connection, frame: HelloFrame): void {
    if (connection.state === 'closed' || connection.socket.readyState !== WebSocket.OPEN) return
    if (frame.deviceId !== connection.deviceId) {
      connection.state = 'closed'
      safeClose(connection.socket, 1008, 'device mismatch')
      return
    }
    if (frame.protocolVersion !== PROTOCOL_VERSION) {
      connection.state = 'closed'
      safeClose(connection.socket, 1008, 'protocol mismatch')
      return
    }
    const record = store.listDevices().find((entry) => entry.id === connection.deviceId)
    if (!record || record.revoked) {
      connection.state = 'closed'
      safeClose(connection.socket, 4001, 'device revoked')
      return
    }
    const seenAt = now()
    try {
      store.touchDevice(connection.deviceId, seenAt)
    } catch {}
    pendingSockets.delete(connection.socket)
    connection.bots = frame.bots
    connection.capabilities = frame.capabilities
    connection.lastActivityAt = seenAt
    connection.state = 'active'
    const previous = connections.get(connection.deviceId)
    connections.set(connection.deviceId, connection)
    if (previous && previous !== connection) {
      previous.state = 'closed'
      safeClose(previous.socket, 1000, 'replaced by new connection')
    }
    const digest = JSON.stringify({ bots: frame.bots, capabilities: frame.capabilities })
    const isReplacementOrFirst = previous !== connection
    const shouldBroadcast =
      isReplacementOrFirst ||
      digest !== connection.lastHelloDigest ||
      seenAt - connection.lastHelloBroadcastAt >= MIN_IDENTICAL_HELLO_INTERVAL_MS
    connection.lastHelloDigest = digest
    if (shouldBroadcast) connection.lastHelloBroadcastAt = seenAt
    const body: HelloOkFrame = {
      type: 'hello_ok',
      accountId: store.account()?.id ?? '',
      devices: deviceList(),
      memoryVersion: memoryVersion(),
      capabilities: [...RELAY_CAPABILITIES],
    }
    send(connection, body)
    if (shouldBroadcast) {
      broadcastPresence(connection.deviceId, true, frame.bots, frame.capabilities)
    }
  }

  function handleRoute(connection: Connection, frame: { id: string; to: string; method: string; params: Record<string, unknown> }): void {
    if (frame.to === connection.deviceId) {
      send(connection, {
        type: 'route_error',
        id: frame.id,
        code: 'bad_request',
        message: 'cannot route to self',
      })
      return
    }
    if (pendingRoutes.has(frame.id)) {
      send(connection, {
        type: 'route_error',
        id: frame.id,
        code: 'bad_request',
        message: 'duplicate route id',
      })
      return
    }
    if (pendingRoutes.size >= MAX_PENDING_ROUTES) {
      send(connection, {
        type: 'route_error',
        id: frame.id,
        code: 'rate_limited',
        message: 'relay route capacity exceeded',
      })
      return
    }
    let senderCount = 0
    for (const pending of pendingRoutes.values()) {
      if (pending.from === connection.deviceId) senderCount += 1
    }
    if (senderCount >= MAX_ROUTES_PER_DEVICE) {
      send(connection, {
        type: 'route_error',
        id: frame.id,
        code: 'rate_limited',
        message: 'too many active requests',
      })
      return
    }
    const target = connections.get(frame.to)
    if (!target || target.state !== 'active') {
      const error: RouteErrorFrame = {
        type: 'route_error',
        id: frame.id,
        code: 'device_offline',
        message: `device ${frame.to} is offline`,
      }
      send(connection, error)
      return
    }
    const streaming = frame.method === 'bot.prompt' || frame.method === 'bot.resume'
    const sessionKey =
      frame.method === 'bot.resume'
        ? `${typeof frame.params.botId === 'string' ? frame.params.botId : ''}:${typeof frame.params.sessionId === 'string' ? frame.params.sessionId : ''}`
        : undefined
    if (sessionKey !== undefined) {
      for (const [existingId, existingRoute] of pendingRoutes) {
        if (
          existingRoute.from === connection.deviceId &&
          existingRoute.to === frame.to &&
          existingRoute.method === 'bot.resume' &&
          existingRoute.sessionKey === sessionKey
        ) {
          pendingRoutes.delete(existingId)
        }
      }
    }
    const current = now()
    pendingRoutes.set(frame.id, {
      from: connection.deviceId,
      to: frame.to,
      method: frame.method as InboundRouteFrame['method'],
      ...(sessionKey !== undefined ? { sessionKey } : {}),
      at: current,
      createdAt: current,
      streaming,
      accepted: false,
    })
    const inbound: InboundRouteFrame = {
      type: 'route',
      id: frame.id,
      from: connection.deviceId,
      to: frame.to,
      method: frame.method as InboundRouteFrame['method'],
      params: frame.params,
    }
    send(target, inbound)
  }

  function forwardRouteResult(connection: Connection, frame: { type: string; id: string } & Record<string, unknown>): void {
    const pending = pendingRoutes.get(frame.id)
    if (!pending) {
      const error: RouteErrorFrame = {
        type: 'route_error',
        id: frame.id,
        code: 'internal',
        message: 'no pending request for this response',
      }
      send(connection, error)
      return
    }
    if (pending.to !== connection.deviceId) {
      const error: RouteErrorFrame = {
        type: 'route_error',
        id: frame.id,
        code: 'internal',
        message: 'response did not come from the target device',
      }
      send(connection, error)
      return
    }
    const requester = connections.get(pending.from)
    pending.at = now()
    if (frame.type === 'route_event') {
      if (!pending.streaming) {
        send(connection, {
          type: 'route_error',
          id: frame.id,
          code: 'bad_request',
          message: 'route_event is only valid for streaming routes',
        })
        return
      }
      if (requester) send(requester, frame as unknown as ServerFrame)
      const event = (frame as { event?: { type?: string } }).event
      if (event?.type === 'agent_settled' && pending.method !== 'bot.resume') {
        pendingRoutes.delete(frame.id)
      }
      return
    }
    if (frame.type === 'route_ok') {
      if (pending.streaming && pending.accepted) {
        send(connection, {
          type: 'route_error',
          id: frame.id,
          code: 'bad_request',
          message: 'duplicate route_ok for route',
        })
        return
      }
      pending.accepted = true
      if (requester) send(requester, { type: 'route_ok', id: frame.id, result: frame.result })
      if (!pending.streaming) pendingRoutes.delete(frame.id)
      return
    }
    pendingRoutes.delete(frame.id)
    if (!requester) return
    send(requester, {
      type: 'route_error',
      id: frame.id,
      code: frame.code as RouteErrorFrame['code'],
      message: String(frame.message),
    })
  }

  function handleSyncEvent(connection: Connection, frame: SyncEventFrame): void {
    const current = now()
    const cutoff = current - SYNC_EVENT_WINDOW_MS
    while (connection.syncEventTimestamps.length > 0 && connection.syncEventTimestamps[0]! <= cutoff) {
      connection.syncEventTimestamps.shift()
    }
    if (connection.syncEventTimestamps.length >= MAX_SYNC_EVENTS_PER_WINDOW) {
      return
    }
    connection.syncEventTimestamps.push(current)
    const outbound: SyncEventFrame = {
      type: 'sync_event',
      deviceId: connection.deviceId,
      kind: frame.kind,
      botId: frame.botId,
      ts: Number.isFinite(frame.ts) && frame.ts > 0 ? frame.ts : current,
    }
    if (frame.sessionId !== undefined) outbound.sessionId = frame.sessionId
    if (frame.busy !== undefined) outbound.busy = frame.busy
    if (frame.pendingApprovals !== undefined) outbound.pendingApprovals = frame.pendingApprovals
    if (frame.provider !== undefined) outbound.provider = frame.provider
    if (frame.modelId !== undefined) outbound.modelId = frame.modelId
    if (frame.thinkingLevel !== undefined) outbound.thinkingLevel = frame.thinkingLevel
    for (const target of connections.values()) {
      if (
        target !== connection &&
        target.deviceId !== connection.deviceId &&
        target.state === 'active' &&
        target.capabilities.includes('sync-events')
      ) {
        send(target, outbound)
      }
    }
  }

  function handleConnection(socket: WebSocket, deviceId: string): void {
    const connection: Connection = {
      deviceId,
      bots: [],
      capabilities: [],
      socket,
      state: 'pending',
      lastActivityAt: now(),
      lastHelloDigest: '',
      lastHelloBroadcastAt: 0,
      syncEventTimestamps: [],
    }
    pendingSockets.set(socket, deviceId)
    const helloTimer = setTimeout(() => {
      connection.state = 'closed'
      pendingSockets.delete(socket)
      try {
        socket.terminate()
      } catch {}
    }, HELLO_TIMEOUT_MS)
    helloTimer.unref?.()

    socket.on('pong', () => {
      connection.lastActivityAt = now()
    })

    socket.on('message', (raw) => {
      if (connection.state === 'closed') return
      connection.lastActivityAt = now()
      let frame: ClientFrame
      try {
        frame = decodeFrameLine('client', raw.toString()) as ClientFrame
      } catch (error) {
        if (error instanceof UnknownRouteMethodError && connections.get(deviceId) === connection) {
          send(connection, {
            type: 'route_error',
            id: error.routeId,
            code: 'bad_request',
            message: `unknown method ${error.method}`,
          })
          return
        }
        if (error instanceof RouteValidationError && connections.get(deviceId) === connection) {
          send(connection, {
            type: 'route_error',
            id: error.routeId,
            code: error.code,
            message: error.message,
          })
          return
        }
        connection.state = 'closed'
        safeClose(socket, 1008, 'protocol error')
        return
      }
      if (frame.type === 'hello') {
        clearTimeout(helloTimer)
        handleHello(connection, frame)
        return
      }
      if (connections.get(deviceId) !== connection) return
      if (frame.type === 'ping') {
        send(connection, { type: 'pong' })
        return
      }
      if (frame.type === 'sync_event') {
        handleSyncEvent(connection, frame)
        return
      }
      if (frame.type === 'route') {
        handleRoute(connection, frame)
        return
      }
      if (frame.type === 'route_ok' || frame.type === 'route_error' || frame.type === 'route_event') {
        forwardRouteResult(connection, frame as unknown as { type: string; id: string } & Record<string, unknown>)
      }
    })

    socket.on('close', () => {
      clearTimeout(helloTimer)
      connection.state = 'closed'
      pendingSockets.delete(socket)
      if (connections.get(deviceId) === connection) {
        connections.delete(deviceId)
        dropDevice(deviceId, `device ${deviceId} disconnected`)
      }
    })

    socket.on('error', () => {
      connection.state = 'closed'
      safeClose(socket, 1006, '')
    })
  }

  return {
    attach(server: Server) {
      server.on('upgrade', (request, socket, head) => {
        let pathname: string
        try {
          pathname = new URL(request.url ?? '/', 'http://relay.local').pathname
        } catch {
          rejectUpgrade(socket, 400, 'bad request')
          return
        }
        if (pathname !== '/v1/ws') {
          rejectUpgrade(socket, 404, 'not found')
          return
        }
        const device = authenticate(store, request.headers.authorization)
        if (!device) {
          rejectUpgrade(socket, 401, 'unauthorized')
          return
        }
        if (pendingSockets.size >= MAX_PENDING_SOCKETS) {
          rejectUpgrade(socket, 503, 'too many pending connections')
          return
        }
        let pendingForDevice = 0
        for (const pendingId of pendingSockets.values()) {
          if (pendingId === device.id) pendingForDevice += 1
        }
        if (pendingForDevice >= MAX_PENDING_PER_DEVICE) {
          rejectUpgrade(socket, 429, 'too many pending connections')
          return
        }
        wss.handleUpgrade(request, socket, head, (ws) => {
          wss.emit('connection', ws, request)
          handleConnection(ws, device.id)
        })
      })
    },
    close() {
      clearInterval(sweeper)
      clearInterval(heartbeat)
      const allSockets: WebSocket[] = [...pendingSockets.keys(), ...[...connections.values()].map((c) => c.socket)]
      for (const connection of connections.values()) connection.state = 'closed'
      pendingSockets.clear()
      connections.clear()
      pendingRoutes.clear()
      for (const socket of allSockets) safeClose(socket, 1001, 'server shutting down')
      const terminator = setTimeout(() => {
        for (const socket of allSockets) {
          try {
            if (socket.readyState !== WebSocket.CLOSED) socket.terminate()
          } catch {}
        }
      }, SHUTDOWN_TERMINATE_MS)
      terminator.unref?.()
      return new Promise((resolve) =>
        wss.close(() => {
          clearTimeout(terminator)
          resolve()
        }),
      )
    },
    isOnline(deviceId: string) {
      return connections.has(deviceId)
    },
    botsOf(deviceId: string) {
      return connections.get(deviceId)?.bots ?? []
    },
    capabilitiesOf(deviceId: string) {
      return connections.get(deviceId)?.capabilities
    },
    broadcastMemoryChanged(version: number) {
      for (const connection of connections.values()) {
        send(connection, { type: 'memory_changed', version })
      }
    },
    disconnectDevice(deviceId: string) {
      for (const [socket, pendingDeviceId] of [...pendingSockets.entries()]) {
        if (pendingDeviceId !== deviceId) continue
        pendingSockets.delete(socket)
        safeClose(socket, 4001, 'device revoked')
      }
      const connection = connections.get(deviceId)
      if (!connection) return
      connection.state = 'closed'
      connections.delete(deviceId)
      safeClose(connection.socket, 4001, 'device revoked')
      const forceTimer = setTimeout(() => {
        try {
          if (connection.socket.readyState !== WebSocket.CLOSED) connection.socket.terminate()
        } catch {}
      }, 250)
      forceTimer.unref?.()
      dropDevice(deviceId, `device ${deviceId} revoked`)
    },
  }
}

function safeClose(socket: WebSocket, code: number, reason: string): void {
  try {
    if (socket.readyState === WebSocket.CLOSING || socket.readyState === WebSocket.CLOSED) return
    if (socket.readyState === WebSocket.CONNECTING) {
      socket.terminate()
      return
    }
    const truncated = truncateUtf8(reason, 120)
    if (code === 1006) {
      socket.terminate()
      return
    }
    socket.close(code, truncated)
  } catch {
    try {
      socket.terminate()
    } catch {}
  }
}

function truncateUtf8(text: string, maxBytes: number): string {
  const buf = Buffer.from(text, 'utf8')
  if (buf.byteLength <= maxBytes) return text
  let end = maxBytes
  while (end > 0 && (buf[end]! & 0xc0) === 0x80) end -= 1
  return buf.subarray(0, end).toString('utf8')
}

function authenticate(store: RelayStore, header: string | undefined): { id: string } | null {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null
  const token = header.slice('Bearer '.length).trim()
  if (token === '') return null
  const device = store.deviceByTokenHash(sha256(token))
  if (!device || device.revoked) return null
  return { id: device.id }
}

function rejectUpgrade(socket: Duplex, status: number, message: string): void {
  socket.write(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\n\r\n`)
  socket.destroy()
}
