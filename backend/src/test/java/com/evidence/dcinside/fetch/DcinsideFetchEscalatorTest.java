package com.evidence.dcinside.fetch;

import com.evidence.dcinside.http.BlockSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcinsideFetchEscalatorTest {

    @Test
    void toMobileUrl_convertsDesktopGalleryUrl() {
        String desktop = "https://gall.dcinside.com/mgallery/board/view/?id=test&no=1";
        assertEquals(
                "https://m.dcinside.com/mgallery/board/view/?id=test&no=1",
                DcinsideFetchEscalator.toMobileUrl(desktop)
        );
    }

    @Test
    void validatePostPage_detectsBotChallenge() {
        BlockSignal signal = DcinsideFetchEscalator.validatePostPage("정상적인 접근이 아닙니다.");
        assertEquals(BlockSignal.BOT_CHALLENGE, signal);
    }

    @Test
    void validatePostPage_acceptsValidHtml() {
        String html = """
                <html><body>
                <div class="gallview_head"><span class="gall_date">2026.01.01</span></div>
                </body></html>
                """;
        assertNull(DcinsideFetchEscalator.validatePostPage(html));
    }
}
