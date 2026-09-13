import { navigate, Route } from '../../router'
import classes from './HeaderNavigation.module.css'

interface Props {
  route: Route
  shortlistCount: number | null
}

export default function HeaderNavigation({ route, shortlistCount }: Props) {
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
        Shortlist ({shortlistCount ?? '—'})
      </button>
    </nav>
  )
}
