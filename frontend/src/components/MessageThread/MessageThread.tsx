import { useEffect, useRef } from 'react';
import { type Message } from '../../types';
import AssistantMessage from '../AssistantMessage/AssistantMessage';
import typography from '../../styles/typography.module.css';
import classes from './MessageThread.module.css';
import { followsBottom } from './follow';

interface Props {
  messages: Message[];
  streamingContent: string | null;
}

export default function MessageThread({ messages, streamingContent }: Props) {
  const threadRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  // Whether the reader is at the bottom, as of their last scroll. A ref, not state: it is
  // read inside the effect below and must never cause a render of its own.
  const followRef = useRef(true);
  const messageCount = useRef(messages.length);

  function handleScroll() {
    const el = threadRef.current;
    if (el) followRef.current = followsBottom(el);
  }

  useEffect(() => {
    // Your own message always comes into view, even if you had scrolled up to read while the
    // previous answer streamed; from there following resumes naturally.
    const sent =
      messages.length > messageCount.current && messages[messages.length - 1]?.role === 'user';
    messageCount.current = messages.length;
    if (sent) followRef.current = true;
    // Everything else follows the bottom only while the reader is there (KAR-46).
    if (!followRef.current) return;

    // This fires on every streamed token, so it is by far the most
    // motion the app produces — more than the streaming cursor the spec
    // treats as its only animation. Chrome does not downgrade an explicit
    // `behavior: 'smooth'` for prefers-reduced-motion, so gate it here.
    // Read at scroll time rather than cached: the OS setting can change
    // mid-session.
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // Instant while streaming: a smooth scroll chasing a target that moves every token never
    // settles, and mid-animation the reader's "at the bottom" reading above would flap.
    const behavior = reduceMotion || streamingContent !== null ? 'auto' : 'smooth';
    bottomRef.current?.scrollIntoView({ behavior });
  }, [messages, streamingContent]);

  // data-thread: useKeyboardAwareViewport finds the scrolling element by it to keep the
  // bottom pinned when the iOS keyboard shortens the shell.
  return (
    <div className={classes.thread} data-thread ref={threadRef} onScroll={handleScroll}>
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
            catalogue={msg.catalogue}
          />
        ),
      )}
      {streamingContent !== null && <AssistantMessage content={streamingContent} isStreaming />}
      <div ref={bottomRef} />
    </div>
  );
}
