import { readFileSync } from 'node:fs'
import { createRelayServer } from './http.ts'
import { createRealtimeHub } from './realtime.ts'
import { MemoryStore } from './memory.ts'
import { loadConfig } from './config.ts'
import { RelayStore } from './store.ts'
import { hashSecret, newAccountId, randomToken, readFingerprint } from './auth.ts'

const config = loadConfig()
const store = new RelayStore(config.dataDir)

let account = store.account()
if (!account) {
  const secret = randomToken(24)
  account = store.createAccount(newAccountId(), hashSecret(secret), Date.now())
  console.log(`account created: ${account.id}`)
  console.log(`login secret (shown once): ${secret}`)
}

const fingerprint = readFingerprint(config.caPath ?? config.certPath)
const caPem = config.caPath ? readFileSync(config.caPath, 'utf8') : null
const memory = new MemoryStore(store.database)
const hub = createRealtimeHub(store, () => memory.version())
const relay = createRelayServer({
  config,
  store,
  fingerprint,
  entries: config.entries,
  caPem,
  memory,
  onMemoryChanged: (version, sourceDeviceId) => hub.broadcastMemoryChanged(version, sourceDeviceId),
  onDeviceRevoked: (id) => hub.disconnectDevice(id),
  onDeviceRenamed: (id, name) => hub.notifyDeviceRenamed(id, name),
  online: (deviceId) => hub.isOnline(deviceId),
  botsOf: (deviceId) => hub.botsOf(deviceId),
  capabilitiesOf: (deviceId) => hub.capabilitiesOf(deviceId),
})
hub.attach(relay.server)
const address = await relay.listen()
console.log(`superpi-relay ${config.version} listening on ${address.host}:${address.port}`)
if (!(config.certPath && config.keyPath)) {
  console.log('no TLS certificate/key pair configured; listening over plain HTTP')
}

let shuttingDown = false
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return
  shuttingDown = true
  console.log(`shutting down on ${signal}`)
  await hub.close()
  relay.server.closeIdleConnections?.()
  const closeTimer = setTimeout(() => {
    relay.server.closeAllConnections?.()
  }, 500)
  closeTimer.unref?.()
  await relay.close()
  clearTimeout(closeTimer)
  store.close()
  process.exit(0)
}

process.on('SIGINT', () => void shutdown('SIGINT'))
process.on('SIGTERM', () => void shutdown('SIGTERM'))
