import classes from './AdminPage.module.css'

export type Status = { kind: 'note' | 'error'; message: string } | null

/** The note/error line shown under a form or action — a save confirmation, a refusal, or nothing. */
export default function StatusLine({ status }: { status: Status }) {
  if (!status) return null
  return (
    <p className={status.kind === 'error' ? classes.error : classes.muted}
       role={status.kind === 'error' ? 'alert' : 'status'}>
      {status.message}
    </p>
  )
}
