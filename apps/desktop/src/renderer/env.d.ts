import type { SuperPiApi } from '../preload'

declare global {
  interface Window {
    superpi: SuperPiApi
  }
}

export {}
