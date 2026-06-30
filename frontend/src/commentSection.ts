const BODY_MARKER = '[body] ';
const COMMENTS_MARKER = '[comments]';
const COMMENTS_LINE_MARKER = '\n[comments]';

export function extractBodySection(content: string): string {
  const bodyStart = content.indexOf(BODY_MARKER);
  if (bodyStart < 0) {
    return content;
  }

  const start = bodyStart + BODY_MARKER.length;
  const commentsIndex = content.indexOf(COMMENTS_LINE_MARKER, start);
  if (commentsIndex < 0) {
    return content.slice(start);
  }

  return content.slice(start, commentsIndex);
}

export function extractCommentsSection(content: string): string {
  const index = content.indexOf(COMMENTS_MARKER);
  if (index < 0) {
    return '';
  }

  return content.slice(index + COMMENTS_MARKER.length).replace(/^\n/, '');
}

export function addSpacingBetweenLines(text: string): string {
  if (!text) {
    return '';
  }

  return text.split('\n').join('\n\n');
}
