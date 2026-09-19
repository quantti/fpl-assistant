import { useEffect, useState } from 'react';
import { Loader } from '@mantine/core';
import { apiFetch } from '../../auth/apiFetch';
import { type Usage } from '../../types';
import classes from './UsagePage.module.css';

/**
 * How much of the current window is left.
 *
 * The share is the page; the count is stated beside it when the server sends one, which on the
 * free plan it does — that limit is the offer. A paid plan sends no numbers (issue #11), so this
 * renders exactly what arrives rather than deciding for itself.
 *
 * The window's kind decides the wording, and the wording names a deadline rather than "this
 * gameweek": while the window `[GW12 deadline, GW13 deadline)` is open the matches being played
 * are GW12's, so "this gameweek" would read two ways. A deadline is one instant.
 */
const WORDING = {
  GAMEWEEK: (gameweek?: number) => ({
    lede: `of your messages for gameweek ${gameweek} remaining`,
    label: `Messages remaining for gameweek ${gameweek}`,
    used: (used: number, limit: number) => `${used} of ${limit} messages used.`,
    reset: (at: string) =>
      `Resets at the gameweek ${gameweek} deadline on ${new Date(at).toLocaleString()}.`,
    noReset: '',
  }),
  OFF_SEASON: () => ({
    lede: 'of your off-season messages remaining',
    label: 'Off-season messages remaining',
    used: (used: number, limit: number) => `${used} of ${limit} messages used.`,
    reset: (at: string) =>
      `Resets at the first deadline of the new season on ${new Date(at).toLocaleString()}.`,
    noReset: 'Resets when the new season’s first deadline is published.',
  }),
  FALLBACK: () => ({
    lede: "of this week's messages remaining",
    label: 'Messages remaining this week',
    used: (used: number, limit: number) =>
      `${used} of ${limit} messages used in the last seven days.`,
    // A rolling window has no reset instant — that it never starts over is the whole reason the
    // fixed windows exist — so it claims none.
    reset: () => '',
    noReset: '',
  }),
};
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

  const wording = WORDING[usage.window.kind](usage.window.gameweek);
  const resetLine = usage.resetsAt ? wording.reset(usage.resetsAt) : wording.noReset;

  return (
    <div className={classes.page}>
      <h1 className={classes.title}>Usage</h1>
      {usage.unlimited ? (
        <p className={classes.lede}>This account has no message limit.</p>
      ) : (
        <>
          {/* Named from what the server sent, not assumed: Phase 5b adds a second plan, and a
              hardcoded label would then be wrong for half the accounts. No upgrade button and no
              "coming soon" either — there is nothing to click until Stripe can take money, and a
              dead control is worse than an honest absence. */}
          {usage.plan === 'FREE' && <p className={classes.lede}>Free plan</p>}
          <p className={classes.percent}>{usage.remainingPercent}%</p>
          <p className={classes.lede}>{wording.lede}</p>
          <div
            className={classes.bar}
            role="progressbar"
            aria-valuenow={usage.remainingPercent}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={wording.label}
          >
            <div className={classes.fill} style={{ width: `${usage.remainingPercent}%` }} />
          </div>
          {usage.used !== undefined && usage.limit !== undefined && (
            <p className={classes.note}>{wording.used(usage.used, usage.limit)}</p>
          )}
          {/* Shown whether or not anything is left: a reset you can see coming is the point of
              counting in windows rather than in a rolling week. */}
          {resetLine && <p className={classes.note}>{resetLine}</p>}
          {/* Only when there is nothing left: telling someone at 70% when they may send again
              reads as being blocked, which they are not. */}
          {usage.remainingPercent === 0 && usage.nextFreeAt && (
            <p className={classes.note}>
              You can send again on {new Date(usage.nextFreeAt).toLocaleString()}.
            </p>
          )}
        </>
      )}
    </div>
  );
}
