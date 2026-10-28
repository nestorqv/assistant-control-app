import { logout } from '../api/auth'
import { getToken, signOut } from './session'

/**
 * Best-effort server revocation (3 s), then the local wipe. A 401 or a network error is ignored:
 * offline, the token stays valid on the server until it expires, but it is gone from this device.
 */
export async function performLogout() {
  if (getToken()) {
    try {
      await logout(3_000)
    } catch {
      // Ignored on purpose.
    }
  }
  return signOut()
}
