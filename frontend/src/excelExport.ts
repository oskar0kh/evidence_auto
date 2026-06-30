import ExcelJS from 'exceljs';
import { addSpacingBetweenLines, extractBodySection, extractCommentsSection } from './commentSection';
import { writeArrayBufferToDirectory } from './localFileStorage';
import { getCaptureFilename, toSameFolderCaptureHyperlink } from './pathUtils';
import type { DcinsidePostData } from './types';

const COLUMNS = [
  { header: '연번', key: 'serial', width: 8 },
  { header: '게시일자', key: 'postDate', width: 22 },
  { header: '닉네임', key: 'nickname', width: 18 },
  { header: 'URL', key: 'url', width: 50 },
  { header: '게시글 제목', key: 'title', width: 40 },
  { header: '내용', key: 'content', width: 80 },
  { header: '댓글 작성자·내용·일시', key: 'commentSection', width: 60 },
  { header: '비고', key: 'remarks', width: 40 },
  { header: '연번표시 캡처파일 (캡처파일 디렉토리)', key: 'captureFile', width: 45 },
  { header: '캡처 썸네일', key: 'captureThumbnail', width: 48 },
] as const;

const CAPTURE_COLUMN = 9;
const THUMBNAIL_COLUMN = 10;
const URL_COLUMN = 4;

const THUMBNAIL_MAX_WIDTH_PX = 320;
const PIXELS_TO_POINTS = 0.75;

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

const CAPTURE_HEADER_FILL = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FFBDD7EE' },
};

const MIN_ROW_HEIGHT = 24;
const LINE_HEIGHT_PT = 15;
const ROW_PADDING_PT = 10;

function stringCellValue(value: unknown): string {
  if (value == null) {
    return '';
  }
  return String(value);
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

  return Math.max(MIN_ROW_HEIGHT, maxLines * LINE_HEIGHT_PT + ROW_PADDING_PT);
}

function getImageDimensions(base64: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight });
    image.onerror = () => reject(new Error('캡처 이미지를 읽을 수 없습니다.'));
    image.src = `data:image/png;base64,${base64}`;
  });
}

function fitThumbnailSize(
  width: number,
  height: number,
  maxWidth: number,
  maxHeight: number
): { width: number; height: number } {
  const scale = Math.min(maxWidth / width, maxHeight / height, 1);
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

function rowHeightToThumbnailMaxPx(rowHeightPt: number): number {
  return Math.max(1, Math.round((rowHeightPt - ROW_PADDING_PT) / PIXELS_TO_POINTS));
}

export async function exportCrimeListExcel(
  posts: DcinsidePostData[],
  directory: FileSystemDirectoryHandle
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
  headerRow.eachCell((cell, colNumber) => {
    cell.font = HEADER_FONT;
    cell.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    if (colNumber === CAPTURE_COLUMN || colNumber === THUMBNAIL_COLUMN) {
      cell.fill = CAPTURE_HEADER_FILL;
    } else {
      cell.fill = HEADER_FILL;
    }
    cell.border = {
      top: { style: 'thin' },
      left: { style: 'thin' },
      bottom: { style: 'thin' },
      right: { style: 'thin' },
    };
  });

  for (let index = 0; index < posts.length; index++) {
    const post = posts[index];
    const serial = index + 1;
    const commentSection = addSpacingBetweenLines(extractCommentsSection(post.content));
    const bodySection = addSpacingBetweenLines(extractBodySection(post.content));

    const rowValues = [
      serial,
      post.postDate,
      post.nickname,
      post.url,
      post.title,
      bodySection,
      commentSection,
      post.remarks,
      post.captureFilePath,
      '',
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
      const url = post.url.trim();
      const urlCell = row.getCell(URL_COLUMN);
      urlCell.value = { text: url, hyperlink: url };
      urlCell.font = { color: { argb: 'FF0563C1' }, underline: true };
    }

    const captureFilename = getCaptureFilename(post.captureFilePath);
    if (captureFilename) {
      const captureCell = row.getCell(CAPTURE_COLUMN);
      captureCell.value = {
        text: captureFilename,
        hyperlink: toSameFolderCaptureHyperlink(captureFilename),
      };
      captureCell.font = { color: { argb: 'FF0563C1' }, underline: true };
    }

    if (post.captureImageBase64) {
      const dimensions = await getImageDimensions(post.captureImageBase64);
      const thumbnail = fitThumbnailSize(
        dimensions.width,
        dimensions.height,
        THUMBNAIL_MAX_WIDTH_PX,
        rowHeightToThumbnailMaxPx(rowHeight)
      );
      const imageId = workbook.addImage({
        base64: post.captureImageBase64,
        extension: 'png',
      });
      sheet.addImage(imageId, {
        tl: { col: THUMBNAIL_COLUMN - 1, row: index + 2 },
        ext: { width: thumbnail.width, height: thumbnail.height },
      });
    }
  }

  const buffer = await workbook.xlsx.writeBuffer();
  const now = new Date();
  const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}`;
  const filename = `범죄일람표_${stamp}.xlsx`;

  await writeArrayBufferToDirectory(directory, filename, buffer as ArrayBuffer);
}
