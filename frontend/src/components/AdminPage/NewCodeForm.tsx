import { useState } from 'react'
import { Button, NativeSelect, TextInput } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import {
  BenefitKind, InviteCodeView, NOT_AVAILABLE, NewCodeFields, SAVE_FAILED, createBody, problemDetail,
} from './inviteCodes'

interface Props {
  onCreated: (view: InviteCodeView) => void
}

type Status = { kind: 'note' | 'error'; message: string } | null

const EMPTY: NewCodeFields = {
  code: '', label: '', maxUses: '', benefitKind: 'access', benefitValue: '', validFrom: '',
  validUntil: '', note: '',
}

/**
 * Issuing a code. On 201 the returned row is appended to the table above and the form clears;
 * a refusal is one message per cause, the `SettingsPage` pattern — 409 is the spec's sentence,
 * 400 is the server's own detail because it names the field, 403 is the page's not-available
 * sentence, and anything else is ours, not the admin's.
 */
export default function NewCodeForm({ onCreated }: Props) {
  const [fields, setFields] = useState<NewCodeFields>(EMPTY)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<Status>(null)

  function set<K extends keyof NewCodeFields>(key: K, value: NewCodeFields[K]) {
    setFields(current => ({ ...current, [key]: value }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (fields.maxUses !== '' && !/^\d+$/.test(fields.maxUses)) {
      setStatus({ kind: 'error', message: 'A ceiling is a plain number, digits only.' })
      return
    }
    if (fields.benefitKind === 'discount_percent' && !/^\d+$/.test(fields.benefitValue)) {
      setStatus({ kind: 'error', message: 'A discount is a plain percentage, digits only.' })
      return
    }
    setBusy(true)
    setStatus(null)
    try {
      const response = await apiFetch('/api/admin/invite-codes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(createBody(fields)),
      })
      if (response.status === 201) {
        onCreated(await response.json())
        setFields(EMPTY)
        setStatus({ kind: 'note', message: 'Created.' })
      } else if (response.status === 409) {
        setStatus({ kind: 'error', message: 'A code with that name already exists.' })
      } else if (response.status === 400) {
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

  return (
    <form className={classes.section} onSubmit={submit}>
      <h2 className={`${typography.label} ${classes.label}`}>New code</h2>
      <div className={classes.formGrid}>
        <TextInput name="code" label="Code" required value={fields.code}
          onChange={event => set('code', event.currentTarget.value)} />
        <TextInput name="label" label="Label" placeholder={fields.code || 'The code'} value={fields.label}
          onChange={event => set('label', event.currentTarget.value)} />
        <TextInput name="maxUses" label="Ceiling" inputMode="numeric" placeholder="No ceiling"
          value={fields.maxUses} onChange={event => set('maxUses', event.currentTarget.value.trim())} />
        <NativeSelect name="benefitKind" label="Benefit" value={fields.benefitKind}
          data={[{ value: 'access', label: 'Access' }, { value: 'discount_percent', label: 'Discount %' }]}
          onChange={event => set('benefitKind', event.currentTarget.value as BenefitKind)} />
        {fields.benefitKind === 'discount_percent' && (
          <TextInput name="benefitValue" label="Discount %" inputMode="numeric" value={fields.benefitValue}
            onChange={event => set('benefitValue', event.currentTarget.value.trim())} />
        )}
        <TextInput name="validFrom" label="Valid from" type="date" value={fields.validFrom}
          onChange={event => set('validFrom', event.currentTarget.value)} />
        <TextInput name="validUntil" label="Valid until" type="date" value={fields.validUntil}
          onChange={event => set('validUntil', event.currentTarget.value)} />
        <TextInput name="note" label="Note" value={fields.note}
          onChange={event => set('note', event.currentTarget.value)} />
      </div>
      <div className={classes.formActions}>
        <Button type="submit" loading={busy} disabled={fields.code.trim() === ''}>Create code</Button>
        {status && (
          <p className={status.kind === 'error' ? classes.error : classes.muted}
             role={status.kind === 'error' ? 'alert' : 'status'}>
            {status.message}
          </p>
        )}
      </div>
    </form>
  )
}
