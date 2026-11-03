package com.ospaulos.vupth.domain.establishment.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * teto-200-lojas-observado (DEC-176, FRAQUEZAS F13): a lista publica de lojas sem {@code page}/{@code size} tem teto em
 * configuracao ({@code vupth.showcase.max-items}, padrao 200), e o ADMIN le em {@code /actuator/metrics} quantas lojas cada
 * listagem devolveu ({@code vupth.showcase.list.size}), quantas foram cortadas pelo teto ({@code vupth.showcase.list.capped})
 * e a posicao na lista da loja que o cliente abriu ({@code vupth.showcase.opened.position}, quando o app a envia). Com esses
 * numeros se decide o teto real.
 */
@Component
public class ShowcaseMetrics {

    private final int maxItems;
    private final DistributionSummary listSize;
    private final DistributionSummary capped;
    private final DistributionSummary openedPosition;

    /** O recorde persistido (F13); opcional nos testes de unidade. */
    @Autowired(required = false)
    private DeepestShowcasePosition deepest;

    public ShowcaseMetrics(@Value("${vupth.showcase.max-items:200}") int maxItems,
                           @Autowired(required = false) MeterRegistry registry) {
        this.maxItems = maxItems;
        this.listSize = registry == null ? null : DistributionSummary.builder("vupth.showcase.list.size")
                .description("Lojas devolvidas por listagem publica").publishPercentiles(0.5, 0.9, 0.99).register(registry);
        this.capped = registry == null ? null : DistributionSummary.builder("vupth.showcase.list.capped")
                .description("Lojas cortadas pelo teto numa listagem").register(registry);
        this.openedPosition = registry == null ? null : DistributionSummary.builder("vupth.showcase.opened.position")
                .description("Posicao (1 = primeira) da loja aberta pelo cliente na lista").publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
    }

    public int maxItems() {
        return maxItems;
    }

    /** Aplica o teto (se {@code maxItems > 0}) e mede o que foi devolvido e o que foi cortado. */
    public <T> List<T> cap(List<T> all) {
        List<T> out = maxItems > 0 && all.size() > maxItems ? all.subList(0, maxItems) : all;
        if (listSize != null) {
            listSize.record(out.size());
            if (out.size() < all.size()) {
                capped.record(all.size() - out.size());
            }
        }
        return out;
    }

    /** Mede a posicao (1-based) da loja aberta; ignora o que nao faz sentido. */
    public void opened(Integer position) {
        if (position == null || position < 1 || position > 100_000) {
            return;
        }
        if (openedPosition != null) {
            openedPosition.record(position);
        }
        if (deepest != null) {
            deepest.record(position);
        }
    }

    /** Para o ADMIN: o que as metricas em memoria mostram desde o ultimo boot. */
    public record Snapshot(long lists, double maxListSize, double cappedTotal, long opened, double maxOpenedSinceBoot) {}

    public Snapshot snapshot() {
        return listSize == null ? new Snapshot(0, 0, 0, 0, 0)
                : new Snapshot(listSize.count(), listSize.max(), capped.totalAmount(), openedPosition.count(), openedPosition.max());
    }
}
