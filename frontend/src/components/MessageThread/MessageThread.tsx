import { useEffect, useRef } from 'react'
import { Message } from '../../types'
import AssistantMessage from '../AssistantMessage/AssistantMessage'
import typography from '../../styles/typography.module.css'
import classes from './MessageThread.module.css'

interface Props {
  messages: Message[]
  streamingContent: string | null
}

export default function MessageThread({ messages, streamingContent }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // This fires on every streamed token, so it is by far the most
    // motion the app produces — more than the streaming cursor the spec
    // treats as its only animation. Chrome does not downgrade an explicit
    // `behavior: 'smooth'` for prefers-reduced-motion, so gate it here.
    // Read at scroll time rather than cached: the OS setting can change
    // mid-session.
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    bottomRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' })
  }, [messages, streamingContent])

  return (
    <div className={classes.thread}>
      {messages.map((msg, i) =>
        msg.role === 'user' ? (
          <div key={i} className={typography.turn}>
            <div className={`${typography.label} ${classes.userLabel}`}>You</div>
            <p className={classes.userBody}>{msg.content}</p>
          </div>
        ) : (
          <AssistantMessage
            key={i}
            content={msg.content}
            isError={msg.isError}
            isNotice={msg.isNotice}
          />
        )
      )}
      {streamingContent !== null && (
        <AssistantMessage content={streamingContent} isStreaming />
      )}
      <div ref={bottomRef} />
    </div>
  )
}
