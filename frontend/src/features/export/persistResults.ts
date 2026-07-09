import ExcelJS from 'exceljs';
import { saveCaptureToScreenshotDir } from '../crawl/captureFiles';
import { getOrCreateLogDirectory } from '../crawl/crawlLogExport';
import {
  getOrCreateSubdirectory,
  writeArrayBufferToDirectory,
  writeBlobToDirectory,
} from '../../shared/lib/localFileStorage';
import {
  addPostRowToWorkbook,
  createCrimeListWorkbook,
  serializeCrimeListWorkbook,
} from '../../platforms/dcinside/excelExport';
import { EXCEL_FLUSH_INTERVAL, EXCEL_SHARD_ROW_LIMIT } from '../crawl/constants';
import type { DcinsidePostData } from '../../platforms/dcinside/types';
import {
  buildExcelFilename,
  buildResultFolderName,
  buildShardFolderName,
  formatTimestamp,
  getCaptureFilename,
  SCREENSHOT_DIR,
  toCaptureRelativePath,
} from './pathUtils';

export interface PersistResultsOptions {
  keyword?: string;
  galleryName?: string;
  communityName?: string;
}

interface ShardState {
  resultDir: FileSystemDirectoryHandle;
  screenshotDir: FileSystemDirectoryHandle;
  excelFilename: string;
  workbook: ExcelJS.Workbook;
  sheet: ExcelJS.Worksheet;
  /** 이 shard의 첫 게시글 연번(1-base) */
  startSerial: number;
  /** 현재 shard 폴더명(예: 1-200). 마지막 shard 확정 시 실제 개수로 보정될 수 있다. */
  folderName: string;
  /** 현재 shard에 기록된 데이터 행 수 */
  rows: number;
  /** 마지막 flush 이후 아직 디스크에 반영되지 않은 행 수 */
  dirtySinceFlush: number;
}

/**
 * 크롤링 1회 실행 동안 유지되는 저장 세션.
 * - 실행마다 결과물_{stamp} 루트 폴더를 하나 만들고, 그 아래에 연번 범위 shard 폴더를 쌓는다.
 * - 워크북을 메모리에 들고 있다가 주기적으로만 디스크에 flush한다(반복 재로드 제거).
 * - shard(엑셀 파일 + 이미지 폴더)를 최대 EXCEL_SHARD_ROW_LIMIT행까지만 채우고 롤오버한다.
 * - seenUrls로 canonical URL 기준 전역 중복을 건너뛴다(파일이 나뉘어도 dedup 유지).
 */
export interface CrawlPersistSession {
  directory: FileSystemDirectoryHandle;
  /** 결과물_{stamp} 루트 폴더 핸들 */
  resultsRoot: FileSystemDirectoryHandle;
  stamp: string;
  keyword?: string;
  galleryName?: string;
  communityName?: string;
  seenUrls: Set<string>;
  shardIndex: number;
  shard: ShardState | null;
}

export function stripCaptureBase64(post: DcinsidePostData): DcinsidePostData {
  return { ...post, captureImageBase64: '' };
}

export async function createCrawlPersistSession(
  directory: FileSystemDirectoryHandle,
  options: PersistResultsOptions
): Promise<CrawlPersistSession> {
  await getOrCreateLogDirectory(directory);
  const stamp = formatTimestamp();
  const resultsRoot = await getOrCreateSubdirectory(
    directory,
    buildResultFolderName(stamp)
  );
  return {
    directory,
    resultsRoot,
    stamp,
    keyword: options.keyword,
    galleryName: options.galleryName,
    communityName: options.communityName,
    seenUrls: new Set<string>(),
    shardIndex: 0,
    shard: null,
  };
}

async function openNextShard(session: CrawlPersistSession): Promise<ShardState> {
  session.shardIndex += 1;
  const part = session.shardIndex;
  const startSerial = (part - 1) * EXCEL_SHARD_ROW_LIMIT + 1;
  // 폴더 생성 시점엔 실제 개수를 알 수 없어 우선 가득 찬 범위로 이름을 정하고,
  // 마지막(미완성) shard는 finalize 때 실제 개수로 보정한다.
  const optimisticEnd = startSerial + EXCEL_SHARD_ROW_LIMIT - 1;
  const folderName = buildShardFolderName(startSerial, optimisticEnd);
  const resultDir = await getOrCreateSubdirectory(session.resultsRoot, folderName);
  const screenshotDir = await getOrCreateSubdirectory(resultDir, SCREENSHOT_DIR);
  const excelFilename = buildExcelFilename({
    keyword: session.keyword,
    communityName: session.communityName,
    galleryName: session.galleryName,
    stamp: session.stamp,
  });
  const { workbook, sheet } = createCrimeListWorkbook();
  const shard: ShardState = {
    resultDir,
    screenshotDir,
    excelFilename,
    workbook,
    sheet,
    startSerial,
    folderName,
    rows: 0,
    dirtySinceFlush: 0,
  };
  session.shard = shard;
  return shard;
}

