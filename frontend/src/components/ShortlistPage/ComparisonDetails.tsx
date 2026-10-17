import type { ComparisonRow } from '../../shortlist/comparison';
import { availabilityBadge, numberText as n } from '../../shortlist/comparison';
import classes from './ShortlistPage.module.css';

export default function ComparisonDetails({
  row,
  position,
}: Readonly<{
  row: ComparisonRow;
  position: number;
}>) {
  const t = row.totals;
  const d = row.defcon;
  const historyNote =
    row.historyReason ??
    (row.history.length === 0 ? 'No completed team fixtures in this capture.' : null);
  const seasonStats: [string, number | null | undefined][] = [
    ['Minutes', t?.minutes],
    ['Goals', t?.goals],
    ['Assists', t?.assists],
    ['Clean sheets', t?.cleanSheets],
    ['Bonus', t?.bonus],
    ...(position === 1 ? [['Saves', t?.saves] as [string, number | null | undefined]] : []),
  ];
  return (
    <div className={classes.details}>
      <div className={classes.detailBody}>
        <h3>Observed season totals</h3>
        <dl className={classes.stats}>
          {seasonStats.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{n(value)}</dd>
            </div>
          ))}
        </dl>
        {t && <p className={classes.note}>{t.source}</p>}
        <h3>Defensive contribution</h3>
        <p>
          Observed appearances: {n(d?.observedAppearances)}.{' '}
          {d && d.threshold > 0 && d.readings > 0
            ? `DefCon: ${d.hits}/${d.readings} appearances with a reading reached ${d.threshold} actions; ${n(d.actionsPer90, 1)} actions/90.`
            : 'DefCon: n/a — no applicable readings.'}
        </p>
        <p className={classes.note}>
          Known readings only; missing data and zero-minute matches are excluded from the DefCon
          rate.
        </p>
        <h3>Captured availability</h3>
        <p>{availabilityBadge(row.market?.status, row.market?.chanceOfPlayingNextRound).label}</p>
        <p className={classes.note}>
          FPL's captured chance of playing next round, not a guarantee of starting or minutes. A
          missing percentage is unknown, not 0% or 100%.
        </p>
        <h3>Captured ownership & set pieces</h3>
        <p>
          Ownership: {row.market?.ownership == null ? 'n/a' : `${n(row.market.ownership, 1)}%`}.{' '}
          Penalties: {rank(row.market?.penalties)} · Direct free kicks:{' '}
          {rank(row.market?.directFreeKicks)} · Corners: {rank(row.market?.corners)}
        </p>
        {row.market && (
          <p className={classes.note}>
            Captured {row.market.capturedAt}. Ranks are captured duties, not a guarantee of taking
            the next kick.
          </p>
        )}
        <h3>Last five completed team fixtures</h3>
        <p className={classes.note}>
          Current club, this season. A missing reading is n/a, not a zero-minute appearance.
        </p>
        {historyNote ? (
          <p>{historyNote}</p>
        ) : (
          <div
            className={classes.history}
            role="region"
            aria-label="Recent match observations"
            tabIndex={0}
          >
            <table>
              <thead>
                <tr>
                  {[
                    'Date',
                    'GW',
                    'Opponent',
                    'Min',
                    'Pts',
                    'G',
                    'A',
                    'xG',
                    'xA',
                    'xGI',
                    'DefCon actions',
                    'Evidence',
                  ].map((label) => (
                    <th key={label} scope="col">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {row.history.map((match) => (
                  <tr key={match.id}>
                    <td>{match.kickoff.slice(0, 10)}</td>
                    <td>{n(match.gameweek)}</td>
                    <th scope="row">
                      {match.opponent} ({match.home ? 'H' : 'A'})
                    </th>
                    {[match.minutes, match.points, match.goals, match.assists].map(
                      (value, index) => (
                        <td key={['min', 'pts', 'g', 'a'][index]}>{n(value)}</td>
                      ),
                    )}
                    <td>{n(match.xg, 2)}</td>
                    <td>{n(match.xa, 2)}</td>
                    <td>{n(match.xgi, 2)}</td>
                    <td>{n(match.defcon)}</td>
                    <td>{match.evidence.toLowerCase()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {row.observationSources.map((source) => (
          <p className={classes.note} key={source}>
            {source}
          </p>
        ))}
      </div>
    </div>
  );
}

function rank(value: number | null | undefined) {
  if (value == null) return 'n/a';
  return value > 0 ? `#${value}` : 'Not listed';
}
