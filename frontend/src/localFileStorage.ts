import { ensureDirectoryPermission } from './nativeFolderPicker';

async function assertWritableDirectory(handle: FileSystemDirectoryHandle): Promise<void> {
  const granted = await ensureDirectoryPermission(handle);
  if (!granted) {
    throw new Error('선택한 폴더에 쓰기 권한이 없습니다. 폴더를 다시 선택해 주세요.');
  }
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
