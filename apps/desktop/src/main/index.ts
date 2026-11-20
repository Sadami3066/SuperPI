import { app, BrowserWindow, ipcMain } from 'electron'
import os from 'node:os'
import path from 'node:path'
import { BotStore, type BotRecord } from './bot-store'
import { PiRpcClient, type PiRpcEvent } from './pi/rpc-client'
import { RelayService } from './relay/service'

const botStore = new BotStore()
const windows = new Set<BrowserWindow>()
const relay = new RelayService({
  homeDir: path.join(os.homedir(), '.superpi'),
  bots: () => botStore.list().map((bot) => ({ id: bot.id, name: bot.name })),
  broadcast: (status) => broadcast('relay:state', status),
  onRemoteEvent: (payload) => broadcast('remote:event', payload),
})
const clients = new Map<string, PiRpcClient>()

function broadcast(channel: string, payload: unknown): void {
  for (const window of windows) {
    if (!window.isDestroyed()) window.webContents.send(channel, payload)
  }
}

function botById(id: string): BotRecord | null {
  return botStore.list().find((bot) => bot.id === id) ?? null
}

async function ensureClient(bot: BotRecord): Promise<PiRpcClient> {
  const existing = clients.get(bot.id)
  if (existing?.running) return existing
  const client = new PiRpcClient({
    command: process.env.SUPERPI_PI_BIN ?? 'pi',
    args: ['--mode', 'rpc'],
    cwd: bot.cwd,
  })
  client.onEvent((event: PiRpcEvent) => broadcast('pi:event', { botId: bot.id, event }))
  client.onExit((error) => {
    clients.delete(bot.id)
    broadcast('pi:exit', { botId: bot.id, message: error?.message ?? null })
  })
  clients.set(bot.id, client)
  await client.start()
  return client
}

async function stopClient(botId: string): Promise<void> {
  const client = clients.get(botId)
  if (!client) return
  clients.delete(botId)
  await client.stop()
}

function createWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 880,
    minHeight: 560,
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
  window.once('ready-to-show', () => window.show())
  window.on('closed', () => windows.delete(window))
  void window.loadFile(path.join(__dirname, '../renderer/index.html'))
  return window
}

function registerIpc(): void {
  ipcMain.handle('bots:list', () => botStore.list())

  ipcMain.handle('bot:state', async (_event, botId: string) => {
    const bot = botById(botId)
    if (!bot) throw new Error(`unknown bot: ${botId}`)
    const client = await ensureClient(bot)
    return client.getState()
  })

  ipcMain.handle('bot:prompt', async (_event, payload: { botId: string; text: string }) => {
    const bot = botById(payload.botId)
    if (!bot) throw new Error(`unknown bot: ${payload.botId}`)
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

  ipcMain.handle('bots:save', (_event, bots: BotRecord[]) => botStore.save(bots))
  ipcMain.handle('bots:add', (_event, bot: BotRecord) => botStore.add(bot))
  ipcMain.handle('bots:remove', (_event, id: string) => botStore.remove(id))
}

app.whenReady().then(() => {
  registerIpc()
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  relay.dispose()
  for (const botId of clients.keys()) void stopClient(botId)
})
