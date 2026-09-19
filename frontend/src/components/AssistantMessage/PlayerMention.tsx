import type { ReactNode } from 'react';
import type { CataloguePlayer, PlayerCatalogue } from '../../types';
import { useShortlist } from '../../shortlist/ShortlistContext';
import classes from './AssistantMessage.module.css';

interface Props {
  catalogue: PlayerCatalogue;
  player: CataloguePlayer;
  children: ReactNode;
}

export default function PlayerMention({ catalogue, player, children }: Props) {
  const shortlist = useShortlist();
  const selected = shortlist.isSelected(catalogue.season, player.elementId);
  const pending = shortlist.isPending(catalogue.season, player.elementId);
  const action = selected ? 'Remove' : 'Add';

  return (
    <button
      type="button"
      className={`${classes.playerMention} ${selected ? classes.selectedMention : ''}`}
      aria-label={`${action} ${player.fullName} ${selected ? 'from' : 'to'} shortlist`}
      aria-pressed={selected}
      disabled={shortlist.loading || shortlist.loadFailed || pending}
      onClick={() => void shortlist.toggle(catalogue, player)}
    >
      <span>{children}</span>
      <span className={classes.star} aria-hidden="true">
        {selected ? '★' : '☆'}
      </span>
    </button>
  );
}
