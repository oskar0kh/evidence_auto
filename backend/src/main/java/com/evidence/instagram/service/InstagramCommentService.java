package com.evidence.instagram.service;

import com.evidence.instagram.dto.InstagramCommentData;
import com.evidence.instagram.http.InstagramHttpClient;
import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.parse.InstagramHtmlParser;
import com.evidence.instagram.util.InstagramGraphqlUtil;
import com.evidence.instagram.util.InstagramUrlUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instagram 댓글 수집.
 * <ul>
 *   <li>로그인 세션: web API v1 ({@code /api/v1/media/{id}/comments/}) 페이지네이션</li>
 *   <li>비로그인: GraphQL ContainerQuery 첫 페이지(~15개)</li>
 *   <li>대댓글: API v1 또는 GraphQL child query</li>
 * </ul>
 */
@Service
public class InstagramCommentService {

    private static final Logger log = LoggerFactory.getLogger(InstagramCommentService.class);
    private static final String COMMENTS_CONNECTION_KEY = "xdt_api__v1__media__media_id__comments__connection";
    private static final String CHILD_COMMENTS_KEY =
            "xdt_api__v1__media__media_id__comments__parent_comment_id__child_comments__connection";

    private final InstagramHttpClient httpClient;
    private final InstagramHtmlParser htmlParser;
    private final ObjectMapper objectMapper;
    private final String commentsContainerDocId;
    private final String commentsPaginationDocId;
    private final String childCommentsDocId;
    private final int maxPages;
    private final int maxChildPages;
    private final long delayMs;
    private final boolean enabled;
    private final boolean apiV1Enabled;

    public InstagramCommentService(
            InstagramHttpClient httpClient,
            InstagramHtmlParser htmlParser,
            ObjectMapper objectMapper,
            @Value("${evidence.instagram.doc-id.comments-container:26297736713236852}") String commentsContainerDocId,
            @Value("${evidence.instagram.doc-id.comments:26248690958161038}") String commentsPaginationDocId,
            @Value("${evidence.instagram.doc-id.child-comments:26914912424764761}") String childCommentsDocId,
            @Value("${evidence.instagram.comments.max-pages:30}") int maxPages,
            @Value("${evidence.instagram.comments.max-child-pages:10}") int maxChildPages,
            @Value("${evidence.instagram.comments.delay-ms:400}") long delayMs,
            @Value("${evidence.instagram.comments.paginate:true}") boolean enabled,
            @Value("${evidence.instagram.comments.api-v1:true}") boolean apiV1Enabled
    ) {
        this.httpClient = httpClient;
        this.htmlParser = htmlParser;
        this.objectMapper = objectMapper;
        this.commentsContainerDocId = commentsContainerDocId;
        this.commentsPaginationDocId = commentsPaginationDocId;
        this.childCommentsDocId = childCommentsDocId;
        this.maxPages = Math.max(0, maxPages);
        this.maxChildPages = Math.max(0, maxChildPages);
        this.delayMs = Math.max(0, delayMs);
        this.enabled = enabled;
        this.apiV1Enabled = apiV1Enabled;
    }

    public void collectComments(InstagramParsedPost post) {
        if (!enabled || post == null) {
            return;
        }
        ensureMediaPk(post);
        if (post.mediaPk().isBlank()) {
            log.warn("media_id를 알 수 없어 댓글 수집을 건너뜁니다. shortcode={}", post.shortcode());
            return;
        }

        boolean loggedIn = httpClient.hasLoginSession();
        if (!loggedIn) {
            log.warn("Instagram sessionid 없음 — 댓글은 일부만 수집됩니다");
        } else {
            log.info("Instagram comment fetch with session user={}", httpClient.sessionUserId().orElse("?"));
        }

        int before = post.comments().size();
        try {
            boolean collected = false;
            if (loggedIn && apiV1Enabled) {
                collected = fetchAllViaApiV1(post);
            }
            if (!collected) {
                fetchViaGraphql(post, loggedIn);
            }
            fetchChildComments(post, loggedIn);
            post.setHasNextCommentsPage(false);
            post.setCommentsCursor("");
            log.info(
                    "Instagram comments done: shortcode={} collected={} (+{}) loggedIn={} apiV1={}",
                    post.shortcode(),
                    post.comments().size(),
                    post.comments().size() - before,
                    loggedIn,
                    collected
            );
        } catch (Exception e) {
            log.warn("Instagram comment collection stopped: {}", e.getMessage());
            post.setHasNextCommentsPage(false);
        }
    }

