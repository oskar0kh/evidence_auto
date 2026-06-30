import ExcelJS from 'exceljs';
import { writeArrayBufferToDirectory } from './localFileStorage';
import type { DcinsidePostData } from './types';

const COLUMNS = [
  { header: '연번', key: 'serial', width: 8 },
  { header: '게시일자', key: 'postDate', width: 22 },
  { header: '닉네임', key: 'nickname', width: 18 },
  { header: 'URL', key: 'url', width: 50 },
  { header: '작성 형태(게시글/댓글)', key: 'writeType', width: 18 },
  { header: '원글 내용(댓글) 또는 게시글 제목(게시글)', key: 'title', width: 40 },
  { header: '내용', key: 'content', width: 80 },
  { header: '죄명', key: 'crimeType', width: 20 },
  { header: '비고', key: 'remarks', width: 40 },
  { header: '연번표시 캡처파일 (캡처파일 디렉토리)', key: 'captureFile', width: 45 },
] as const;

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

const CONTENT_HIGHLIGHT = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FFFFFF00' },
};

const CAPTURE_HEADER_FILL = {
  type: 'pattern' as const,
  pattern: 'solid' as const,
  fgColor: { argb: 'FFBDD7EE' },
};


export async function exportCrimeListExcel(
  posts: DcinsidePostData[],
  directory: FileSystemDirectoryHandle
): Promise<string> {
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
    if (colNumber === 10) {
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

  posts.forEach((post, index) => {
    const serial = index + 1;

    const row = sheet.getRow(index + 3);
    row.values = [
      serial,
      post.postDate,
      post.nickname,
      post.url,
      post.writeType,
      post.title,
      post.content,
      post.crimeType || '',
      post.remarks,
      post.captureFilePath,
    ];

    row.height = 80;
    row.eachCell((cell, colNumber) => {
      cell.alignment = { vertical: 'top', horizontal: 'left', wrapText: true };
      cell.border = {
        top: { style: 'thin' },
        left: { style: 'thin' },
        bottom: { style: 'thin' },
        right: { style: 'thin' },
      };
      if (colNumber === 7) {
        cell.fill = CONTENT_HIGHLIGHT;
      }
    });
  });

  const buffer = await workbook.xlsx.writeBuffer();
  const now = new Date();
  const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}`;
  const filename = `범죄일람표_${stamp}.xlsx`;

  await writeArrayBufferToDirectory(directory, filename, buffer as ArrayBuffer);

  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);

  return `${directory.name}/${filename}`;
}
