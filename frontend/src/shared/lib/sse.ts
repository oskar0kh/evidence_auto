export interface SseMessage {
  event: string;
  data: string;
}

export function parseSseChunk(buffer: string): { messages: SseMessage[]; remainder: string } {
  const messages: SseMessage[] = [];
  const normalized = buffer.replace(/\r\n/g, '\n');
  const parts = normalized.split('\n\n');

  let remainder = '';
  if (!normalized.endsWith('\n\n') && parts.length > 0) {
    remainder = parts.pop() ?? '';
  }

  for (const part of parts) {
    if (!part.trim()) {
      continue;
    }

    let event = 'message';
    const dataLines: string[] = [];

    for (const line of part.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }

    if (dataLines.length > 0) {
      messages.push({ event, data: dataLines.join('\n') });
    }
  }

  return { messages, remainder };
}
