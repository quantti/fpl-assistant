import { useEffect, useState } from 'react'
import { Button, Loader } from '@mantine/core'
import { navigate } from '../../router'
import SquadSection from './SquadSection'
import {
  formatChipName,
  loadMyTeam,
  MyTeamLoadResult,
  ReadyResponse,
  selectPrimarySquad,
} from './myTeam'
import classes from './MyTeamPage.module.css'

type PageState = { kind: 'loading' } | { kind: 'result'; result: MyTeamLoadResult }

export default function MyTeamPage() {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<PageState>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    let mounted = true
    setState({ kind: 'loading' })

    void loadMyTeam(controller.signal).then(result => {
      if (mounted && result !== null) setState({ kind: 'result', result })
    })

    return () => {
      mounted = false
      controller.abort()
    }
  }, [attempt])

  function retry() {
    if (state.kind === 'loading') return
    // Disable the control immediately, before the next effect starts the new request.
    setState({ kind: 'loading' })
    setAttempt(value => value + 1)
  }

  return (
    <main className={classes.page}>
      <h1 className={classes.title}>My team</h1>
      {state.kind === 'loading'
        ? <Loading />
        : <Result result={state.result} retry={retry} />}
    </main>
  )
}

function Loading() {
  return (
    <div className={classes.loading} role="status" aria-live="polite">
      <Loader size="sm" />
      <span>Loading your published squad…</span>
    </div>
  )
}

function Result({ result, retry }: { result: MyTeamLoadResult; retry: () => void }) {
  switch (result.status) {
    case 'ready':
      return <Ready data={result} />
    case 'no_team_id':
      return <SetupState
        title="Add your FPL team id"
        message={'Save the number from your FPL points-page address to see your squad here.'}
      />
    case 'team_not_found':
      return <SetupState
        title="Check your FPL team id"
        message={'FPL does not recognise the saved id. Check it in Settings and save the correct number.'}
      />
    case 'no_squad_yet':
      return (
        <section className={classes.state} aria-labelledby="no-squad-title">
          <h2 id="no-squad-title" className={classes.sectionTitle}>No published squad yet</h2>
          <p>FPL publishes picks only after a gameweek deadline. Transfers made for an upcoming
            deadline cannot be shown here before it passes.</p>
        </section>
      )
    case 'signed_out':
      return <p className={classes.stateMessage} role="status">Your session ended. Returning to sign in…</p>
    case 'rate_limited':
      return <Failure
        message="Too many requests were made from this connection. Wait a minute, then retry."
        retry={retry}
      />
    case 'unavailable':
      return <Failure
        message={'FPL is temporarily unavailable, so your squad could not be loaded. Your saved team id was not changed.'}
        retry={retry}
      />
    case 'network_error':
      return <Failure message="The request did not go through. Check your connection and retry." retry={retry} />
    case 'invalid_response':
      return <Failure message="The squad response could not be read. Retry in a moment." retry={retry} />
    case 'request_failed':
      return <Failure message="Your squad could not be loaded. Retry in a moment." retry={retry} />
  }
}

function SetupState({ title, message }: { title: string; message: string }) {
  return (
    <section className={classes.state} aria-labelledby="team-setup-title">
      <h2 id="team-setup-title" className={classes.sectionTitle}>{title}</h2>
      <p>{message}</p>
      <Button onClick={() => navigate('/settings')}>Open Settings</Button>
    </section>
  )
}

function Failure({ message, retry }: { message: string; retry: () => void }) {
  return (
    <section className={classes.state} aria-labelledby="team-error-title" role="alert">
      <h2 id="team-error-title" className={classes.sectionTitle}>Could not load My team</h2>
      <p>{message}</p>
      <Button onClick={retry}>Retry</Button>
    </section>
  )
}

