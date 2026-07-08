import { ensureDirectoryPermission } from './nativeFolderPicker';

async function assertWritableDirectory(handle: FileSystemDirectoryHandle): Promise<void> {
  const granted = await ensureDirectoryPermission(handle);
  if (!granted) {
    throw new Error('선택한 폴더에 쓰기 권한이 없습니다. 폴더를 다시 선택해 주세요.');
  }
}

export async function getOrCreateSubdirectory(
  parent: FileSystemDirectoryHandle,
  name: string
): Promise<FileSystemDirectoryHandle> {
  await assertWritableDirectory(parent);
  return parent.getDirectoryHandle(name, { create: true });
}

export async function writeBlobToDirectory(
  handle: FileSystemDirectoryHandle,
  filename: string,
  blob: Blob
): Promise<void> {
  await assertWritableDirectory(handle);
  const fileHandle = await handle.getFileHandle(filename, { create: true });
  const writable = await fileHandle.createWritable();
  await writable.write(blob);
  await writable.close();
}

export async function writeArrayBufferToDirectory(
  handle: FileSystemDirectoryHandle,
  filename: string,
  buffer: ArrayBuffer
): Promise<void> {
  await writeBlobToDirectory(handle, filename, new Blob([buffer]));
}

/** src 디렉터리의 파일들을 dst 디렉터리로 복사한다(하위 폴더는 무시). */
export async function copyDirectoryFiles(
  src: FileSystemDirectoryHandle,
  dst: FileSystemDirectoryHandle
): Promise<void> {
  await assertWritableDirectory(src);
  await assertWritableDirectory(dst);
  for await (const handle of (src as unknown as {
    values(): AsyncIterableIterator<FileSystemHandle>;
  }).values()) {
    if (handle.kind !== 'file') {
      continue;
    }
    const fileHandle = await src.getFileHandle(handle.name);
    const file = await fileHandle.getFile();
    const writable = await (await dst.getFileHandle(handle.name, { create: true })).createWritable();
    await writable.write(file);
    await writable.close();
  }
}

/** 부모 디렉터리에서 하위 폴더(및 내부 전체)를 삭제한다. */
export async function removeSubdirectory(
  parent: FileSystemDirectoryHandle,
  name: string
): Promise<void> {
  await assertWritableDirectory(parent);
  await parent.removeEntry(name, { recursive: true });
}

export async function tryReadFileFromDirectory(
  handle: FileSystemDirectoryHandle,
  filename: string
): Promise<ArrayBuffer | null> {
  try {
    await assertWritableDirectory(handle);
    const fileHandle = await handle.getFileHandle(filename);
    const file = await fileHandle.getFile();
    return await file.arrayBuffer();
  } catch {
    return null;
  }
}
