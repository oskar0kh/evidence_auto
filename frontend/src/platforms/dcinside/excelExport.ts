import ExcelJS from 'exceljs';
import { extractBodySection, extractCommentsSection } from './commentSection';
import {
  tryReadFileFromDirectory,
  writeArrayBufferToDirectory,
} from '../../shared/lib/localFileStorage';
import {
  buildExcelFilename,
  extractSerialFromCaptureFilename,
  getCaptureFilename,
  toCaptureHyperlink,
} from '../../features/export/pathUtils';
import type { DcinsidePostData } from './types';

export interface ExportCrimeListOptions {
  communityName: string;
  keyword?: string;
  stamp: string;
}

const SHEET_NAME = '범죄일람표';
const DATA_START_ROW = 3;

const COLUMNS = [
  { header: '연번', key: 'serial', width: 8 },
  { header: '게시일자', key: 'postDate', width: 22 },
  { header: '갤러리명', key: 'galleryName', width: 18 },
  { header: '닉네임', key: 'nickname', width: 18 },
  { header: '게시글 제목', key: 'title', width: 40 },
  { header: '내용', key: 'content', width: 80 },
  { header: '댓글 작성자·내용·일시', key: 'commentSection', width: 60 },
  { header: '비고', key: 'remarks', width: 40 },
  { header: 'URL', key: 'url', width: 50 },
  { header: '연번표시 캡처파일 (캡처파일 디렉토리)', key: 'captureFile', width: 45 },
  { header: '캡처 썸네일', key: 'captureThumbnail', width: 48 },
  { header: '죄명', key: 'crimeType', width: 20 },
] as const;

const CAPTURE_COLUMN = 10;
const THUMBNAIL_COLUMN = 11;
const URL_COLUMN = 9;

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

function columnWidthToPixels(columnWidth: number): number {
  return Math.max(1, Math.floor(((256 * columnWidth + Math.floor(128 / 7)) / 256) * 7));
}

function rowHeightToPixels(rowHeightPt: number): number {
  return Math.max(1, Math.floor((rowHeightPt * 96) / 72));
}

function loadImageFromBase64(base64: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('캡처 이미지를 읽을 수 없습니다.'));
    image.src = `data:image/png;base64,${base64}`;
  });
}

function rowHeightForContainedImage(
  imageWidth: number,
  imageHeight: number,
  columnWidth: number
): number {
  if (imageWidth <= 0 || imageHeight <= 0) {
    return MIN_ROW_HEIGHT;
  }
  const cellWidthPx = columnWidthToPixels(columnWidth);
  const scale = cellWidthPx / imageWidth;
  const scaledHeightPx = imageHeight * scale;
  const heightPt = (scaledHeightPx * 72) / 96 + ROW_PADDING_PT;
  return Math.min(MAX_ROW_HEIGHT, Math.max(MIN_ROW_HEIGHT, heightPt));
}

function fitThumbnailToPngBase64(
  image: HTMLImageElement,
  cellWidthPx: number,
  cellHeightPx: number
): { base64: string; width: number; height: number } {
  const canvas = document.createElement('canvas');
  canvas.width = cellWidthPx;
  canvas.height = cellHeightPx;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('캔버스를 사용할 수 없습니다.');
  }

  ctx.fillStyle = '#FFFFFF';
  ctx.fillRect(0, 0, cellWidthPx, cellHeightPx);

  const scale = Math.min(cellWidthPx / image.width, cellHeightPx / image.height);
  const drawWidth = image.width * scale;
  const drawHeight = image.height * scale;
  const offsetX = (cellWidthPx - drawWidth) / 2;
  const offsetY = (cellHeightPx - drawHeight) / 2;
  ctx.drawImage(image, offsetX, offsetY, drawWidth, drawHeight);

  const dataUrl = canvas.toDataURL('image/png');
  const commaIndex = dataUrl.indexOf(',');
  return {
    base64: commaIndex >= 0 ? dataUrl.slice(commaIndex + 1) : dataUrl,
    width: cellWidthPx,
    height: cellHeightPx,
  };
}

function getUrlFromRow(sheet: ExcelJS.Worksheet, rowIndex: number): string {
  const cellValue = sheet.getRow(rowIndex).getCell(URL_COLUMN).value;
  if (cellValue && typeof cellValue === 'object' && 'text' in cellValue) {
    return String((cellValue as { text: string }).text).trim();
  }
  return String(cellValue ?? '').trim();
}

function findRowIndexByUrl(sheet: ExcelJS.Worksheet, url: string): number | null {
  const normalized = url.trim();
  if (!normalized) {
    return null;
  }

  const lastRow = sheet.lastRow?.number ?? DATA_START_ROW - 1;
  for (let rowIndex = DATA_START_ROW; rowIndex <= lastRow; rowIndex++) {
    if (getUrlFromRow(sheet, rowIndex) === normalized) {
      return rowIndex;
    }
  }
  return null;
}

function getNextDataRow(sheet: ExcelJS.Worksheet): number {
  const lastRow = sheet.lastRow?.number ?? DATA_START_ROW - 1;
  return Math.max(lastRow + 1, DATA_START_ROW);
}

