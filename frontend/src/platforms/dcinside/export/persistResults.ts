import ExcelJS from 'exceljs';
import { saveCaptureToScreenshotDir } from '../crawl/captureFiles';
import { getOrCreateLogDirectory } from '../crawl/crawlLogExport';
import {
  getOrCreateSubdirectory,
  writeArrayBufferToDirectory,
  writeBlobToDirectory,
} from '../../../shared/lib/localFileStorage';
import {
  addPostRowToWorkbook,
  createCrimeListWorkbook,
  mergeCrimeListWorkbooks,
  serializeCrimeListWorkbook,
} from '../excelExport';
import { EXCEL_FLUSH_INTERVAL, EXCEL_SHARD_ROW_LIMIT } from '../crawl/constants';
import type { DcinsidePostData } from '../types';
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
 * - 크롤 종료 시 finalizeCrawlPersistSession이 shard를 루트의 단일 엑셀·Screenshot으로 병합한다.
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
  /** 롤오버로 닫힌 shard(워크북·스크린샷 디렉터리 참조 유지). 종료 시 병합에 사용한다. */
  completedShards: ShardState[];
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
    completedShards: [],
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
    session.completedShards.push(session.shard);
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
      shard.startSerial + shard.rows,
      session.keyword
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

function collectShardsForMerge(session: CrawlPersistSession): ShardState[] {
  const shards = [...session.completedShards];
  if (session.shard && session.shard.rows > 0) {
    shards.push(session.shard);
  }
  return shards;
}

function loadImageFromObjectUrl(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('캡처 이미지를 읽을 수 없습니다.'));
    image.src = url;
  });
}

async function loadCaptureImageFromDirectory(
  screenshotDir: FileSystemDirectoryHandle,
  captureRelativePath: string
): Promise<HTMLImageElement | null> {
  const filename = getCaptureFilename(captureRelativePath);
  try {
    const fileHandle = await screenshotDir.getFileHandle(filename);
    const file = await fileHandle.getFile();
    const url = URL.createObjectURL(file);
    try {
      return await loadImageFromObjectUrl(url);
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch {
    return null;
  }
}

/**
 * shard별로 나뉜 범죄일람표·Screenshot을 결과물 루트의 단일 파일·폴더로 합친 뒤
 * shard 하위 폴더(예: 1-200)는 제거한다.
 */
async function mergeShardResults(session: CrawlPersistSession): Promise<void> {
  const shards = collectShardsForMerge(session);
  if (shards.length === 0) {
    return;
  }

  const excelFilename = buildExcelFilename({
    keyword: session.keyword,
    communityName: session.communityName,
    galleryName: session.galleryName,
    stamp: session.stamp,
  });
  const mergedScreenshotDir = await getOrCreateSubdirectory(
    session.resultsRoot,
    SCREENSHOT_DIR
  );

  for (const shard of shards) {
    await copyDirectoryContents(shard.screenshotDir, mergedScreenshotDir);
  }

  const resolveThumbnail = (captureRelativePath: string) =>
    loadCaptureImageFromDirectory(mergedScreenshotDir, captureRelativePath);

  const mergedWorkbook = await mergeCrimeListWorkbooks(
    shards.map((shard) => shard.workbook),
    resolveThumbnail
  );

  const buffer = await serializeCrimeListWorkbook(mergedWorkbook);
  await writeArrayBufferToDirectory(session.resultsRoot, excelFilename, buffer);

  for (const shard of shards) {
    try {
      await session.resultsRoot.removeEntry(shard.folderName, { recursive: true });
    } catch (error) {
      console.warn(`shard 폴더 제거 실패 (${shard.folderName}):`, error);
    }
  }
}

/** 크롤링 종료 시 마지막 flush 후 shard를 하나의 범죄일람표·Screenshot으로 병합한다. */
export async function finalizeCrawlPersistSession(
  session: CrawlPersistSession
): Promise<void> {
  if (session.shard) {
    await flushShard(session.shard);
  }
  await mergeShardResults(session);
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
