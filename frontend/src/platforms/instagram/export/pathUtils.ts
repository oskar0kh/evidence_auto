export const SCREENSHOT_DIR = 'Screenshot';

export const INSTAGRAM_COMMUNITY_NAME = '인스타그램';

export function getCaptureFilename(pathOrFilename: string): string {
  const parts = pathOrFilename.split(/[/\\]/);
  return parts[parts.length - 1] || pathOrFilename;
}

export function formatTimestamp(date = new Date()): string {
  return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}_${String(date.getHours()).padStart(2, '0')}${String(date.getMinutes()).padStart(2, '0')}`;
}

export function buildResultFolderName(stamp: string, part?: number): string {
  return part && part > 0 ? `결과물_${stamp}_${part}` : `결과물_${stamp}`;
}

export function buildShardFolderName(startSerial: number, endSerial: number): string {
  return `${startSerial}-${endSerial}`;
}

export function sanitizeFilenamePart(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '_').trim() || '미상';
}

export interface ExcelFilenameOptions {
  keyword?: string;
  communityName?: string;
  stamp: string;
  part?: number;
}

export function buildExcelFilename(options: ExcelFilenameOptions): string {
  const { keyword, communityName = INSTAGRAM_COMMUNITY_NAME, stamp, part } = options;
  const trimmedKeyword = keyword?.trim();
  const suffix = part && part > 0 ? `_${stamp}_${part}` : `_${stamp}`;
  const parts = ['범죄일람표'];
  if (trimmedKeyword) {
    parts.push(sanitizeFilenamePart(trimmedKeyword));
  }
  parts.push(sanitizeFilenamePart(communityName));
  return `${parts.join('_')}${suffix}.xlsx`;
}

export function toCaptureRelativePath(filename: string): string {
  const normalized = getCaptureFilename(filename).replace(/\\/g, '/');
  return `${SCREENSHOT_DIR}/${normalized}`;
}

export function toCaptureHyperlink(filename: string): string {
  return `./${toCaptureRelativePath(filename)}`;
}

export function extractSerialFromCaptureFilename(filename: string): number | null {
  const match = getCaptureFilename(filename).match(/연번\s*(\d+)/);
  return match ? parseInt(match[1], 10) : null;
}

export function rowDedupKey(post: { url: string; commentPk?: string }): string {
  if (post.commentPk?.trim()) {
    return `${post.url}#comment:${post.commentPk}`;
  }
  return `${post.url}#post`;
}
