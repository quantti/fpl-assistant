import { navigate, Route } from '../../router'
import classes from './HeaderNavigation.module.css'

interface Props {
  route: Route
}

/**
 * The two pages a manager keeps coming back to, beside the wordmark. Small caps in the interface
 * face, the same voice as the transcript's attribution labels; the count is on the shortlist page
 * itself, not in its link.
 */
export default function HeaderNavigation({ route }: Props) {
  return (
    <nav className={classes.navigation} aria-label="Your FPL">
      <button
        type="button"
        className={classes.link}
        aria-current={route === '/my-team' ? 'page' : undefined}
        onClick={() => navigate('/my-team')}
      >
        My team
      </button>
      <button
        type="button"
        className={classes.link}
        aria-current={route === '/shortlist' ? 'page' : undefined}
        onClick={() => navigate('/shortlist')}
      >
        Shortlist
      </button>
    </nav>
  )
}
