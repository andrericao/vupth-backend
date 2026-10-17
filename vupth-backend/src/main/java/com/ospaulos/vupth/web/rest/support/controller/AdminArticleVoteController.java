package com.ospaulos.vupth.web.rest.support.controller;

import com.ospaulos.vupth.api.support.dto.response.ArticleVoteSummaryResponse;
import com.ospaulos.vupth.domain.support.service.ArticleVoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A contagem dos votos dos artigos de suporte no painel (voto-do-artigo). So ADMIN ({@code /admin/**}). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/support")
public class AdminArticleVoteController {

    private final ArticleVoteService articleVoteService;

    @GetMapping("/article-votes")
    public ResponseEntity<List<ArticleVoteSummaryResponse>> articleVotes() {
        return ResponseEntity.ok(articleVoteService.summary());
    }
}
