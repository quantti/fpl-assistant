import { useEffect, useRef } from 'react'
import { Message } from '../../types'
import AssistantMessage from '../AssistantMessage/AssistantMessage'
import classes from './MessageThread.module.css'

interface Props {
  messages: Message[]
  streamingContent: string | null
}

export default function MessageThread({ messages, streamingContent }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent])

  return (
    <div className={classes.thread}>
      {messages.map((msg, i) =>
        msg.role === 'user' ? (
          <div key={i} className={classes.turn}>
            <div className={classes.label}>You</div>
            <p className={classes.userBody}>{msg.content}</p>
          </div>
        ) : (
          <AssistantMessage key={i} content={msg.content} />
        )
      )}
      {streamingContent !== null && (
        <AssistantMessage content={streamingContent} isStreaming />
      )}
      <div ref={bottomRef} />
    </div>
  )
}
