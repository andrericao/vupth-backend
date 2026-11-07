package com.ospaulos.vupth.domain.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("recursos-infraestrutura: o sorteio da porcentagem")
class AppFeatureBucketTest {

    @Test
    @DisplayName("a mesma conta cai sempre no mesmo lado, e de 0 a 99")
    void stable() {
        UUID id = UUID.randomUUID();
        int bucket = AppFeatureService.bucket("chave", id);
        assertThat(bucket).isBetween(0, 99);
        assertThat(IntStream.range(0, 20).map(i -> AppFeatureService.bucket("chave", id))).containsOnly(bucket);
    }

    @Test
    @DisplayName("10 mil contas: perto de metade fica abaixo de 50")
    void spread() {
        long below = IntStream.range(0, 10_000).filter(i -> AppFeatureService.bucket("chave", UUID.randomUUID()) < 50).count();
        assertThat(below).isBetween(4_500L, 5_500L);
    }
}
