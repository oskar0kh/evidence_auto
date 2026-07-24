package com.evidence.instagram.service;

import com.evidence.dcinside.dto.CaptureImage;
import com.evidence.dcinside.dto.TimedResult;
import com.evidence.dcinside.util.StepTimer;
import com.evidence.dcinside.util.StepTimings;
import com.evidence.instagram.dto.InstagramCommentData;
import com.evidence.instagram.dto.InstagramPostData;
import com.evidence.instagram.http.InstagramHttpClient;
import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.parse.InstagramGraphqlClient;
import com.evidence.instagram.parse.InstagramHtmlParser;
import com.evidence.instagram.util.InstagramUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class InstagramCrawlService {

    private static final Logger log = LoggerFactory.getLogger(InstagramCrawlService.class);

    private final InstagramHttpClient httpClient;
    private final InstagramHtmlParser htmlParser;
    private final InstagramGraphqlClient graphqlClient;
    private final InstagramCommentService commentService;

    public InstagramCrawlService(
            InstagramHttpClient httpClient,
            InstagramHtmlParser htmlParser,
            InstagramGraphqlClient graphqlClient,
            InstagramCommentService commentService
    ) {
        this.httpClient = httpClient;
        this.htmlParser = htmlParser;
        this.graphqlClient = graphqlClient;
        this.commentService = commentService;
    }

    public InstagramParsedPost fetchParsedPost(String url) throws Exception {
        return fetchParsedPostTimed(url).value();
    }

    public TimedResult<InstagramParsedPost> fetchParsedPostTimed(String url) throws Exception {
        TimedResult<InstagramParsedPost> meta = fetchPostMetaTimed(url);
        httpClient.reloadSessionCookiesFromFile();
        TimedResult<InstagramParsedPost> withComments = collectCommentsTimed(meta.value());
        return new TimedResult<>(
                withComments.value(),
                StepTimings.merge(meta.timings(), withComments.timings())
        );
    }

    /** 게시글 메타만 조회 (댓글 제외). 스크린샷과 병렬 실행할 때 사용. */
    public TimedResult<InstagramParsedPost> fetchPostMetaTimed(String url) throws Exception {
        StepTimer timer = new StepTimer(log, "instagram-meta " + url);
        String normalized = InstagramUrlUtils.normalizeUrl(url);
        String shortcode = InstagramUrlUtils.extractShortcode(normalized);

        HttpResponse<String> htmlResponse = httpClient.getHtml(normalized);
        timer.step("fetch-page");

        InstagramParsedPost post = htmlParser.tryParse(normalized, shortcode, htmlResponse.body());
        if (post.username().isBlank() && post.caption().isBlank()) {
            log.info("HTML prefetch empty for {}; falling back to GraphQL PolarisPostRootQuery", shortcode);
            post = graphqlClient.fetchPost(normalized, shortcode);
            timer.step("graphql-post");
        } else {
            timer.step("parse-html");
        }

        post.setUrl(normalized);
        post.setShortcode(shortcode);
        return new TimedResult<>(post, timer.finish());
    }

    public void collectComments(InstagramParsedPost post) {
        commentService.collectComments(post);
    }

    public TimedResult<InstagramParsedPost> collectCommentsTimed(InstagramParsedPost post) {
        StepTimer timer = new StepTimer(log, "instagram-comments " + post.shortcode());
        collectComments(post);
        timer.step("fetch-comments");
        return new TimedResult<>(post, timer.finish());
    }

    public List<InstagramPostData> buildRows(InstagramParsedPost post, String searchQuery) {
        List<InstagramPostData> rows = new ArrayList<>();
        String title = firstLine(post.caption());
        String content = buildPostContent(post);
        String remarks = buildRemarks(post);

        rows.add(new InstagramPostData(
                post.url(),
                post.takenAt(),
                InstagramPostData.TYPE_POST,
                post.username(),
                title,
                content,
                "",
                remarks,
                "",
                "",
                post.commentCount(),
                post.shortcode(),
                "",
                List.copyOf(post.comments())
        ));

        if (hasSearchQuery(searchQuery)) {
            List<String> terms = parseTerms(searchQuery);
            for (InstagramCommentData comment : post.comments()) {
                if (!matchesAny(comment.text(), terms)) {
                    continue;
                }
                rows.add(new InstagramPostData(
                        post.url(),
                        comment.timestamp(),
                        InstagramPostData.TYPE_COMMENT,
                        comment.username(),
                        title,
                        comment.text(),
                        "",
                        "검색어 매칭 댓글",
                        "",
                        "",
                        post.commentCount(),
                        post.shortcode(),
                        comment.pk(),
                        List.of(comment)
                ));
            }
        }

        return rows;
    }

    public List<InstagramPostData> attachCapture(List<InstagramPostData> rows, CaptureImage capture) {
        if (capture == null || capture.pngBytes() == null || capture.pngBytes().length == 0) {
            return rows;
        }
        String base64 = Base64.getEncoder().encodeToString(capture.pngBytes());
        String relativePath = "./Screenshot/" + capture.filename();
        List<InstagramPostData> updated = new ArrayList<>(rows.size());
        for (InstagramPostData row : rows) {
            if (InstagramPostData.TYPE_POST.equals(row.postType())) {
                updated.add(new InstagramPostData(
                        row.url(),
                        row.postDate(),
                        row.postType(),
                        row.nickname(),
                        row.title(),
                        row.content(),
                        row.crimeType(),
                        row.remarks(),
                        relativePath,
                        base64,
                        row.commentCount(),
                        row.shortcode(),
                        row.commentPk(),
                        row.comments()
                ));
            } else {
                updated.add(new InstagramPostData(
                        row.url(),
                        row.postDate(),
                        row.postType(),
                        row.nickname(),
                        row.title(),
                        row.content(),
                        row.crimeType(),
                        row.remarks(),
                        relativePath,
                        "",
                        row.commentCount(),
                        row.shortcode(),
                        row.commentPk(),
                        row.comments()
                ));
            }
        }
        return updated;
    }

    private String buildPostContent(InstagramParsedPost post) {
        StringBuilder sb = new StringBuilder();
        sb.append("[caption] ").append(post.caption() == null ? "" : post.caption());
        if (!post.comments().isEmpty()) {
            sb.append("\n[comments]");
            int index = 1;
            for (InstagramCommentData comment : post.comments()) {
                sb.append("\n[댓글 ").append(index).append("] ")
                        .append(comment.username() == null ? "" : comment.username())
                        .append(comment.isReply() ? " (대댓글)" : "")
                        .append(": ")
                        .append(comment.text() == null ? "" : comment.text())
                        .append(" (")
                        .append(comment.timestamp() == null ? "" : comment.timestamp())
                        .append(')');
                index++;
            }
        }
        return sb.toString();
    }

    private String buildRemarks(InstagramParsedPost post) {
        List<String> parts = new ArrayList<>();
        if (!httpClient.hasLoginSession()) {
            parts.add("sessionid 없음 — 댓글이 일부만 수집될 수 있음");
        }
        if (post.hasNextCommentsPage()) {
            parts.add("추가 댓글 페이지가 남아 있을 수 있음");
        }
        if (post.commentCount() > post.comments().size()) {
            parts.add("표시 댓글수 " + post.commentCount() + " / 수집 " + post.comments().size());
        }
        if (!post.fullName().isBlank()) {
            parts.add("표시명: " + post.fullName());
        }
        return String.join(" | ", parts);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R", 2);
        return lines[0].trim();
    }

    private static boolean hasSearchQuery(String searchQuery) {
        return searchQuery != null && !searchQuery.isBlank();
    }

    private static List<String> parseTerms(String searchQuery) {
        List<String> terms = new ArrayList<>();
        if (searchQuery == null) {
            return terms;
        }
        for (String part : searchQuery.split("[,\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                terms.add(trimmed.toLowerCase());
            }
        }
        return terms;
    }

    private static boolean matchesAny(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
