import { useEffect } from 'react';
import { Loader } from '@mantine/core';
import type { Session } from '@supabase/supabase-js';
import ChatView from './components/ChatView/ChatView';
import LandingPage from './components/LandingPage/LandingPage';
import PrivacyPage from './components/PrivacyPage/PrivacyPage';
import SignIn, { type AuthMode } from './components/SignIn/SignIn';
import StorageNotice from './components/StorageNotice/StorageNotice';
import { useSession } from './auth/useSession';
import { useKeyboardAwareViewport } from './hooks/useKeyboardAwareViewport';
import { navigate, type Route, useRoute } from './router';
import { ShortlistProvider } from './shortlist/ShortlistContext';
import classes from './App.module.css';

export default function App() {
  // Above both ChatView and SignIn: the sign-in form has the same iOS keyboard over its
  // password field as the chat has over its composer.
  useKeyboardAwareViewport();
  const { session, loading } = useSession();
  const route = useRoute();

  // /login and /register are entry points, not application pages. A completed sign-in (or an
  // already-restored session) goes to chat; a protected deep link stays untouched and opens as
  // soon as its session is available.
  useEffect(() => {
    if (!loading && session && (route === '/login' || route === '/register')) {
      navigate('/app');
    }
  }, [loading, route, session]);

  return (
    <div className={classes.app}>
      <div className={classes.page}>
        <CurrentPage route={route} session={session} loading={loading} />
      </div>
      <StorageNotice />
    </div>
  );
}

interface CurrentPageProps {
  route: Route;
  session: Session | null;
  loading: boolean;
}

function CurrentPage({ route, session, loading }: Readonly<CurrentPageProps>) {
  // The homepage and privacy notice are public even while auth-js is reading storage. Keeping the
  // homepage visible also lets Supabase consume a verification callback there before showing the
  // signed-in app link.
  if (route === '/') {
    return <LandingPage signedIn={session !== null} sessionLoading={loading} />;
  }
  if (route === '/privacy') {
    return <PrivacyPage />;
  }

  // Protected routes wait until the stored session has been read: showing the sign-in screen
  // first and replacing it a moment later reads as being signed out on every reload.
  if (loading || (session && (route === '/login' || route === '/register'))) {
    return (
      <div
        style={{
          display: 'grid',
          placeItems: 'center',
          minHeight: '100%',
          // Every other surface paints these. Left off, a dark-mode reload flashes a white
          // viewport before ChatView mounts — the "reads as broken on reload" this gate exists
          // to prevent.
          background: 'var(--paper)',
          color: 'var(--ink)',
        }}
      >
        <Loader />
      </div>
    );
  }

  // A different account can arrive from another tab without an intervening sign-out. Reset
  // account-owned state and abort its pending loads, but keep it on this user's token refresh.
  return session ? (
    <ShortlistProvider key={session.user.id}>
      <ChatView route={route} />
    </ShortlistProvider>
  ) : (
    <SignIn
      key={route === '/register' ? 'signUp' : 'signIn'}
      initialMode={route === '/register' ? 'signUp' : 'signIn'}
      onModeChange={
        route === '/login' || route === '/register'
          ? (mode: AuthMode) => navigate(mode === 'signUp' ? '/register' : '/login')
          : undefined
      }
    />
  );
}
