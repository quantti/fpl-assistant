import { formatMoney, SquadPlayer } from './myTeam'
import classes from './MyTeamPage.module.css'

interface Props {
  title: string
  bank: number
  value: number
  players: SquadPlayer[]
  choiceGameweek: number
  note: string
}

/** The same ordered, list-based squad presentation for locked and restored picks. */
export default function SquadSection({ title, bank, value, players, choiceGameweek, note }: Props) {
  const starters = players.filter(player => player.pickPosition <= 11)
  const bench = players.filter(player => player.pickPosition > 11)

  return (
    <section className={classes.squadSection}>
      <h2 className={classes.sectionTitle}>{title}</h2>
      <p className={classes.funds}>
        <span>Bank <strong>{formatMoney(bank)}</strong></span>
        <span>Recorded squad value <strong>{formatMoney(value)}</strong></span>
      </p>
      <p className={classes.sectionNote}>{note}</p>
      <PlayerGroup title="Starting XI" gameweek={choiceGameweek} players={starters} />
      <PlayerGroup title="Bench" gameweek={choiceGameweek} players={bench} />
    </section>
  )
}

function PlayerGroup({ title, gameweek, players }: {
  title: string
  gameweek: number
  players: SquadPlayer[]
}) {
  return (
    <section className={classes.playerGroup}>
      <h3 className={classes.groupTitle}>{title}</h3>
      {players.length === 0 ? (
        <p className={classes.emptyGroup}>No published picks in this group.</p>
      ) : (
        <ol className={classes.playerList} role="list">
          {players.map(player => (
            <li className={classes.player} key={`${player.pickPosition}-${player.elementId}`}>
              <span className={classes.pickPosition} aria-label={`Pick position ${player.pickPosition}`}>
                {player.pickPosition}
              </span>
              <span className={classes.playerDetails}>
                <span className={classes.playerIdentity}>
                  <strong>{player.name}</strong>
                  {' · '}{player.club ?? 'Club unavailable'} · {player.position}
                </span>
                {(player.captain || player.viceCaptain) && (
                  <span className={classes.roles}>
                    {player.captain && <span className={classes.role}>Captain</span>}
                    {player.viceCaptain && <span className={classes.role}>Vice-captain</span>}
                  </span>
                )}
                <span className={classes.playerMeta}>
                  {player.gameweekPoints !== null && (
                    <>{`GW ${gameweek}: ${player.gameweekPoints} pts · ${player.gameweekMinutes ?? 0} min`}{' · '}</>
                  )}
                  {player.marketPrice === null
                    ? 'Market price unavailable'
                    : `Market price ${formatMoney(player.marketPrice)}`}
                  {' · '}
                  {availability(player)}
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function availability(player: SquadPlayer): string {
  if (player.availability !== null) return player.availability
  if (player.marketPrice === null || player.club === null) return 'Availability unavailable'
  return 'Available'
}
