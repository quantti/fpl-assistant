import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import classes from './AssistantMessage.module.css'

interface Props {
  content: string
  isStreaming?: boolean
}

export default function AssistantMessage({ content, isStreaming }: Props) {
  return (
    <div className={classes.turn}>
      <div className={classes.label}>Assistant</div>
      <div className={`${classes.prose} ${isStreaming ? classes.cursor : ''}`}>
        <Markdown remarkPlugins={[remarkGfm]}>{content}</Markdown>
      </div>
    </div>
  )
}
