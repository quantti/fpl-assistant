import { useEffect, useState } from 'react'
import { Button, Loader } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import typography from '../../styles/typography.module.css'
import classes from './AdminPage.module.css'
import CodeDetails from './CodeDetails'
import NewCodeForm from './NewCodeForm'
import {
  InviteCodeList, InviteCodeView, NOT_AVAILABLE, ReleaseResult, STATE_WORDS, benefitText,
  placesLine, releaseNote, usedText,
} from './inviteCodes'

type Page =
  | { kind: 'loading' }
  | { kind: 'unavailable' }
  | { kind: 'failed' }
  | { kind: 'ready'; list: InviteCodeList }

type Notice = { kind: 'note' | 'error'; message: string } | null

const COLUMNS = 8

/**
 * Operating the invite codes: places taken, every code with the state the server computed for
 * it, disable/enable, and (Tasks 3 and 4) a code's ceiling and redemptions, and a form for a new
 * one.
 *
 * The API decides who may be here. The menu link is a convenience; a 403 on the list renders one
 * sentence and no table, the same sentence whether the admin set is empty or this account is not
 * in it. Every mutation re-renders from the row the server returns, because `state` is computed
 * there from the same rule sign-up refuses by and the page must not disagree with the gate.
 */
export default function AdminPage() {
  const [page, setPage] = useState<Page>({ kind: 'loading' })
  const [notice, setNotice] = useState<Notice>(null)
  const [busy, setBusy] = useState(false)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  async function load(): Promise<InviteCodeList | null> {
    const response = await apiFetch('/api/admin/invite-codes')
    if (response.status === 403) {
      setPage({ kind: 'unavailable' })
      return null
    }
    if (!response.ok) {
      setPage({ kind: 'failed' })
      return null
    }
    const list: InviteCodeList = await response.json()
    setPage({ kind: 'ready', list })
    return list
  }

  useEffect(() => {
    load().catch(() => setPage({ kind: 'failed' }))
  }, [])

  function replaceCode(view: InviteCodeView) {
    setPage(current => current.kind !== 'ready' ? current : {
      kind: 'ready',
      list: { ...current.list, codes: current.list.codes.map(code => (code.id === view.id ? view : code)) },
    })
  }

  // Task 4's form appends the created row here.
  function appendCode(view: InviteCodeView) {
    setPage(current => current.kind !== 'ready' ? current : {
      kind: 'ready',
      list: { ...current.list, codes: [...current.list.codes, view] },
    })
  }

  async function toggle(code: InviteCodeView) {
    setBusy(true)
    setNotice(null)
    try {
      const response = await apiFetch(
        `/api/admin/invite-codes/${code.id}/${code.active ? 'disable' : 'enable'}`,
        { method: 'POST' },
      )
      if (response.ok) {
        replaceCode(await response.json())
      } else if (response.status === 403) {
        setNotice({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setNotice({ kind: 'error', message: 'That change did not go through. The code is as it was — try again shortly.' })
      }
    } catch {
      setNotice({ kind: 'error', message: 'That change did not go through. The code is as it was — try again shortly.' })
    } finally {
      setBusy(false)
    }
  }

  async function releaseBurnt() {
    setBusy(true)
    setNotice(null)
    try {
      const response = await apiFetch('/api/admin/invite-codes/release-burnt', { method: 'POST' })
      if (response.ok) {
        const result: ReleaseResult = await response.json()
        // The release answers with what it did, not with the counts; the line is re-read.
        const list = await load()
        setNotice({ kind: 'note', message: releaseNote(result, list?.burnt ?? 0) })
      } else if (response.status === 403) {
        setNotice({ kind: 'error', message: NOT_AVAILABLE })
      } else if (response.status !== 401) {
        setNotice({ kind: 'error', message: 'Nothing was released — try again shortly.' })
      }
    } catch {
      setNotice({ kind: 'error', message: 'Nothing was released — try again shortly.' })
    } finally {
      setBusy(false)
    }
  }

  if (page.kind === 'loading') {
    return <div className={classes.page}><Loader /></div>
  }
  if (page.kind === 'unavailable') {
    return <div className={classes.page}><p className={classes.muted}>{NOT_AVAILABLE}</p></div>
  }
  if (page.kind === 'failed') {
    return <div className={classes.page}><p className={classes.error}>Invite codes could not be loaded.</p></div>
  }

  const { list } = page
  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Admin</h1>

      <section className={classes.section}>
        <h2 className={`${typography.label} ${classes.label}`}>Places</h2>
        <div className={classes.places}>
          <p className={classes.line}>{placesLine(list)}</p>
          {list.burnt > 0 && (
            <Button size="compact-sm" variant="subtle" onClick={releaseBurnt} disabled={busy}>
              Release burnt places
            </Button>
          )}
        </div>
      </section>

      <section className={classes.section}>
        <h2 className={`${typography.label} ${classes.label}`}>Codes</h2>
        <table className={classes.table}>
          <thead>
            <tr>
              <th>Code</th><th>Label</th><th>Used</th><th>Accounts</th><th>State</th>
              <th>Valid until</th><th>Benefit</th><th><span className={classes.visuallyHidden}>Action</span></th>
            </tr>
          </thead>
          <tbody>
            {list.codes.map(code => {
              const muted = code.state === 'DISABLED' || code.state === 'EXHAUSTED'
              const expanded = expandedId === code.id
              return (
                <CodeRowGroup key={code.id} code={code} muted={muted} expanded={expanded} busy={busy}
                  onToggleExpand={() => setExpandedId(expanded ? null : code.id)}
                  onToggleActive={() => toggle(code)}
                  onChange={replaceCode}
                />
              )
            })}
          </tbody>
        </table>
      </section>

      {notice && (
        <p className={notice.kind === 'error' ? classes.error : classes.muted}
           role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.message}
        </p>
      )}

      <NewCodeForm onCreated={appendCode} />
    </div>
  )
}

