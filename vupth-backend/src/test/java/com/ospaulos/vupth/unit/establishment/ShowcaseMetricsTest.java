package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.service.ShowcaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** teto-200-lojas-observado (DEC-176): o teto vem da configuracao e cada listagem e cada loja aberta viram metrica. */
@DisplayName("DEC-176: teto da lista de lojas e as metricas que o observam")
class ShowcaseMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("acima do teto corta e mede o devolvido e o cortado; abaixo devolve tudo")
    void capsAndMeasures() {
        ShowcaseMetrics m = new ShowcaseMetrics(200, registry);
        List<Integer> big = IntStream.range(0, 250).boxed().toList();

        assertThat(m.cap(big)).hasSize(200).startsWith(0, 1, 2);
        assertThat(m.cap(List.of(1, 2, 3))).hasSize(3);

        assertThat(registry.summary("vupth.showcase.list.size").count()).isEqualTo(2);
        assertThat(registry.summary("vupth.showcase.list.size").max()).isEqualTo(200);
        assertThat(registry.summary("vupth.showcase.list.capped").totalAmount()).isEqualTo(50);
    }

    @Test
    @DisplayName("teto 0 desliga; sem registro de metricas so aplica o teto")
    void zeroDisablesAndNoRegistry() {
        List<Integer> big = IntStream.range(0, 250).boxed().toList();
        assertThat(new ShowcaseMetrics(0, registry).cap(big)).hasSize(250);
        assertThat(new ShowcaseMetrics(10, null).cap(big)).hasSize(10);
        new ShowcaseMetrics(10, null).opened(3);
    }

    @Test
    @DisplayName("posicao da loja aberta: so valores de 1 em diante")
    void openedPosition() {
        ShowcaseMetrics m = new ShowcaseMetrics(200, registry);
        m.opened(7);
        m.opened(null);
        m.opened(0);
        m.opened(-2);
        assertThat(registry.summary("vupth.showcase.opened.position").count()).isEqualTo(1);
        assertThat(registry.summary("vupth.showcase.opened.position").max()).isEqualTo(7);
    }
}
