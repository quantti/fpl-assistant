import { useChat } from '../../hooks/useChat'
import MessageThread from '../MessageThread/MessageThread'
import MessageInput from '../MessageInput/MessageInput'
import classes from './ChatView.module.css'

export default function ChatView() {
  const { messages, streamingContent, isStreaming, sendMessage } = useChat()

  return (
    <div className={classes.root}>
      <div className={classes.header}>⚽ FPL Assistant</div>
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
