export function getCaptureFilename(pathOrFilename: string): string {
  const parts = pathOrFilename.split(/[/\\]/);
  return parts[parts.length - 1] || pathOrFilename;
}

export function joinDirectoryPath(directoryPath: string, filename: string): string {
  const base = directoryPath.trim().replace(/[/\\]+$/, '');
  if (!base) {
    return filename;
  }
  const separator = base.includes('\\') || /^[A-Za-z]:/.test(base) ? '\\' : '/';
  return `${base}${separator}${filename}`;
}

/** 엑셀(.xlsx)과 같은 폴더에 있는 캡처 PNG를 여는 상대 하이퍼링크 */
export function toSameFolderCaptureHyperlink(filename: string): string {
  const normalized = filename.replace(/\\/g, '/').replace(/^\.\//, '');
  return `./${normalized}`;
}
