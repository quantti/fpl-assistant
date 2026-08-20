import { Loader } from '@mantine/core'
import ChatView from './components/ChatView/ChatView'
import SignIn from './components/SignIn/SignIn'
import { useSession } from './auth/useSession'

export default function App() {
  const { session, loading } = useSession()

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

  return session ? <ChatView /> : <SignIn />
}
