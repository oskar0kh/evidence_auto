import type ExcelJS from 'exceljs';
import { sanitizeExcelText } from './excelUtils';

export type HighlightCellValue = string | ExcelJS.CellRichTextValue;

export function parseSearchTerms(query: string | undefined): string[] {
  if (!query?.trim()) {
    return [];
  }

  return query
    .trim()
    .split(/[,\s]+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
}

function findBoldRanges(text: string, searchTerms: string[]): Array<{ start: number; end: number }> {
  const lowerText = text.toLowerCase();
  const sortedTerms = [...searchTerms].sort((a, b) => b.length - a.length);
  const ranges: Array<{ start: number; end: number }> = [];

  let index = 0;
  while (index < text.length) {
    let matched: { start: number; end: number } | null = null;

    for (const term of sortedTerms) {
      const lowerTerm = term.toLowerCase();
      if (lowerText.startsWith(lowerTerm, index)) {
        matched = { start: index, end: index + term.length };
        break;
      }
    }

    if (matched) {
      ranges.push(matched);
      index = matched.end;
    } else {
      index += 1;
    }
  }

  return ranges;
}

export function buildBoldHighlightCellValue(
  value: unknown,
  searchTerms: string[]
): HighlightCellValue {
  const text = sanitizeExcelText(value);
  if (!text || searchTerms.length === 0) {
    return text;
  }

  const ranges = findBoldRanges(text, searchTerms);
  if (ranges.length === 0) {
    return text;
  }

  const richText: ExcelJS.RichText[] = [];
  let cursor = 0;

  for (const range of ranges) {
    if (cursor < range.start) {
      richText.push({ text: text.slice(cursor, range.start) });
    }
    richText.push({
      text: text.slice(range.start, range.end),
      font: { bold: true },
    });
    cursor = range.end;
  }

  if (cursor < text.length) {
    richText.push({ text: text.slice(cursor) });
  }

  return { richText };
}

export function plainTextFromCellValue(value: unknown): string {
  if (value == null) {
    return '';
  }
  if (typeof value === 'object' && value !== null && 'richText' in value) {
    const richText = (value as ExcelJS.CellRichTextValue).richText;
    return richText.map((segment) => segment.text).join('');
  }
  return sanitizeExcelText(value);
}
