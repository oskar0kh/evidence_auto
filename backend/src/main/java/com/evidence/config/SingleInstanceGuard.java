package com.evidence.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 락으로 중복 실행을 막고, 이미 실행 중이면 기존 UI URL을 연다.
 */
public final class SingleInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    private static RandomAccessFile lockRaf;
    private static FileChannel lockChannel;
    private static FileLock fileLock;

    private SingleInstanceGuard() {
    }

    /**
     * @return true 이면 이 프로세스가 주인, false 이면 이미 다른 인스턴스가 실행 중
     */
    public static boolean tryAcquire() {
        try {
            Path dataDir = AppDataDirectory.ensureExists();
            Path lockPath = dataDir.resolve("app.lock");
            lockRaf = new RandomAccessFile(lockPath.toFile(), "rw");
            lockChannel = lockRaf.getChannel();
            fileLock = lockChannel.tryLock();
            if (fileLock == null) {
                closeQuietly();
                return false;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstanceGuard::release, "single-instance-unlock"));
            return true;
        } catch (Exception e) {
            log.warn("단일 인스턴스 락 획득 실패 — 계속 진행합니다: {}", e.toString());
            closeQuietly();
            return true;
        }
    }

    public static void notifyExistingInstance() {
        int port = readInstancePort(8080);
        String url = "http://127.0.0.1:" + port + "/";
        log.info("이미 실행 중입니다. UI를 다시 엽니다: {}", url);
        DesktopLauncher.openUrl(url);
    }

    private static int readInstancePort(int fallback) {
        try {
            Path portFile = AppDataDirectory.resolve().resolve("instance.port");
            if (!Files.isRegularFile(portFile)) {
                return fallback;
            }
            String text = Files.readString(portFile, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void release() {
        try {
            if (fileLock != null) {
                fileLock.release();
            }
        } catch (IOException ignored) {
        }
        closeQuietly();
        try {
            Files.deleteIfExists(AppDataDirectory.resolve().resolve("instance.port"));
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly() {
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (lockRaf != null) {
                lockRaf.close();
            }
        } catch (IOException ignored) {
        }
        fileLock = null;
        lockChannel = null;
        lockRaf = null;
    }
}
