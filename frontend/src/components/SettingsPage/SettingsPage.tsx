import { useEffect, useState } from 'react'
import { Button, TextInput } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import { Profile } from '../../types'
import classes from './SettingsPage.module.css'

type Status = { kind: 'saved' | 'cleared' | 'error'; message: string } | null

/**
 * The user's own settings. One field today: the FPL team id.
 *
 * The two ways saving can fail are kept apart, because they send the user to different places.
 * "FPL does not know that team" means look for a typo; "we could not reach FPL" means the number
 * may be perfectly good and nothing was saved. One shared error message would make the second
 * case read as the first, and the user would go hunting for a mistake they did not make.
 */
export default function SettingsPage() {
  const [teamId, setTeamId] = useState('')
  const [stored, setStored] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<Status>(null)

  useEffect(() => {
    apiFetch('/api/profile')
      .then(async response => {
        // A blank field is what "nothing saved" looks like, so a failed read must not be allowed
        // to look like one — the user would conclude their id is gone and save it again.
        if (!response.ok) {
          setStatus({ kind: 'error', message: 'Your settings could not be loaded. What is shown'
            + ' below may not be what is stored.' })
          return
        }
        const profile: Profile = await response.json()
        setStored(profile.fplTeamId)
        setTeamId(profile.fplTeamId === null ? '' : String(profile.fplTeamId))
      })
      .catch(() => setStatus({ kind: 'error', message: 'Your settings could not be loaded.' }))
  }, [])

  async function save(event: React.FormEvent) {
    event.preventDefault()
    // Digits only, checked here: Number('12a') is NaN, which JSON.stringify writes as null, and
    // the server answers the same 400 it uses for a missing field — the user would be told FPL
    // does not know an id they never actually sent.
    if (!/^\d+$/.test(teamId)) {
      setStatus({ kind: 'error', message: 'An FPL team id is a plain number, digits only.' })
      return
    }
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch('/api/profile/fpl-team-id', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fplTeamId: Number(teamId) }),
      })
      if (response.ok) {
        const profile: Profile = await response.json()
        setStored(profile.fplTeamId)
        setStatus({ kind: 'saved', message: 'Saved.' })
      } else if (response.status === 503) {
        setStatus({
          kind: 'error',
          message: 'Could not reach FPL to check that id just now. Nothing was saved — the number'
            + ' may well be fine, so try again shortly.',
        })
      } else if (response.status === 400) {
        setStatus({
          kind: 'error',
          message: 'FPL does not know a team with that id. It is the number in the address of'
            + ' your own points page on fantasy.premierleague.com.',
        })
      } else if (response.status !== 401) {
        // 401 is not reported: apiFetch has already ended the session, and the sign-in screen is
        // one render away. Everything else is ours, not the user's — saying "no such team" for a
        // 500 sends them hunting for a mistake they did not make.
        setStatus({
          kind: 'error',
          message: 'Something went wrong saving that. Nothing was changed — try again shortly.',
        })
      }
    } catch {
      setStatus({ kind: 'error', message: 'Nothing was saved — the request did not go through.' })
    } finally {
      setBusy(false)
    }
  }

  async function clear() {
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch('/api/profile/fpl-team-id', { method: 'DELETE' })
      if (response.ok) {
        setStored(null)
        setTeamId('')
        setStatus({ kind: 'cleared', message: 'Removed.' })
      } else {
        setStatus({ kind: 'error', message: 'The id could not be removed. Try again shortly.' })
      }
    } catch {
      setStatus({ kind: 'error', message: 'The id could not be removed. Try again shortly.' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Settings</h1>

      <form className={classes.form} onSubmit={save}>
        <TextInput
          label="FPL team id"
          description={'Optional. Used by My team and by chat answers about your own squad.'
            + ' Find it in the address of your points page on fantasy.premierleague.com.'}
          value={teamId}
          onChange={event => setTeamId(event.currentTarget.value.trim())}
          inputMode="numeric"
          placeholder="1234567"
        />

        <div className={classes.actions}>
          <Button type="submit" loading={busy} disabled={teamId === ''}>
            Save
          </Button>
          {stored !== null && (
            <Button type="button" variant="subtle" onClick={clear} disabled={busy}>
              Remove
            </Button>
          )}
        </div>

        {status && (
          <p
            className={status.kind === 'error' ? classes.error : classes.note}
            role={status.kind === 'error' ? 'alert' : 'status'}
          >
            {status.message}
          </p>
        )}
      </form>
    </div>
  )
}
