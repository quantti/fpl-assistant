import { useLayoutEffect } from 'react'
import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core'
import { useChat } from '../../hooks/useChat'
import MessageThread from '../MessageThread/MessageThread'
import MessageInput from '../MessageInput/MessageInput'
import classes from './ChatView.module.css'

export default function ChatView() {
  const { messages, streamingContent, isStreaming, sendMessage } = useChat()

  // Mantine's own colour-scheme system (useMantineColorScheme /
  // useComputedColorScheme) is the single source of truth for the toggle —
  // it already persists the choice and listens to prefers-color-scheme
  // when unset. `computedColorScheme` always resolves to 'light' | 'dark',
  // never 'auto'. Kept in sync with tokens.css's data-theme attribute below
  // so Mantine's own internals and the project's token palette never
  // diverge.
  //
  // useLayoutEffect (not useEffect) — this must land before paint, the same
  // way Mantine's own data-mantine-color-scheme write does internally
  // (useIsomorphicEffect, effectively useLayoutEffect on the client). A
  // post-paint useEffect leaves a one-frame gap on reload whenever the
  // persisted choice opposes the OS preference: computedColorScheme is
  // already correct synchronously (Mantine reads the persisted value in a
  // useState initializer), but tokens.css's dark media-query block reads
  // data-theme, which wouldn't be written yet — so that first frame would
  // paint the wrong palette before this effect corrects it.
  const { setColorScheme } = useMantineColorScheme()
  const computedColorScheme = useComputedColorScheme('light')

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = computedColorScheme
  }, [computedColorScheme])

  return (
    <div className={classes.root}>
      <div className={classes.header}>
        <span>⚽ FPL Assistant</span>
        <ActionIcon
          className={classes.themeToggle}
          variant="subtle"
          onClick={() => setColorScheme(computedColorScheme === 'dark' ? 'light' : 'dark')}
          aria-label={computedColorScheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {computedColorScheme === 'dark' ? '☀' : '☾'}
        </ActionIcon>
      </div>
      {messages.length === 0 && streamingContent === null ? (
        <div className={classes.empty}>
          Ask me anything about Fantasy Premier League — players, fixtures, strategy, chips, transfers.
        </div>
      ) : (
        <MessageThread messages={messages} streamingContent={streamingContent} />
      )}
      <MessageInput onSubmit={sendMessage} disabled={isStreaming} />
    </div>
  )
}
