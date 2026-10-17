package com.ospaulos.vupth.domain.support.repository;

import com.ospaulos.vupth.api.support.dto.response.ArticleVoteSummaryResponse;
import com.ospaulos.vupth.domain.support.model.ArticleVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleVoteRepository extends JpaRepository<ArticleVote, UUID> {

    Optional<ArticleVote> findBySlugAndIpHashAndVotedOn(String slug, String ipHash, LocalDate votedOn);

    /** A contagem por artigo para o Painel: os mais votados com dedao primeiro. */
    @Query("""
            SELECT new com.ospaulos.vupth.api.support.dto.response.ArticleVoteSummaryResponse(
                v.slug,
                SUM(CASE WHEN v.vote = com.ospaulos.vupth.domain.support.model.ArticleVoteType.UP THEN 1L ELSE 0L END),
                SUM(CASE WHEN v.vote = com.ospaulos.vupth.domain.support.model.ArticleVoteType.DOWN THEN 1L ELSE 0L END),
                MAX(v.updatedAt))
            FROM ArticleVote v
            GROUP BY v.slug
            ORDER BY SUM(CASE WHEN v.vote = com.ospaulos.vupth.domain.support.model.ArticleVoteType.DOWN THEN 1L ELSE 0L END) DESC,
                     v.slug ASC
            """)
    List<ArticleVoteSummaryResponse> summary();
}
