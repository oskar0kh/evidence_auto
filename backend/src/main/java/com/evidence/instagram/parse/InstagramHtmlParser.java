package com.evidence.instagram.parse;

import com.evidence.instagram.dto.InstagramCommentData;
import com.evidence.instagram.model.InstagramParsedPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Instagram 게시물 HTML의 {@code <script type="application/json">} prefetch 블록을 파싱한다.
 * <ul>
 *   <li>게시글: {@code xdt_api__v1__media__shortcode__web_info.items[0]}</li>
 *   <li>댓글: {@code xdt_api__v1__media__media_id__comments__connection}</li>
 * </ul>
 * JSON 유니코드 이스케이프는 Jackson이 자동 디코딩한다.
 */
@Component
public class InstagramHtmlParser {

    private static final Logger log = LoggerFactory.getLogger(InstagramHtmlParser.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);

    private static final String WEB_INFO_KEY = "xdt_api__v1__media__shortcode__web_info";
    private static final String COMMENTS_CONNECTION_KEY = "xdt_api__v1__media__media_id__comments__connection";

    private final ObjectMapper objectMapper;

    public InstagramHtmlParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InstagramParsedPost parse(String url, String shortcode, String html) {
        InstagramParsedPost post = tryParse(url, shortcode, html);
        if (post.username().isBlank() && post.caption().isBlank()) {
            throw new IllegalStateException(
                    "Instagram HTML에서 게시글 데이터를 찾지 못했습니다. shortcode=" + shortcode
            );
        }
        return post;
    }

    /**
     * HTML에서 가능한 만큼만 채운다. 데이터가 없어도 예외를 던지지 않는다.
     */
    public InstagramParsedPost tryParse(String url, String shortcode, String html) {
        InstagramParsedPost post = new InstagramParsedPost();
        post.setUrl(url);
        post.setShortcode(shortcode);

        Document doc = Jsoup.parse(html);
        Elements scripts = doc.select("script[type=application/json]");
        for (Element script : scripts) {
            String json = script.data();
            if (json == null || json.isBlank()) {
                continue;
            }
            if (!json.contains(WEB_INFO_KEY) && !json.contains(COMMENTS_CONNECTION_KEY)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                fillFromTree(root, post, shortcode);
            } catch (Exception e) {
                log.debug("Instagram JSON script parse skipped: {}", e.getMessage());
            }
        }

        if (post.caption().isBlank()) {
            fillFromOgMeta(doc, post);
        }
        return post;
    }

    public InstagramParsedPost parseGraphqlPostResponse(String url, String shortcode, JsonNode root) {
        InstagramParsedPost post = new InstagramParsedPost();
        post.setUrl(url);
        post.setShortcode(shortcode);
        fillFromTree(root, post, shortcode);
        // GraphQL web_info는 shortcode 필터 없이 items[0]만 오는 경우가 많다.
        if (post.username().isBlank() && post.caption().isBlank()) {
            for (JsonNode webInfo : findAllByKey(root, WEB_INFO_KEY)) {
                JsonNode items = webInfo.path("items");
                if (items.isArray() && !items.isEmpty()) {
                    fillMediaItem(items.get(0), post);
                }
            }
        }
        return post;
    }

    public List<InstagramCommentData> parseCommentsConnection(JsonNode connection) {
        List<InstagramCommentData> result = new ArrayList<>();
        if (connection == null || connection.isMissingNode()) {
            return result;
        }
        JsonNode edges = connection.path("edges");
        if (!edges.isArray()) {
            return result;
        }
        for (JsonNode edge : edges) {
            InstagramCommentData comment = toComment(edge.path("node"), false);
            if (comment != null) {
                result.add(comment);
            }
        }
        return result;
    }

    public PageCursor readPageCursor(JsonNode connection) {
        if (connection == null || connection.isMissingNode()) {
            return new PageCursor("", false);
        }
        JsonNode pageInfo = connection.path("page_info");
        String endCursor = textOrEmpty(pageInfo.path("end_cursor"));
        boolean hasNext = pageInfo.path("has_next_page").asBoolean(false);
        return new PageCursor(endCursor, hasNext && !endCursor.isBlank());
    }

