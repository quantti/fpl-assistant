import { defineConfig } from 'vitest/config';

// Kept apart from vite.config.ts on purpose: that file refuses to load without
// VITE_SUPABASE_ANON_KEY, and a unit test of a pure function has no business needing one.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
