package com.evidence.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 앱 데이터 루트 경로.
 * <p>
 * 우선순위: {@code -Devidence.data.dir} → {@code EVIDENCE_DATA_DIR} →
 * Windows {@code %APPDATA%/EvidenceAuto} → {@code ~/.evidence-auto}
 */
public final class AppDataDirectory {

    private static final String APP_FOLDER = "EvidenceAuto";

    private AppDataDirectory() {
    }

    public static Path resolve() {
        String fromProp = System.getProperty("evidence.data.dir");
        if (fromProp != null && !fromProp.isBlank()) {
            return Path.of(fromProp.trim()).toAbsolutePath().normalize();
        }

        String fromEnv = System.getenv("EVIDENCE_DATA_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv.trim()).toAbsolutePath().normalize();
        }

        String configured = System.getProperty("evidence.data-dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, APP_FOLDER).toAbsolutePath().normalize();
            }
        }

        return Path.of(System.getProperty("user.home"), ".evidence-auto").toAbsolutePath().normalize();
    }

    /**
     * 설정값이 절대경로면 그대로, 상대경로면 앱 데이터 루트 아래로 해석한다.
     */
    public static Path resolvePath(String configured) {
        String value = configured != null ? configured.trim() : "";
        if (value.isBlank()) {
            return resolve();
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return resolve().resolve(path).toAbsolutePath().normalize();
    }

    public static Path ensureExists() {
        Path dir = resolve();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            // 호출 측에서 필요 시 재시도/로그
        }
        return dir;
    }
}
