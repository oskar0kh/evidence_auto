export const SCREENSHOT_DIR = 'Screenshot';

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

/** shard 폴더명: 연번 시작~끝 범위 (예: 1-200, 401-527) */
export function buildShardFolderName(startSerial: number, endSerial: number): string {
  return `${startSerial}-${endSerial}`;
}

export function sanitizeFilenamePart(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '_').trim() || '미상';
}

function formatGalleryFilenamePart(galleryName: string): string {
  const trimmed = galleryName.trim();
  const withSuffix = trimmed.endsWith('갤러리') ? trimmed : `${trimmed} 갤러리`;
  return sanitizeFilenamePart(withSuffix);
}

export const DCINSIDE_COMMUNITY_NAME = '디시인사이드';

export interface ExcelFilenameOptions {
  keyword?: string;
  communityName?: string;
  galleryName?: string;
  stamp: string;
  part?: number;
}

/**
 * 엑셀 파일명 규칙:
 * - 검색어 O, 갤러리 미지정: 범죄일람표_{검색어}_{커뮤니티명}_{YYYYMMDD_HHMM}.xlsx
 * - 검색어 O, 갤러리 지정: 범죄일람표_{검색어}_{커뮤니티명}_{갤러리명}갤러리_{YYYYMMDD_HHMM}.xlsx
 * - 검색어 X (URL 직접입력): 범죄일람표_{커뮤니티명}_{YYYYMMDD_HHMM}.xlsx
 */
export function buildExcelFilename(options: ExcelFilenameOptions): string {
  const {
    keyword,
    communityName = DCINSIDE_COMMUNITY_NAME,
    galleryName,
    stamp,
    part,
  } = options;
  const trimmedKeyword = keyword?.trim();
  const suffix = part && part > 0 ? `_${stamp}_${part}` : `_${stamp}`;
  const parts = ['범죄일람표'];
  if (trimmedKeyword) {
    parts.push(sanitizeFilenamePart(trimmedKeyword));
  }
  parts.push(sanitizeFilenamePart(communityName));
  if (galleryName?.trim()) {
    parts.push(formatGalleryFilenamePart(galleryName));
  }
  return `${parts.join('_')}${suffix}.xlsx`;
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
