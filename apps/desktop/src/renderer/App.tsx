import { useCallback, useEffect, useRef, useState } from 'react'
import type { BotInfo, PiEventPayload } from '../preload'
import './styles.css'

type Entry =
  | { kind: 'user'; id: string; text: string }
  | { kind: 'assistant'; id: string; text: string }
  | { kind: 'tool'; id: string; name: string; status: 'running' | 'done' | 'error'; detail: string }
  | { kind: 'notice'; id: string; text: string; tone: 'error' | 'info' }

let nextEntryId = 0
function entryId(): string {
  nextEntryId += 1
  return `e${nextEntryId}`
}

export function App(): React.JSX.Element {
  const [bots, setBots] = useState<BotInfo[]>([])
  const [activeBotId, setActiveBotId] = useState<string | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const activeBotIdRef = useRef<string | null>(null)

  activeBotIdRef.current = activeBotId

  useEffect(() => {
    void window.superpi.listBots().then((list) => {
      setBots(list)
      setActiveBotId((current) => current ?? list[0]?.id ?? null)
    })
  }, [])

  useEffect(() => {
    return window.superpi.onEvent((payload: PiEventPayload) => {
      if (payload.botId !== activeBotIdRef.current) return
      applyEvent(payload.event)
    })
  }, [])

  useEffect(() => {
    return window.superpi.onExit((payload) => {
      if (payload.botId !== activeBotIdRef.current) return
      setRunning(false)
      setNotice(payload.message ? `pi exited: ${payload.message}` : 'pi session ended')
    })
  }, [])

  useEffect(() => {
    const element = scrollRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [entries])

  function applyEvent(event: { type: string; [key: string]: unknown }): void {
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
      case 'tool_call': {
        const id = typeof event.callId === 'string' ? event.callId : entryId()
        const name = typeof event.name === 'string' ? event.name : 'tool'
        setEntries((current) => [...current, { kind: 'tool', id, name, status: 'running', detail: '' }])
        break
      }
      case 'tool_result': {
        const callId = typeof event.callId === 'string' ? event.callId : ''
        const isError = event.isError === true
        const detail = typeof event.result === 'string' ? event.result : ''
        setEntries((current) =>
          current.map((entry) =>
            entry.kind === 'tool' && entry.id === callId
              ? { ...entry, status: isError ? 'error' : 'done', detail }
              : entry,
          ),
        )
        break
      }
      case 'agent_end':
        setRunning(false)
        break
      case 'agent_settled':
        setRunning(false)
        break
      case 'error': {
        const message = typeof event.message === 'string' ? event.message : 'pi reported an error'
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

  const send = useCallback(async () => {
    const text = input.trim()
    const botId = activeBotId
    if (text === '' || botId === null || running) return
    setEntries((current) => [...current, { kind: 'user', id: entryId(), text }])
    setInput('')
    setRunning(true)
    setNotice(null)
    try {
      await window.superpi.prompt(botId, text)
    } catch (error) {
      setRunning(false)
      setEntries((current) => [
        ...current,
        { kind: 'notice', id: entryId(), text: error instanceof Error ? error.message : String(error), tone: 'error' },
      ])
    }
  }, [activeBotId, input, running])

  const abort = useCallback(async () => {
    if (activeBotId === null) return
    try {
      await window.superpi.abort(activeBotId)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : String(error))
    }
  }, [activeBotId])

  const selectBot = useCallback((botId: string) => {
    if (botId === activeBotId) return
    setActiveBotId(botId)
    setEntries([])
    setRunning(false)
    setNotice(null)
  }, [activeBotId])

  const activeBot = bots.find((bot) => bot.id === activeBotId) ?? null

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
              className={`bot-row${bot.id === activeBotId ? ' active' : ''}`}
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
        <div className="sidebar-footer">
          <span className={`status-dot${running ? ' busy' : ''}`} />
          {running ? 'working' : 'idle'}
        </div>
      </aside>

      <main className="chat">
        <header className="chat-header">
          <div>
            <div className="chat-title">{activeBot?.name ?? 'No bot'}</div>
            <div className="chat-subtitle">{activeBot?.cwd ?? ''}</div>
          </div>
          {notice ? <div className="chat-notice">{notice}</div> : null}
        </header>

        <div className="messages" ref={scrollRef}>
          {entries.length === 0 ? (
            <div className="empty-state">
              <div className="empty-title">Ask PI anything</div>
              <div className="empty-hint">Sessions run in this bot's working directory.</div>
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
            placeholder="Message PI"
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
            <button type="button" className="send" disabled={input.trim() === '' || activeBotId === null} onClick={() => void send()}>
              Send
            </button>
          )}
        </footer>
      </main>
    </div>
  )
}

function appendAssistantDelta(entries: Entry[], delta: string): Entry[] {
  const last = entries[entries.length - 1]
  if (last && last.kind === 'assistant') {
    const updated: Entry = { ...last, text: last.text + delta }
    return [...entries.slice(0, -1), updated]
  }
  return [...entries, { kind: 'assistant', id: entryId(), text: delta }]
}
