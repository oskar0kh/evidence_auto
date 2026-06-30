package com.evidence.service;

import com.evidence.dto.CaptureImage;
import com.evidence.dto.CommentData;
import com.evidence.dto.DcinsidePostData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DcinsideCrawlService {

    // 사용자 에이전트
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 디시인사이드 게시글 URL 패턴
    private static final Pattern DC_URL_PATTERN = Pattern.compile(
            "https?://(?:m\\.)?gall\\.dcinside\\.com/(?:mgallery|mini|board)/board/view/\\?.*"
    );

    // JSON 파서
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 디시인사이드 게시글 크롤링
    public DcinsidePostData crawl(String url) throws Exception {
        if (!DC_URL_PATTERN.matcher(url).find() && !url.contains("gall.dcinside.com")) {
            throw new IllegalArgumentException("디시인사이드 게시글 URL이 아닙니다.");
        }

        // 게시글 URL 정규화
        String normalizedUrl = normalizeUrl(url);
        
        // 쿠키 관리자
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        
        // HTTP 클라이언트
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 페이지 파싱
        HttpResponse<String> pageResponse = fetchPage(client, normalizedUrl);
        
        // 문서 파싱
        Document doc = Jsoup.parse(pageResponse.body(), normalizedUrl);
        
        // JSON-LD 파싱
        JsonNode jsonLd = parseJsonLd(doc);
        
        // 작성자 정보 파싱
        WriterInfo writer = parseWriter(doc, jsonLd);
        
        // 게시글 메타데이터 추출 (갤러리 ID, 게시글 번호, 에스노, 갤러리 타입)
        String galleryId = extractHiddenValue(doc, "gallery_id", "id");
        String postNo = extractHiddenValue(doc, "no");
        String esno = extractHiddenValue(doc, "e_s_n_o");
        String galleryType = parseGalleryType(doc, normalizedUrl);

        if (galleryId.isEmpty() || postNo.isEmpty() || esno.isEmpty()) {
            throw new IllegalStateException("게시글 메타데이터를 파싱할 수 없습니다.");
        }

        // 게시글 제목, 본문, 게시글 URL 추출
        String headline = textOrEmpty(jsonLd, "headline");
        String title = extractTitle(headline, doc);
        String body = extractBody(doc, textOrEmpty(jsonLd, "articleBody"));
        String postUrl = textOrEmpty(jsonLd, "URL");

        if (postUrl.isEmpty()) {
            postUrl = normalizedUrl;
        }

        // 게시일자 추출
        String datePublished = textOrEmpty(jsonLd, "datePublished");
        String postDate = formatPostDate(datePublished);

        // 조회수, 댓글 수 추출
        int viewCount = extractInteractionCount(jsonLd, "ViewAction");
        int commentCountFromLd = extractInteractionCount(jsonLd, "CommentAction");

        // 댓글 추출
        List<CommentData> comments = fetchAllComments(client, normalizedUrl, galleryId, postNo, esno, galleryType);
        int realCommentCount = countRealComments(comments);
        int commentCount = comments.isEmpty() ? commentCountFromLd : realCommentCount;

        // 작성자 정보, 내용 추출
        String nickname = formatDisplayNickname(writer.nick(), writer.ip(), writer.uid());
        String content = buildContent(title, body, comments);

        // 게시글 데이터 반환
        return new DcinsidePostData(
                postUrl,
                postDate,
                nickname,
                title,
                body,
                content,
                "",
                "",
                "",
                "",
                viewCount,
                commentCount,
                postNo,
                comments
        );
    }

    public DcinsidePostData attachCapture(DcinsidePostData data, CaptureImage capture) {
        String filename = capture.filename();
        int deletedCommentCount = countDeletedComments(data.comments());
        int collectedCommentCount = countRealComments(data.comments());
        int totalCommentCount = collectedCommentCount + deletedCommentCount;
        if (totalCommentCount == 0) {
            totalCommentCount = data.commentCount();
            collectedCommentCount = data.commentCount();
        }
        String remarks = buildRemarks(
                data.viewCount(),
                totalCommentCount,
                collectedCommentCount,
                deletedCommentCount,
                filename
        );
        String captureImageBase64 = Base64.getEncoder().encodeToString(capture.pngBytes());

        return new DcinsidePostData(
                data.url(),
                data.postDate(),
                data.nickname(),
                data.title(),
                data.body(),
                data.content(),
                data.crimeType(),
                remarks,
                filename,
                captureImageBase64,
                data.viewCount(),
                data.commentCount(),
                data.postNo(),
                data.comments()
        );
    }

    // 페이지 파싱
    private HttpResponse<String> fetchPage(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("페이지를 불러올 수 없습니다. HTTP " + response.statusCode());
        }
        return response;
    }

    // JSON-LD 파싱
    private JsonNode parseJsonLd(Document doc) throws Exception {
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element script : scripts) {
            String json = script.data();
            if (!json.contains("DiscussionForumPosting")) {
                continue;
            }
            return objectMapper.readTree(json);
        }
        throw new IllegalStateException("게시글 JSON-LD 데이터를 찾을 수 없습니다.");
    }

    // 작성자 정보 레코드
    private record WriterInfo(String nick, String uid, String ip) {}

    // 작성자 정보 파싱
    private WriterInfo parseWriter(Document doc, JsonNode jsonLd) {
        Element head = doc.selectFirst("div.gallview_head");
        if (head != null) {
            Element writer = head.selectFirst("div.gall_writer[data-loc=view]");
            if (writer != null) {
                return new WriterInfo(
                        writer.attr("data-nick"),
                        writer.attr("data-uid"),
                        writer.attr("data-ip")
                );
            }
        }
        Element writer = doc.selectFirst("div.gall_writer[data-loc=view]");
        if (writer != null) {
            return new WriterInfo(
                    writer.attr("data-nick"),
                    writer.attr("data-uid"),
                    writer.attr("data-ip")
            );
        }
        String authorName = jsonLd.path("author").path("name").asText("");
        return new WriterInfo(authorName, "", "");
    }

    // 숨겨진 값 추출
    private String extractHiddenValue(Document doc, String... ids) {
        for (String id : ids) {
            Element el = doc.selectFirst("#" + id);
            if (el != null && !el.val().isBlank()) {
                return el.val().trim();
            }
            el = doc.selectFirst("input[name=" + id + "]");
            if (el != null && !el.val().isBlank()) {
                return el.val().trim();
            }
        }
        return "";
    }

    // 갤러리 타입 파싱
    private String parseGalleryType(Document doc, String url) {
        String scriptText = doc.select("script").stream()
                .map(Element::data)
                .filter(s -> s.contains("_GALLERY_TYPE_"))
                .findFirst()
                .orElse("");
        Matcher matcher = Pattern.compile("_GALLERY_TYPE_\\s*=\\s*\"([^\"]+)\"").matcher(scriptText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (url.contains("/mgallery/")) {
            return "M";
        }
        if (url.contains("/mini/")) {
            return "MI";
        }
        return "G";
    }

    // 게시글 본문 추출 (HTML 줄바꿈 보존, 없으면 JSON-LD articleBody 사용)
    private String extractBody(Document doc, String jsonLdBody) {
        Element content = doc.selectFirst("div.write_div");
        if (content == null) {
            content = doc.selectFirst(".gallview_contents .writing_view");
        }
        if (content != null) {
            String htmlBody = htmlToPlainTextWithLineBreaks(content);
            if (!htmlBody.isBlank()) {
                return htmlBody;
            }
        }
        return jsonLdBody == null ? "" : jsonLdBody;
    }

    private String htmlToPlainTextWithLineBreaks(Element root) {
        Element clone = root.clone();
        clone.select("script, style").remove();

        StringBuilder sb = new StringBuilder();
        appendPlainText(clone, sb);
        return normalizeBodyLines(sb.toString());
    }

    private void appendPlainText(Node node, StringBuilder sb) {
        if (node instanceof TextNode textNode) {
            sb.append(textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase();
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

    private String normalizeBodyLines(String text) {
        String[] lines = text.replace('\r', '\n').split("\n");
        StringBuilder normalized = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append('\n');
            }
            normalized.append(trimmed);
        }
        return normalized.toString();
    }

    // 텍스트 또는 빈 문자열 반환
    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText("") : "";
    }

    // 제목 추출
    private String extractTitle(String headline, Document doc) {
        Element subject = doc.selectFirst("h3.title span.title_subject");
        if (subject != null && !subject.text().isBlank()) {
            return subject.text().trim();
        }
        if (!headline.isBlank()) {
            int idx = headline.lastIndexOf(" - ");
            if (idx > 0) {
                return headline.substring(0, idx).trim();
            }
            return headline.trim();
        }
        return "";
    }

    // 게시일자 포맷팅
    private String formatPostDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return "";
        }
        try {
            OffsetDateTime dt = OffsetDateTime.parse(isoDate);
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return isoDate;
        }
    }

    // 상호작용 수 추출
    private int extractInteractionCount(JsonNode jsonLd, String actionType) {
        JsonNode stats = jsonLd.get("interactionStatistic");
        if (stats == null || !stats.isArray()) {
            return 0;
        }
        for (JsonNode stat : stats) {
            String type = stat.path("interactionType").asText("");
            if (type.contains(actionType)) {
                return stat.path("userInteractionCount").asInt(0);
            }
        }
        return 0;
    }

    // 모든 댓글 추출
    private List<CommentData> fetchAllComments(
            HttpClient client,
            String referer,
            String galleryId,
            String postNo,
            String esno,
            String galleryType
    ) throws Exception {
        List<CommentData> all = new ArrayList<>();
        int page = 1;
        int totalCnt = Integer.MAX_VALUE;

        while (all.size() < totalCnt && page <= 50) {
            JsonNode data = fetchCommentPage(client, referer, galleryId, postNo, esno, galleryType, page);
            totalCnt = data.path("total_cnt").asInt(0);
            JsonNode comments = data.get("comments");
            if (comments == null || !comments.isArray() || comments.isEmpty()) {
                break;
            }
            int before = all.size();
            for (JsonNode c : comments) {
                if (c.path("no").asInt(0) == 0) {
                    continue;
                }
                String memo = c.path("memo").asText("");
                memo = Jsoup.parse(memo).text();

                all.add(new CommentData(
                        c.path("no").asText(),
                        c.path("name").asText(),
                        c.path("user_id").asText(),
                        c.path("ip").asText(),
                        memo,
                        c.path("reg_date").asText(),
                        c.path("is_delete").asText("0")
                ));
            }
            if (all.size() == before) {
                break;
            }
            if (comments.size() < 50) {
                break;
            }
            page++;
        }
        return all;
    }

    // 댓글 페이지 파싱
    private JsonNode fetchCommentPage(
            HttpClient client,
            String referer,
            String galleryId,
            String postNo,
            String esno,
            String galleryType,
            int page
    ) throws Exception {
        String form = "id=" + enc(galleryId)
                + "&no=" + enc(postNo)
                + "&cmt_id=" + enc(galleryId)
                + "&cmt_no=" + enc(postNo)
                + "&e_s_n_o=" + enc(esno)
                + "&comment_page=" + page
                + "&sort=D"
                + "&prevCnt="
                + "&board_type="
                + "&_GALLERY_TYPE_=" + enc(galleryType);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://gall.dcinside.com/board/comment/"))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Referer", referer)
                .header("Origin", "https://gall.dcinside.com")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        if (response.statusCode() != 200 || body.startsWith("<") || body.contains("정상적인 접근")) {
            throw new IllegalStateException("댓글 API 호출에 실패했습니다.");
        }
        return objectMapper.readTree(body);
    }

    // URL 인코딩
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    // 삭제 댓글 수 카운트 (is_delete=1)
    private int countDeletedComments(List<CommentData> comments) {
        return (int) comments.stream()
                .filter(c -> "1".equals(c.isDelete()))
                .count();
    }

    // 실제 댓글 수 카운트
    private int countRealComments(List<CommentData> comments) {
        return (int) comments.stream()
                .filter(c -> !"1".equals(c.isDelete()))
                .count();
    }

    /**
     * 닉네임 생성 메서드
     * IP 있을 때(유동): 닉네임(IP) 구조 / ex: ㅇㅇ(118.35)
     * IP 없을 때(반고닉/고닉): 닉네임(식별번호) 구조 / ex: hello(bowld4333)
     */
    private String formatDisplayNickname(String nick, String ip, String uid) {
        if (nick == null || nick.isBlank()) {
            return "";
        }
        if (ip != null && !ip.isBlank()) {
            return nick + "(" + ip + ")";
        }
        if (uid != null && !uid.isBlank()) {
            return nick + "(" + uid + ")";
        }
        return nick;
    }

    private String formatCommentNickname(String name, String ip, String userId) {
        return formatDisplayNickname(name, ip, userId);
    }

    // 범죄일람표 '내용'란 빌드
    private String buildContent(String title, String body, List<CommentData> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("[issue] ").append(title).append("\n");
        sb.append("[body] ").append(body == null ? "" : body);
        if (!comments.isEmpty()) {
            sb.append("\n[comments]");
            int index = 1;
            for (CommentData c : comments) {
                if ("1".equals(c.isDelete())) {
                    continue;
                }
                String nick = formatCommentNickname(c.name(), c.ip(), c.userId());
                String memo = c.memo() == null ? "" : c.memo();
                String date = c.regDate() == null ? "" : c.regDate();
                sb.append("\n[댓글 ").append(index).append("] ")
                        .append(nick).append(": ")
                        .append(memo)
                        .append(" (").append(date).append(")");
                index++;
            }
        }
        return sb.toString();
    }

    // 범죄일람표 '비고'란 빌드
    private String buildRemarks(
            int viewCount,
            int totalCommentCount,
            int collectedCommentCount,
            int deletedCommentCount,
            String captureFilename
    ) {
        return "조회수 : " + viewCount + "\n"
                + "댓글 수 : " + totalCommentCount
                + " (수집 댓글: " + collectedCommentCount
                + " / 삭제된 댓글 : " + deletedCommentCount + ")\n"
                + "증거자료(캡처파일) : " + captureFilename + "\n";
    }

    // URL 정규화
    private String normalizeUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.startsWith("http://")) {
            trimmed = "https://" + trimmed.substring(7);
        }
        return trimmed;
    }
}