function Ready({ data }: { data: ReadyResponse }) {
  const primary = selectPrimarySquad(data)
  const freeHit = data.lockedSquad.activeChip === 'freehit'

  return (
    <>
      <section className={classes.snapshot} aria-labelledby="team-name">
        <h2 id="team-name" className={classes.teamName}>{data.entry.name}</h2>
        <p>Entry {data.entry.id} · Squad published for locked GW {data.lockedGameweek}</p>
        {data.deadline && <p>Deadline {formatTimestamp(data.deadline)}</p>}
        <p>Loaded {formatTimestamp(data.loadedAt)}</p>
      </section>

      {freeHit && (
        <>
          <p className={classes.freeHitNotice}>
            <strong>Free Hit · GW {data.lockedGameweek}</strong>
            {' — '}{primary.kind === 'available'
              ? 'Showing your continuing squad'
              : 'Continuing squad unavailable'}
          </p>
          <details className={classes.disclosure}>
            <summary>View Free Hit squad</summary>
            <SquadSection
              title={`Free Hit squad — GW ${data.lockedGameweek}`}
              bank={data.lockedSquad.bank}
              value={data.lockedSquad.value}
              players={data.lockedSquad.players}
              choiceGameweek={data.lockedGameweek}
              note={'These temporary picks belong to the named gameweek only and did not carry forward. The funds are from this Free Hit snapshot.'}
            />
          </details>
        </>
      )}

      {primary.kind === 'available' ? (
        <SquadSection
          title={primary.context === 'continuing'
            ? `Continuing squad — restored at the GW ${primary.restoredAtGameweek} deadline`
            : `Squad locked for GW ${primary.sourceGameweek}`}
          bank={primary.bank}
          value={primary.value}
          players={primary.players}
          choiceGameweek={primary.sourceGameweek}
          note={primary.context === 'continuing'
            ? `Restored from the GW ${primary.sourceGameweek} picks. Its bank is the restored estimate before any later transfers; its captain and bench order are GW ${primary.sourceGameweek} choices, not a recommendation.`
            : 'This is the published deadline snapshot. Transfers made after that deadline are not visible.'}
        />
      ) : (
        <section className={classes.warning} aria-labelledby="continuing-unavailable-title">
          <h2 id="continuing-unavailable-title" className={classes.sectionTitle}>
            Continuing squad unavailable
          </h2>
          <p>The squad restored at the GW {primary.restoredAtGameweek} deadline could not be
            shown: {primary.reason}. The temporary Free Hit squad above has not been substituted
            for it.</p>
        </section>
      )}

      <ChipSection data={data} />

      <section className={classes.caveats} aria-labelledby="snapshot-notes-title">
        <h2 id="snapshot-notes-title" className={classes.sectionTitle}>About this snapshot</h2>
        <p>Transfers made after the locked gameweek’s deadline are not visible here.</p>
        <p>FPL responses and player details may be cached. If a refresh failed, an older player
          snapshot may survive; “Loaded” is when this page assembled the response, not when FPL
          updated it.</p>
        <p>Player prices are market prices from that player snapshot and may describe a different
          moment from the recorded bank and squad value. Selling prices and free-transfer counts
          are unavailable, so this is not an exact transfer budget.</p>
      </section>
    </>
  )
}

function ChipSection({ data }: { data: ReadyResponse }) {
  return (
    <section className={classes.chips} aria-labelledby="chips-title">
      <h2 id="chips-title" className={classes.sectionTitle}>Chips</h2>
      <p>Used for locked GW {data.lockedGameweek}: {data.lockedSquad.activeChip === null
        ? 'None'
        : formatChipName(data.lockedSquad.activeChip)}.</p>
      {data.chips.known ? (
        <>
          <p>Playable at the next deadline (GW {data.chips.gameweek}): {
            data.chips.playable.length === 0
              ? 'None'
              : data.chips.playable.map(formatChipName).join(', ')
          }.</p>
          {data.chips.openingLater.length > 0 && data.chips.openingGameweek !== null && (
            <p>Opening in GW {data.chips.openingGameweek}: {
              data.chips.openingLater.map(formatChipName).join(', ')
            }.</p>
          )}
        </>
      ) : (
        <p>Chip availability could not be loaded.</p>
      )}
    </section>
  )
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return 'unavailable'
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/London',
    timeZoneName: 'short',
  }).format(date)
}
