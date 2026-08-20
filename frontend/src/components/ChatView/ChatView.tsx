import { useLayoutEffect, useState } from 'react'
import { useComputedColorScheme } from '@mantine/core'
import { useChat } from '../../hooks/useChat'
import { useConversations } from '../../hooks/useConversations'
import { Route } from '../../router'
import AppMenu from '../AppMenu/AppMenu'
import ConversationSidebar from '../ConversationSidebar/ConversationSidebar'
import MessageThread from '../MessageThread/MessageThread'
import MessageInput from '../MessageInput/MessageInput'
import SettingsPage from '../SettingsPage/SettingsPage'
import UsagePage from '../UsagePage/UsagePage'
import classes from './ChatView.module.css'

interface Props {
  route: Route
}

export default function ChatView({ route }: Props) {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const conversations = useConversations()
  const { messages, setMessages, streamingContent, isStreaming, sendMessage } = useChat({
    conversationId: conversations.currentId,
    onConversation: id => (id === null ? conversations.startNew() : conversations.adopt(id)),
    onTurnFinished: conversations.refresh,
  })

  // Mantine's own colour-scheme system is the single source of truth for the
  // toggle — it already persists the choice and listens to
  // prefers-color-scheme when unset. `computedColorScheme` always resolves to
  // 'light' | 'dark', never 'auto'. Kept in sync with tokens.css's data-theme
  // attribute below so Mantine's own internals and the project's token
  // palette never diverge.
  //
  // useLayoutEffect (not useEffect) — this must land before paint, the same
  // way Mantine's own data-mantine-color-scheme write does internally. A
  // post-paint useEffect leaves a one-frame gap on reload whenever the
  // persisted choice opposes the OS preference.
  const computedColorScheme = useComputedColorScheme('light')

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = computedColorScheme
  }, [computedColorScheme])

  async function openConversation(id: string) {
    const stored = await conversations.select(id)
    if (stored) setMessages(stored)
    setSidebarOpen(false)
  }

  function newConversation() {
    conversations.startNew()
    setMessages([])
    setSidebarOpen(false)
  }

  return (
    <div className={classes.shell}>
      <ConversationSidebar
        conversations={conversations.conversations}
        currentId={conversations.currentId}
        onSelect={openConversation}
        onNew={newConversation}
        open={sidebarOpen}
      />
      <div className={classes.root}>
        <div className={classes.header}>
          <div className={classes.headerLeft}>
            <button
              type="button"
              className={classes.sidebarToggle}
              aria-label="Conversations"
              aria-expanded={sidebarOpen}
              onClick={() => setSidebarOpen(open => !open)}
            >
              ☰
            </button>
            <span>⚽ FPL Assistant</span>
          </div>
          <AppMenu route={route} />
        </div>
        {route === '/usage' ? (
          <UsagePage />
        ) : route === '/settings' ? (
          <SettingsPage />
        ) : messages.length === 0 && streamingContent === null ? (
          <div className={classes.empty}>
            Ask me anything about Fantasy Premier League — players, fixtures, strategy, chips, transfers.
          </div>
        ) : (
          <MessageThread messages={messages} streamingContent={streamingContent} />
        )}
        {route === '/' && <MessageInput onSubmit={sendMessage} disabled={isStreaming} />}
      </div>
    </div>
  )
}
