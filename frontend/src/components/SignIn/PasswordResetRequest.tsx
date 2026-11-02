import { useRef, useState } from 'react';
import { Button, TextInput } from '@mantine/core';
import { supabase } from '../../auth/supabaseClient';
import classes from './SignIn.module.css';

export default function PasswordResetRequest({ onBack }: Readonly<{ onBack: () => void }>) {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);

  async function submit(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || !event.currentTarget.reportValidity()) return;
    pending.current = true;
    setBusy(true);
    setError(false);
    try {
      const { error: requestError } = await supabase.auth.resetPasswordForEmail(email.trim(), {
        redirectTo: `${globalThis.location.origin}/reset-password`,
      });
      // Account-specific refusals must look exactly like an accepted request.
      if (
        requestError &&
        !['user_not_found', 'email_not_confirmed', 'user_banned'].includes(requestError.code ?? '')
      ) {
        setError(true);
      } else {
        setSent(true);
      }
    } catch {
      setError(true);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  return (
    <div className={classes.root}>
      <form
        className={classes.card}
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <h1 className={classes.title}>Reset your password</h1>
        {sent ? (
          <p className={classes.lede} role="status">
            If an account exists for that email, you’ll receive a password reset link.
          </p>
        ) : (
          <>
            <p className={classes.lede}>Enter your email to request a reset link.</p>
            <TextInput
              label="Email"
              type="email"
              autoComplete="email"
              required
              autoFocus
              value={email}
              onChange={(event) => setEmail(event.currentTarget.value)}
              disabled={busy}
            />
            {error && (
              <p className={classes.error} role="alert">
                We could not complete the request. Please try again shortly.
              </p>
            )}
            <Button type="submit" loading={busy} fullWidth>
              Send reset link
            </Button>
          </>
        )}
        <Button type="button" variant="subtle" onClick={onBack} disabled={busy}>
          Back to sign in
        </Button>
      </form>
    </div>
  );
}
