package com.ospaulos.vupth.unit.support;

import com.ospaulos.vupth.domain.support.exception.InvalidArticleSlugException;
import com.ospaulos.vupth.domain.support.model.ArticleVote;
import com.ospaulos.vupth.domain.support.model.ArticleVoteType;
import com.ospaulos.vupth.domain.support.repository.ArticleVoteRepository;
import com.ospaulos.vupth.domain.support.service.ArticleVoteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** voto-do-artigo: um voto por IP, artigo e dia (o ultimo vale); o IP so entra como hash; slug so pelo formato. */
class ArticleVoteServiceTest {

    private final ArticleVoteRepository repository = mock(ArticleVoteRepository.class);
    private final ArticleVoteService service = new ArticleVoteService(repository, "sal");

    @Test
    @DisplayName("primeiro voto do dia: grava com o hash do IP, nunca o IP")
    void firstVote() {
        when(repository.findBySlugAndIpHashAndVotedOn(any(), any(), any())).thenReturn(Optional.empty());

        service.vote("como-pagar", ArticleVoteType.UP, "203.0.113.9");

        ArgumentCaptor<ArticleVote> saved = ArgumentCaptor.forClass(ArticleVote.class);
        verify(repository).save(saved.capture());
        ArticleVote v = saved.getValue();
        assertThat(v.getSlug()).isEqualTo("como-pagar");
        assertThat(v.getVote()).isEqualTo(ArticleVoteType.UP);
        assertThat(v.getVotedOn()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(v.getIpHash()).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(v.getId()).isNotNull();
    }

    @Test
    @DisplayName("mesmo IP, artigo e dia: troca o voto em vez de somar outro")
    void changesTheVote() {
        ArticleVote existing = new ArticleVote();
        existing.setVote(ArticleVoteType.UP);
        when(repository.findBySlugAndIpHashAndVotedOn(eq("multas"), any(), any())).thenReturn(Optional.of(existing));

        service.vote("multas", ArticleVoteType.DOWN, "203.0.113.9");

        verify(repository).save(existing);
        assertThat(existing.getVote()).isEqualTo(ArticleVoteType.DOWN);
    }

    @Test
    @DisplayName("slug nulo, vazio, com maiuscula, barra ou acima de 80: 422 e nada grava")
    void invalidSlug() {
        for (String slug : new String[]{null, "", "Maiuscula", "a/b", "com espaco", "a".repeat(81)}) {
            assertThatThrownBy(() -> service.vote(slug, ArticleVoteType.UP, "1.1.1.1"))
                    .isInstanceOf(InvalidArticleSlugException.class);
        }
        service.vote("a".repeat(80), ArticleVoteType.UP, "1.1.1.1");
        verify(repository).save(any());
        verify(repository, never()).summary();
    }

    @Test
    @DisplayName("a contagem vem do repositorio")
    void summary() {
        when(repository.summary()).thenReturn(List.of());
        assertThat(service.summary()).isEmpty();
    }
}
