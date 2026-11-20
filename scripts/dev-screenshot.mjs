#!/usr/bin/env node
import { writeFileSync } from 'node:fs'

const port = process.env.SUPERPI_DEBUG_PORT ?? '9333'
const output = process.argv[2] ?? 'shot.png'

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const page = targets.find((target) => target.type === 'page')
if (!page?.webSocketDebuggerUrl) {
  console.error('no page target found; is Electron running with --remote-debugging-port?')
  process.exit(1)
}

const socket = new WebSocket(page.webSocketDebuggerUrl)
let nextId = 0
const pending = new Map()

socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  const resolver = pending.get(message.id)
  if (resolver) {
    pending.delete(message.id)
    resolver(message)
  }
})

await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true })
  socket.addEventListener('error', reject, { once: true })
})

function send(method, params = {}) {
  const id = ++nextId
  return new Promise((resolve) => {
    pending.set(id, resolve)
    socket.send(JSON.stringify({ id, method, params }))
  })
}

await send('Page.enable')
const result = await send('Page.captureScreenshot', { format: 'png' })
if (!result.result?.data) {
  console.error('capture failed', JSON.stringify(result))
  process.exit(1)
}
writeFileSync(output, Buffer.from(result.result.data, 'base64'))
console.log(`screenshot written to ${output}`)
socket.close()
