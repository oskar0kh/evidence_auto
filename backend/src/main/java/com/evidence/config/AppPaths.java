package com.evidence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring 설정을 반영한 앱 데이터 경로 해석기.
 */
@Component
public class AppPaths {

    private final Path dataDir;

    public AppPaths(@Value("${evidence.data-dir:}") String configuredDataDir) {
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            this.dataDir = Path.of(configuredDataDir.trim()).toAbsolutePath().normalize();
        } else {
            this.dataDir = AppDataDirectory.resolve();
        }
        try {
            Files.createDirectories(this.dataDir);
        } catch (Exception ignored) {
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path resolve(String configured) {
        String value = configured != null ? configured.trim() : "";
        if (value.isBlank()) {
            return dataDir;
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return dataDir.resolve(path).toAbsolutePath().normalize();
    }
}
