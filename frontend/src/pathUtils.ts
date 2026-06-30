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
