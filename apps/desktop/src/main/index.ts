import { app, BrowserWindow, ipcMain, Menu, nativeImage, screen, Tray } from 'electron'
import { existsSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { BotStore, type BotRecord } from './bot-store'
import { loadDesktopState, rememberSession, saveDesktopState, type DesktopState } from './desktop-state'
import { PiRpcClient, type PiRpcEvent } from './pi/rpc-client'
import { listSessions, resumableSession } from './pi/sessions'
import { RelayService } from './relay/service'
import { MIN_WINDOW_HEIGHT, MIN_WINDOW_WIDTH, closeAction, restoreWindowBounds, type CloseAction } from './shell'
import type { BotSessionPayload, BotSessionResult, PiStatePayload } from '../shared/ipc'

const homeDir = path.join(os.homedir(), '.superpi')
const botStore = new BotStore()
const windows = new Set<BrowserWindow>()
const clients = new Map<string, PiRpcClient>()
let shellState: DesktopState = loadDesktopState(homeDir)
let tray: Tray | null = null
let quitting = false
let saveTimer: ReturnType<typeof setTimeout> | null = null

const relay = new RelayService({
  homeDir,
  bots: () => botStore.list().map((bot) => ({ id: bot.id, name: bot.name })),
  broadcast: (status) => broadcast('relay:state', status),
  broadcastMemory: (summary) => broadcast('memory:state', summary),
  onRemoteEvent: (payload) => broadcast('remote:event', payload),
})

function broadcast(channel: string, payload: unknown): void {
  for (const window of windows) {
    if (!window.isDestroyed()) window.webContents.send(channel, payload)
  }
}

function resolveBot(botId: string | null): BotRecord {
  const bots = botStore.list()
  if (botId !== null) {
    const bot = bots.find((entry) => entry.id === botId)
    if (!bot) throw new Error(`unknown bot: ${botId}`)
    return bot
  }
  const remembered = shellState.lastBotId === null ? undefined : bots.find((bot) => bot.id === shellState.lastBotId)
  const bot = remembered ?? bots[0]
  if (!bot) throw new Error('no bots are configured')
  return bot
}

async function ensureClient(bot: BotRecord): Promise<PiRpcClient> {
  const existing = clients.get(bot.id)
  if (existing?.running) return existing
  const extension = path.join(__dirname, '../../extensions/superpi-memory.mjs')
  const args = ['--mode', 'rpc']
  const resume = resumableSession(shellState.lastSession[bot.id])
  if (resume) args.push('--session', resume)
  if (existsSync(extension)) args.push('-e', extension)
  const client = new PiRpcClient({
    command: process.env.SUPERPI_PI_BIN ?? 'pi',
    args,
    cwd: bot.cwd,
    env: { ...process.env, ...relay.memoryEnvironment() },
  })
  client.onEvent((event: PiRpcEvent) => {
    broadcast('pi:event', { botId: bot.id, event })
    if (event.type === 'agent_settled') void rememberClientSession(bot.id, client)
  })
  client.onExit((error) => {
    clients.delete(bot.id)
    if (error) {
      console.error(`pi exited for bot ${bot.id}: ${error.message}`)
      const stderr = client.getStderr().trim()
      if (stderr !== '') console.error(stderr.slice(-2000))
    } else {
      console.error(`pi exited cleanly for bot ${bot.id}: ${JSON.stringify(client.getLastExit())}`)
    }
    broadcast('pi:exit', { botId: bot.id, message: error?.message ?? null })
  })
  clients.set(bot.id, client)
  try {
    await client.start()
  } catch (failure) {
    clients.delete(bot.id)
    console.error(`pi failed to start for bot ${bot.id}: ${failure instanceof Error ? failure.message : String(failure)}`)
    throw failure
  }
  return client
}

async function rememberClientSession(botId: string, client: PiRpcClient): Promise<void> {
  try {
    const raw = await client.getState()
    shellState = rememberSession(shellState, botId, resumableSession(piStatePayload(raw).sessionFile))
    scheduleSaveState()
  } catch {
    return
  }
}

function piStatePayload(raw: unknown): PiStatePayload {
  const state = isRecord(raw) ? raw : {}
  const model = isRecord(state.model) ? state.model : null
  return {
    model:
      model !== null && typeof model.provider === 'string' && typeof model.id === 'string'
        ? {
            provider: model.provider,
            id: model.id,
            name: typeof model.name === 'string' && model.name !== '' ? model.name : model.id,
          }
        : null,
    thinkingLevel: typeof state.thinkingLevel === 'string' ? state.thinkingLevel : 'off',
    sessionFile: typeof state.sessionFile === 'string' ? state.sessionFile : null,
    sessionName: typeof state.sessionName === 'string' && state.sessionName !== '' ? state.sessionName : null,
  }
}

async function sessionSnapshot(bot: BotRecord, client: PiRpcClient): Promise<BotSessionPayload> {
  const raw = await client.getState()
  const messages = await client.getMessages<{ messages?: unknown[] }>()
  const payload = piStatePayload(raw)
  shellState = rememberSession(shellState, bot.id, resumableSession(payload.sessionFile))
  shellState = { ...shellState, lastBotId: bot.id }
  scheduleSaveState()
  return {
    botId: bot.id,
    state: payload,
    messages: messages.messages ?? [],
    sessions: listSessions(bot.cwd),
  }
}

async function openBot(requestedBotId: string | null): Promise<BotSessionPayload> {
  const bot = resolveBot(requestedBotId)
  const client = await ensureClient(bot)
  return sessionSnapshot(bot, client)
}

async function newBotSession(botId: string): Promise<BotSessionResult> {
  const bot = resolveBot(botId)
  const client = await ensureClient(bot)
  const result = await client.newSession<{ cancelled?: boolean }>()
  const snapshot = await sessionSnapshot(bot, client)
  if (result.cancelled === true) {
    return { ...snapshot, cancelled: true }
  }
  shellState = rememberSession(shellState, bot.id, null)
  scheduleSaveState()
  return { ...snapshot, cancelled: false }
}

async function switchBotSession(botId: string, sessionPath: string): Promise<BotSessionResult> {
  const bot = resolveBot(botId)
  if (!path.isAbsolute(sessionPath) || !sessionPath.endsWith('.jsonl') || !existsSync(sessionPath)) {
    throw new Error('That session is no longer available')
  }
  const client = await ensureClient(bot)
  const result = await client.switchSession<{ cancelled?: boolean }>(sessionPath)
  const snapshot = await sessionSnapshot(bot, client)
  return { ...snapshot, cancelled: result.cancelled === true }
}

function primaryWindow(): BrowserWindow | null {
  for (const window of windows) {
    if (!window.isDestroyed()) return window
  }
  return null
}

function rememberWindowBounds(): void {
  const window = primaryWindow()
  if (!window) return
  shellState = {
    ...shellState,
    window: { bounds: window.getNormalBounds(), maximized: window.isMaximized() },
  }
  scheduleSaveState()
}

function scheduleSaveState(): void {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    saveTimer = null
    saveDesktopState(shellState, homeDir)
  }, 400)
}

