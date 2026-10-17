package com.ospaulos.vupth.api.support.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** GET /admin/support/article-votes: a contagem de cada artigo (voto-do-artigo). */
public record ArticleVoteSummaryResponse(
        @JsonProperty("slug") String slug,
        @JsonProperty("up") long up,
        @JsonProperty("down") long down,
        @JsonProperty("last_vote_at") OffsetDateTime lastVoteAt
) {
}
