import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Minimal ambient declaration: the repo has no @types/node, and the config only
// needs env access, not the whole Node API surface.
declare const process: { env: Record<string, string | undefined>; cwd(): string };

// `||` rather than `??`: an empty-string FPL_API_PROXY is an invalid target and
// should fall back to the default, not crash the dev server's proxy.
//
// `npm run dev:prod` points it at the deployed backend, which is the way to work on the frontend
// without running Spring Boot locally. The default stays local so a running backend is the one
// being tested — a default of production would silently ignore local backend changes.
const apiTarget = process.env.FPL_API_PROXY || 'http://localhost:8080';

export default defineConfig(({ mode }) => {
  // The Supabase publishable key is not in the source tree, so a build without it produces a
  // bundle that throws on first paint — a blank page nobody sees until it is deployed. Failing
  // here makes it a build error instead, where the message can say what to do.
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  if (!env.VITE_SUPABASE_URL) {
    throw new Error('VITE_SUPABASE_URL is not set. Configure your own Supabase project URL.');
  }
  if (!env.VITE_SUPABASE_ANON_KEY) {
    throw new Error(
      'VITE_SUPABASE_ANON_KEY is not set. Copy frontend/.env.example to frontend/.env.local for' +
        ' local work; CI and the Docker build pass it in.',
    );
  }

  const email = env.VITE_PRIVACY_CONTACT_EMAIL ?? '';
  const parts = email.split('@', 3);
  const isValidEmail =
    parts.length === 2 && Boolean(parts[0]) && parts[1].includes('.') && !/\s/.test(email);
  if (mode === 'production' && (!isValidEmail || !env.VITE_PRIVACY_CONTROLLER_NAME?.trim())) {
    throw new Error(
      'Set VITE_PRIVACY_CONTROLLER_NAME and a valid VITE_PRIVACY_CONTACT_EMAIL before building.',
    );
  }

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
          configure(proxy) {
            // Vite prints its own URL but not what /api resolves to, so a dev server with no
            // backend behind it looks identical to a working one until a request fails — with a
            // "something went wrong" in the UI that names neither the port nor the cause. This
            // and the error below are the two lines that would have made that obvious.
            console.log(`  \u279c  API proxy: ${apiTarget}`);
            // Cast, and typed inline: ProxyServer inherits `on` from Node's EventEmitter, and
            // this repo has no @types/node, so neither that method nor NodeJS.ErrnoException
            // resolves here.
            const emitter = proxy as unknown as {
              on(event: 'error', listener: (err: Error & { code?: string }) => void): void;
            };
            emitter.on('error', (err) => {
              if (err.code === 'ECONNREFUSED') {
                console.error(
                  `  \u2717  No backend at ${apiTarget}. Start it (backend/./gradlew bootRun)` +
                    ' or run `npm run dev:prod` to use the deployed one.',
                );
              }
            });
          },
        },
      },
    },
  };
});
