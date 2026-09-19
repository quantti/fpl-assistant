import { useEffect, useState } from 'react';

/**
 * A few pages, one history entry each — a router, not a routing library.
 *
 * The application has a small fixed set of top-level views and no route parameters: which
 * conversation is open is state, not an address, because a conversation is chosen from the list
 * beside it. A dependency that solves nested routes, loaders and lazy segments would be answering
 * questions nobody here is asking. Spring's SpaForwardingController makes these deep links load
 * the application.
 */
export type Route =
  | '/'
  | '/login'
  | '/register'
  | '/app'
  | '/my-team'
  | '/shortlist'
  | '/usage'
  | '/settings'
  | '/admin';

export function navigate(route: Route) {
  if (window.location.pathname !== route) {
    window.history.pushState(null, '', route);
    // pushState does not fire popstate, so nothing would re-render without this.
    window.dispatchEvent(new PopStateEvent('popstate'));
  }
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => routeFromPath(window.location.pathname));

  useEffect(() => {
    const onPopState = () => setRoute(routeFromPath(window.location.pathname));
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  return route;
}

export function routeFromPath(path: string): Route {
  return path === '/login' ||
    path === '/register' ||
    path === '/app' ||
    path === '/my-team' ||
    path === '/shortlist' ||
    path === '/usage' ||
    path === '/settings' ||
    path === '/admin'
    ? path
    : '/';
}
