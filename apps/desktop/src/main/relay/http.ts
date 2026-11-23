import { request as httpRequest, type RequestOptions } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { X509Certificate } from 'node:crypto'

export interface RelayHttpRequest {
  url: string
  method?: string
  body?: unknown
  token?: string
  caPem?: string | null
}

export interface RelayHttpResponse {
  status: number
  body: string
}

export function fingerprintOfPem(pem: string): string {
  const certificate = new X509Certificate(pem)
  return certificate.fingerprint256.replaceAll(':', '').toLowerCase()
}

export async function fetchCaPem(origin: string): Promise<string> {
  const response = await relayRequest({ url: `${origin}/v1/ca`, caPem: null, allowInsecure: true })
  if (response.status !== 200) throw new Error(`relay returned ${response.status} for the certificate authority`)
  return response.body
}

export async function relayRequest(
  request: RelayHttpRequest & { allowInsecure?: boolean },
): Promise<RelayHttpResponse> {
  const url = new URL(request.url)
  const isHttps = url.protocol === 'https:'
  const payload = request.body === undefined ? null : JSON.stringify(request.body)
  const headers: Record<string, string | number> = {}
  if (payload !== null) {
    headers['content-type'] = 'application/json'
    headers['content-length'] = Buffer.byteLength(payload)
  }
  if (request.token) headers.authorization = `Bearer ${request.token}`

  const options: RequestOptions = {
    method: request.method ?? (payload === null ? 'GET' : 'POST'),
    headers,
  }
  if (isHttps) {
    const tls = options as RequestOptions & { ca?: string[]; rejectUnauthorized?: boolean }
    if (request.caPem) tls.ca = [request.caPem]
    if (request.allowInsecure) tls.rejectUnauthorized = false
  }

  return new Promise<RelayHttpResponse>((resolve, reject) => {
    const send = isHttps ? httpsRequest : httpRequest
    const client = send(url, options, (response) => {
      const chunks: Buffer[] = []
      response.on('data', (chunk: Buffer) => chunks.push(chunk))
      response.on('end', () => {
        resolve({ status: response.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') })
      })
      response.on('error', reject)
    })
    client.on('error', reject)
    if (payload !== null) client.write(payload)
    client.end()
  })
}
