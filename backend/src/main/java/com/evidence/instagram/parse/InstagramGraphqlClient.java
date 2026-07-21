package com.evidence.instagram.parse;

import com.evidence.instagram.http.InstagramHttpClient;
import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.util.InstagramGraphqlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTML에 prefetch 데이터가 없을 때 GraphQL로 게시글 메타를 가져온다.
 * (비로그인 HTTP GET은 셸 HTML만 주고 {@code PolarisPostRootQuery} 데이터가 없음)
 */
@Component
public class InstagramGraphqlClient {

    private static final Logger log = LoggerFactory.getLogger(InstagramGraphqlClient.class);

    private final InstagramHttpClient httpClient;
    private final InstagramHtmlParser htmlParser;
    private final ObjectMapper objectMapper;
    private final String postDocId;

    public InstagramGraphqlClient(
            InstagramHttpClient httpClient,
            InstagramHtmlParser htmlParser,
            ObjectMapper objectMapper,
            @Value("${evidence.instagram.doc-id.post:27128499623469141}") String postDocId
    ) {
        this.httpClient = httpClient;
        this.htmlParser = htmlParser;
        this.objectMapper = objectMapper;
        this.postDocId = postDocId;
    }

    public InstagramParsedPost fetchPost(String url, String shortcode) throws Exception {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("shortcode", shortcode);
        variables.put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false);

        HttpResponse<String> response = httpClient.postGraphql(
                url,
                InstagramHttpClient.formOf(
                        "doc_id", postDocId,
                        "variables", objectMapper.writeValueAsString(variables),
                        "server_timestamps", "true"
                )
        );

        String body = response.body();
        if (InstagramGraphqlUtil.isHtmlBody(body)) {
            throw new IllegalStateException("Instagram GraphQL이 HTML을 반환했습니다. 로그인/차단 여부를 확인하세요.");
        }

        JsonNode root = InstagramGraphqlUtil.parseRoot(objectMapper, body);
        if (root == null) {
            throw new IllegalStateException("Instagram GraphQL이 HTML을 반환했습니다. 로그인/차단 여부를 확인하세요.");
        }
        InstagramParsedPost post = htmlParser.parseGraphqlPostResponse(url, shortcode, root);
        if (post.username().isBlank() && post.caption().isBlank()) {
            log.warn("PolarisPostRootQuery empty for shortcode={}", shortcode);
            throw new IllegalStateException("Instagram GraphQL에서 게시글 데이터를 찾지 못했습니다. shortcode=" + shortcode);
        }
        return post;
    }
}
