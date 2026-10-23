package com.ospaulos.vupth.api.serviceItem.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.util.UUID;

public record ServiceItemResponse(
        UUID establishmentId,
        String establishmentName,
        UUID id,
        ServiceItemType serviceItemType,
        String customName,
        BigDecimal price,

        @JsonProperty("client_price")
        BigDecimal clientPrice,

        @JsonProperty("uses_platform_pricing")
        Boolean usesPlatformPricing,

        Integer weeklyAverageDurationInMinutes,

        // Rating denormalizado por servico (item #6). Media (2 casas) null quando sem votos.
        @JsonProperty("rating_average")
        Double ratingAverage,

        @JsonProperty("rating_count")
        long ratingCount,

        // Divisao do preco (Receita LIQUIDA). As tres sao null em servico legado — o app deve pedir
        // ao profissional que atualize o preco desse servico.
        @JsonProperty("pro_receives")
        BigDecimal proReceives,

        @JsonProperty("base_price")
        BigDecimal basePrice,

        @JsonProperty("intermediary_fee")
        BigDecimal intermediaryFee,

        @JsonProperty("platform_fee")
        BigDecimal platformFee,

        // A duracao que o profissional cadastrou — o POST e o PATCH gravavam, mas a resposta nao
        // devolvia, e o app mostrava a edicao como perdida (Partner, 25/09). Diferente de
        // weeklyAverageDurationInMinutes, que e a media MEDIDA na semana.
        Integer estimatedMinutes,

        // valor-minimo-do-servico (DEC-121): so no cadastro e na edicao. O minimo vigente e o aviso (null quando o preco passa dele);
        // o cadastro continua permitido, o aviso so diz que as taxas vao fazer o preco do cliente subir demais.
        @JsonProperty("min_price")
        BigDecimal minPrice,

        PriceWarning warning
){

    public record PriceWarning(String code, String message) {
        public static final String BELOW_MINIMUM = "PRICE_BELOW_MINIMUM";
    }

    /** Sem o minimo (leituras e listas). */
    public ServiceItemResponse(UUID establishmentId, String establishmentName, UUID id,
                               ServiceItemType serviceItemType, String customName, BigDecimal price,
                               BigDecimal clientPrice, Boolean usesPlatformPricing,
                               Integer weeklyAverageDurationInMinutes, Double ratingAverage, long ratingCount,
                               BigDecimal proReceives, BigDecimal basePrice, BigDecimal intermediaryFee,
                               BigDecimal platformFee, Integer estimatedMinutes) {
        this(establishmentId, establishmentName, id, serviceItemType, customName, price, clientPrice, usesPlatformPricing,
                weeklyAverageDurationInMinutes, ratingAverage, ratingCount, proReceives, basePrice, intermediaryFee,
                platformFee, estimatedMinutes, null, null);
    }

    /** Traz o minimo vigente e, se o preco for igual ou menor, o aviso (DEC-121). Nao bloqueia nada. */
    public ServiceItemResponse withMinimum(BigDecimal minimum) {
        PriceWarning warning = price != null && minimum != null && price.compareTo(minimum) <= 0
                ? new PriceWarning(PriceWarning.BELOW_MINIMUM, String.format(java.util.Locale.of("pt", "BR"),
                "O valor está igual ou abaixo do mínimo de R$ %.2f do VUPTH. As taxas vão fazer o preço do cliente subir demais.", minimum))
                : null;
        return new ServiceItemResponse(establishmentId, establishmentName, id, serviceItemType, customName, price, clientPrice,
                usesPlatformPricing, weeklyAverageDurationInMinutes, ratingAverage, ratingCount, proReceives, basePrice,
                intermediaryFee, platformFee, estimatedMinutes, minimum, warning);
    }

    /** Sem a divisao do preco (servico legado). */
    public ServiceItemResponse(UUID establishmentId, String establishmentName, UUID id,
                               ServiceItemType serviceItemType, String customName, BigDecimal price,
                               BigDecimal clientPrice, Boolean usesPlatformPricing,
                               Integer weeklyAverageDurationInMinutes, Double ratingAverage, long ratingCount) {
        this(establishmentId, establishmentName, id, serviceItemType, customName, price, clientPrice,
                usesPlatformPricing, weeklyAverageDurationInMinutes, ratingAverage, ratingCount, null, null, null, null, null);
    }

    public static ServiceItemResponse fromEntity(ServiceItem item) {
        PriceSplit split = item.getPriceSplit();
        return new ServiceItemResponse(
                item.getProfessional().getEstablishment().getId(),
                item.getProfessional().getEstablishment().getName(),
                item.getId(),
                item.getServiceItemType(),
                item.getCustomName(),
                item.getPrice(),
                item.getClientPrice(),
                item.getUsesPlatformPricing(),
                item.getWeeklyAverageDurationInMinutes(),
                item.getRatingAverage(),
                item.getRatingCount(),
                split != null ? split.proReceives() : null,
                split != null ? split.basePrice() : null,
                split != null ? split.intermediaryFee() : null,
                split != null ? split.platformFee() : null,
                item.getEstimatedMinutes()
        );
    }
}
