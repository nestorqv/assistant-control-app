import basicSsl from '@vitejs/plugin-basic-ssl'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { VitePWA, type VitePWAOptions } from 'vite-plugin-pwa'
import { defineConfig } from 'vitest/config'
import { DEV_HEADERS, SECURITY_HEADERS } from './securityHeaders.ts'

const apiProxy = {
  '/api': { target: 'http://localhost:8080', changeOrigin: true },
}

// Exported so pwaConfig.test.ts can assert the service worker policy (prompt updates, no runtime caching).
export const pwaOptions: Partial<VitePWAOptions> = {
  // 'prompt': an automatic reload could interrupt a selfie capture.
  registerType: 'prompt',
  injectRegister: false,
  includeAssets: ['favicon.ico', 'apple-touch-icon-180x180.png', 'logo.svg'],
  manifest: {
    name: 'Control de Asistencia',
    short_name: 'Asistencia',
    description: 'Marcas de entrada y salida del personal',
    lang: 'es-CR',
    theme_color: '#0F5F56',
    background_color: '#F2F2F0',
    display: 'standalone',
    start_url: '/',
    scope: '/',
    icons: [
      { src: 'pwa-64x64.png', sizes: '64x64', type: 'image/png' },
      { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
      { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  },
  workbox: {
    globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}'],
    navigateFallback: 'index.html',
    // The service worker must never answer API calls with index.html.
    navigateFallbackDenylist: [/^\/api\//],
    cleanupOutdatedCaches: true,
    // sw.js must not call importScripts: it is served under Trusted Types (a TrustedScriptURL sink).
    inlineWorkboxRuntime: true,
    // No runtimeCaching: API responses and selfies are personal data and are never cached.
  },
  devOptions: { enabled: false },
}

// https://vite.dev/config/
// `--mode phone` serves HTTPS on the LAN (self-signed) so the phone's camera and GPS work.
export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    ...(mode === 'phone' ? [basicSsl()] : []),
    VitePWA(pwaOptions),
  ],
  build: { assetsInlineLimit: 0 },
  // The dev server has no CSP: React Refresh injects an inline script and Vite injects <style> tags.
  server: { port: 5173, strictPort: true, proxy: apiProxy, headers: DEV_HEADERS },
  preview: { proxy: apiProxy, headers: SECURITY_HEADERS },
  test: {
    // The PWA virtual module only exists in real builds.
    alias: { 'virtual:pwa-register/react': resolve(process.cwd(), 'src/test/pwaRegisterStub.ts') },
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
}))