function flushState(): void {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  saveDesktopState(shellState, homeDir)
}

function showWindow(): void {
  const window = primaryWindow()
  if (!window) {
    createWindow()
    return
  }
  if (window.isMinimized()) window.restore()
  window.show()
  window.focus()
}

function toggleWindow(): void {
  const window = primaryWindow()
  if (!window) {
    createWindow()
    return
  }
  if (window.isVisible() && !window.isMinimized()) {
    window.hide()
    return
  }
  showWindow()
}

function createWindow(): BrowserWindow {
  const bounds = restoreWindowBounds(
    shellState.window?.bounds ?? null,
    screen.getAllDisplays().map((display) => display.workArea),
  )
  const window = new BrowserWindow({
    ...bounds,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    backgroundColor: '#121316',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  windows.add(window)
  if (shellState.window?.maximized) window.maximize()
  window.once('ready-to-show', () => {
    if (!quitting) window.show()
  })
  window.on('resize', rememberWindowBounds)
  window.on('move', rememberWindowBounds)
  window.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown' || !(input.control || input.meta)) return
    if (input.key.toLowerCase() !== 'w') return
    event.preventDefault()
    window.close()
  })
  window.on('close', (event) => {
    const action: CloseAction = closeAction(quitting, tray !== null)
    if (action === 'close') {
      rememberWindowBounds()
      return
    }
    event.preventDefault()
    rememberWindowBounds()
    flushState()
    if (action === 'hide') window.hide()
    else window.minimize()
  })
  window.on('closed', () => windows.delete(window))
  void window.loadFile(path.join(__dirname, '../renderer/index.html'))
  return window
}

