export const EXCEL_MAX_CELL_CHARS = 32767;
export const EXCEL_TRUNCATION_SUFFIX = '\n…(이하 생략)';

const CONTROL_CHAR_PATTERN = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g;

export function sanitizeExcelText(value: unknown): string {
  if (value == null) {
    return '';
  }
  const sanitized = String(value).replace(CONTROL_CHAR_PATTERN, '').replace(/\r\n/g, '\n');
  if (sanitized.length <= EXCEL_MAX_CELL_CHARS) {
    return sanitized;
  }
  const maxContentLength = EXCEL_MAX_CELL_CHARS - EXCEL_TRUNCATION_SUFFIX.length;
  return sanitized.slice(0, maxContentLength) + EXCEL_TRUNCATION_SUFFIX;
}

export const EXCEL_HEADER_FILL = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FF375623' },
};

export const EXCEL_HEADER_FONT = {
  bold: true,
  color: { argb: 'FFFFFFFF' },
  size: 11,
};

export const CRAWL_LOG_HEADER_FILL = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FF1F4E78' },
};
