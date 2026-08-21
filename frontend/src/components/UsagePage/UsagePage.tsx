import { useEffect, useState } from 'react'
import { Loader } from '@mantine/core'
import { apiFetch } from '../../auth/apiFetch'
import { Usage } from '../../types'
import classes from './UsagePage.module.css'

/**
 * How much of this week is left, as a share.
 *
 * No counts, by design (issue #11): the user sees how much room they have, not how many messages
 * they are given. "When it frees" is not a count and is the one thing someone who has run out
 * actually needs.
 */
export default function UsagePage() {
  const [usage, setUsage] = useState<Usage | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    apiFetch('/api/usage')
      .then(async response => {
        if (!response.ok) throw new Error(String(response.status))
        setUsage(await response.json())
      })
      .catch(() => setFailed(true))
  }, [])

  if (failed) {
    return <div className={classes.page}><p className={classes.error}>Usage could not be loaded.</p></div>
  }
  if (!usage) {
    return <div className={classes.page}><Loader /></div>
  }

  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Usage</h1>
      {usage.unlimited ? (
        <p className={classes.lede}>This account has no weekly limit.</p>
      ) : (
        <>
          <p className={classes.percent}>{usage.remainingPercent}%</p>
          <p className={classes.lede}>of this week&rsquo;s messages remaining</p>
          <div
            className={classes.bar}
            role="progressbar"
            aria-valuenow={usage.remainingPercent}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Messages remaining this week"
          >
            <div className={classes.fill} style={{ width: `${usage.remainingPercent}%` }} />
          </div>
          {/* Only when there is nothing left: telling someone at 70% when a message "frees up"
              reads as being blocked, which they are not. */}
          {usage.remainingPercent === 0 && usage.nextFreeAt && (
            <p className={classes.note}>
              The next message frees up on {new Date(usage.nextFreeAt).toLocaleString()}.
            </p>
          )}
        </>
      )}
    </div>
  )
}
