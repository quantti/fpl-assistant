import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Session } from '@supabase/supabase-js'
import App from './App'
import { useSession } from './auth/useSession'

vi.mock('./auth/useSession', () => ({ useSession: vi.fn() }))
vi.mock('./hooks/useKeyboardAwareViewport', () => ({ useKeyboardAwareViewport: vi.fn() }))
vi.mock('./router', () => ({ useRoute: () => '/my-team' }))
vi.mock('./components/ChatView/ChatView', () => ({ default: () => null }))
vi.mock('./components/SignIn/SignIn', () => ({ default: () => null }))

const sessionMock = vi.mocked(useSession)

beforeEach(() => {
  sessionMock.mockReset()
})

// Tests the key used by React's reconciliation, not mounted effects or browser navigation.
// Cross-tab session replacement and abort behavior are also checked in the browser.
describe('signed-in state ownership', () => {
  it('changes the shell key when the account changes without an intervening sign-out', () => {
    sessionMock.mockReturnValue({ session: session('user-a', 'token-a'), loading: false })
    const firstKey = App().key

    sessionMock.mockReturnValue({ session: session('user-b', 'token-b'), loading: false })
    const nextKey = App().key

    expect(firstKey).toBe('user-a')
    expect(nextKey).toBe('user-b')
  })

  it('keeps the shell key on a token refresh for the same account', () => {
    sessionMock.mockReturnValue({ session: session('user-a', 'old-token'), loading: false })
    const firstKey = App().key

    sessionMock.mockReturnValue({ session: session('user-a', 'new-token'), loading: false })

    expect(App().key).toBe(firstKey)
  })
})

function session(id: string, token: string): Session {
  return {
    access_token: token,
    refresh_token: 'fixture-refresh',
    expires_in: 3600,
    token_type: 'bearer',
    user: { id, aud: 'authenticated', app_metadata: {}, user_metadata: {}, created_at: '' },
  }
}