function createTray(): void {
  if (tray) return
  const icon = nativeImage.createFromPath(path.join(__dirname, '../../assets/tray.png'))
  try {
    const created = new Tray(icon)
    created.setToolTip('SuperPI')
    created.setContextMenu(
      Menu.buildFromTemplate([
        { label: 'Show SuperPI', click: () => showWindow() },
        { type: 'separator' },
        { label: 'Quit SuperPI', click: () => requestQuit() },
      ]),
    )
    created.on('click', () => toggleWindow())
    tray = created
  } catch (failure) {
    console.error(`tray unavailable: ${failure instanceof Error ? failure.message : String(failure)}`)
  }
}

async function shutdown(): Promise<void> {
  rememberWindowBounds()
  flushState()
  if (tray) {
    tray.destroy()
    tray = null
  }
  relay.dispose()
  await Promise.all([...clients.values()].map((client) => client.stop()))
  clients.clear()
}

function requestQuit(): void {
  if (quitting) return
  quitting = true
  void shutdown().finally(() => app.quit())
}

function registerIpc(): void {
  ipcMain.handle('bots:list', () => botStore.list())
  ipcMain.handle('bot:open', (_event, botId: string | null) => openBot(botId))
  ipcMain.handle('bot:sessions', (_event, botId: string) => listSessions(resolveBot(botId).cwd))
  ipcMain.handle('bot:state', async (_event, botId: string) => {
    const bot = resolveBot(botId)
    const client = await ensureClient(bot)
    return piStatePayload(await client.getState())
  })
  ipcMain.handle('bot:new-session', (_event, botId: string) => newBotSession(botId))
  ipcMain.handle('bot:switch-session', (_event, payload: { botId: string; sessionPath: string }) =>
    switchBotSession(payload.botId, payload.sessionPath),
  )

  ipcMain.handle('bot:prompt', async (_event, payload: { botId: string; text: string }) => {
    const bot = resolveBot(payload.botId)
    const client = await ensureClient(bot)
    return client.prompt(payload.text)
  })

  ipcMain.handle('bot:abort', async (_event, botId: string) => {
    const client = clients.get(botId)
    if (!client) return null
    return client.abort()
  })

  ipcMain.handle('relay:status', () => relay.status())
  ipcMain.handle('relay:pair', (_event, input: { link: string; loginSecret?: string; deviceName?: string }) =>
    relay.pair(input),
  )
  ipcMain.handle('relay:logout', () => relay.logout())
  ipcMain.handle('remote:start', (_event, input: { deviceId: string; botId: string }) => relay.startRemote(input))
  ipcMain.handle('remote:prompt', (_event, input: { sessionId: string; text: string }) => {
    relay.promptRemote(input.sessionId, input.text)
  })
  ipcMain.handle('remote:abort', (_event, sessionId: string) => {
    relay.abortRemote(sessionId)
  })
  ipcMain.handle('remote:close', (_event, sessionId: string) => {
    relay.closeRemote(sessionId)
  })
  ipcMain.handle('memory:status', () => relay.memoryStatus())

  ipcMain.handle('bots:save', (_event, bots: BotRecord[]) => botStore.save(bots))
  ipcMain.handle('bots:add', (_event, bot: BotRecord) => botStore.add(bot))
  ipcMain.handle('bots:remove', (_event, id: string) => botStore.remove(id))
}

function start(): void {
  registerIpc()
  createTray()
  createWindow()
  app.on('activate', () => showWindow())
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })
  app.on('before-quit', (event) => {
    if (quitting) return
    event.preventDefault()
    requestQuit()
  })
  app.on('will-quit', () => {
    if (tray) {
      tray.destroy()
      tray = null
    }
  })
  process.on('SIGINT', requestQuit)
  process.on('SIGTERM', requestQuit)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => showWindow())
  void app.whenReady().then(start)
}
