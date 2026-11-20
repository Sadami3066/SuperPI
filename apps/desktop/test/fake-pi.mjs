const emit = (record) => {
  process.stdout.write(`${JSON.stringify(record)}\n`)
}

let buffer = ''
const splitMode = process.env.FAKE_PI_SPLIT === '1'

process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buffer += chunk
  let index = buffer.indexOf('\n')
  while (index >= 0) {
    const line = buffer.slice(0, index)
    buffer = buffer.slice(index + 1)
    if (line.trim() !== '') handle(JSON.parse(line))
    index = buffer.indexOf('\n')
  }
})

function handle(command) {
  switch (command.type) {
    case 'prompt': {
      emit({ id: command.id, type: 'response', command: 'prompt', success: true, data: { disposition: 'started' } })
      emit({ type: 'agent_start' })
      const update = {
        type: 'message_update',
        assistantMessageEvent: { type: 'text_delta', delta: 'hello from fake pi' },
      }
      if (splitMode) {
        const record = JSON.stringify(update)
        const cut = Math.floor(record.length / 2)
        process.stdout.write(record.slice(0, cut))
        setTimeout(() => {
          process.stdout.write(`${record.slice(cut)}\n`)
          emit({ type: 'agent_end' })
          emit({ type: 'agent_settled' })
        }, 20)
      } else {
        emit(update)
        emit({ type: 'agent_end' })
        emit({ type: 'agent_settled' })
      }
      break
    }
    case 'get_state':
      emit({ id: command.id, type: 'response', command: 'get_state', success: true, data: { model: 'fake/model', messageCount: 0 } })
      break
    case 'abort':
      emit({ id: command.id, type: 'response', command: 'abort', success: true, data: { stopped: true } })
      break
    case 'fail':
      emit({ id: command.id, type: 'response', command: 'fail', success: false, error: 'synthetic failure' })
      break
    case 'hang':
      break
    case 'exit':
      process.exit(3)
      break
    default:
      emit({ id: command.id, type: 'response', command: command.type, success: true, data: {} })
  }
}
