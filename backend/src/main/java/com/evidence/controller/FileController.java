package com.evidence.controller;

import com.evidence.service.ScreenshotService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Pattern SAFE_CAPTURE_FILENAME =
            Pattern.compile("^연번 \\d{3}_post_\\d+\\.png$");

    private final ScreenshotService screenshotService;

    public FileController(ScreenshotService screenshotService) {
        this.screenshotService = screenshotService;
    }

    @GetMapping("/captures/{filename}")
    public ResponseEntity<?> downloadCapture(@PathVariable String filename) {
        if (!SAFE_CAPTURE_FILENAME.matcher(filename).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "허용되지 않는 파일명입니다."));
        }

        try {
            Path directory = screenshotService.getOutputDir();
            Path filePath = directory.resolve(filename).normalize();
            if (!filePath.startsWith(directory) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error",
                    e.getMessage() != null ? e.getMessage() : "캡처 파일을 불러올 수 없습니다."
            ));
        }
    }
}
