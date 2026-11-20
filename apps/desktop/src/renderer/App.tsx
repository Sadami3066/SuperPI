import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  BotInfo,
  BotSessionPayload,
  MemorySummaryPayload,
  PiEventPayload,
  PiStatePayload,
  RelayStatusPayload,
  RemoteEventPayload,
  SessionSummary,
} from '../shared/ipc'
import {
  appendAssistantDelta,
  entriesFromMessages,
  entryId,
  finishTool,
  startTool,
  toolResultDetail,
  updateTool,
  type Entry,
} from './entries'
import './styles.css'

interface RemoteTarget {
  sessionId: string
  deviceId: string
  botId: string
  label: string
}

export function App(): React.JSX.Element {
  const [bots, setBots] = useState<BotInfo[]>([])
  const [activeBotId, setActiveBotId] = useState<string | null>(null)
  const [remote, setRemote] = useState<RemoteTarget | null>(null)
  const [piState, setPiState] = useState<PiStatePayload | null>(null)
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [sessionsBusy, setSessionsBusy] = useState(false)
  const [entries, setEntries] = useState<Entry[]>([])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [retryable, setRetryable] = useState(false)
  const [relay, setRelay] = useState<RelayStatusPayload | null>(null)
  const [memory, setMemory] = useState<MemorySummaryPayload | null>(null)
  const [showRelay, setShowRelay] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const activeBotIdRef = useRef<string | null>(null)
  const remoteRef = useRef<RemoteTarget | null>(null)
  const openSequence = useRef(0)

  activeBotIdRef.current = activeBotId
  remoteRef.current = remote

  const applySnapshot = useCallback((snapshot: BotSessionPayload) => {
    setActiveBotId(snapshot.botId)
    setPiState(snapshot.state)
    setSessions(snapshot.sessions)
    setEntries(entriesFromMessages(snapshot.messages))
    setRunning(false)
    setNotice(null)
    setRetryable(false)
  }, [])

  const activate = useCallback(
    async (botId: string | null) => {
      const sequence = ++openSequence.current
      setSessionsBusy(true)
      setNotice(null)
      setRetryable(false)
      try {
        const opened = await window.superpi.openBot(botId)
        if (sequence !== openSequence.current) return
        applySnapshot(opened)
      } catch (error) {
        if (sequence !== openSequence.current) return
        setNotice(errorMessage(error))
        setRetryable(true)
      } finally {
        if (sequence === openSequence.current) setSessionsBusy(false)
      }
    },
    [applySnapshot],
  )

  const refreshSessions = useCallback(async () => {
    const botId = activeBotIdRef.current
    if (botId === null || remoteRef.current !== null) return
    try {
      const [nextSessions, nextState] = await Promise.all([
        window.superpi.listSessions(botId),
        window.superpi.getState(botId),
      ])
      if (botId !== activeBotIdRef.current) return
      setSessions(nextSessions)
      setPiState(nextState)
    } catch {
      return
    }
  }, [])

  useEffect(() => {
    void window.superpi.listBots().then((list) => {
      setBots(list)
      void activate(null)
    })
    void window.superpi.relayStatus().then(setRelay)
    void window.superpi.memoryStatus().then(setMemory)
    const stopRelay = window.superpi.onRelayState(setRelay)
    const stopMemory = window.superpi.onMemoryState(setMemory)
    return () => {
      stopRelay()
      stopMemory()
    }
  }, [activate])

  useEffect(() => {
    return window.superpi.onEvent((payload: PiEventPayload) => {
      if (remoteRef.current !== null) return
      if (payload.botId !== activeBotIdRef.current) return
      applyEvent(payload.event, setEntries, setRunning, setNotice)
      if (payload.event.type === 'agent_settled') void refreshSessions()
    })
  }, [refreshSessions])

  useEffect(() => {
    return window.superpi.onRemoteEvent((payload: RemoteEventPayload) => {
      const current = remoteRef.current
      if (!current || payload.sessionId !== current.sessionId) return
      applyEvent(payload.event, setEntries, setRunning, setNotice)
    })
  }, [])

  useEffect(() => {
    return window.superpi.onExit((payload) => {
      if (payload.botId !== activeBotIdRef.current) return
      setRunning(false)
      setRetryable(true)
      setNotice(payload.message ? `pi exited: ${payload.message}` : 'pi session ended')
    })
  }, [])

  useEffect(() => {
    const element = scrollRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [entries])

  const send = useCallback(async () => {
    const text = input.trim()
    if (text === '' || running) return
    setEntries((current) => [...current, { kind: 'user', id: entryId(), text }])
    setInput('')
    setRunning(true)
    setNotice(null)
    try {
      const target = remoteRef.current
      if (target) {
        await window.superpi.remotePrompt({ sessionId: target.sessionId, text })
      } else if (activeBotId !== null) {
        await window.superpi.prompt(activeBotId, text)
      }
    } catch (error) {
      setRunning(false)
      setEntries((current) => [
        ...current,
        { kind: 'notice', id: entryId(), text: errorMessage(error), tone: 'error' },
      ])
    }
  }, [activeBotId, input, running])

  const abort = useCallback(async () => {
    try {
      const target = remoteRef.current
      if (target) {
        await window.superpi.remoteAbort(target.sessionId)
      } else if (activeBotId !== null) {
        await window.superpi.abort(activeBotId)
      }
    } catch (error) {
      setNotice(errorMessage(error))
    }
  }, [activeBotId])

  const selectBot = useCallback(
    (botId: string) => {
      if (botId === activeBotId && remote === null) return
      void closeRemote(remote)
      setRemote(null)
      setPiState(null)
      setSessions([])
      setEntries([])
      setRunning(false)
      setNotice(null)
      void activate(botId)
    },
    [activeBotId, remote, activate],
  )

  const newChat = useCallback(async () => {
    if (activeBotId === null || running || remote !== null) return
    setSessionsBusy(true)
    setNotice(null)
    try {
      const result = await window.superpi.newSession(activeBotId)
      if (result.cancelled) {
        setNotice('pi kept the current session')
        return
      }
      applySnapshot(result)
    } catch (error) {
      setNotice(errorMessage(error))
    } finally {
      setSessionsBusy(false)
    }
  }, [activeBotId, running, remote, applySnapshot])

  const openSession = useCallback(
    async (sessionPath: string) => {
      if (activeBotId === null || running || remote !== null) return
      if (sessionPath === piState?.sessionFile) return
      setSessionsBusy(true)
      setNotice(null)
      try {
        const result = await window.superpi.switchSession(activeBotId, sessionPath)
        if (result.cancelled) {
          setNotice('pi kept the current session')
          return
        }
        applySnapshot(result)
      } catch (error) {
        setNotice(errorMessage(error))
      } finally {
        setSessionsBusy(false)
      }
    },
    [activeBotId, running, remote, piState?.sessionFile, applySnapshot],
  )

  const retry = useCallback(() => {
    const botId = activeBotIdRef.current
    if (botId !== null) void activate(botId)
  }, [activate])

  const openRemote = useCallback(
    async (deviceId: string, deviceName: string, bot: { id: string; name: string }) => {
      void closeRemote(remote)
      setRemote(null)
      setPiState(null)
      setSessions([])
      setEntries([])
      setRunning(false)
      setNotice(null)
      setRetryable(false)
      try {
        const session = await window.superpi.remoteStart({ deviceId, botId: bot.id })
        setRemote({ sessionId: session.sessionId, deviceId, botId: bot.id, label: `${bot.name} · ${deviceName}` })
      } catch (error) {
        setNotice(errorMessage(error))
      }
    },
    [remote],
  )

  const activeBot = bots.find((bot) => bot.id === activeBotId) ?? null
  const relayView = describeRelay(relay)
  const otherDevices = relay?.devices.filter((device) => device.deviceId !== relay.deviceId) ?? []
  const title = remote ? remote.label : (activeBot?.name ?? 'No bot')
  const subtitle = remote ? 'remote bot' : sessionSubtitle(piState, activeBot)

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">SuperPI</div>
        <div className="section-label">Bots</div>
        <nav className="bot-list">
          {bots.map((bot) => (
            <button
              key={bot.id}
              type="button"
              className={`bot-row${remote === null && bot.id === activeBotId ? ' active' : ''}`}
              onClick={() => selectBot(bot.id)}
            >
              <span className="bot-avatar">{bot.name.slice(0, 1).toUpperCase()}</span>
              <span className="bot-meta">
                <span className="bot-name">{bot.name}</span>
                <span className="bot-path">{bot.cwd}</span>
              </span>
            </button>
          ))}
        </nav>

        {remote === null ? (
          <>
            <div className="section-row">
              <span className="section-label">Chats</span>
              <button
                type="button"
                className="link-button"
                disabled={activeBotId === null || running || sessionsBusy}
                onClick={() => void newChat()}
              >
                New
              </button>
            </div>
            <nav className="session-list">
              {sessions.length === 0 ? (
                <div className="session-empty">{sessionsBusy ? 'Loading…' : 'No chats yet'}</div>
              ) : (
                sessions.map((session) => (
                  <button
                    key={session.path}
                    type="button"
                    className={`session-row${session.path === piState?.sessionFile ? ' active' : ''}`}
                    disabled={running || sessionsBusy}
                    onClick={() => void openSession(session.path)}
                  >
                    <span className="session-title">{session.title}</span>
                    <span className="session-time">{formatSessionTime(session.modified)}</span>
                  </button>
                ))
              )}
            </nav>
          </>
        ) : null}

        {otherDevices.length > 0 ? (
          <>
            <div className="section-label">Devices</div>
            <nav className="bot-list">
              {otherDevices.map((device) => (
                <div key={device.deviceId} className="device-group">
                  <div className={`device-title${device.online ? '' : ' offline'}`}>
                    <span className={`status-dot relay ${device.online ? 'online' : 'offline'}`} />
                    {device.name}
                  </div>
                  {device.bots.map((bot) => (
                    <button
                      key={`${device.deviceId}:${bot.id}`}
                      type="button"
                      className={`bot-row remote${remote?.deviceId === device.deviceId && remote?.botId === bot.id ? ' active' : ''}`}
                      disabled={!device.online}
                      onClick={() => void openRemote(device.deviceId, device.name, bot)}
                    >
                      <span className="bot-avatar">{bot.name.slice(0, 1).toUpperCase()}</span>
                      <span className="bot-meta">
                        <span className="bot-name">{bot.name}</span>
                        <span className="bot-path">{device.online ? 'remote' : 'offline'}</span>
                      </span>
                    </button>
                  ))}
                </div>
              ))}
            </nav>
          </>
        ) : null}

        <div className="sidebar-footer">
          <span className={`status-dot${running ? ' busy' : ''}`} />
          {running ? 'working' : 'idle'}
        </div>
        <button type="button" className="account-row" onClick={() => setShowRelay(true)}>
          <span className={`status-dot relay ${relayView.tone}`} />
          <span className="account-text">
            <span className="account-title">{relayView.title}</span>
            <span className="account-subtitle">{relayView.subtitle}</span>
          </span>
        </button>
      </aside>

      <main className="chat">
        <header className="chat-header">
          <div className="chat-header-main">
            <div>
              <div className="chat-title">{title}</div>
              <div className="chat-subtitle">{subtitle}</div>
            </div>
          </div>
          {notice ? (
            <div className="chat-notice">
              <span>{notice}</span>
              {retryable ? (
                <button type="button" className="link-button" onClick={retry}>
                  Retry
                </button>
              ) : null}
            </div>
          ) : null}
        </header>

        <div className="messages" ref={scrollRef}>
          {entries.length === 0 ? (
            <div className="empty-state">
              <div className="empty-title">{emptyTitle(remote, sessionsBusy)}</div>
              <div className="empty-hint">
                {remote ? 'Messages run on the other device.' : "Sessions run in this bot's working directory."}
              </div>
            </div>
          ) : null}
          {entries.map((entry) => {
            if (entry.kind === 'user') {
              return (
                <div key={entry.id} className="msg user">
                  <div className="bubble">{entry.text}</div>
                </div>
              )
            }
            if (entry.kind === 'assistant') {
              return (
                <div key={entry.id} className="msg assistant">
                  <div className="bubble">{entry.text}</div>
                </div>
              )
            }
            if (entry.kind === 'tool') {
              return (
                <div key={entry.id} className={`tool-card ${entry.status}`}>
                  <div className="tool-head">
                    <span className="tool-name">{entry.name}</span>
                    <span className="tool-status">{entry.status}</span>
                  </div>
                  {entry.detail ? <pre className="tool-detail">{entry.detail.slice(0, 2000)}</pre> : null}
                </div>
              )
            }
            return (
              <div key={entry.id} className={`notice ${entry.tone}`}>
                {entry.text}
              </div>
            )
          })}
        </div>

        <footer className="composer">
          <textarea
            value={input}
            placeholder={remote ? 'Message the remote bot' : 'Message PI'}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                void send()
              }
            }}
          />
          {running ? (
            <button type="button" className="stop" onClick={() => void abort()}>
              Stop
            </button>
          ) : (
            <button
              type="button"
              className="send"
              disabled={input.trim() === '' || (remote === null && activeBotId === null)}
              onClick={() => void send()}
            >
              Send
            </button>
          )}
        </footer>
      </main>

      {showRelay ? (
        <RelayDialog relay={relay} memory={memory} onClose={() => setShowRelay(false)} onChanged={setRelay} />
      ) : null}
    </div>
  )
}

