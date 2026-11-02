import { useEffect, useRef, useState } from 'react';
import { Button, PasswordInput } from '@mantine/core';
import type { Session } from '@supabase/supabase-js';
import { supabase } from '../../auth/supabaseClient';
import { navigate } from '../../router';
import PasswordResetRequest from '../SignIn/PasswordResetRequest';
import classes from '../SignIn/SignIn.module.css';

export interface ResetPasswordProps {
  session: Session | null;
  initializationFailed: boolean;
}

export default function ResetPassword({
  session,
  initializationFailed,
}: Readonly<ResetPasswordProps>) {
  const [requesting, setRequesting] = useState(false);
  const [expired, setExpired] = useState(false);
  const [updated, setUpdated] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [signOutFailed, setSignOutFailed] = useState(false);
  const pendingSignOut = useRef(false);

  async function signOut() {
    if (pendingSignOut.current) return;
    pendingSignOut.current = true;
    setSigningOut(true);
    setSignOutFailed(false);
    try {
      const { error } = await supabase.auth.signOut({ scope: 'local' });
      setSignOutFailed(Boolean(error));
    } catch {
      setSignOutFailed(true);
    } finally {
      pendingSignOut.current = false;
      setSigningOut(false);
    }
  }

  let content;
  if (updated) {
    let next = signingOut ? (
      <p role="status">Signing out…</p>
    ) : (
      <a href="/login">Back to sign in</a>
    );
    if (signOutFailed) {
      next = (
        <>
          <p className={classes.error} role="alert">
            We could not sign out of this browser. Your password change succeeded.
          </p>
          <Button
            onClick={() => {
              void signOut();
            }}
            loading={signingOut}
          >
            Try signing out again
          </Button>
        </>
      );
    }
    content = (
      <div className={classes.card}>
        <h1 className={classes.title}>Password updated</h1>
        <p className={classes.lede} role="status">
          Your password has been updated. Sign in with your new password.
        </p>
        {next}
      </div>
    );
  } else if (!session || initializationFailed || expired) {
    content = (
      <div className={classes.card}>
        <h1 className={classes.title}>Reset your password</h1>
        <p className={classes.lede} role="alert">
          This reset link is invalid or has expired. Request a new link to continue.
        </p>
        <Button onClick={() => setRequesting(true)}>Request a new reset link</Button>
      </div>
    );
  } else {
    content = (
      <PasswordForm
        key={session.user.id}
        onExpired={() => setExpired(true)}
        onUpdated={() => {
          setUpdated(true);
          void signOut();
        }}
      />
    );
  }

  return requesting ? (
    <PasswordResetRequest onBack={() => navigate('/login')} />
  ) : (
    <div className={classes.root}>{content}</div>
  );
}

function PasswordForm({
  onExpired,
  onUpdated,
}: Readonly<{ onExpired: () => void; onUpdated: () => void }>) {
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function submit(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || !event.currentTarget.reportValidity()) return;
    setError(null);
    if (password !== confirmation) {
      setError('The passwords do not match. Enter the same password in both fields.');
      return;
    }
    pending.current = true;
    setBusy(true);
    try {
      const { error: updateError } = await supabase.auth.updateUser({ password });
      // An account change unmounts this form; its late response must not sign out the new user.
      if (!mounted.current) return;
      if (!updateError) {
        setPassword('');
        setConfirmation('');
        onUpdated();
      } else if (
        updateError.status === 401 ||
        updateError.status === 403 ||
        updateError.name === 'AuthSessionMissingError'
      ) {
        onExpired();
      } else if (updateError.code === 'weak_password' || updateError.code === 'same_password') {
        setError(
          'Choose a different, stronger password. Try a longer password with a mix of characters.',
        );
      } else {
        setError('Your password could not be updated. Please try again shortly.');
      }
    } catch {
      setError('Your password could not be updated. Please try again shortly.');
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  return (
    <form
      className={classes.card}
      onSubmit={(event) => {
        void submit(event);
      }}
    >
      <h1 className={classes.title}>Choose a new password</h1>
      <p className={classes.lede}>Enter and confirm your new password.</p>
      <PasswordInput
        label="New password"
        autoComplete="new-password"
        required
        autoFocus
        disabled={busy}
        value={password}
        onChange={(event) => setPassword(event.currentTarget.value)}
      />
      <PasswordInput
        label="Confirm new password"
        autoComplete="new-password"
        required
        disabled={busy}
        value={confirmation}
        onChange={(event) => setConfirmation(event.currentTarget.value)}
      />
      {error && (
        <p className={classes.error} role="alert">
          {error}
        </p>
      )}
      <Button type="submit" loading={busy} fullWidth>
        Update password
      </Button>
    </form>
  );
}
