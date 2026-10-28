// Stands in for the virtual module that vite-plugin-pwa generates only in real builds.
export function useRegisterSW() {
  return {
    needRefresh: [false, () => undefined] as const,
    offlineReady: [false, () => undefined] as const,
    updateServiceWorker: async () => undefined,
  }
}