    public InstagramCommentData toComment(JsonNode node, boolean isReply) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String pk = textOrEmpty(node.path("pk"));
        if (pk.isBlank()) {
            pk = textOrEmpty(node.path("id"));
        }
        String text = textOrEmpty(node.path("text"));
        if (pk.isBlank() && text.isBlank()) {
            return null;
        }
        String username = textOrEmpty(node.path("user").path("username"));
        if (username.isBlank()) {
            username = textOrEmpty(node.path("owner").path("username"));
        }
        long createdAt = node.path("created_at").asLong(0);
        if (createdAt <= 0) {
            createdAt = node.path("created_at_utc").asLong(0);
        }
        int likeCount = node.path("comment_like_count").asInt(0);
        int childCount = node.path("child_comment_count").asInt(0);
        return new InstagramCommentData(
                pk,
                username,
                text,
                formatEpochSeconds(createdAt),
                likeCount,
                isReply,
                childCount
        );
    }

    private void fillFromTree(JsonNode root, InstagramParsedPost post, String shortcode) {
        for (JsonNode webInfo : findAllByKey(root, WEB_INFO_KEY)) {
            JsonNode items = webInfo.path("items");
            if (!items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                if (!shortcode.equals(textOrEmpty(item.path("code")))) {
                    continue;
                }
                fillMediaItem(item, post);
            }
        }

        for (JsonNode connection : findAllByKey(root, COMMENTS_CONNECTION_KEY)) {
            post.addComments(parseCommentsConnection(connection));
            PageCursor cursor = readPageCursor(connection);
            if (cursor.hasNextPage()) {
                post.setCommentsCursor(cursor.endCursor());
                post.setHasNextCommentsPage(true);
            }
        }
    }

    private void fillMediaItem(JsonNode item, InstagramParsedPost post) {
        post.setMediaPk(textOrEmpty(item.path("pk")));
        if (post.mediaPk().isBlank()) {
            post.setMediaPk(textOrEmpty(item.path("id")).split("_")[0]);
        }
        post.setUsername(textOrEmpty(item.path("user").path("username")));
        post.setFullName(textOrEmpty(item.path("user").path("full_name")));
        post.setLikeCount(item.path("like_count").asInt(0));
        post.setCommentCount(item.path("comment_count").asInt(0));

        long takenAt = item.path("taken_at").asLong(0);
        if (takenAt > 0) {
            post.setTakenAt(formatEpochSeconds(takenAt));
        }

        JsonNode caption = item.path("caption");
        if (caption.isObject() && !caption.isNull()) {
            post.setCaption(textOrEmpty(caption.path("text")));
            post.setCaptionPk(textOrEmpty(caption.path("pk")));
        }

        JsonNode previewComments = item.path("preview_comments");
        if (previewComments.isArray()) {
            for (JsonNode preview : previewComments) {
                InstagramCommentData comment = toComment(preview, false);
                if (comment != null) {
                    post.addComment(comment);
                }
            }
        }
    }

    private void fillFromOgMeta(Document doc, InstagramParsedPost post) {
        Element desc = doc.selectFirst("meta[property=og:description]");
        if (desc != null) {
            String content = desc.attr("content");
            // e.g. "re_family_rescue - June 8, 2026: \"caption...\""
            int colon = content.indexOf(": \"");
            if (colon > 0 && content.endsWith("\"")) {
                String caption = content.substring(colon + 3, content.length() - 1);
                if (!caption.isBlank()) {
                    post.setCaption(caption);
                }
            }
            if (post.username().isBlank()) {
                int dash = content.indexOf(" - ");
                if (dash > 0) {
                    String maybeUser = content.substring(0, dash).trim();
                    if (!maybeUser.contains(" ") && !maybeUser.contains(",")) {
                        post.setUsername(maybeUser);
                    }
                }
            }
        }
        Element urlMeta = doc.selectFirst("meta[property=og:url]");
        if (urlMeta != null && !urlMeta.attr("content").isBlank()) {
            post.setUrl(urlMeta.attr("content"));
        }
    }

    private List<JsonNode> findAllByKey(JsonNode root, String key) {
        List<JsonNode> found = new ArrayList<>();
        collectByKey(root, key, found, 0);
        return found;
    }

    private void collectByKey(JsonNode node, String key, List<JsonNode> out, int depth) {
        if (node == null || node.isMissingNode() || depth > 40) {
            return;
        }
        if (node.isObject()) {
            if (node.has(key)) {
                out.add(node.get(key));
            }
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                collectByKey(node.get(fields.next()), key, out, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectByKey(child, key, out, depth + 1);
            }
        }
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.asText("");
        return value != null ? value : "";
    }

    public static String formatEpochSeconds(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        return DATE_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
    }

    public record PageCursor(String endCursor, boolean hasNextPage) {
    }
}
