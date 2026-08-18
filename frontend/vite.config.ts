import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Minimal ambient declaration: the repo has no @types/node, and the config only
// needs env access, not the whole Node API surface.
declare const process: { env: Record<string, string | undefined> }

const apiTarget = process.env.FPL_API_PROXY ?? 'http://localhost:8080'

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
