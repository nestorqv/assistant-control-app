// Single source of truth for the response headers of the built app.
// `vite preview` enforces SECURITY_HEADERS now; Caddy (Stage 5) copies these strings and adds HSTS.
// Plain values and no imports on purpose, so the proxy config can be written from this file.

// Why each directive:
// - img-src blob:        object URL of the selfie review image
// - no data: anywhere    build.assetsInlineLimit is 0, so nothing is inlined as a data URI
// - style-src 'self'     React style props go through the CSSOM, which this directive does not block
// - Trusted Types        navigator.serviceWorker.register() is a TrustedScriptURL sink;
//                        src/security/trustedTypes.ts installs the only allowed default policy
export const PROD_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' blob:",
  "connect-src 'self'",
  "worker-src 'self'",
  "manifest-src 'self'",
  "font-src 'self'",
  "media-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  'upgrade-insecure-requests',
  "require-trusted-types-for 'script'",
  'trusted-types default',
].join('; ')

// Everything except the CSP. camera/geolocation are the only features the app uses.
export const DEV_HEADERS: Record<string, string> = {
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'camera=(self), geolocation=(self), microphone=(), payment=(), usb=()',
  'X-Content-Type-Options': 'nosniff',
  'Cross-Origin-Opener-Policy': 'same-origin',
}

export const SECURITY_HEADERS: Record<string, string> = {
  'Content-Security-Policy': PROD_CSP,
  ...DEV_HEADERS,
}