async function closeRemote(target: RemoteTarget | null): Promise<void> {
  if (!target) return
  try {
    await window.superpi.remoteClose(target.sessionId)
  } catch {
    return
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function sessionSubtitle(state: PiStatePayload | null, bot: BotInfo | null): string {
  const parts = [state?.sessionName ?? null, bot?.cwd ?? null].filter((part): part is string => part !== null)
  return parts.join(' · ')
}

function emptyTitle(remote: RemoteTarget | null, busy: boolean): string {
  if (remote) return 'Remote bot ready'
  return busy ? 'Starting pi…' : 'Ask PI anything'
}

function formatSessionTime(timestamp: number): string {
  const date = new Date(timestamp)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  if (date.getFullYear() === now.getFullYear()) return date.toLocaleDateString([], { month: 'short', day: 'numeric' })
  return date.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
}

function applyEvent(
  event: { type: string; [key: string]: unknown },
  setEntries: React.Dispatch<React.SetStateAction<Entry[]>>,
  setRunning: React.Dispatch<React.SetStateAction<boolean>>,
  setNotice: React.Dispatch<React.SetStateAction<string | null>>,
): void {
  switch (event.type) {
    case 'agent_start':
      setRunning(true)
      setNotice(null)
      break
    case 'message_update': {
      const update = event.assistantMessageEvent as { type?: string; delta?: string } | undefined
      if (update?.type !== 'text_delta' || typeof update.delta !== 'string') break
      const delta = update.delta
      setEntries((current) => appendAssistantDelta(current, delta))
      break
    }
    case 'tool_execution_start': {
      const id = typeof event.toolCallId === 'string' ? event.toolCallId : entryId()
      const name = typeof event.toolName === 'string' ? event.toolName : 'tool'
      setEntries((current) => startTool(current, id, name, event.args))
      break
    }
    case 'tool_execution_update': {
      const id = typeof event.toolCallId === 'string' ? event.toolCallId : ''
      const detail = toolResultDetail(event.partialResult)
      if (id === '' || detail === '') break
      setEntries((current) => updateTool(current, id, { detail }))
      break
    }
    case 'tool_execution_end': {
      const id = typeof event.toolCallId === 'string' ? event.toolCallId : ''
      if (id === '') break
      const name = typeof event.toolName === 'string' ? event.toolName : 'tool'
      const status = event.isError === true ? 'error' : 'done'
      setEntries((current) => finishTool(current, id, name, status, event.result))
      break
    }
    case 'agent_end':
      setRunning(false)
      break
    case 'agent_settled':
      setRunning(false)
      break
    case 'error': {
      const message = typeof event.message === 'string' ? event.message : 'the remote bot reported an error'
      setEntries((current) => [...current, { kind: 'notice', id: entryId(), text: message, tone: 'error' }])
      setRunning(false)
      break
    }
    case 'client_error': {
      const message = typeof event.message === 'string' ? event.message : 'protocol error'
      setEntries((current) => [...current, { kind: 'notice', id: entryId(), text: message, tone: 'error' }])
      break
    }
    default:
      break
  }
}

function RelayDialog(props: {
  relay: RelayStatusPayload | null
  memory: MemorySummaryPayload | null
  onClose: () => void
  onChanged: (relay: RelayStatusPayload) => void
}): React.JSX.Element {
  const connected = props.relay !== null && props.relay.state !== 'disconnected'
  const [link, setLink] = useState('')
  const [loginSecret, setLoginSecret] = useState('')
  const [deviceName, setDeviceName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const pair = async (): Promise<void> => {
    setBusy(true)
    setError(null)
    try {
      const status = await window.superpi.relayPair({
        link,
        loginSecret: loginSecret.trim() === '' ? undefined : loginSecret.trim(),
        deviceName: deviceName.trim() === '' ? undefined : deviceName.trim(),
      })
      props.onChanged(status)
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  const logout = async (): Promise<void> => {
    setBusy(true)
    setError(null)
    try {
      props.onChanged(await window.superpi.relayLogout())
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  const relay = props.relay
  const others = relay?.devices.filter((device) => device.deviceId !== relay.deviceId) ?? []

  return (
    <div className="overlay" onClick={props.onClose}>
      <div className="dialog" onClick={(event) => event.stopPropagation()}>
        <div className="dialog-title">Connect devices</div>

        {connected && relay ? (
          <>
            <div className="dialog-lines">
              <div>
                <span className="dialog-label">Device</span> {relay.deviceName ?? 'this desktop'}
              </div>
              <div>
                <span className="dialog-label">Server</span> {relay.origin}
              </div>
              <div>
                <span className="dialog-label">Status</span> {relay.state}
                {relay.error ? ` · ${relay.error}` : ''}
              </div>
              <div>
                <span className="dialog-label">Memory</span>{' '}
                {props.memory
                  ? `v${props.memory.version} · ${props.memory.files.length} files · synced ${new Date(props.memory.syncedAt).toLocaleTimeString()}`
                  : 'not synced yet'}
              </div>
            </div>
            <div className="dialog-section">Devices</div>
            <div className="device-list">
              {others.map((device) => (
                <div key={device.deviceId} className="device-row">
                  <span className={`status-dot relay ${device.online ? 'online' : 'offline'}`} />
                  <span className="device-name">{device.name}</span>
                  <span className="device-bots">{device.bots.map((bot) => bot.name).join(', ')}</span>
                </div>
              ))}
            </div>
            {error ? <div className="dialog-error">{error}</div> : null}
            <div className="dialog-actions">
              <button type="button" className="ghost" onClick={props.onClose}>
                Close
              </button>
              <button type="button" className="danger" disabled={busy} onClick={() => void logout()}>
                Disconnect
              </button>
            </div>
          </>
        ) : (
          <>
            <label className="field">
              <span className="field-label">Pairing link or server address</span>
              <textarea
                rows={2}
                value={link}
                placeholder="https://203.0.113.10:8443/pair#fp=…&code=…"
                onChange={(event) => setLink(event.target.value)}
              />
            </label>

            <label className="field">
              <span className="field-label">Login secret</span>
              <input
                type="password"
                value={loginSecret}
                onChange={(event) => setLoginSecret(event.target.value)}
                placeholder="first device only, printed by the relay"
              />
            </label>

            <label className="field">
              <span className="field-label">Device name</span>
              <input value={deviceName} onChange={(event) => setDeviceName(event.target.value)} placeholder="this computer" />
            </label>

            {error ? <div className="dialog-error">{error}</div> : null}

            <div className="dialog-actions">
              <button type="button" className="ghost" onClick={props.onClose}>
                Cancel
              </button>
              <button type="button" className="primary" disabled={busy || link.trim() === ''} onClick={() => void pair()}>
                {busy ? 'Connecting…' : 'Connect'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function describeRelay(relay: RelayStatusPayload | null): { title: string; subtitle: string; tone: string } {
  if (!relay) return { title: 'Connect devices', subtitle: '', tone: 'offline' }
  switch (relay.state) {
    case 'online':
      return { title: relay.deviceName ?? 'Connected', subtitle: 'sync online', tone: 'online' }
    case 'connecting':
      return { title: 'Connecting…', subtitle: relay.origin ?? '', tone: 'pending' }
    case 'offline':
      return { title: 'Offline', subtitle: 'reconnecting', tone: 'offline' }
    case 'error':
      return { title: 'Connection error', subtitle: relay.error ?? '', tone: 'error' }
    default:
      return { title: 'Connect devices', subtitle: '', tone: 'offline' }
  }
}
