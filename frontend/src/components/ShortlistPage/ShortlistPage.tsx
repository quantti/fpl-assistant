import { Loader } from '@mantine/core';
import type { CataloguePlayer, PlayerCatalogue } from '../../types';
import { type ShortlistPlayer, useShortlist } from '../../shortlist/ShortlistContext';
import classes from './ShortlistPage.module.css';

export default function ShortlistPage() {
  const shortlist = useShortlist();

  return (
    <main className={classes.page}>
      <h1 className={classes.title}>Shortlist</h1>
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
        <ul className={classes.list}>
          {shortlist.players.map((player) => (
            <ShortlistRow key={`${player.season}:${player.elementId}`} player={player} />
          ))}
        </ul>
      )}
    </main>
  );
}

function ShortlistRow({ player }: { player: ShortlistPlayer }) {
  const shortlist = useShortlist();
  const pending = shortlist.isPending(player.season, player.elementId);

  return (
    <li className={classes.player}>
      <div className={classes.identity}>
        <strong>{player.fullName}</strong>
        <span>
          {player.teamName} · {positionName(player.elementType)} · {seasonName(player.season)}
        </span>
      </div>
      <button
        type="button"
        className={classes.remove}
        disabled={pending}
        onClick={() => void shortlist.toggle(catalogueFor(player), cataloguePlayerFor(player))}
      >
        {pending ? 'Removing…' : 'Remove'}
      </button>
    </li>
  );
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
