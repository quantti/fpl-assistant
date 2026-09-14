import { useState } from 'react'
import { Anchor, Button, PasswordInput, TextInput } from '@mantine/core'
import { supabase } from '../../auth/supabaseClient'
import { consumeSessionEnded } from '../../auth/apiFetch'
import { problemDetail } from '../../auth/problemDetail'
import classes from './SignIn.module.css'

/**
 * Sign in, or create an account with an invite code.
 *
 * The code is checked by our backend, never here: `supabase.auth.signUp` is a client call anyone
 * can make without this form, so a check in the browser would be decoration. `POST /api/auth/signup`
 * creates the account with the service-role key, and this form then signs in with it — landing
 * the user in the app rather than back at a form they just filled in.
 */
export default function SignIn() {
  const [mode, setMode] = useState<'signIn' | 'signUp'>('signIn')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [awaitingVerification, setAwaitingVerification] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Read once, on mount: whether we are here because a request was rejected rather than because
  // the user never signed in. Read during render and cleared, so a later sign-out shows it again.
  const [sessionEnded] = useState(consumeSessionEnded)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)

    // Trimmed once, so a trailing space in the field cannot create an account under one address
    // and then fail to sign in to it under another.
    const trimmedEmail = email.trim()
    try {
      if (mode === 'signUp') {
        const created = await createAccount(trimmedEmail)
        if (!created) return
        // No sign-in after sign-up. The address is unverified until the person follows the link
        // Supabase mails them, and signInWithPassword would fail with "Email not confirmed"
        // anyway — a refusal that reads as a broken form rather than as the next step.
        setAwaitingVerification(true)
        return
      }

      const { error: signInError } = await supabase.auth.signInWithPassword({
        email: trimmedEmail,
        password,
      })

      // Supabase answers the same "Invalid login credentials" for a wrong password and an
      // unknown address, which is what stops the form from telling an attacker which addresses
      // exist. The message is passed through rather than rewritten.
      if (signInError) {
        // Supabase says "Email not confirmed" for an account that exists but has not been
        // verified. Passed through as an instruction rather than as a failure: the person has an
        // account and one thing left to do, and the raw message does not say that.
        setError(/not confirmed/i.test(signInError.message)
          ? 'Confirm your email address first — check your inbox for the link we sent.'
          : signInError.message)
      }
    } catch {
      // auth-js rethrows anything that is not an AuthError — storage it cannot write to, most
      // plausibly. Without this the button stays in its loading state for good, saying nothing.
      setError('That could not complete. Check that this browser allows site storage.')
    } finally {
      setBusy(false)
    }
  }

  /**
   * A plain `fetch`, not `apiFetch`: there is no token yet, and no session for a 401 to end.
   * Returns whether the account was created, so the caller knows not to try signing in after a
   * refusal.
   */
  async function createAccount(email: string): Promise<boolean> {
    let response: Response
    try {
      response = await fetch('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // The invite code is trimmed here rather than in the field: a pasted code with a
        // trailing newline would otherwise fail a byte-exact compare on the server.
        body: JSON.stringify({ email, password, inviteCode: inviteCode.trim() }),
      })
    } catch {
      // A fetch that rejects — DNS, offline, a blocked connection — is a network problem, not
      // the storage failure auth-js reports through the outer catch. Confusing the two sends
      // someone debugging their cookies when their network is down.
      setError('Sign-up could not reach the server. Check your connection and try again.')
      return false
    }
    if (response.ok) return true

    // Each refusal has a different fix, so each says something different: a wrong code is not a
    // rejected password, and a full tier is neither.
    if (response.status === 403) {
      setError('That invite code is not valid.')
    } else if (response.status === 409) {
      setError('Sign-up is full at the moment. Ask for a place and we will let you know.')
    } else if (response.status === 422) {
      // Supabase's own words: only it knows whether the address is taken or the password too
      // short, and a refusal the user cannot read is one they cannot act on. Spring writes them
      // into the "detail" field of a problem response — reading the body as text would put the
      // whole JSON object on screen.
      setError(await problemDetail(response) ?? 'That email address or password was not accepted.')
    } else if (response.status === 503) {
      setError('Sign-up is unavailable right now. Please try again shortly.')
    } else {
      setError('The account could not be created. Please try again.')
    }
    return false
  }

  const signingUp = mode === 'signUp'

  // The account exists and the person cannot sign in yet. Shown instead of the form rather than
  // beside it: leaving the fields on screen invites them to try signing in, which will be refused
  // until they follow the link, and a refusal here reads as the sign-up not having worked.
  if (awaitingVerification) {
    return (
      <div className={classes.root}>
        <div className={classes.card}>
          <h1 className={classes.title}>FPL Sidekick</h1>
          <p className={classes.lede}>
            Check your email. We have sent a confirmation link to {email.trim()} — follow it, then
            sign in.
          </p>
          <Button
            variant="subtle"
            onClick={() => {
              setAwaitingVerification(false)
              setMode('signIn')
              setPassword('')
            }}
          >
            Back to sign in
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className={classes.root}>
      <form className={classes.card} onSubmit={submit}>
        <h1 className={classes.title}>FPL Sidekick</h1>
        <p className={classes.lede}>
          {signingUp
            ? 'Create an account with your invite code.'
            : sessionEnded
              ? 'Your session ended. Sign in again to continue.'
              : 'Sign in to continue.'}
        </p>

        <TextInput
          label="Email"
          type="email"
          value={email}
          onChange={event => setEmail(event.currentTarget.value)}
          autoComplete="email"
          required
        />
        <PasswordInput
          label="Password"
          value={password}
          onChange={event => setPassword(event.currentTarget.value)}
          autoComplete={signingUp ? 'new-password' : 'current-password'}
          required
        />
        {signingUp && (
          <TextInput
            label="Invite code"
            value={inviteCode}
            onChange={event => setInviteCode(event.currentTarget.value)}
            autoComplete="off"
            required
          />
        )}

        {error && <p className={classes.error} role="alert">{error}</p>}

        <Button type="submit" loading={busy} fullWidth>
          {signingUp ? 'Create account' : 'Sign in'}
        </Button>

        <p className={classes.note}>
          {signingUp ? 'Already have an account? ' : 'Have an invite code? '}
          <Anchor
            component="button"
            type="button"
            inherit
            onClick={() => {
              setMode(signingUp ? 'signIn' : 'signUp')
              setError(null)
            }}
          >
            {signingUp ? 'Sign in' : 'Create an account'}
          </Anchor>
          .{signingUp ? '' : ' The free plan is 20 messages a week.'}
        </p>
      </form>
    </div>
  )
}
