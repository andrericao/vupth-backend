package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.service.DeepestShowcasePosition;
import com.ospaulos.vupth.domain.establishment.service.ShowcaseMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * teto-200-lojas-observado (DEC-176, F13): o que o ADMIN le para decidir o teto real da lista de lojas. O recorde de posicao
 * sobrevive ao reinicio; os demais numeros sao desde o ultimo boot (tambem em {@code /actuator/metrics}). Permissao OVERVIEW.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/overview/showcase")
public class AdminShowcaseController {

    private final ShowcaseMetrics metrics;
    private final DeepestShowcasePosition deepest;

    public record ShowcaseStats(
            @JsonProperty("max_items") int maxItems,
            @JsonProperty("max_opened_position") Integer maxOpenedPosition,
            @JsonProperty("max_opened_position_at") OffsetDateTime maxOpenedPositionAt,
            @JsonProperty("lists_since_boot") long listsSinceBoot,
            @JsonProperty("max_list_size_since_boot") double maxListSizeSinceBoot,
            @JsonProperty("capped_total_since_boot") double cappedTotalSinceBoot,
            @JsonProperty("opened_since_boot") long openedSinceBoot) {}

    @GetMapping
    public ResponseEntity<ShowcaseStats> get() {
        var snap = metrics.snapshot();
        var top = deepest.read();
        return ResponseEntity.ok(new ShowcaseStats(metrics.maxItems(),
                top.map(DeepestShowcasePosition.Deepest::position).orElse(null),
                top.map(DeepestShowcasePosition.Deepest::reachedAt).orElse(null),
                snap.lists(), snap.maxListSize(), snap.cappedTotal(), snap.opened()));
    }
}
