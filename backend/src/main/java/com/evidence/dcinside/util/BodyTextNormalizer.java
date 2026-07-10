package com.evidence.dcinside.util;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * 디시인사이드 게시글 HTML 본문을 평문으로 변환할 때
 * 숨김 요소·임베드·중복 문단을 제거한다.
 */
public final class BodyTextNormalizer {

    private static final Pattern DC_APP_FOOTER = Pattern.compile(
            "^-\\s*dc official App\\.?$",
            Pattern.CASE_INSENSITIVE
    );

    private BodyTextNormalizer() {
    }

    public static String htmlToPlainText(Element root) {
        Element clone = root.clone();
        removeNoiseElements(clone);

        StringBuilder sb = new StringBuilder();
        appendPlainText(clone, sb);
        return normalizeBodyLines(sb.toString());
    }

    static void removeNoiseElements(Element root) {
        root.select(
                "script, style, iframe, embed, object, noscript, "
                        + ".blind, .sr_only, [aria-hidden=true], "
                        + "[style*=display:none], [style*=display: none], "
                        + "[style*=visibility:hidden], [style*=visibility: hidden]"
        ).remove();
    }

    static void appendPlainText(Node node, StringBuilder sb) {
        if (node instanceof TextNode textNode) {
            sb.append(textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase();
        if ("iframe".equals(tag) || "embed".equals(tag) || "object".equals(tag) || "noscript".equals(tag)) {
            return;
        }
        if ("br".equals(tag)) {
            sb.append('\n');
            return;
        }

        for (Node child : element.childNodes()) {
            appendPlainText(child, sb);
        }

        if ("p".equals(tag) || "div".equals(tag) || "li".equals(tag) || "blockquote".equals(tag)) {
            sb.append('\n');
        }
    }

    public static String normalizeBodyLines(String text) {
        String[] lines = text.replace('\r', '\n').split("\n");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        StringBuilder normalized = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || DC_APP_FOOTER.matcher(trimmed).matches()) {
                continue;
            }
            if (!seen.add(trimmed)) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append('\n');
            }
            normalized.append(trimmed);
        }
        return normalized.toString();
    }
}
