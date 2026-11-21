import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  PROTOCOL_VERSION,
  ProtocolError,
  decodeFrameLine,
  encodeFrame,
  parseClientFrame,
  parseServerFrame,
  type ClientFrame,
  type ServerFrame,
} from '../src/index.ts'

const vectorDir = fileURLToPath(new URL('../vectors/', import.meta.url))

function readVector(name: string): unknown {
  return JSON.parse(readFileSync(vectorDir + name, 'utf8'))
}

describe('protocol vectors', () => {
  it('parses the client hello vector', () => {
    const frame = parseClientFrame(readVector('client-hello.json'))
    expect(frame.type).toBe('hello')
    expect(frame).toMatchObject({ protocolVersion: PROTOCOL_VERSION, deviceId: 'dev-phone' })
  })

  it('parses the client route vector', () => {
    const frame = parseClientFrame(readVector('client-route.json'))
    expect(frame).toMatchObject({ type: 'route', id: 'req-1', to: 'dev-desktop', method: 'bot.prompt' })
  })

  it('parses the server route event vector', () => {
    const frame = parseServerFrame(readVector('server-route-event.json'))
    expect(frame).toMatchObject({ type: 'route_event', id: 'req-1', seq: 3 })
    if (frame.type !== 'route_event') throw new Error('unexpected frame')
    expect(frame.event).toMatchObject({ type: 'message_update' })
  })

  it('parses the server hello_ok vector', () => {
    const frame = parseServerFrame(readVector('server-hello-ok.json'))
    expect(frame).toMatchObject({ type: 'hello_ok', accountId: 'acct-main', memoryVersion: 12 })
    if (frame.type !== 'hello_ok') throw new Error('unexpected frame')
    expect(frame.devices).toHaveLength(2)
    expect(frame.devices[1]?.online).toBe(false)
  })

  it('round-trips every frame through encode/decode', () => {
    const frames: Array<ClientFrame | ServerFrame> = [
      parseClientFrame(readVector('client-hello.json')),
      parseClientFrame(readVector('client-route.json')),
      parseServerFrame(readVector('server-route-event.json')),
      parseServerFrame(readVector('server-hello-ok.json')),
      parseServerFrame(readVector('server-presence.json')),
    ]
    for (const frame of frames) {
      const line = encodeFrame(frame).trimEnd()
      const direction = frame.type === 'hello' || frame.type === 'route' || frame.type === 'ping' ? 'client' : 'server'
      expect(decodeFrameLine(direction, line)).toEqual(frame)
    }
  })
})

describe('protocol validation', () => {
  it('rejects a protocol version mismatch', () => {
    const frame = readVector('client-hello.json') as Record<string, unknown>
    expect(() => parseClientFrame({ ...frame, protocolVersion: PROTOCOL_VERSION + 1 })).toThrow(ProtocolError)
  })

  it('rejects unknown frame types', () => {
    expect(() => parseClientFrame({ type: 'nope' })).toThrow(ProtocolError)
    expect(() => parseServerFrame({ type: 'route' })).toThrow(ProtocolError)
  })

  it('rejects malformed events', () => {
    expect(() => parseServerFrame({ type: 'route_event', id: 'x', seq: 1, event: { type: 'message_update' } })).toThrow(
      ProtocolError,
    )
    expect(() =>
      parseServerFrame({ type: 'route_event', id: 'x', seq: 1, event: { type: 'tool_call', callId: 'c1' } }),
    ).toThrow(ProtocolError)
  })

  it('rejects unknown route methods and error codes', () => {
    expect(() =>
      parseClientFrame({ type: 'route', id: 'x', to: 'y', method: 'bot.explode', params: {} }),
    ).toThrow(ProtocolError)
    expect(() => parseClientFrame({ type: 'route_error', id: 'x', code: 'nope', message: 'm' })).toThrow(ProtocolError)
  })

  it('throws on non-JSON lines', () => {
    expect(() => decodeFrameLine('client', '{oops')).toThrow(ProtocolError)
  })

  it('round-trips optional bot avatars and rejects invalid avatar payloads', () => {
    const avatar = 'data:image/jpeg;base64,AAECAw=='
    const helloFrame = parseClientFrame({
      ...(readVector('client-hello.json') as Record<string, unknown>),
      bots: [{ id: 'pi', name: 'PI', avatar }],
    })
    expect(helloFrame).toMatchObject({ type: 'hello', bots: [{ id: 'pi', name: 'PI', avatar }] })
    expect(decodeFrameLine('client', encodeFrame(helloFrame).trimEnd())).toEqual(helloFrame)

    const presenceFrame = parseServerFrame({
      type: 'presence',
      deviceId: 'dev-phone',
      online: true,
      lastSeen: 123,
      bots: [{ id: 'pi', name: 'PI', avatar }],
    })
    expect(presenceFrame).toMatchObject({ bots: [{ id: 'pi', name: 'PI', avatar }] })

    expect(() =>
      parseClientFrame({
        ...(readVector('client-hello.json') as Record<string, unknown>),
        bots: [{ id: 'pi', name: 'PI', avatar: 'https://example.com/a.png' }],
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        ...(readVector('client-hello.json') as Record<string, unknown>),
        bots: [{ id: 'pi', name: 'PI', avatar: `data:image/jpeg;base64,${'A'.repeat(70_000)}` }],
      }),
    ).toThrow(ProtocolError)
  })

  it('accepts bot.sessions and bot.history route frames', () => {
    const sessionsRoute = parseClientFrame({
      type: 'route',
      id: 'req-s',
      to: 'dev-phone',
      method: 'bot.sessions',
      params: { botId: 'pi' },
    })
    expect(sessionsRoute).toMatchObject({ method: 'bot.sessions' })

    const historyRoute = parseClientFrame({
      type: 'route',
      id: 'req-h',
      to: 'dev-phone',
      method: 'bot.history',
      params: { botId: 'pi', sessionId: 'sess-1' },
    })
    expect(historyRoute).toMatchObject({ method: 'bot.history' })
  })
})
