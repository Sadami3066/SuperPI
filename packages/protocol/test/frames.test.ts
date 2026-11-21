import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  MAX_FILE_CHUNK_BYTES,
  MAX_FILE_TRANSFER_BYTES,
  PROTOCOL_VERSION,
  ProtocolError,
  RELAY_CAPABILITIES,
  RouteValidationError,
  UnknownRouteMethodError,
  decodeFrameLine,
  encodeFrame,
  parseClientFrame,
  parseServerFrame,
  validateRelativeWorkspacePath,
  type ClientFrame,
  type RemoteSessionSummary,
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

  it('round-trips optional bot avatars and validates image headers safely', () => {
    const avatar = 'data:image/jpeg;base64,/9j/4AAQ'
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
      bots: [
        { id: 'pi', name: 'PI', avatar },
        { id: 'other', name: 'Other', avatar: 'data:image/gif;base64,R0lGOD==' },
      ],
    })
    expect(presenceFrame).toMatchObject({
      bots: [{ id: 'pi', name: 'PI', avatar }, { id: 'other', name: 'Other' }],
    })

    expect(() =>
      parseClientFrame({
        ...(readVector('client-hello.json') as Record<string, unknown>),
        bots: [{ id: 'pi', name: 'PI', avatar: 'https://example.com/a.png' }],
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        ...(readVector('client-hello.json') as Record<string, unknown>),
        bots: [{ id: 'pi', name: 'PI', avatar: 'data:image/jpeg;base64,AAECAw==' }],
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

  it('normalizes image/jpg, supports image/gif, and throws RouteValidationError on invalid route images', () => {
    const gifData = 'R0lGODdhAQABAIAAAP///////ywAAAAAAQABAAACAkQBADs='
    const jpgData = '/9j/4AAQ'
    const parsed = parseClientFrame({
      type: 'route',
      id: 'req-img',
      to: 'dev-phone',
      method: 'bot.prompt',
      params: {
        botId: 'pi',
        text: 'look',
        images: [
          { data: jpgData, mimeType: 'image/jpg', name: '  photo.jpg  ' },
          { data: gifData, mimeType: 'image/gif' },
        ],
      },
    })
    expect(parsed).toMatchObject({
      type: 'route',
      params: {
        images: [
          { data: jpgData, mimeType: 'image/jpeg', name: 'photo.jpg' },
          { data: gifData, mimeType: 'image/gif' },
        ],
      },
    })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'req-bad-img',
        to: 'dev-phone',
        method: 'bot.prompt',
        params: {
          botId: 'pi',
          text: 'look',
          images: [{ data: 'AAECAw==', mimeType: 'image/png' }],
        },
      }),
    ).toThrow(/image format/i)
  })

  it('validates structured approval_request questions, resolved events, user_message events, and busy sessions', () => {
    const summary: RemoteSessionSummary = {
      id: 'sess-1',
      title: 'Test',
      updatedAt: 1000,
      busy: true,
    }
    expect(summary.busy).toBe(true)

    const questionEvent = parseClientFrame({
      type: 'route_event',
      id: 'r-q',
      seq: 1,
      event: {
        type: 'approval_request',
        approvalId: 'ask:tc-1',
        kind: 'question',
        toolCallId: 'tc-1',
        summary: 'Which option?',
        title: 'Pick one',
        questions: [
          {
            id: 'q1',
            header: 'Choice',
            question: 'Which option?',
            multiSelect: false,
            options: [
              { label: 'Alpha', description: 'First choice' },
              { label: 'Beta' },
            ],
          },
        ],
      },
    })
    expect(questionEvent).toMatchObject({ type: 'route_event', id: 'r-q', seq: 1 })

    const resolvedEvent = parseServerFrame({
      type: 'route_event',
      id: 'r-q',
      seq: 2,
      event: {
        type: 'approval_request',
        approvalId: 'ask:tc-1',
        kind: 'resolved',
        summary: 'resolved',
        resolved: true,
      },
    })
    expect(resolvedEvent).toMatchObject({ type: 'route_event', id: 'r-q', seq: 2 })

    const userMessageEvent = parseServerFrame({
      type: 'route_event',
      id: 'r-q',
      seq: 3,
      event: {
        type: 'user_message',
        text: 'hello from phone',
        images: [{ data: '/9j/4AAQ', mimeType: 'IMAGE/JPG', name: '  pic.jpg  ', extra: true }],
      },
    })
    expect(userMessageEvent).toEqual({
      type: 'route_event',
      id: 'r-q',
      seq: 3,
      event: {
        type: 'user_message',
        text: 'hello from phone',
        images: [{ data: '/9j/4AAQ', mimeType: 'image/jpeg', name: 'pic.jpg' }],
      },
    })

    const imageOnlyUserMessage = parseClientFrame({
      type: 'route_event',
      id: 'r-img-only',
      seq: 3,
      event: {
        type: 'user_message',
        text: '   ',
        images: [{ data: '/9j/4AAQ', mimeType: 'image/jpeg' }],
      },
    })
    expect(imageOnlyUserMessage).toMatchObject({ type: 'route_event', id: 'r-img-only' })

    const approvalRoute = parseClientFrame({
      type: 'route',
      id: 'r-app',
      to: 'dev-2',
      method: 'bot.approval',
      params: {
        approvalId: 'ask:tc-1',
        answers: [{ id: 'q1', selected: ['Alpha'] }],
      },
    })
    expect(approvalRoute).toMatchObject({ type: 'route', id: 'r-app', method: 'bot.approval' })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-app-bad',
        to: 'dev-2',
        method: 'bot.approval',
        params: { approvalId: 'ask:tc-1', answers: [{ id: 123 }] },
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-empty-user',
        seq: 4,
        event: { type: 'user_message', text: '   ' },
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-empty-q',
        seq: 4,
        event: {
          type: 'approval_request',
          approvalId: 'ask:tc-2',
          kind: 'question',
          summary: 'Empty questions',
          questions: [],
        },
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-dup-q',
        seq: 4,
        event: {
          type: 'approval_request',
          approvalId: 'ask:tc-2',
          kind: 'question',
          summary: 'Dup',
          questions: [
            { id: 'q1', question: 'One' },
            { id: 'q1', question: 'Two' },
          ],
        },
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-bad',
        seq: 4,
        event: {
          type: 'approval_request',
          approvalId: 'ask:tc-2',
          kind: 'question',
          summary: 'Bad',
          questions: [{ id: 'q1', question: 123 }],
        },
      }),
    ).toThrow(ProtocolError)

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-bad-user',
        seq: 5,
        event: {
          type: 'user_message',
          text: 'hi',
          images: [{ data: 'AAECAw==', mimeType: 'image/png' }],
        },
      }),
    ).toThrow(ProtocolError)

    const startWithUser = parseServerFrame({
      type: 'route_event',
      id: 'r-start',
      seq: 6,
      event: {
        type: 'agent_start',
        runId: 'run-1',
        sessionId: 'sess-1',
        originRouteId: 'r-origin',
        userMessage: {
          text: 'from desktop',
          images: [{ data: '/9j/4AAQ', mimeType: 'IMAGE/JPG', name: '  img.jpg  ' }],
        },
      },
    })
    expect(startWithUser).toEqual({
      type: 'route_event',
      id: 'r-start',
      seq: 6,
      event: {
        type: 'agent_start',
        runId: 'run-1',
        sessionId: 'sess-1',
        originRouteId: 'r-origin',
        userMessage: {
          text: 'from desktop',
          images: [{ data: '/9j/4AAQ', mimeType: 'image/jpeg', name: 'img.jpg' }],
        },
      },
    })

    const queueWithUser = parseServerFrame({
      type: 'route_event',
      id: 'r-queue',
      seq: 7,
      event: {
        type: 'queue_update',
        steering: [],
        followUp: ['follow up'],
        sessionId: 'sess-1',
        userMessage: {
          text: 'follow up',
          images: [{ data: '/9j/4AAQ', mimeType: 'image/jpeg' }],
        },
      },
    })
    expect(queueWithUser).toMatchObject({
      type: 'route_event',
      id: 'r-queue',
      seq: 7,
      event: {
        type: 'queue_update',
        sessionId: 'sess-1',
        userMessage: { text: 'follow up' },
      },
    })

    expect(() =>
      parseClientFrame({
        type: 'route_event',
        id: 'r-bad-start',
        seq: 8,
        event: {
          type: 'agent_start',
          userMessage: { text: '   ' },
        },
      }),
    ).toThrow(ProtocolError)
  })

  it('validates bot manage, session manage, workspace file routes, relative paths, and sync_event frames', () => {
    expect(RELAY_CAPABILITIES).toContain('bot-manage')
    expect(RELAY_CAPABILITIES).toContain('session-manage')
    expect(RELAY_CAPABILITIES).toContain('workspace-files')
    expect(RELAY_CAPABILITIES).toContain('sync-events')

    expect(validateRelativeWorkspacePath('', true)).toBe('')
    expect(validateRelativeWorkspacePath('src//app/main.ts', false)).toBe('src/app/main.ts')
    for (const invalid of ['', '/etc/passwd', '../secret', 'a/../b', './a', 'a\\b', 'C:foo', 'a\u0000b']) {
      expect(() => validateRelativeWorkspacePath(invalid, false)).toThrow(ProtocolError)
    }

    const botCreate = parseClientFrame({
      type: 'route',
      id: 'r-bc',
      to: 'dev-phone-2',
      method: 'bot.create',
      params: { name: 'Researcher', avatar: 'data:image/jpeg;base64,/9j/4AAQ', intro: 'Notes' },
    })
    expect(botCreate).toMatchObject({ method: 'bot.create', params: { name: 'Researcher' } })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-bc-cwd',
        to: 'dev-phone-2',
        method: 'bot.create',
        params: { name: 'Bad', cwd: '/tmp/work' },
      }),
    ).toThrow(RouteValidationError)

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-bc-bad-avatar',
        to: 'dev-desktop',
        method: 'bot.create',
        params: { name: 'Bad', avatar: 'https://example.com/x.png' },
      }),
    ).toThrow(RouteValidationError)

    const sessionMeta = parseClientFrame({
      type: 'route',
      id: 'r-sm',
      to: 'dev-desktop',
      method: 'session.update_meta',
      params: { botId: 'pi', sessionId: 's1', pinned: true, archived: false },
    })
    expect(sessionMeta).toMatchObject({ method: 'session.update_meta' })

    const fileList = parseClientFrame({
      type: 'route',
      id: 'r-fl',
      to: 'dev-desktop',
      method: 'file.list',
      params: { botId: 'pi', path: 'docs//notes' },
    })
    expect(fileList).toMatchObject({ params: { botId: 'pi', path: 'docs/notes' } })

    const writeChunk = parseClientFrame({
      type: 'route',
      id: 'r-fw',
      to: 'dev-desktop',
      method: 'file.write_chunk',
      params: {
        botId: 'pi',
        path: 'out/hello.txt',
        transferId: 't-1',
        offset: 0,
        data: Buffer.from('hello world').toString('base64'),
        eof: true,
        expectedSha256: 'b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9',
      },
    })
    expect(writeChunk).toMatchObject({ method: 'file.write_chunk', params: { path: 'out/hello.txt' } })

    try {
      parseClientFrame({
        type: 'route',
        id: 'r-fw-big',
        to: 'dev-desktop',
        method: 'file.write_chunk',
        params: {
          botId: 'pi',
          path: 'out/big.bin',
          offset: 0,
          data: Buffer.alloc(MAX_FILE_CHUNK_BYTES + 1, 65).toString('base64'),
          eof: false,
        },
      })
      throw new Error('expected validation error')
    } catch (error) {
      expect(error).toBeInstanceOf(RouteValidationError)
      expect((error as RouteValidationError).code).toBe('payload_too_large')
    }

    try {
      parseClientFrame({
        type: 'route',
        id: 'r-fw-max-offset',
        to: 'dev-desktop',
        method: 'file.write_chunk',
        params: {
          botId: 'pi',
          path: 'out/big.bin',
          offset: MAX_FILE_TRANSFER_BYTES,
          data: Buffer.from('a').toString('base64'),
          eof: true,
        },
      })
      throw new Error('expected offset validation error')
    } catch (error) {
      expect(error).toBeInstanceOf(RouteValidationError)
      expect((error as RouteValidationError).code).toBe('payload_too_large')
    }

    const clientSync = parseClientFrame({
      type: 'sync_event',
      kind: 'sessions_changed',
      botId: 'pi',
      sessionId: 's1',
      busy: false,
      ts: 1234,
    })
    expect(clientSync).toMatchObject({ type: 'sync_event', kind: 'sessions_changed', botId: 'pi', ts: 1234 })

    const serverSync = parseServerFrame({
      type: 'sync_event',
      deviceId: 'dev-desktop',
      kind: 'bot_activity',
      botId: 'pi',
      busy: true,
      pendingApprovals: 1,
      ts: 5678,
    })
    expect(serverSync).toMatchObject({ type: 'sync_event', deviceId: 'dev-desktop', busy: true, pendingApprovals: 1 })
    expect(() =>
      parseServerFrame({
        type: 'sync_event',
        kind: 'bot_activity',
        botId: 'pi',
        ts: 5678,
      }),
    ).toThrow(ProtocolError)

    const setModel = parseClientFrame({
      type: 'route',
      id: 'r-sm-ctrl',
      to: 'dev-desktop',
      method: 'bot.set_model',
      params: { botId: 'pi', sessionId: 's1', provider: 'anthropic', modelId: 'claude-sonnet', thinkingLevel: 'high' },
    })
    expect(setModel).toMatchObject({ method: 'bot.set_model', params: { provider: 'anthropic', thinkingLevel: 'high' } })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-set-thinking-bad',
        to: 'dev-desktop',
        method: 'bot.set_thinking',
        params: { botId: 'pi', thinkingLevel: '   ' },
      }),
    ).toThrow(RouteValidationError)

    const compactFrame = parseClientFrame({
      type: 'route',
      id: 'r-cmp',
      to: 'dev-desktop',
      method: 'session.compact',
      params: { botId: 'pi', sessionId: 's1', customInstructions: 'focus on decisions' },
    })
    expect(compactFrame).toMatchObject({ method: 'session.compact' })

    expect(RELAY_CAPABILITIES).not.toContain('workspace-git')
    expect(RELAY_CAPABILITIES).not.toContain('session-fork')
    for (const removedMethod of ['workspace.git_status', 'session.fork_messages', 'session.fork']) {
      expect(() =>
        parseClientFrame({
          type: 'route',
          id: `r-removed-${removedMethod}`,
          to: 'dev-phone-2',
          method: removedMethod,
          params: { botId: 'home' },
        }),
      ).toThrow(UnknownRouteMethodError)
    }

    try {
      parseClientFrame({
        type: 'route',
        id: 'r-inst-big',
        to: 'dev-desktop',
        method: 'bot.instructions.set',
        params: { botId: 'pi', content: 'a'.repeat(65537) },
      })
      throw new Error('expected instructions validation error')
    } catch (error) {
      expect(error).toBeInstanceOf(RouteValidationError)
      expect((error as RouteValidationError).code).toBe('payload_too_large')
    }

    const modelSync = parseServerFrame({
      type: 'sync_event',
      deviceId: 'dev-desktop',
      kind: 'model_changed',
      botId: 'pi',
      sessionId: 's1',
      provider: 'openai',
      modelId: 'gpt-5',
      thinkingLevel: 'xhigh',
      ts: 9999,
    })
    expect(modelSync).toMatchObject({
      type: 'sync_event',
      kind: 'model_changed',
      provider: 'openai',
      modelId: 'gpt-5',
      thinkingLevel: 'xhigh',
    })

    expect(RELAY_CAPABILITIES).toContain('bot-capabilities')
    expect(RELAY_CAPABILITIES).toContain('present-artifacts')

    const capGet = parseClientFrame({
      type: 'route',
      id: 'r-cap-get',
      to: 'dev-phone-2',
      method: 'bot.capabilities.get',
      params: { botId: 'home' },
    })
    expect(capGet).toMatchObject({ method: 'bot.capabilities.get', params: { botId: 'home' } })

    const capSet = parseClientFrame({
      type: 'route',
      id: 'r-cap-set',
      to: 'dev-phone-2',
      method: 'bot.capabilities.set',
      params: {
        botId: 'home',
        policy: {
          allCapabilities: true,
          skills: ['weather'],
          plugins: ['amap'],
          mcpServers: [],
          screenTools: true,
          miniappTools: false,
        },
      },
    })
    expect(capSet).toMatchObject({ method: 'bot.capabilities.set' })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-cap-bad',
        to: 'dev-phone-2',
        method: 'bot.capabilities.set',
        params: { botId: 'home', policy: 'invalid' },
      }),
    ).toThrow(RouteValidationError)

    const capSync = parseServerFrame({
      type: 'sync_event',
      deviceId: 'dev-phone-2',
      kind: 'capabilities_changed',
      botId: 'home',
      ts: 10001,
    })
    expect(capSync).toMatchObject({ type: 'sync_event', kind: 'capabilities_changed', botId: 'home' })

    const artifactRead = parseClientFrame({
      type: 'route',
      id: 'r-art-1',
      to: 'dev-phone-2',
      method: 'artifact.read_chunk',
      params: { botId: 'home', artifactId: 'art_abc123', offset: 0, length: 65536 },
    })
    expect(artifactRead).toMatchObject({ method: 'artifact.read_chunk', params: { artifactId: 'art_abc123' } })

    expect(() =>
      parseClientFrame({
        type: 'route',
        id: 'r-art-bad-id',
        to: 'dev-phone-2',
        method: 'artifact.read_chunk',
        params: { botId: 'home', artifactId: '../secret', offset: 0, length: 64 },
      }),
    ).toThrow(RouteValidationError)

    try {
      parseClientFrame({
        type: 'route',
        id: 'r-art-too-big',
        to: 'dev-phone-2',
        method: 'artifact.read_chunk',
        params: { botId: 'home', artifactId: 'art_1', offset: 0, length: MAX_FILE_CHUNK_BYTES + 1 },
      })
      throw new Error('expected artifact chunk size error')
    } catch (error) {
      expect(error).toBeInstanceOf(RouteValidationError)
      expect((error as RouteValidationError).code).toBe('payload_too_large')
    }
  })
})
