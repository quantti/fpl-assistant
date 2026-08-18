import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Minimal ambient declaration: the repo has no @types/node, and the config only
// needs env access, not the whole Node API surface.
declare const process: { env: Record<string, string | undefined> }

// `||` rather than `??`: an empty-string FPL_API_PROXY is an invalid target and
// should fall back to the default, not crash the dev server's proxy.
const apiTarget = process.env.FPL_API_PROXY || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
})
