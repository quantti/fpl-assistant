import { Loader } from '@mantine/core';
import { Fragment, useId, useState } from 'react';
import type { CataloguePlayer, PlayerCatalogue } from '../../types';
import { type ShortlistPlayer, useShortlist } from '../../shortlist/ShortlistContext';
import {
  availability,
  compareNumbers,
  numberText as n,
  sortMetric,
} from '../../shortlist/comparison';
import type { ComparisonRow, MetricSort } from '../../shortlist/comparison';
import { useComparison } from '../../shortlist/useComparison';
import ComparisonDetails from './ComparisonDetails';
import classes from './ShortlistPage.module.css';

type Sort = 'name' | 'position' | MetricSort;

export default function ShortlistPage() {
  const shortlist = useShortlist();
  const [position, setPosition] = useState('all');
  const [sort, setSort] = useState<Sort>('name');
  const membershipKey = shortlist.players.map((p) => `${p.season}:${p.elementId}`).join(',');
  const comparison = useComparison(shortlist.loadFailed || shortlist.loading ? '' : membershipKey);
  const byKey = new Map(
    comparison.data?.rows.map((row) => [`${row.season}:${row.elementId}`, row]),
  );
  const players = shortlist.players
    .map((player) => ({ player, row: byKey.get(`${player.season}:${player.elementId}`) }))
    .filter(
      ({ player, row }) =>
        position === 'all' || String(row?.market?.position ?? player.elementType) === position,
    )
    // eslint-disable-next-line unicorn/no-array-sort -- Fresh array; toSorted is outside the ES2022 browser target.
    .sort((a, b) => {
      const byName = displayName(a.player, a.row).localeCompare(displayName(b.player, b.row));
      if (sort === 'name') return byName;
      return sort === 'position'
        ? (a.row?.market?.position ?? a.player.elementType) -
            (b.row?.market?.position ?? b.player.elementType)
        : compareNumbers(sortMetric(a.row, sort), sortMetric(b.row, sort)) || byName;
    });

  const gameweeks = [
    ...new Set(
      comparison.data?.rows.flatMap((row) => row.forecast.weeks.map((week) => week.gameweek)),
    ),
  ];
  gameweeks.sort((a, b) => a - b);

  return (
    <main className={classes.page}>
      <header>
        <h1 className={classes.title}>Shortlist</h1>
        <p className={classes.note}>Observed season numbers. Five editable gameweeks ahead.</p>
      </header>
      {shortlist.loading ? (
        <div className={classes.state} role="status" aria-live="polite">
          <Loader size="sm" />
          <span>Loading your shortlist…</span>
        </div>
      ) : shortlist.loadFailed ? (
        <div className={classes.state}>
          <p role="alert">Your shortlist could not be loaded.</p>
          <button type="button" className={classes.remove} onClick={() => void shortlist.retry()}>
            Retry
          </button>
        </div>
      ) : shortlist.players.length === 0 ? (
        <p className={classes.state}>
          Your shortlist is empty. Add players from assistant answers.
        </p>
      ) : (
        <>
          <div className={classes.toolbar}>
            <label>
              Position
              <select value={position} onChange={(event) => setPosition(event.currentTarget.value)}>
                <option value="all">All positions</option>
                {[1, 2, 3, 4].map((id) => (
                  <option key={id} value={id}>
                    {positionName(id)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Sort by
              <select value={sort} onChange={(event) => setSort(event.currentTarget.value as Sort)}>
                <option value="name">Name</option>
                <option value="position">Position</option>
                <option value="price">Price · highest first</option>
                <option value="points">Points · highest first</option>
                <option value="value">Points/£m · highest first</option>
                <option value="xg">xG · highest first</option>
                <option value="xa">xA · highest first</option>
                <option value="xgi">xGI · highest first</option>
                <option value="cs">Team xCS · highest first</option>
              </select>
            </label>
            <span className={classes.note}>{comparison.data?.horizon}</span>
          </div>
          {comparison.loading && (
            <p className={classes.note} role="status">
              Loading comparison…
            </p>
          )}
          {comparison.failed && (
            <div className={classes.state}>
              <p role="alert">Comparison unavailable. Your saved players can still be removed.</p>
              <button type="button" className={classes.remove} onClick={comparison.retry}>
                Retry comparison
              </button>
            </div>
          )}
          <p className={classes.note}>
            xG / xA / xGI are season totals; xGI = xG + xA. Points/£m uses current captured price,
            not a forecast. Team xCS = expected clean sheets over this window. FDR: 1 easiest–5
            hardest. n/a = unavailable.
          </p>
          <div
            className={classes.tableScroll}
            role="region"
            aria-label="Player comparison table"
            tabIndex={0}
          >
            <table className={classes.table} aria-label="Player comparison">
              <thead>
                <tr>
                  {[
                    'Player',
                    'Club',
                    'Pos',
                    'Price',
                    'Points',
                    'Points/£m',
                    'xG',
                    'xA',
                    'xGI',
                    'Team xCS',
                    ...gameweeks.map((week) => `GW${week}`),
                    'Actions',
                  ].map((label) => (
                    <th key={label} scope="col">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {players.map(({ player, row }) => (
                  <ShortlistRow
                    key={`${player.season}:${player.elementId}`}
                    player={player}
                    row={row}
                    gameweeks={gameweeks}
                  />
                ))}
              </tbody>
            </table>
          </div>
          {players.length === 0 && (
            <p className={classes.state}>No saved players in this position.</p>
          )}
          {comparison.data && (
            <details className={classes.details}>
              <summary>Sources & model limits</summary>
              <div className={classes.detailBody}>
                <p className={classes.note}>Assessed at {comparison.data.asOf}</p>
                {comparison.data.sources.map((source) => (
                  <p key={source} className={classes.note}>
                    {source}
                  </p>
                ))}
              </div>
            </details>
          )}
        </>
      )}
    </main>
  );
}

function ShortlistRow({
  player,
  row,
  gameweeks,
}: {
  player: ShortlistPlayer;
  row?: ComparisonRow;
  gameweeks: number[];
}) {
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
  const shortlist = useShortlist();
  const pending = shortlist.isPending(player.season, player.elementId);
  const position = row?.market?.position ?? player.elementType;
  const name = displayName(player, row);
  const stats = [
    ['Price', row?.market ? `£${n(row.market.nowCost / 10, 1)}m` : 'n/a'],
    ['Points', n(row?.totals?.points)],
    ['Points/£m', n(row?.pointsPerMillion, 1)],
    ['xG', n(row?.totals?.xg, 2)],
    ['xA', n(row?.totals?.xa, 2)],
    ['xGI', n(row?.totals?.xgi, 2)],
    ['Team xCS', n(row?.forecast.expectedCleanSheets, 2)],
  ];
  return (
    <Fragment>
      <tr>
        <th scope="row" className={classes.playerCell}>
          <button
            type="button"
            className={classes.playerName}
            aria-label={`Details for ${name}`}
            aria-expanded={expanded}
            aria-controls={expanded ? detailsId : undefined}
            onClick={() => setExpanded(!expanded)}
          >
            <span aria-hidden="true">{expanded ? '▾' : '▸'}</span> {name}
          </button>
          <span className={classes.availability} title={row?.market?.news ?? undefined}>
            {availability(row?.market?.status)}
          </span>
          {row?.reason && <p className={classes.note}>{row.reason}</p>}
          {row?.forecast.reason && <p className={classes.note}>{row.forecast.reason}</p>}
        </th>
        <td>{row?.market?.teamShort ?? player.teamShort}</td>
        <td>{['GK', 'DEF', 'MID', 'FWD'][position - 1] ?? 'n/a'}</td>
        {stats.map(([label, value]) => (
          <td
            key={label}
            className={label === 'Team xCS' && position <= 2 ? classes.emphasis : classes.number}
          >
            {value}
          </td>
        ))}
        {gameweeks.map((gameweek) => {
          const week = row?.forecast.weeks.find((item) => item.gameweek === gameweek);
          return (
            <td key={gameweek}>
              <div className={classes.week}>
                {!week && 'n/a'}
                {week?.fixtures.length === 0 && 'Blank'}
                {week?.fixtures.map((fixture) => (
                  <span
                    key={fixture.id}
                    className={classes.fixture}
                    data-fdr={fixture.difficulty}
                    title={
                      'Team clean-sheet probability: ' +
                      (fixture.cleanSheetProbability == null
                        ? 'n/a'
                        : n(fixture.cleanSheetProbability * 100) + '%')
                    }
                  >
                    {fixture.opponent} ({fixture.home ? 'H' : 'A'})
                    <small>FDR {fixture.difficulty ?? 'n/a'}</small>
                  </span>
                ))}
              </div>
            </td>
          );
        })}
        <td>
          <button
            type="button"
            className={classes.remove}
            disabled={pending}
            aria-label={`Remove ${name}`}
            onClick={() => void shortlist.toggle(catalogueFor(player), cataloguePlayerFor(player))}
          >
            {pending ? 'Removing…' : 'Remove'}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={11 + gameweeks.length} className={classes.cardCell}>
            <section id={detailsId} className={classes.card} aria-label={`${name} details`}>
              <h2>{name}</h2>
              <p className={classes.note}>{seasonName(player.season)}</p>
              <p className={classes.note}>
                {row?.market?.news ?? availability(row?.market?.status)}
              </p>
              {row ? (
                <ComparisonDetails row={row} position={position} />
              ) : (
                <p>Comparison unavailable.</p>
              )}
            </section>
          </td>
        </tr>
      )}
    </Fragment>
  );
}

function displayName(player: ShortlistPlayer, row?: ComparisonRow) {
  return row?.market?.fullName ?? row?.market?.webName ?? player.fullName;
}

function catalogueFor(player: ShortlistPlayer): PlayerCatalogue {
  return { catalogueId: player.catalogueId, season: player.season, players: [] };
}

function cataloguePlayerFor(player: ShortlistPlayer): CataloguePlayer {
  return {
    elementId: player.elementId,
    webName: player.webName,
    fullName: player.fullName,
    teamShort: player.teamShort,
    teamName: player.teamName,
    elementType: player.elementType,
    aliases: [],
  };
}

export function positionName(elementType: number): string {
  return ['Goalkeeper', 'Defender', 'Midfielder', 'Forward'][elementType - 1] ?? 'Unknown position';
}

export function seasonName(season: string): string {
  return `${season.replace('-', '/')} season`;
}
