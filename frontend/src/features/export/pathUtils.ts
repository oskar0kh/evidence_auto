export const SCREENSHOT_DIR = 'Screenshot';

export function getCaptureFilename(pathOrFilename: string): string {
  const parts = pathOrFilename.split(/[/\\]/);
  return parts[parts.length - 1] || pathOrFilename;
}

export function formatTimestamp(date = new Date()): string {
  return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}_${String(date.getHours()).padStart(2, '0')}${String(date.getMinutes()).padStart(2, '0')}`;
}

export function buildResultFolderName(stamp: string): string {
  return `결과물_${stamp}`;
}

export function sanitizeFilenamePart(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '_').trim() || '미상';
}

export function buildExcelFilename(
  communityName: string,
  keyword: string | undefined,
  stamp: string
): string {
  const community = sanitizeFilenamePart(communityName);
  const trimmedKeyword = keyword?.trim();
  if (trimmedKeyword) {
    return `범죄일람표_${community}_${sanitizeFilenamePart(trimmedKeyword)}_${stamp}.xlsx`;
  }
  return `범죄일람표_${community}_${stamp}.xlsx`;
}

/** 캡처 PNG가 Screenshot 하위 폴더에 있을 때 엑셀과 같은 결과물 폴더 기준 상대 경로 */
export function toCaptureRelativePath(filename: string): string {
  const normalized = getCaptureFilename(filename).replace(/\\/g, '/');
  return `${SCREENSHOT_DIR}/${normalized}`;
}

/** 엑셀(.xlsx)과 같은 결과물 폴더 기준 Screenshot 하위 캡처 PNG 하이퍼링크 */
export function toCaptureHyperlink(filename: string): string {
  return `./${toCaptureRelativePath(filename)}`;
}

export function extractSerialFromCaptureFilename(filename: string): number | null {
  const match = getCaptureFilename(filename).match(/연번\s*(\d+)/);
  return match ? parseInt(match[1], 10) : null;
}
