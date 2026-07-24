#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>

/*
 * Launches EvidenceAuto.bat in a new console so startup logs stay visible.
 * If Java exits quickly, the .bat "pause" keeps the window open.
 */
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)lpCmdLine;
    (void)nCmdShow;

    char base[MAX_PATH];
    if (GetModuleFileNameA(NULL, base, MAX_PATH) == 0) {
        MessageBoxA(NULL, "실행 파일 경로를 확인할 수 없습니다.", "EvidenceAuto", MB_OK | MB_ICONERROR);
        return 1;
    }
    char *slash = strrchr(base, '\\');
    if (slash != NULL) {
        *slash = '\0';
    }

    char bat[MAX_PATH];
    snprintf(bat, sizeof(bat), "%s\\EvidenceAuto.bat", base);
    if (GetFileAttributesA(bat) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxA(NULL,
                "EvidenceAuto.bat 이 없습니다.\n배포 폴더 전체를 복사했는지 확인하세요.",
                "EvidenceAuto",
                MB_OK | MB_ICONERROR);
        return 1;
    }

    /* cmd /c keeps running the bat; bat pauses on failure so the window stays */
    char cmd[4096];
    snprintf(cmd, sizeof(cmd), "cmd.exe /c \"\"%s\"\"", bat);

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (!CreateProcessA(
            NULL,
            cmd,
            NULL,
            NULL,
            FALSE,
            CREATE_NEW_CONSOLE,
            NULL,
            base,
            &si,
            &pi)) {
        char msg[512];
        snprintf(msg, sizeof(msg), "실행에 실패했습니다 (error %lu).", GetLastError());
        MessageBoxA(NULL, msg, "EvidenceAuto", MB_OK | MB_ICONERROR);
        return 1;
    }

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
