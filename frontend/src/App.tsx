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
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100dvh' }}>
        <Loader />
      </div>
    )
  }

  return session ? <ChatView /> : <SignIn />
}
