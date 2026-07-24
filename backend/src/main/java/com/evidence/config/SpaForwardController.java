package com.evidence.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 확장자 없는 경로를 SPA index.html 로 포워드한다.
 * 정적 파일({@code /assets/*})과 {@code /api/**} 는 Spring 기본 매핑이 처리한다.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {
            "/",
            "/{path:^(?!api$)[^\\.]*$}"
    })
    public String forwardSpa() {
        return "forward:/index.html";
    }
}
