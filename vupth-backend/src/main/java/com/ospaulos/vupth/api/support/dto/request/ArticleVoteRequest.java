package com.ospaulos.vupth.api.support.dto.request;

import com.ospaulos.vupth.domain.support.model.ArticleVoteType;
import jakarta.validation.constraints.NotNull;

/** POST /support/articles/{slug}/vote: {@code {"vote":"UP"}} ou {@code {"vote":"DOWN"}}. */
public record ArticleVoteRequest(@NotNull ArticleVoteType vote) {
}
