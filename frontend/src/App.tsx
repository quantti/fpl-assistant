import { Loader } from '@mantine/core'
import ChatView from './components/ChatView/ChatView'
import SignIn from './components/SignIn/SignIn'
import { useSession } from './auth/useSession'
import { useKeyboardAwareViewport } from './hooks/useKeyboardAwareViewport'
import { useRoute } from './router'

export default function App() {
  // Above both ChatView and SignIn: the sign-in form has the same iOS keyboard over its
  // password field as the chat has over its composer.
  useKeyboardAwareViewport()
  const { session, loading } = useSession()
  const route = useRoute()

  // Nothing rendered until the stored session has been read: showing the sign-in screen first
  // and replacing it a moment later reads as being signed out on every reload.
  if (loading) {
    return (
      <div style={{
        display: 'grid',
        placeItems: 'center',
        minHeight: '100dvh',
        // Every other surface paints these. Left off, a dark-mode reload flashes a white
        // viewport before ChatView mounts — the "reads as broken on reload" this gate exists
        // to prevent.
        background: 'var(--paper)',
        color: 'var(--ink)',
      }}>
        <Loader />
      </div>
    )
  }

  // A different account can arrive from another tab without an intervening sign-out. Reset
  // account-owned state and abort its pending loads, but keep it on this user's token refresh.
  return session ? <ChatView key={session.user.id} route={route} /> : <SignIn />
}
