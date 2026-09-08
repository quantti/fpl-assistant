import { Burger, Menu, useComputedColorScheme, useMantineColorScheme } from '@mantine/core'
import { useState } from 'react'
import { supabase } from '../../auth/supabaseClient'
import { navigate, Route } from '../../router'

interface Props {
  route: Route
}

/**
 * Everything that is not the conversation: My team, usage, settings, theme, and signing out.
 *
 * There is no "Sign in" item, and there cannot be: the menu lives inside the signed-in shell, so
 * anyone who can open it is already signed in.
 */
export default function AppMenu({ route }: Props) {
  const [open, setOpen] = useState(false)
  const { setColorScheme } = useMantineColorScheme()
  const scheme = useComputedColorScheme('light')

  return (
    <Menu opened={open} onChange={setOpen} position="bottom-end" withinPortal>
      <Menu.Target>
        <Burger opened={open} size="sm" aria-label="Menu" />
      </Menu.Target>
      <Menu.Dropdown>
        {route !== '/' && (
          <Menu.Item onClick={() => navigate('/')}>Back to chat</Menu.Item>
        )}
        <Menu.Item onClick={() => navigate('/my-team')}>My team</Menu.Item>
        <Menu.Item onClick={() => navigate('/usage')}>Usage</Menu.Item>
        <Menu.Item onClick={() => navigate('/settings')}>Settings</Menu.Item>
        <Menu.Item onClick={() => setColorScheme(scheme === 'dark' ? 'light' : 'dark')}>
          {scheme === 'dark' ? 'Light theme' : 'Dark theme'}
        </Menu.Item>
        <Menu.Divider />
        {/* Local scope: this browser's session, not every device the user is signed in on. */}
        <Menu.Item onClick={() => { supabase.auth.signOut({ scope: 'local' }).catch(() => {}) }}>
          Sign out
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  )
}
