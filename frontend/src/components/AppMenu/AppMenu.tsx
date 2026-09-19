import { Burger, Menu, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { useState } from 'react';
import { supabase } from '../../auth/supabaseClient';
import { navigate, type Route } from '../../router';

interface Props {
  route: Route;
  admin: boolean;
}

export const APP_MENU_LINKS = [
  { label: 'Usage', route: '/usage' },
  { label: 'Settings', route: '/settings' },
] as const;

/**
 * Secondary account actions: usage, settings, theme, and signing out. My team and Shortlist
 * stay visible in the header instead of being hidden in this menu.
 *
 * There is no "Sign in" item, and there cannot be: the menu lives inside the signed-in shell, so
 * anyone who can open it is already signed in.
 *
 * Admin appears only when the profile says so.
 */
export default function AppMenu({ route, admin }: Props) {
  const [open, setOpen] = useState(false);
  const { setColorScheme } = useMantineColorScheme();
  const scheme = useComputedColorScheme('light');

  return (
    <Menu opened={open} onChange={setOpen} position="bottom-end" withinPortal>
      <Menu.Target>
        <Burger opened={open} size="sm" aria-label="Menu" />
      </Menu.Target>
      <Menu.Dropdown>
        {route !== '/' && <Menu.Item onClick={() => navigate('/')}>Back to chat</Menu.Item>}
        {APP_MENU_LINKS.map((item) => (
          <Menu.Item key={item.route} onClick={() => navigate(item.route)}>
            {item.label}
          </Menu.Item>
        ))}
        {/* A convenience, not a gate: the API answers 403 to a non-admin whatever this shows,
            and the page says so. Kept out of APP_MENU_LINKS, which is the fixed list. */}
        {admin && <Menu.Item onClick={() => navigate('/admin')}>Admin</Menu.Item>}
        <Menu.Item onClick={() => setColorScheme(scheme === 'dark' ? 'light' : 'dark')}>
          {scheme === 'dark' ? 'Light theme' : 'Dark theme'}
        </Menu.Item>
        <Menu.Divider />
        {/* Local scope: this browser's session, not every device the user is signed in on. */}
        <Menu.Item
          onClick={() => {
            supabase.auth.signOut({ scope: 'local' }).catch(() => {});
          }}
        >
          Sign out
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
