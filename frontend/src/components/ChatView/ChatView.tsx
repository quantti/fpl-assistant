import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useComputedColorScheme, VisuallyHidden } from '@mantine/core';
import { useProfile } from '../../auth/useProfile';
import { useChat } from '../../hooks/useChat';
import { useConversations } from '../../hooks/useConversations';
import { navigate, type Route } from '../../router';
import AdminPage from '../AdminPage/AdminPage';
import AppMenu from '../AppMenu/AppMenu';
import ConversationSidebar from '../ConversationSidebar/ConversationSidebar';
import HeaderNavigation from '../HeaderNavigation/HeaderNavigation';
import MessageThread from '../MessageThread/MessageThread';
import MessageInput from '../MessageInput/MessageInput';
import MyTeamPage from '../MyTeamPage/MyTeamPage';
import SettingsPage from '../SettingsPage/SettingsPage';
import ShortlistPage from '../ShortlistPage/ShortlistPage';
import UsagePage from '../UsagePage/UsagePage';
import classes from './ChatView.module.css';

interface Props {
  route: Route;
}

export default function ChatView({ route }: Props) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const selectionRef = useRef(0);
  const profile = useProfile();
  const conversations = useConversations();
  const { messages, replaceMessages, streamingContent, isStreaming, sendMessage } = useChat({
    conversationId: conversations.currentId,
    onConversation: (id) => (id === null ? conversations.startNew() : conversations.adopt(id)),
    onTurnFinished: conversations.refresh,
  });

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
  const computedColorScheme = useComputedColorScheme('light');

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = computedColorScheme;
  }, [computedColorScheme]);

  // The overlay covers the button that opened it, so without this a phone user who opened the
  // sidebar to look at it has no way back other than picking something.
  useEffect(() => {
    if (!sidebarOpen) return;
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSidebarOpen(false);
    };
    window.addEventListener('keydown', onEscape);
    return () => window.removeEventListener('keydown', onEscape);
  }, [sidebarOpen]);

  async function openConversation(id: string) {
    // Two clicks in a row race, and the slower answer must not win. Only the newest selection
    // may write to the thread.
    const selection = ++selectionRef.current;
    replaceMessages([]);
    setSidebarOpen(false);
    // The sidebar is visible on every page, so opening a conversation from Settings has to take
    // the user to where the conversation is.
    navigate('/app');
    const stored = await conversations.select(id);
    if (selection !== selectionRef.current) return;
    if (stored === null) {
      replaceMessages([
        {
          role: 'assistant',
          content: 'That conversation could not be opened. It may have been removed.',
          isError: true,
        },
      ]);
    } else {
      replaceMessages(stored);
    }
  }

  function newConversation() {
    selectionRef.current++;
    conversations.startNew();
    replaceMessages([]);
    setSidebarOpen(false);
    navigate('/app');
  }

  const isSelecting = conversations.pendingId !== null;
  const threadContent = isSelecting ? (
    <div className={classes.empty} aria-hidden="true">
      Loading conversation…
    </div>
  ) : (
    <MessageThread messages={messages} streamingContent={streamingContent} />
  );

  return (
    <div className={classes.shell}>
      <header className={classes.header}>
        <button
          type="button"
          className={classes.sidebarToggle}
          aria-label="Conversations"
          aria-expanded={sidebarOpen}
          onClick={() => setSidebarOpen((open) => !open)}
        >
          ☰
        </button>
        <span className={classes.wordmark}>FPL Sidekick</span>
        <span className={classes.divider} aria-hidden="true" />
        <div className={classes.pages}>
          <HeaderNavigation route={route} />
        </div>
        <div className={classes.menu}>
          <AppMenu route={route} admin={profile?.admin ?? false} />
        </div>
      </header>
      <div className={classes.body}>
        {sidebarOpen && (
          <div
            className={classes.backdrop}
            onClick={() => setSidebarOpen(false)}
            aria-hidden="true"
          />
        )}
        <ConversationSidebar
          conversations={conversations.conversations}
          currentId={conversations.currentId}
          pendingId={conversations.pendingId}
          onSelect={openConversation}
          onNew={newConversation}
          open={sidebarOpen}
          failed={conversations.failed}
          onRetry={conversations.refresh}
        />
        <div className={classes.root} data-page={route}>
          <VisuallyHidden role="status">
            {isSelecting ? 'Loading conversation…' : ''}
          </VisuallyHidden>
          {route === '/my-team' ? (
            <MyTeamPage />
          ) : route === '/shortlist' ? (
            <ShortlistPage />
          ) : route === '/usage' ? (
            <UsagePage />
          ) : route === '/settings' ? (
            <SettingsPage />
          ) : route === '/admin' ? (
            <AdminPage />
          ) : !isSelecting && messages.length === 0 && streamingContent === null ? (
            <div className={classes.empty}>
              <p>
                Ask me anything about Fantasy Premier League — players, fixtures, strategy, chips,
                transfers.
              </p>
              <p className={classes.chatDisclosure}>
                Chats are sent to the configured AI provider. Saved history is separate from
                diagnostic traces of chats and tool results, which are kept for 14 days.{' '}
                <a href="/privacy">Privacy details</a>
              </p>
            </div>
          ) : (
            threadContent
          )}
          {route === '/app' && (
            <MessageInput onSubmit={sendMessage} disabled={isStreaming || isSelecting} />
          )}
        </div>
      </div>
    </div>
  );
}
