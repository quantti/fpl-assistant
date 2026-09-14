import { useEffect, useState } from 'react'
import { Button, TextInput } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import { problemDetail } from '../../auth/problemDetail'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import StatusLine, { Status } from './StatusLine'
import {
  InviteCodeView, NOT_AVAILABLE, Redemption, SAVE_FAILED, byNewest, isCount, shortUserId,
} from './inviteCodes'

interface Props {
  code: InviteCodeView
  onChange: (view: InviteCodeView) => void
}

type Redemptions =
  | { kind: 'loading' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready'; rows: Redemption[] }

/**
 * The expanded row: the code's ceiling, and who came in on it.
 *
 * A 409 on the ceiling carries the server's own sentence — it says to disable the code rather
 * than lower the ceiling below what is spent — and that sentence is shown as is, because the
 * rule lives there and this page must not paraphrase it into something the API would not say.
 */
export default function CodeDetails({ code, onChange }: Props) {
  const [ceiling, setCeiling] = useState(code.maxUses === undefined ? '' : String(code.maxUses))
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<Status>(null)
  const [redemptions, setRedemptions] = useState<Redemptions>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false
    apiFetch(`/api/admin/invite-codes/${code.id}/redemptions`)
      .then(async response => {
        if (response.status === 403) {
          if (!cancelled) setRedemptions({ kind: 'failed', message: NOT_AVAILABLE })
          return
        }
        if (!response.ok) throw new Error(String(response.status))
        const rows: Redemption[] = await response.json()
        if (!cancelled) setRedemptions({ kind: 'ready', rows: [...rows].sort(byNewest) })
      })
      .catch(() => {
        if (!cancelled) {
          setRedemptions({ kind: 'failed', message: 'Redemptions could not be loaded.' })
        }
      })
    return () => { cancelled = true }
  }, [code.id])

  async function apply(init: RequestInit) {
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch(`/api/admin/invite-codes/${code.id}/max-uses`, init)
      if (response.ok) {
        const view: InviteCodeView = await response.json()
        setCeiling(view.maxUses === undefined ? '' : String(view.maxUses))
        onChange(view)
        setStatus({ kind: 'note', message: 'Saved.' })
      } else if (response.status === 409 || response.status === 400) {
        setStatus({ kind: 'error', message: (await problemDetail(response)) ?? SAVE_FAILED })
      } else if (response.status === 403) {
        setStatus({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setStatus({ kind: 'error', message: SAVE_FAILED })
      }
    } catch {
      setStatus({ kind: 'error', message: SAVE_FAILED })
    } finally {
      setBusy(false)
    }
  }

  function save(event: React.FormEvent) {
    event.preventDefault()
    if (!isCount(ceiling)) {
      setStatus({ kind: 'error', message: 'A ceiling is a plain number, digits only.' })
      return
    }
    void apply({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxUses: Number(ceiling) }),
    })
  }

  return (
    <div className={classes.details}>
      <form className={classes.ceiling} onSubmit={save}>
        <h3 className={`${typography.label} ${classes.label}`}>Ceiling</h3>
        <div className={classes.ceilingRow}>
          <TextInput name="maxUses" aria-label="Ceiling" inputMode="numeric" value={ceiling}
            onChange={event => setCeiling(event.currentTarget.value.trim())} placeholder="No ceiling" />
          <Button type="submit" size="compact-sm" loading={busy} disabled={ceiling === ''}>Save</Button>
          {code.maxUses !== undefined && (
            <Button type="button" size="compact-sm" variant="subtle" disabled={busy}
              onClick={() => { void apply({ method: 'DELETE' }) }}>
              No ceiling
            </Button>
          )}
        </div>
        <StatusLine status={status} />
      </form>

      <div className={classes.redemptions}>
        <h3 className={`${typography.label} ${classes.label}`}>Redemptions</h3>
        {redemptions.kind === 'loading' && <p className={classes.muted}>Loading…</p>}
        {redemptions.kind === 'failed' && <p className={classes.error}>{redemptions.message}</p>}
        {redemptions.kind === 'ready' && redemptions.rows.length === 0 && (
          <p className={classes.muted}>Nobody has used this code yet.</p>
        )}
        {redemptions.kind === 'ready' && redemptions.rows.length > 0 && (
          <ul className={classes.redemptionList}>
            {redemptions.rows.map(row => (
              <li key={row.id}>
                <span>{new Date(row.redeemedAt).toLocaleString()}</span>
                {row.userId === null
                  ? <em className={classes.muted}>burnt</em>
                  : <code title={row.userId}>{shortUserId(row.userId)}</code>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
