import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { PROTOCOL_VERSION, type ApiErrorBody, type HealthResponse } from '@superpi/protocol'
import type { RelayConfig } from './config.ts'

export interface RelayServer {
  server: Server
  listen(): Promise<{ host: string; port: number }>
  close(): Promise<void>
}

export function createRelayServer(config: RelayConfig, fingerprint: string | null): RelayServer {
  const server = createServer((request, response) => {
    handle(config, fingerprint, request, response)
  })

  return {
    server,
    listen() {
      return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(config.port, config.host, () => {
          server.removeListener('error', reject)
          const address = server.address()
          if (address === null || typeof address === 'string') {
            resolve({ host: config.host, port: config.port })
            return
          }
          resolve({ host: address.address, port: address.port })
        })
      })
    },
    close() {
      return new Promise((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()))
      })
    },
  }
}

function handle(config: RelayConfig, fingerprint: string | null, request: IncomingMessage, response: ServerResponse): void {
  const url = new URL(request.url ?? '/', 'http://relay.local')
  if (request.method === 'GET' && url.pathname === '/v1/health') {
    const body: HealthResponse = {
      ok: true,
      version: config.version,
      protocolVersion: PROTOCOL_VERSION,
      fingerprint,
    }
    sendJson(response, 200, body)
    return
  }
  const body: ApiErrorBody = { code: 'not_found', message: `no route for ${request.method ?? 'GET'} ${url.pathname}` }
  sendJson(response, 404, body)
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  })
  response.end(payload)
}
