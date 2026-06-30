const COMMENTS_MARKER = '[comments]';

export function extractCommentsSection(content: string): string {
  const index = content.indexOf(COMMENTS_MARKER);
  if (index < 0) {
    return '';
  }

  return content.slice(index + COMMENTS_MARKER.length).replace(/^\n/, '');
}