async function flushShard(shard: ShardState): Promise<void> {
  if (shard.dirtySinceFlush === 0) {
    return;
  }
  const buffer = await serializeCrimeListWorkbook(shard.workbook);
  await writeArrayBufferToDirectory(shard.resultDir, shard.excelFilename, buffer);
  shard.dirtySinceFlush = 0;
}

async function ensureShardWithCapacity(session: CrawlPersistSession): Promise<ShardState> {
  if (!session.shard) {
    return openNextShard(session);
  }
  if (session.shard.rows >= EXCEL_SHARD_ROW_LIMIT) {
    await flushShard(session.shard);
    return openNextShard(session);
  }
  return session.shard;
}

export async function appendBatchToSession(
  session: CrawlPersistSession,
  posts: DcinsidePostData[]
): Promise<DcinsidePostData[]> {
  const savedForExcel: DcinsidePostData[] = [];

  for (const post of posts) {
    const url = post.url?.trim();
    if (url) {
      if (session.seenUrls.has(url)) {
        continue;
      }
      session.seenUrls.add(url);
    }

    const shard = await ensureShardWithCapacity(session);

    const postForExcel: DcinsidePostData = {
      ...post,
      captureFilePath: toCaptureRelativePath(getCaptureFilename(post.captureFilePath)),
    };

    await saveCaptureToScreenshotDir(shard.screenshotDir, post);
    await addPostRowToWorkbook(
      shard.workbook,
      shard.sheet,
      postForExcel,
      shard.startSerial + shard.rows
    );
    shard.rows += 1;
    shard.dirtySinceFlush += 1;

    if (shard.dirtySinceFlush >= EXCEL_FLUSH_INTERVAL) {
      await flushShard(shard);
    }

    savedForExcel.push(stripCaptureBase64(postForExcel));
  }

  return savedForExcel;
}

type DirectoryEntries = {
  entries(): AsyncIterableIterator<[string, FileSystemHandle]>;
};

async function copyDirectoryContents(
  src: FileSystemDirectoryHandle,
  dest: FileSystemDirectoryHandle
): Promise<void> {
  for await (const [name, handle] of (src as unknown as DirectoryEntries).entries()) {
    if (handle.kind === 'file') {
      const file = await (handle as FileSystemFileHandle).getFile();
      await writeBlobToDirectory(dest, name, file);
    } else {
      const subDest = await getOrCreateSubdirectory(dest, name);
      await copyDirectoryContents(handle as FileSystemDirectoryHandle, subDest);
    }
  }
}

/**
 * 마지막 shard 폴더명을 실제 수집 개수에 맞게 보정한다.
 * File System Access API가 디렉터리 이름 변경(move)을 지원하지 않아,
 * 올바른 이름의 폴더를 새로 만들고 내용을 복사한 뒤 기존 폴더를 제거한다.
 */
async function renameShardToActualRange(
  session: CrawlPersistSession,
  shard: ShardState
): Promise<void> {
  if (shard.rows === 0) {
    return;
  }
  const actualEnd = shard.startSerial + shard.rows - 1;
  const desiredName = buildShardFolderName(shard.startSerial, actualEnd);
  if (desiredName === shard.folderName) {
    return;
  }
  const newDir = await getOrCreateSubdirectory(session.resultsRoot, desiredName);
  await copyDirectoryContents(shard.resultDir, newDir);
  await session.resultsRoot.removeEntry(shard.folderName, { recursive: true });
  shard.resultDir = newDir;
  shard.folderName = desiredName;
}

/** 크롤링 종료 시 아직 디스크에 반영되지 않은 마지막 행들을 저장하고, 폴더명을 보정한다. */
export async function finalizeCrawlPersistSession(
  session: CrawlPersistSession
): Promise<void> {
  if (session.shard) {
    await flushShard(session.shard);
    await renameShardToActualRange(session, session.shard);
  }
}

/** 수동 저장: 전체 결과를 새 결과물 폴더 집합에 shard 단위로 내보낸다. */
export async function persistCrimeListAndCaptures(
  directory: FileSystemDirectoryHandle,
  posts: DcinsidePostData[],
  options: PersistResultsOptions
): Promise<{ stamp: string; postsForExcel: DcinsidePostData[] }> {
  const session = await createCrawlPersistSession(directory, options);
  const postsForExcel = await appendBatchToSession(session, posts);
  await finalizeCrawlPersistSession(session);
  return { stamp: session.stamp, postsForExcel };
}