interface RowProps {
  code: InviteCodeView
  muted: boolean
  expanded: boolean
  busy: boolean
  onToggleExpand: () => void
  onToggleActive: () => void
  onChange: (view: InviteCodeView) => void
}

/**
 * One code as a row, and its details as a second row while expanded. The row itself is the
 * click target (the spec's "clicking a row"); the code cell holds a button so the same thing is
 * reachable from the keyboard, and its click bubbles to the row rather than toggling twice. The
 * action button stops propagation, or every disable would also expand.
 */
function CodeRowGroup({ code, muted, expanded, busy, onToggleExpand, onToggleActive, onChange }: RowProps) {
  return (
    <>
      <tr className={muted ? classes.mutedRow : undefined} onClick={onToggleExpand}>
        <td data-label="Code">
          <button type="button" className={classes.codeButton} aria-expanded={expanded}>{code.code}</button>
        </td>
        <td data-label="Label">{code.label}</td>
        <td data-label="Used">{usedText(code)}</td>
        <td data-label="Accounts">
          {code.accounts}{code.burnt > 0 && <span className={classes.muted}> ({code.burnt} burnt)</span>}
        </td>
        <td data-label="State">{STATE_WORDS[code.state]}</td>
        <td data-label="Valid until">
          {code.validUntil === undefined ? '—' : new Date(code.validUntil).toLocaleDateString()}
        </td>
        <td data-label="Benefit">{benefitText(code)}</td>
        <td data-label="Action" className={classes.actionCell}>
          <Button size="compact-sm" variant="subtle" disabled={busy}
            onClick={event => { event.stopPropagation(); onToggleActive() }}>
            {code.active ? 'Disable' : 'Enable'}
          </Button>
        </td>
      </tr>
      {expanded && (
        <tr className={classes.detailsRow}>
          <td colSpan={COLUMNS}>
            <CodeDetails code={code} onChange={onChange} />
          </td>
        </tr>
      )}
    </>
  )
}
