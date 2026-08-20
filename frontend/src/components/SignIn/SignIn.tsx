import { useState } from 'react'
import { Button, PasswordInput, TextInput } from '@mantine/core'
import { supabase } from '../../auth/supabaseClient'
import { consumeSessionEnded } from '../../auth/apiFetch'
import classes from './SignIn.module.css'

/**
 * Sign-in only. Public sign-up is disabled in the Supabase project for now — without billing
 * there is no cost ceiling per person and no card anchoring an account, so accounts are created
 * by hand. Offering a sign-up form that the API refuses would be worse than not offering one.
 */
export default function SignIn() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Read once, on mount: whether we are here because a request was rejected rather than because
  // the user never signed in. Read during render and cleared, so a later sign-out shows it again.
  const [sessionEnded] = useState(consumeSessionEnded)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)

    try {
      const { error: signInError } = await supabase.auth.signInWithPassword({ email, password })

      // Supabase answers the same "Invalid login credentials" for a wrong password and an
      // unknown address, which is what stops the form from telling an attacker which addresses
      // exist. The message is passed through rather than rewritten.
      if (signInError) {
        setError(signInError.message)
      }
    } catch {
      // auth-js rethrows anything that is not an AuthError — storage it cannot write to, most
      // plausibly. Without this the button stays in its loading state for good, saying nothing.
      setError('Sign-in could not complete. Check that this browser allows site storage.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={classes.root}>
      <form className={classes.card} onSubmit={submit}>
        <h1 className={classes.title}>⚽ FPL Assistant</h1>
        <p className={classes.lede}>
          {sessionEnded ? 'Your session ended. Sign in again to continue.' : 'Sign in to continue.'}
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
          autoComplete="current-password"
          required
        />

        {error && <p className={classes.error} role="alert">{error}</p>}

        <Button type="submit" loading={busy} fullWidth>
          Sign in
        </Button>

        <p className={classes.note}>
          Accounts are created by invitation while the assistant is in private use.
        </p>
      </form>
    </div>
  )
}
