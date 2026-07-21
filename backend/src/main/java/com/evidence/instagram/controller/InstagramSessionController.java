package com.evidence.instagram.controller;

import com.evidence.instagram.service.InstagramLoginHelperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/crawl/instagram/session")
public class InstagramSessionController {

    private final InstagramLoginHelperService loginHelperService;

    public InstagramSessionController(InstagramLoginHelperService loginHelperService) {
        this.loginHelperService = loginHelperService;
    }

    @GetMapping
    public InstagramLoginHelperService.SessionStatus status() {
        return loginHelperService.status();
    }

    @PostMapping("/login")
    public Map<String, Object> startLogin() {
        return loginHelperService.startLogin();
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancelLogin() {
        return loginHelperService.cancelLogin();
    }
}