function setupNewCrimeListSheet(workbook: ExcelJS.Workbook): ExcelJS.Worksheet {
  const sheet = workbook.addWorksheet(SHEET_NAME, {
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

  return sheet;
}

async function loadOrCreateCrimeListWorkbook(
  directory: FileSystemDirectoryHandle,
  filename: string
): Promise<{ workbook: ExcelJS.Workbook; sheet: ExcelJS.Worksheet }> {
  const workbook = new ExcelJS.Workbook();
  const existing = await tryReadFileFromDirectory(directory, filename);

  if (existing) {
    await workbook.xlsx.load(existing);
    const sheet = workbook.getWorksheet(SHEET_NAME) ?? workbook.worksheets[0];
    if (!sheet) {
      throw new Error('기존 범죄일람표 파일을 읽을 수 없습니다.');
    }
    return { workbook, sheet };
  }

  workbook.creator = '범죄일람표 크롤러';
  const sheet = setupNewCrimeListSheet(workbook);
  return { workbook, sheet };
}

async function writePostToSheet(
  workbook: ExcelJS.Workbook,
  sheet: ExcelJS.Worksheet,
  post: DcinsidePostData,
  rowIndex: number,
  serial: number
): Promise<void> {
  const captureFilename = getCaptureFilename(post.captureFilePath);
  const commentSection = extractCommentsSection(post.content);
  const bodySection = extractBodySection(post.content);

  const rowValues = [
    serial,
    sanitizeExcelText(post.postDate),
    sanitizeExcelText(post.galleryName ?? ''),
    sanitizeExcelText(post.nickname),
    sanitizeExcelText(post.title),
    sanitizeExcelText(bodySection),
    sanitizeExcelText(commentSection),
    sanitizeExcelText(post.remarks),
    sanitizeExcelText(post.url),
    sanitizeExcelText(post.captureFilePath),
    '',
    sanitizeExcelText(post.crimeType || ''),
  ];

  const row = sheet.getRow(rowIndex);
  let rowHeight = calculateRowHeight(rowValues);
  let captureImage: HTMLImageElement | null = null;

  if (post.captureImageBase64) {
    captureImage = await loadImageFromBase64(post.captureImageBase64);
    const thumbnailColumnWidth = COLUMNS[THUMBNAIL_COLUMN - 1].width;
    const thumbnailRowHeight = rowHeightForContainedImage(
      captureImage.width,
      captureImage.height,
      thumbnailColumnWidth
    );
    rowHeight = Math.max(rowHeight, thumbnailRowHeight);
  }

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

  if (captureImage) {
    const thumbnailColumnWidth = COLUMNS[THUMBNAIL_COLUMN - 1].width;
    const cellWidthPx = columnWidthToPixels(thumbnailColumnWidth);
    const cellHeightPx = rowHeightToPixels(rowHeight);
    const thumbnail = fitThumbnailToPngBase64(captureImage, cellWidthPx, cellHeightPx);
    const imageId = workbook.addImage({
      base64: thumbnail.base64,
      extension: 'png',
    });
    const tlCol = THUMBNAIL_COLUMN - 1;
    const tlRow = rowIndex - 1;
    sheet.addImage(imageId, {
      tl: { col: tlCol, row: tlRow },
      br: { col: tlCol + 1, row: tlRow + 1 },
    } as ExcelJS.ImageRange);
  }
}

export async function appendCrimeListExcel(
  posts: DcinsidePostData[],
  directory: FileSystemDirectoryHandle,
  excelFilename: string
): Promise<void> {
  if (posts.length === 0) {
    return;
  }

  const { workbook, sheet } = await loadOrCreateCrimeListWorkbook(directory, excelFilename);

  for (const post of posts) {
    const captureFilename = getCaptureFilename(post.captureFilePath);
    const serial =
      extractSerialFromCaptureFilename(captureFilename) ??
      getNextDataRow(sheet) - DATA_START_ROW + 1;
    const existingRow = findRowIndexByUrl(sheet, post.url);
    const rowIndex = existingRow ?? getNextDataRow(sheet);
    await writePostToSheet(workbook, sheet, post, rowIndex, serial);
  }

  const buffer = await workbook.xlsx.writeBuffer();
  await writeArrayBufferToDirectory(directory, excelFilename, buffer as ArrayBuffer);
}

export async function exportCrimeListExcel(
  posts: DcinsidePostData[],
  directory: FileSystemDirectoryHandle,
  options: ExportCrimeListOptions
): Promise<void> {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = '범죄일람표 크롤러';
  const sheet = setupNewCrimeListSheet(workbook);

  for (let index = 0; index < posts.length; index++) {
    const post = posts[index];
    const captureFilename = getCaptureFilename(post.captureFilePath);
    const serial =
      extractSerialFromCaptureFilename(captureFilename) ?? index + 1;
    await writePostToSheet(workbook, sheet, post, index + DATA_START_ROW, serial);
  }

  const buffer = await workbook.xlsx.writeBuffer();
  const filename = buildExcelFilename(options.communityName, options.keyword, options.stamp);
  await writeArrayBufferToDirectory(directory, filename, buffer as ArrayBuffer);
}
