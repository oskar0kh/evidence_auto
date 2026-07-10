package com.evidence.dcinside.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BodyTextNormalizerTest {

    @Test
    void removesHiddenAndBlindText() {
        Element root = Jsoup.parseBodyFragment(
                "<div><span class=\"blind\">숨김</span><p>본문</p></div>"
        ).body().child(0);

        assertEquals("본문", BodyTextNormalizer.htmlToPlainText(root));
    }

    @Test
    void removesDcAppFooter() {
        assertEquals(
                "내용",
                BodyTextNormalizer.normalizeBodyLines("내용\n- dc official App")
        );
    }

    @Test
    void deduplicatesRepeatedLines() {
        String input = "제목\n유튜브 설명\n제목\n유튜브 설명\n본문";
        String normalized = BodyTextNormalizer.normalizeBodyLines(input);

        assertEquals("제목\n유튜브 설명\n본문", normalized);
    }

    @Test
    void skipsIframeContent() {
        Element root = Jsoup.parseBodyFragment(
                "<div><p>본문</p><iframe src=\"https://youtube.com/embed/1\"></iframe></div>"
        ).body().child(0);

        String text = BodyTextNormalizer.htmlToPlainText(root);
        assertEquals("본문", text);
        assertFalse(text.contains("youtube"));
    }
}
