import { readFileSync } from 'node:fs'
import path from 'node:path'
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

const fingerprint = readFingerprint(config.certPath)
const caPem = config.caPath ? readFileSync(config.caPath, 'utf8') : null
const hub = createRealtimeHub(store)
const memory = new MemoryStore(path.join(config.dataDir, 'memory'), store.database)
const relay = createRelayServer({
  config,
  store,
  fingerprint,
  entries: config.entries,
  caPem,
  memory,
  onMemoryChanged: (version) => hub.broadcastMemoryChanged(version),
  online: (deviceId) => hub.isOnline(deviceId),
  botsOf: (deviceId) => hub.botsOf(deviceId),
})
hub.attach(relay.server)
const address = await relay.listen()
console.log(`superpi-relay ${config.version} listening on ${address.host}:${address.port}`)
if (!config.certPath) console.log('no certificate configured; clients must pair over plain HTTP for now')

let shuttingDown = false
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return
  shuttingDown = true
  console.log(`shutting down on ${signal}`)
  await hub.close()
  await relay.close()
  store.close()
  process.exit(0)
}

process.on('SIGINT', () => void shutdown('SIGINT'))
process.on('SIGTERM', () => void shutdown('SIGTERM'))
