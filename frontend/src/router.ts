import { useEffect, useState } from 'react'

/**
 * Two pages, one history entry each — a router, not a routing library.
 *
 * The application has three top-level views and no route parameters: which conversation is
 * open is state, not an address, because a conversation is chosen from the list beside it. A
 * dependency that solves nested routes, loaders and lazy segments would be answering questions
 * nobody here is asking. Spring's SpaForwardingController already makes a deep link to /usage
 * load the application.
 */
export type Route = '/' | '/usage' | '/settings'

export function navigate(route: Route) {
  if (window.location.pathname !== route) {
    window.history.pushState(null, '', route)
    // pushState does not fire popstate, so nothing would re-render without this.
    window.dispatchEvent(new PopStateEvent('popstate'))
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(current)

  useEffect(() => {
    const onPopState = () => setRoute(current())
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  return route
}

function current(): Route {
  const path = window.location.pathname
  return path === '/usage' || path === '/settings' ? path : '/'
}
