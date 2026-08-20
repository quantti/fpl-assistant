import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Minimal ambient declaration: the repo has no @types/node, and the config only
// needs env access, not the whole Node API surface.
declare const process: { env: Record<string, string | undefined>; cwd(): string }

// `||` rather than `??`: an empty-string FPL_API_PROXY is an invalid target and
// should fall back to the default, not crash the dev server's proxy.
const apiTarget = process.env.FPL_API_PROXY || 'http://localhost:8080'

export default defineConfig(({ mode }) => {
  // The Supabase publishable key is not in the source tree, so a build without it produces a
  // bundle that throws on first paint — a blank page nobody sees until it is deployed. Failing
  // here makes it a build error instead, where the message can say what to do.
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  if (!env.VITE_SUPABASE_ANON_KEY) {
    throw new Error(
      'VITE_SUPABASE_ANON_KEY is not set. Copy frontend/.env.example to frontend/.env.local for'
      + ' local work; CI and the Docker build pass it in.',
    )
  }

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
