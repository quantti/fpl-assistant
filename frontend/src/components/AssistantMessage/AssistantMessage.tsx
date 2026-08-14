import Markdown from 'react-markdown'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
}

export default function AssistantMessage({ content, isStreaming }: Props) {
  return (
    <div className={`${classes.bubble} ${isStreaming ? classes.cursor : ''}`}>
      <Markdown>{content}</Markdown>
    </div>
  )
}
