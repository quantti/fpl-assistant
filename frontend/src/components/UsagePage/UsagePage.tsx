import { useEffect, useState } from 'react';
import { Loader } from '@mantine/core';
import { apiFetch } from '../../auth/apiFetch';
import { type Usage } from '../../types';
import classes from './UsagePage.module.css';

/**
 * How much of this week is left.
 *
 * The share is the page; the count is stated beside it when the server sends one, which on the
 * free plan it does — that limit is the offer. A paid plan sends no numbers (issue #11), so this
 * renders exactly what arrives rather than deciding for itself.
 */
export default function UsagePage() {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    apiFetch('/api/usage')
      .then(async (response) => {
        if (!response.ok) throw new Error(String(response.status));
        setUsage(await response.json());
      })
      .catch(() => setFailed(true));
  }, []);

  if (failed) {
    return (
      <div className={classes.page}>
        <p className={classes.error}>Usage could not be loaded.</p>
      </div>
    );
  }
  if (!usage) {
    return (
      <div className={classes.page}>
        <Loader />
      </div>
    );
  }

  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Usage</h1>
      {usage.unlimited ? (
        <p className={classes.lede}>This account has no weekly limit.</p>
      ) : (
        <>
          {/* Named from what the server sent, not assumed: Phase 5b adds a second plan, and a
              hardcoded label would then be wrong for half the accounts. No upgrade button and no
              "coming soon" either — there is nothing to click until Stripe can take money, and a
              dead control is worse than an honest absence. */}
          {usage.plan === 'FREE' && <p className={classes.lede}>Free plan</p>}
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
          {usage.used !== undefined && usage.limit !== undefined && (
            <p className={classes.note}>
              {usage.used} of {usage.limit} messages used this week.
            </p>
          )}
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
  );
}