    private boolean fetchAllViaApiV1(InstagramParsedPost post) {
        String maxId = null;
        String minId = null;
        int pages = 0;
        int added = 0;
        while (pages < maxPages) {
            sleepQuietly();
            try {
                String apiUrl = buildCommentsApiUrl(post.mediaPk(), maxId, minId);
                HttpResponse<String> response = httpClient.getApiJson(post.url(), apiUrl);
                JsonNode root = objectMapper.readTree(response.body());
                if (!"ok".equalsIgnoreCase(root.path("status").asText())) {
                    log.warn("Instagram comments API status not ok: {}", root.path("message").asText(""));
                    return pages > 0;
                }
                JsonNode comments = root.path("comments");
                int pageAdded = 0;
                if (comments.isArray()) {
                    for (JsonNode node : comments) {
                        InstagramCommentData comment = htmlParser.toComment(node, false);
                        if (comment != null) {
                            int before = post.comments().size();
                            post.addComment(comment);
                            if (post.comments().size() > before) {
                                pageAdded++;
                            }
                        }
                    }
                }
                added += pageAdded;
                pages++;

                String nextMaxId = readCursor(root, "next_max_id");
                String nextMinId = readCursor(root, "next_min_id");
                boolean hasMoreTail = root.path("has_more_comments").asBoolean(nextMaxId != null);
                boolean hasMoreHead = root.path("has_more_headload_comments").asBoolean(nextMinId != null);
                log.info(
                        "Instagram comments [api-v1] page {}: +{}, total={}, has_more={}, has_headload={}, next_max={}, next_min={}",
                        pages,
                        pageAdded,
                        post.comments().size(),
                        hasMoreTail,
                        hasMoreHead,
                        nextMaxId != null,
                        nextMinId != null
                );

                // Instagram은 스크롤 방향에 따라 next_max_id / next_min_id 중 하나를 준다.
                if (hasMoreTail && nextMaxId != null) {
                    maxId = nextMaxId;
                    minId = null;
                    continue;
                }
                if (hasMoreHead && nextMinId != null) {
                    minId = nextMinId;
                    maxId = null;
                    continue;
                }
                // 플래그가 비어도 커서가 있으면 한 번 더 시도
                if (nextMaxId != null && !nextMaxId.equals(maxId)) {
                    maxId = nextMaxId;
                    minId = null;
                    continue;
                }
                if (nextMinId != null && !nextMinId.equals(minId)) {
                    minId = nextMinId;
                    maxId = null;
                    continue;
                }
                break;
            } catch (Exception e) {
                log.warn("Instagram comments API page failed: {}", e.getMessage());
                return pages > 0;
            }
        }
        return added > 0 || pages > 0;
    }

    private void fetchViaGraphql(InstagramParsedPost post, boolean loggedIn) {
        JsonNode first = requestCommentsContainer(post.url(), post.mediaPk(), loggedIn);
        if (first != null) {
            applyConnectionPage(post, first, "container");
        }
        if (post.comments().isEmpty()) {
            fetchBySortOrder(post, "popular", loggedIn);
            sleepQuietly();
            fetchBySortOrder(post, "recent", loggedIn);
        }
    }

    private void applyConnectionPage(InstagramParsedPost post, JsonNode connection, String label) {
        List<InstagramCommentData> page = htmlParser.parseCommentsConnection(connection);
        post.addComments(page);
        log.info("Instagram comments [{}]: +{}, total={}", label, page.size(), post.comments().size());
    }

    private JsonNode requestCommentsContainer(String referer, String mediaId, boolean loggedIn) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("media_id", mediaId);
            variables.put("__relay_internal__pv__PolarisIsLoggedInrelayprovider", loggedIn);

