package com.evidence;

import com.evidence.config.AppDataDirectory;
import com.evidence.config.SingleInstanceGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
public class EvidenceAutoApplication {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        try {
            if (!SingleInstanceGuard.tryAcquire()) {
                SingleInstanceGuard.notifyExistingInstance();
                JOptionPane.showMessageDialog(
                        null,
                        "이미 Evidence Auto가 실행 중입니다.\n"
                                + "브라우저에서 http://127.0.0.1:8080 을 열어 보세요.\n\n"
                                + "창이 없다면 작업 관리자에서 java.exe 종료 후 다시 실행하세요.",
                        "Evidence Auto",
                        JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            SpringApplication.run(EvidenceAutoApplication.class, args);
        } catch (Throwable t) {
            t.printStackTrace();
            writeCrashLog(t);
            JOptionPane.showMessageDialog(
                    null,
                    "앱 시작에 실패했습니다.\n\n"
                            + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n\n"
                            + "로그: " + crashLogPath() + "\n"
                            + "cmd 창의 오류 메시지도 확인해 주세요.",
                    "Evidence Auto — 시작 실패",
                    JOptionPane.ERROR_MESSAGE
            );
            // bat에서 pause 할 수 있도록 비정상 종료 코드
            System.exit(1);
        }
    }

    private static Path crashLogPath() {
        return AppDataDirectory.resolve().resolve("crash.log");
    }

    private static void writeCrashLog(Throwable t) {
        try {
            Path path = crashLogPath();
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append(java.time.Instant.now()).append('\n');
            sb.append(t).append('\n');
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("  at ").append(el).append('\n');
            }
            Files.writeString(
                    path,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception ignored) {
        }
    }
}
