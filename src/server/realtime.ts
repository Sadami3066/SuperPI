import type { Server } from 'node:http'
import type { Duplex } from 'node:stream'
import { WebSocketServer, WebSocket } from 'ws'
import {
  PROTOCOL_VERSION,
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
} from '@superpi/protocol'
import type { RelayStore } from './store.ts'
import { sha256 } from './auth.ts'

export interface RealtimeHub {
  attach(server: Server): void
  close(): Promise<void>
  isOnline(deviceId: string): boolean
  botsOf(deviceId: string): BotInfo[]
}

interface Connection {
  deviceId: string
  bots: BotInfo[]
  socket: WebSocket
}

interface PendingRoute {
  from: string
  to: string
  at: number
}

const HELLO_TIMEOUT_MS = 10_000
const ROUTE_TTL_MS = 5 * 60 * 1000

export function createRealtimeHub(
  store: RelayStore,
  memoryVersion: () => number = () => 0,
  now: () => number = Date.now,
): RealtimeHub {
  const wss = new WebSocketServer({ noServer: true })
  const connections = new Map<string, Connection>()
  const pendingRoutes = new Map<string, PendingRoute>()

  function deviceList(): DeviceInfo[] {
    return store.listDevices().map((device) => {
      const connection = connections.get(device.id)
      return {
        deviceId: device.id,
        name: device.name,
        platform: device.platform as DeviceInfo['platform'],
        online: connection !== undefined,
        lastSeen: device.lastSeen,
        bots: connection?.bots ?? [],
      }
    })
  }

  function send(connection: Connection, frame: ServerFrame): void {
    if (connection.socket.readyState === WebSocket.OPEN) connection.socket.send(encodeFrame(frame))
  }

  function broadcastPresence(deviceId: string, online: boolean, bots?: BotInfo[]): void {
    const device = store.listDevices().find((entry) => entry.id === deviceId)
    const frame: PresenceFrame = {
      type: 'presence',
      deviceId,
      online,
      lastSeen: device?.lastSeen ?? now(),
    }
    if (bots) frame.bots = bots
    for (const connection of connections.values()) {
      if (connection.deviceId !== deviceId) send(connection, frame)
    }
  }

  function handleHello(connection: Connection, frame: HelloFrame): void {
    if (frame.deviceId !== connection.deviceId) {
      connection.socket.close(1008, 'device mismatch')
      return
    }
    if (frame.protocolVersion !== PROTOCOL_VERSION) {
      connection.socket.close(1008, 'protocol mismatch')
      return
    }
    connection.bots = frame.bots
    const body: HelloOkFrame = {
      type: 'hello_ok',
      accountId: store.account()?.id ?? '',
      devices: deviceList(),
      memoryVersion: memoryVersion(),
    }
    send(connection, body)
    broadcastPresence(connection.deviceId, true, frame.bots)
  }

  function handleRoute(connection: Connection, frame: { id: string; to: string; method: string; params: Record<string, unknown> }): void {
    const target = connections.get(frame.to)
    if (!target) {
      const error: RouteErrorFrame = {
        type: 'route_error',
        id: frame.id,
        code: 'device_offline',
        message: `device ${frame.to} is offline`,
      }
      send(connection, error)
      return
    }
    pendingRoutes.set(frame.id, { from: connection.deviceId, to: frame.to, at: now() })
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
    if (frame.type === 'route_event') {
      if (requester) send(requester, frame as unknown as ServerFrame)
      return
    }
    pendingRoutes.delete(frame.id)
    if (!requester) return
    if (frame.type === 'route_ok') {
      send(requester, { type: 'route_ok', id: frame.id, result: frame.result })
      return
    }
    send(requester, {
      type: 'route_error',
      id: frame.id,
      code: frame.code as RouteErrorFrame['code'],
      message: String(frame.message),
    })
  }

  function handleConnection(socket: WebSocket, deviceId: string): void {
    const connection: Connection = { deviceId, bots: [], socket }
    connections.set(deviceId, connection)
    const helloTimer = setTimeout(() => socket.close(1008, 'hello timeout'), HELLO_TIMEOUT_MS)

    socket.on('message', (raw) => {
      let frame: ClientFrame
      try {
        frame = decodeFrameLine('client', raw.toString()) as ClientFrame
      } catch (error) {
        socket.close(1008, error instanceof Error ? error.message : 'protocol error')
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
      if (connections.get(deviceId) === connection) {
        connections.delete(deviceId)
        const offline: PresenceFrame = { type: 'presence', deviceId, online: false, lastSeen: now() }
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
                message: `device ${deviceId} disconnected`,
              })
            }
          }
        }
      }
    })

    socket.on('error', () => {
      socket.close()
    })
  }

  return {
    attach(server: Server) {
      server.on('upgrade', (request, socket, head) => {
        const pathname = new URL(request.url ?? '/', 'http://relay.local').pathname
        if (pathname !== '/v1/ws') {
          rejectUpgrade(socket, 404, 'not found')
          return
        }
        const device = authenticate(store, request.headers.authorization)
        if (!device) {
          rejectUpgrade(socket, 401, 'unauthorized')
          return
        }
        wss.handleUpgrade(request, socket, head, (ws) => {
          wss.emit('connection', ws, request)
          handleConnection(ws, device.id)
        })
      })
    },
    close() {
      for (const connection of connections.values()) connection.socket.close(1001, 'server shutting down')
      connections.clear()
      return new Promise((resolve) => wss.close(() => resolve()))
    },
    isOnline(deviceId: string) {
      return connections.has(deviceId)
    },
    botsOf(deviceId: string) {
      return connections.get(deviceId)?.bots ?? []
    },
  }
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