            HttpResponse<String> response = httpClient.postGraphql(
                    referer,
                    httpClient.buildLoggedInGraphqlForm(
                            objectMapper,
                            "PolarisPostCommentsContainerQuery",
                            commentsContainerDocId,
                            variables
                    )
            );
            return readConnection(response.body(), COMMENTS_CONNECTION_KEY, "container");
        } catch (Exception e) {
            log.warn("Instagram comments container failed: {}", e.getMessage());
            return null;
        }
    }

    private void ensureMediaPk(InstagramParsedPost post) {
        if (!post.mediaPk().isBlank()) {
            return;
        }
        if (post.shortcode().isBlank()) {
            return;
        }
        try {
            post.setMediaPk(InstagramUrlUtils.shortcodeToMediaId(post.shortcode()));
            log.info("Resolved media_id={} from shortcode={}", post.mediaPk(), post.shortcode());
        } catch (Exception e) {
            log.warn("shortcode→media_id 변환 실패: {}", e.getMessage());
        }
    }

    private void fetchBySortOrder(InstagramParsedPost post, String sortOrder, boolean loggedIn) {
        String cursor = null;
        boolean hasNext = true;
        int pages = 0;

        while (hasNext && pages < maxPages) {
            sleepQuietly();
            JsonNode connection = requestCommentsPaginationPage(post.url(), post.mediaPk(), cursor, sortOrder, loggedIn);
            if (connection == null) {
                break;
            }
            List<InstagramCommentData> page = htmlParser.parseCommentsConnection(connection);
            post.addComments(page);
            InstagramHtmlParser.PageCursor pageCursor = htmlParser.readPageCursor(connection);
            cursor = blankToNull(pageCursor.endCursor());
            hasNext = pageCursor.hasNextPage();
            pages++;
            log.info("Instagram comments [{}] page {}: +{}, total={}",
                    sortOrder, pages, page.size(), post.comments().size());
        }
    }

    private void fetchChildComments(InstagramParsedPost post, boolean loggedIn) {
        List<InstagramCommentData> parents = List.copyOf(post.comments()).stream()
                .filter(c -> c != null && !c.isReply() && c.childCommentCount() > 0 && !c.pk().isBlank())
                .toList();
        if (parents.isEmpty()) {
            return;
        }
        log.info("Fetching replies for {} parent comments", parents.size());
        for (InstagramCommentData parent : parents) {
            sleepQuietly();
            try {
                if (loggedIn && apiV1Enabled) {
                    post.addComments(fetchRepliesViaApiV1(post.url(), post.mediaPk(), parent.pk()));
                } else {
                    post.addComments(requestRepliesGraphql(post.url(), post.mediaPk(), parent.pk(), loggedIn));
                }
            } catch (Exception e) {
                log.debug("Child comments failed for {}: {}", parent.pk(), e.getMessage());
            }
        }
    }

    private List<InstagramCommentData> fetchRepliesViaApiV1(String referer, String mediaId, String parentCommentId)
            throws Exception {
        List<InstagramCommentData> replies = new ArrayList<>();
        String maxId = null;
        String minId = null;
        int pages = 0;
        while (pages < maxChildPages) {
            String apiUrl = buildChildCommentsApiUrl(mediaId, parentCommentId, maxId, minId);
            HttpResponse<String> response = httpClient.getApiJson(referer, apiUrl);
            JsonNode root = objectMapper.readTree(response.body());
            if (!"ok".equalsIgnoreCase(root.path("status").asText())) {
                break;
            }
            JsonNode childComments = root.path("child_comments");
            if (!childComments.isArray()) {
                childComments = root.path("comments");
            }
            int before = replies.size();
            if (childComments.isArray()) {
                for (JsonNode node : childComments) {
                    InstagramCommentData comment = htmlParser.toComment(node, true);
                    if (comment != null && !parentCommentId.equals(comment.pk())) {
                        replies.add(comment);
                    }
                }
            }
            pages++;
            String nextMaxId = readCursor(root, "next_max_id");
            String nextMinId = readCursor(root, "next_min_id");
            boolean hasMoreTail = root.path("has_more_comments").asBoolean(nextMaxId != null);
            boolean hasMoreHead = root.path("has_more_headload_comments").asBoolean(nextMinId != null);
            if (replies.size() == before && nextMaxId == null && nextMinId == null) {
                break;
            }
            if (hasMoreTail && nextMaxId != null) {
                maxId = nextMaxId;
                minId = null;
                sleepQuietly();
                continue;
            }
            if (hasMoreHead && nextMinId != null) {
                minId = nextMinId;
                maxId = null;
                sleepQuietly();
                continue;
            }
            break;
        }
        return replies;
    }

    private JsonNode requestCommentsPaginationPage(
            String referer,
            String mediaId,
            String after,
            String sortOrder,
            boolean loggedIn
    ) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("after", after);
            variables.put("before", null);
            variables.put("first", 50);
            variables.put("last", null);
            variables.put("media_id", mediaId);
            variables.put("sort_order", sortOrder);
            variables.put("__relay_internal__pv__PolarisIsLoggedInrelayprovider", loggedIn);

            HttpResponse<String> response = httpClient.postGraphql(
                    referer,
                    httpClient.buildLoggedInGraphqlForm(
                            objectMapper,
                            "PolarisPostCommentsPaginationQuery",
                            commentsPaginationDocId,
                            variables
                    )
            );
            return readConnection(response.body(), COMMENTS_CONNECTION_KEY, sortOrder);
        } catch (Exception e) {
            log.debug("Instagram comments pagination failed ({}): {}", sortOrder, e.getMessage());
            return null;
        }
    }

    private List<InstagramCommentData> requestRepliesGraphql(
            String referer,
            String mediaId,
            String parentCommentId,
            boolean loggedIn
    ) throws Exception {
        List<InstagramCommentData> replies = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;
        int pages = 0;

        while (hasNext && pages < maxChildPages) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("after", cursor);
            variables.put("before", null);
            variables.put("media_id", mediaId);
            variables.put("parent_comment_id", parentCommentId);
            variables.put("is_chronological", null);
            variables.put("first", 50);
            variables.put("last", null);
            variables.put("__relay_internal__pv__PolarisIsLoggedInrelayprovider", loggedIn);

            HttpResponse<String> response = httpClient.postGraphql(
                    referer,
                    httpClient.buildLoggedInGraphqlForm(
                            objectMapper,
                            "PolarisPostChildCommentsQuery",
                            childCommentsDocId,
                            variables
                    )
            );
            JsonNode connection = readConnection(response.body(), CHILD_COMMENTS_KEY, "child");
            if (connection == null) {
                break;
            }
            JsonNode edges = connection.path("edges");
            if (edges.isArray()) {
                for (JsonNode edge : edges) {
                    InstagramCommentData comment = htmlParser.toComment(edge.path("node"), true);
                    if (comment != null && !parentCommentId.equals(comment.pk())) {
                        replies.add(comment);
                    }
                }
            }
            InstagramHtmlParser.PageCursor pageCursor = htmlParser.readPageCursor(connection);
            cursor = blankToNull(pageCursor.endCursor());
            hasNext = pageCursor.hasNextPage();
            pages++;
            if (hasNext) {
                sleepQuietly();
            }
        }
        return replies;
    }

    private static String buildCommentsApiUrl(String mediaId, String maxId, String minId) {
        StringBuilder url = new StringBuilder("https://www.instagram.com/api/v1/media/")
                .append(mediaId)
                .append("/comments/?can_support_threading=true&permalink_enabled=false");
        if (maxId != null && !maxId.isBlank()) {
            url.append("&max_id=").append(URLEncoder.encode(maxId, StandardCharsets.UTF_8));
        }
        if (minId != null && !minId.isBlank()) {
            url.append("&min_id=").append(URLEncoder.encode(minId, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private static String buildChildCommentsApiUrl(
            String mediaId,
            String parentCommentId,
            String maxId,
            String minId
    ) {
        StringBuilder url = new StringBuilder("https://www.instagram.com/api/v1/media/")
                .append(mediaId)
                .append("/comments/")
                .append(parentCommentId)
                .append("/child_comments/?can_support_threading=true&permalink_enabled=false");
        if (maxId != null && !maxId.isBlank()) {
            url.append("&max_id=").append(URLEncoder.encode(maxId, StandardCharsets.UTF_8));
        }
        if (minId != null && !minId.isBlank()) {
            url.append("&min_id=").append(URLEncoder.encode(minId, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private JsonNode readConnection(String body, String connectionKey, String label) throws Exception {
        if (InstagramGraphqlUtil.isHtmlBody(body)) {
            log.warn("Instagram comment GraphQL returned HTML ({})", label);
            return null;
        }
        JsonNode root = InstagramGraphqlUtil.parseRoot(objectMapper, body);
        if (root == null) {
            log.warn("Instagram comment GraphQL returned HTML ({})", label);
            return null;
        }
        JsonNode connection = findFirstByKey(root, connectionKey);
        if (connection == null) {
            log.warn("Instagram comments connection missing ({})", label);
        }
        return connection;
    }

    private JsonNode findFirstByKey(JsonNode root, String key) {
        List<JsonNode> found = new ArrayList<>();
        collectByKey(root, key, found, 0);
        return found.isEmpty() ? null : found.getFirst();
    }

    private void collectByKey(JsonNode node, String key, List<JsonNode> out, int depth) {
        if (node == null || node.isMissingNode() || depth > 40 || !out.isEmpty()) {
            return;
        }
        if (node.isObject()) {
            if (node.has(key)) {
                out.add(node.get(key));
                return;
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** next_max_id / next_min_id 는 문자열 또는 JSON 객체로 올 수 있다. */
    private static String readCursor(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return blankToNull(node.asText());
        }
        if (node.isNumber()) {
            return blankToNull(node.asText());
        }
        if (node.isObject() || node.isArray()) {
            return blankToNull(node.toString());
        }
        return blankToNull(node.asText(null));
    }

    private void sleepQuietly() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
