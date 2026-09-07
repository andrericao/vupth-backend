package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.domain.establishment.valueObject.GeoSearch;
import com.ospaulos.vupth.domain.establishment.valueObject.LocationFilter;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.Showcase;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.util.List;
import java.util.UUID;

public interface EstablishmentService {

    EstablishmentResponse registerEstablishment(EstablishmentRequest request);

    EstablishmentResponse findById(UUID id);

    List<EstablishmentResponse> findAll();

    /**
     * Listagem publica com todos os filtros opcionais (backlog #2, fatia B).
     *
     * <p>Substituiu os cinco metodos de busca que existiam antes ({@code findAllActive},
     * {@code searchByName}, {@code findByServiceType}, {@code searchByNeighborhood} e a combinacao
     * dos dois ultimos): o roteamento entre eles era um {@code if/else} no controller, e com a busca
     * por proximidade cada ramo passaria a precisar do mesmo tratamento de distancia. Com uma porta
     * so, a ordenacao por distancia vale para <b>qualquer</b> combinacao de filtros de graca.</p>
     *
     * @param location escopo de cidade/UF (backlog #29), ou {@code null} quando a busca nao mandou
     *                 nenhum dos dois. E o filtro de quem esta <b>sem coordenada</b> — a fatia C do
     *                 #2 — e por isso ele <b>cede a vez ao {@code name}</b>: ver o javadoc da
     *                 implementacao.
     * @param geo criterio de proximidade, ou {@code null} quando a busca nao mandou coordenada —
     *            nesse caso a ordenacao continua sendo por menor tempo de espera.
     * @param showcase o que o usuario escolheu na tela: ordenacao e os filtros de prontidao
     *                 (aberta agora, tem profissional, teto de espera). Nunca {@code null} —
     *                 {@link Showcase#NONE} e a vitrine sem pedido nenhum.
     */
    List<EstablishmentResponse> searchPublic(String name,
                                             ServiceType serviceType,
                                             String neighborhood,
                                             LocationFilter location,
                                             GeoSearch geo,
                                             Showcase showcase);

    EstablishmentResponse updateOpenStatus(UUID establishmentId, UUID requesterId, OpenStatus newStatus);

    List<ServiceItem> getServicesItemByEstablishment(UUID establishmentId);
}
