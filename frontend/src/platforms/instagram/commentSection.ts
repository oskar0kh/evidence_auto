const CAPTION_MARKER = '[caption] ';
const OCR_MARKER = '[ocr] ';
const COMMENTS_MARKER = '[comments]';
const COMMENTS_LINE_MARKER = '\n[comments]';

export function extractCaptionSection(content: string): string {
  const captionStart = content.indexOf(CAPTION_MARKER);
  if (captionStart < 0) {
    return content;
  }
  const start = captionStart + CAPTION_MARKER.length;
  const ocrIndex = content.indexOf('\n' + OCR_MARKER, start);
  const commentsIndex = content.indexOf(COMMENTS_LINE_MARKER, start);
  const end = Math.min(
    ocrIndex >= 0 ? ocrIndex : content.length,
    commentsIndex >= 0 ? commentsIndex : content.length
  );
  return content.slice(start, end).trim();
}

export function extractOcrSection(content: string): string {
  const ocrStart = content.indexOf(OCR_MARKER);
  if (ocrStart < 0) {
    return '';
  }
  const start = ocrStart + OCR_MARKER.length;
  const commentsIndex = content.indexOf(COMMENTS_LINE_MARKER, start);
  if (commentsIndex < 0) {
    return content.slice(start).trim();
  }
  return content.slice(start, commentsIndex).trim();
}

export function extractCommentsSection(content: string): string {
  const index = content.indexOf(COMMENTS_MARKER);
  if (index < 0) {
    return '';
  }
  return content.slice(index + COMMENTS_MARKER.length).replace(/^\n/, '');
}

export function extractBodySection(content: string): string {
  const caption = extractCaptionSection(content);
  const ocr = extractOcrSection(content);
  if (ocr) {
    return `${caption}\n\n[OCR]\n${ocr}`.trim();
  }
  return caption;
}

export function extractCommentRowSection(post: { nickname: string; content: string; postDate: string }): string {
  if (!post.nickname && !post.content) {
    return '';
  }
  return `${post.nickname}: ${post.content} (${post.postDate})`;
}
