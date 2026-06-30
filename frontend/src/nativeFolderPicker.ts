export function isNativeFolderPickerSupported(): boolean {
  return typeof window.showDirectoryPicker === 'function';
}

export async function pickNativeDirectory(): Promise<FileSystemDirectoryHandle> {
  if (!isNativeFolderPickerSupported()) {
    throw new Error(
      '이 브라우저는 시스템 폴더 선택을 지원하지 않습니다. Chrome 또는 Edge를 사용해 주세요.'
    );
  }

  return window.showDirectoryPicker({ mode: 'readwrite' });
}

export async function ensureDirectoryPermission(
  handle: FileSystemDirectoryHandle
): Promise<boolean> {
  const options = { mode: 'readwrite' as const };
  if ((await handle.queryPermission(options)) === 'granted') {
    return true;
  }
  return (await handle.requestPermission(options)) === 'granted';
}
