import { createRelayServer } from './http.ts'
import { loadConfig } from './config.ts'

const config = loadConfig()
const relay = createRelayServer(config, null)

const address = await relay.listen()
console.log(`superpi-relay ${config.version} listening on ${address.host}:${address.port}`)

let shuttingDown = false
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return
  shuttingDown = true
  console.log(`shutting down on ${signal}`)
  await relay.close()
  process.exit(0)
}

process.on('SIGINT', () => void shutdown('SIGINT'))
process.on('SIGTERM', () => void shutdown('SIGTERM'))
