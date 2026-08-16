import { useEffect } from 'react'
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
  const { setColorScheme } = useMantineColorScheme()
  const computedColorScheme = useComputedColorScheme('light')

  useEffect(() => {
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
