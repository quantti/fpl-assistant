import { useState, KeyboardEvent } from 'react'
import { Textarea, ActionIcon } from '@mantine/core'
import classes from './MessageInput.module.css'

interface Props {
  onSubmit: (message: string) => void
  disabled: boolean
}

// Must match MAX_CONTENT_CHARS in ChatController.java (backend/.../chat/ChatController.java).
const MAX_MESSAGE_CHARS = 4000

export default function MessageInput({ onSubmit, disabled }: Props) {
  const [value, setValue] = useState('')

  function handleSubmit() {
    const trimmed = value.trim()
    if (!trimmed || disabled) return
    onSubmit(trimmed)
    setValue('')
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter that confirms an IME composition (CJK input) must not submit.
    if (e.nativeEvent.isComposing) return
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  return (
    <div className={classes.wrapper}>
      <Textarea
        className={classes.textarea}
        placeholder="Ask about FPL players, fixtures, strategy..."
        value={value}
        onChange={e => setValue(e.currentTarget.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        autosize
        minRows={1}
        maxRows={5}
        maxLength={MAX_MESSAGE_CHARS}
      />
      <ActionIcon
        size="lg"
        variant="filled"
        onClick={handleSubmit}
        disabled={disabled || !value.trim()}
        aria-label="Send"
      >
        ↑
      </ActionIcon>
    </div>
  )
}
