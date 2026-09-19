import { useState } from 'react';
import classes from './StorageNotice.module.css';

export const STORAGE_NOTICE_KEY = 'fpl-storage-notice-seen';

function wasAcknowledged(): boolean {
  try {
    return globalThis.sessionStorage.getItem(STORAGE_NOTICE_KEY) === '1';
  } catch {
    return false;
  }
}

export default function StorageNotice() {
  const [visible, setVisible] = useState(() => !wasAcknowledged());

  if (!visible) return null;

  function dismiss() {
    // This only avoids repeating the notice in this tab. It is an acknowledgement, not consent,
    // and deliberately does not add another persistent preference to localStorage.
    try {
      globalThis.sessionStorage.setItem(STORAGE_NOTICE_KEY, '1');
    } catch {
      // The notice remains dismissible in memory when private mode or browser policy blocks storage.
    }
    setVisible(false);
  }

  return (
    <aside className={classes.notice} aria-label="Browser storage notice">
      <p>
        This site uses browser storage to keep you signed in and remember your theme. It has no
        optional analytics or advertising trackers. <a href="/privacy">Privacy details</a>
      </p>
      <button type="button" onClick={dismiss}>
        Got it
      </button>
    </aside>
  );
}
