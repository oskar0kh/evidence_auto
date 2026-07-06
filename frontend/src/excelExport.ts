import ExcelJS from 'exceljs';
import { extractBodySection, extractCommentsSection } from './commentSection';
import { writeArrayBufferToDirectory } from './localFileStorage';
import {
  buildExcelFilename,
  extractSerialFromCaptureFilename,
  getCaptureFilename,
  toCaptureHyperlink,
} from './pathUtils';
import type { DcinsidePostData } from './types';

export interface ExportCrimeListOptions {
  communityName: string;
  keyword?: string;
  stamp: string;
}

const COLUMNS = [
  { header: '연번', key: 'serial', width: 8 },
  { header: '게시일자', key: 'postDate', width: 22 },
  { header: '갤러리명', key: 'galleryName', width: 18 },
  { header: '닉네임', key: 'nickname', width: 18 },
  { header: 'URL', key: 'url', width: 50 },
  { header: '게시글 제목', key: 'title', width: 40 },
  { header: '내용', key: 'content', width: 80 },
  { header: '댓글 작성자·내용·일시', key: 'commentSection', width: 60 },
  { header: '비고', key: 'remarks', width: 40 },
  { header: '연번표시 캡처파일 (캡처파일 디렉토리)', key: 'captureFile', width: 45 },
  { header: '캡처 썸네일', key: 'captureThumbnail', width: 48 },
  { header: '죄명', key: 'crimeType', width: 20 },
] as const;

const CAPTURE_COLUMN = 10;
const THUMBNAIL_COLUMN = 11;
const URL_COLUMN = 5;

const HEADER_FILL = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FF375623' },
};

const HEADER_FONT = {
  bold: true,
  color: { argb: 'FFFFFFFF' },
  size: 11,
};

const MIN_ROW_HEIGHT = 24;
const MAX_ROW_HEIGHT = 250;
const LINE_HEIGHT_PT = 15;
const ROW_PADDING_PT = 10;
const EXCEL_MAX_CELL_CHARS = 32767;
const EXCEL_TRUNCATION_SUFFIX = '\n…(이하 생략)';

function sanitizeExcelText(value: unknown): string {
  if (value == null) {
    return '';
  }
  // XML 1.0에서 허용되지 않는 제어 문자 제거 (탭·LF·CR은 유지)
  const sanitized = String(value).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '');
  if (sanitized.length <= EXCEL_MAX_CELL_CHARS) {
    return sanitized;
  }
  const maxContentLength = EXCEL_MAX_CELL_CHARS - EXCEL_TRUNCATION_SUFFIX.length;
  return sanitized.slice(0, maxContentLength) + EXCEL_TRUNCATION_SUFFIX;
}

function stringCellValue(value: unknown): string {
  return sanitizeExcelText(value);
}

function estimateWrappedLines(text: string, columnWidth: number): number {
  if (!text) {
    return 1;
  }

  const charsPerLine = Math.max(1, Math.floor(columnWidth * 0.85));
  return text.split('\n').reduce((lineCount, line) => {
    return lineCount + Math.max(1, Math.ceil(line.length / charsPerLine));
  }, 0);
}

function calculateRowHeight(values: unknown[]): number {
  let maxLines = 1;

  values.forEach((value, index) => {
    const columnWidth = COLUMNS[index]?.width ?? 10;
    const lines = estimateWrappedLines(stringCellValue(value), columnWidth);
    maxLines = Math.max(maxLines, lines);
  });

  const contentHeight = maxLines * LINE_HEIGHT_PT + ROW_PADDING_PT;
  return Math.min(MAX_ROW_HEIGHT, Math.max(MIN_ROW_HEIGHT, contentHeight));
}

/** Excel 열 너비(문자 단위) → 화면 픽셀 (Calibri 11pt 기준) */
function columnWidthToPixels(columnWidth: number): number {
  return Math.max(1, Math.floor(((256 * columnWidth + Math.floor(128 / 7)) / 256) * 7));
}

/** Excel 행 높이(pt) → 화면 픽셀 */
function rowHeightToPixels(rowHeightPt: number): number {
  return Math.max(1, Math.floor((rowHeightPt * 96) / 72));
}

/** 원본 크기(1:1)로 셀 좌상단에 배치하고, 셀 경계를 벗어나는 부분은 잘라냅니다. */
function cropThumbnailToPngBase64(
  base64: string,
  cellWidthPx: number,
  cellHeightPx: number
): Promise<{ base64: string; width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = cellWidthPx;
      canvas.height = cellHeightPx;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        reject(new Error('캔버스를 사용할 수 없습니다.'));
        return;
      }
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, cellWidthPx, cellHeightPx);
      ctx.drawImage(image, 0, 0);

      const dataUrl = canvas.toDataURL('image/png');
      const commaIndex = dataUrl.indexOf(',');
      resolve({
        base64: commaIndex >= 0 ? dataUrl.slice(commaIndex + 1) : dataUrl,
        width: cellWidthPx,
        height: cellHeightPx,
      });
    };
    image.onerror = () => reject(new Error('캡처 이미지를 읽을 수 없습니다.'));
    image.src = `data:image/png;base64,${base64}`;
  });
}

