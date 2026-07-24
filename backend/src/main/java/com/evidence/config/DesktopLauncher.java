package com.evidence.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 서버 기동 후 브라우저를 열고, 실제 포트를 앱 데이터에 기록한다.
 */
@Component
public class DesktopLauncher {

    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private final boolean openBrowser;

    public DesktopLauncher(
            @Value("${evidence.open-browser:false}") boolean openBrowser
    ) {
        this.openBrowser = openBrowser;
    }

    @Order(0)
    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        String url = "http://127.0.0.1:" + port + "/";
        writeInstancePort(port);

        // cmd 창에서도 바로 보이도록 stdout에도 출력
        System.out.println();
        System.out.println("==================================================");
        System.out.println(" Evidence Auto UI: " + url);
        System.out.println(" 브라우저가 안 열리면 위 주소를 Chrome/Edge에 입력하세요.");
        System.out.println("==================================================");
        System.out.println();
        log.info("Evidence Auto UI: {}", url);

        if (!openBrowser) {
            System.out.println("(evidence.open-browser=false — 브라우저 자동 실행 안 함)");
            return;
        }
        openUrl(url);
    }

    static void openUrl(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            if (openWindows(url)) {
                return;
            }
        }

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            log.warn("Desktop.browse failed: {}", e.toString());
        }

        try {
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (!os.contains("win")) {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            log.warn("브라우저 자동 실행 실패 (수동으로 열어 주세요): {} — {}", url, e.toString());
            System.out.println("브라우저 자동 실행 실패. 수동으로 열어 주세요: " + url);
        }
    }

    private static boolean openWindows(String url) {
        // Windows에서 가장 확실한 방법: cmd start
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
            return true;
        } catch (Exception e) {
            log.warn("cmd start failed: {}", e.toString());
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            return true;
        } catch (Exception e) {
            log.warn("rundll32 failed: {}", e.toString());
        }
        return false;
    }

    private static void writeInstancePort(int port) {
        try {
            Path dataDir = AppDataDirectory.ensureExists();
            Path portFile = dataDir.resolve("instance.port");
            Files.writeString(
                    portFile,
                    Integer.toString(port),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception e) {
            log.debug("instance.port 기록 실패: {}", e.toString());
        }
    }
}