export async function exportCrimeListExcel(
  posts: DcinsidePostData[],
  directory: FileSystemDirectoryHandle,
  options: ExportCrimeListOptions
): Promise<void> {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = '범죄일람표 크롤러';
  const sheet = workbook.addWorksheet('범죄일람표', {
    views: [{ state: 'frozen', ySplit: 2 }],
  });

  sheet.columns = COLUMNS.map((col) => ({
    key: col.key,
    width: col.width,
  }));

  const titleRow = sheet.getRow(1);
  titleRow.getCell(1).value = '범죄일람표';
  titleRow.getCell(1).font = { bold: true, size: 14 };
  sheet.mergeCells(1, 1, 1, COLUMNS.length);

  const headerRow = sheet.getRow(2);
  COLUMNS.forEach((col, i) => {
    headerRow.getCell(i + 1).value = col.header;
  });
  headerRow.height = 28;
  headerRow.eachCell((cell) => {
    cell.font = HEADER_FONT;
    cell.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    cell.fill = HEADER_FILL;
    cell.border = {
      top: { style: 'thin' },
      left: { style: 'thin' },
      bottom: { style: 'thin' },
      right: { style: 'thin' },
    };
  });

  for (let index = 0; index < posts.length; index++) {
    const post = posts[index];
    const captureFilename = getCaptureFilename(post.captureFilePath);
    const serial =
      extractSerialFromCaptureFilename(captureFilename) ?? index + 1;
    const commentSection = extractCommentsSection(post.content);
    const bodySection = extractBodySection(post.content);

    const rowValues = [
      serial,
      sanitizeExcelText(post.postDate),
      sanitizeExcelText(post.galleryName ?? ''),
      sanitizeExcelText(post.nickname),
      sanitizeExcelText(post.url),
      sanitizeExcelText(post.title),
      sanitizeExcelText(bodySection),
      sanitizeExcelText(commentSection),
      sanitizeExcelText(post.remarks),
      sanitizeExcelText(post.captureFilePath),
      '',
      sanitizeExcelText(post.crimeType || ''),
    ];

    const row = sheet.getRow(index + 3);
    const rowHeight = calculateRowHeight(rowValues);
    row.values = rowValues;
    row.height = rowHeight;
    row.eachCell((cell) => {
      cell.alignment = { vertical: 'top', horizontal: 'left', wrapText: true };
      cell.border = {
        top: { style: 'thin' },
        left: { style: 'thin' },
        bottom: { style: 'thin' },
        right: { style: 'thin' },
      };
    });

    const thumbnailCell = row.getCell(THUMBNAIL_COLUMN);
    thumbnailCell.alignment = { vertical: 'middle', horizontal: 'center', wrapText: false };

    if (post.url?.trim()) {
      const url = sanitizeExcelText(post.url.trim());
      const urlCell = row.getCell(URL_COLUMN);
      urlCell.value = { text: url, hyperlink: url };
      urlCell.font = { color: { argb: 'FF0563C1' }, underline: true };
    }

    if (captureFilename) {
      const captureCell = row.getCell(CAPTURE_COLUMN);
      const safeFilename = sanitizeExcelText(post.captureFilePath);
      captureCell.value = {
        text: safeFilename,
        hyperlink: toCaptureHyperlink(captureFilename),
      };
      captureCell.font = { color: { argb: 'FF0563C1' }, underline: true };
    }

    if (post.captureImageBase64) {
      const thumbnailColumnWidth = COLUMNS[THUMBNAIL_COLUMN - 1].width;
      const cellWidthPx = columnWidthToPixels(thumbnailColumnWidth);
      const cellHeightPx = rowHeightToPixels(rowHeight);
      const thumbnail = await cropThumbnailToPngBase64(
        post.captureImageBase64,
        cellWidthPx,
        cellHeightPx
      );
      const imageId = workbook.addImage({
        base64: thumbnail.base64,
        extension: 'png',
      });
      const tlCol = THUMBNAIL_COLUMN - 1;
      const tlRow = index + 2;
      sheet.addImage(imageId, {
        tl: { col: tlCol, row: tlRow },
        br: { col: tlCol + 1, row: tlRow + 1 },
      } as ExcelJS.ImageRange);
    }
  }

  const buffer = await workbook.xlsx.writeBuffer();
  const filename = buildExcelFilename(options.communityName, options.keyword, options.stamp);

  await writeArrayBufferToDirectory(directory, filename, buffer as ArrayBuffer);
}
