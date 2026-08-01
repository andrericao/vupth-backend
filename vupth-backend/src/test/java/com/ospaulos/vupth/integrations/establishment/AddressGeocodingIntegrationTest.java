package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.repository.AddressRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentAddressRepository;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.GeocodeQuery;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — geocoding do endereco do estabelecimento (backlog #2, fatia A).
 *
 * <p>A resolucao roda numa thread de fundo <b>apos o commit</b>, entao os testes fazem o POST e
 * depois esperam a coordenada aparecer no banco. O que esta sendo garantido aqui e o contrato que o
 * PRD promete aos fronts: <b>o cadastro nunca depende do geocoder</b> — com ele fora do ar o
 * endereco e salvo mesmo assim, apenas sem coordenada (e o estabelecimento so nao entra na busca por
 * raio ate ser resolvido).</p>
 *
 * <p>O {@link Geocoder} e mockado: nenhuma suite pode bater em BrasilAPI/Nominatim de verdade. No
 * profile de teste o bean real ja e o {@code NoOpGeocoder} ({@code vupth.geocoding.provider=none}).</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddressGeocodingIntegrationTest {

    private static final double PENHA_LAT = -22.8422;
    private static final double PENHA_LNG = -43.2764;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AddressRepository addressRepository;
    @Autowired EstablishmentAddressRepository establishmentAddressRepository;

    @MockBean Geocoder geocoder;

    private final Faker faker = new Faker();

    @Test
    @Order(10)
    @DisplayName("Cadastro de endereco -> coordenada e resolvida e GRAVADA (pos-commit)")
    void addressGetsCoordinates() throws Exception {
        when(geocoder.geocode(any())).thenReturn(Optional.of(new Coordinates(PENHA_LAT, PENHA_LNG)));

        UUID addressId = createAddress();

        Address address = awaitCoordinates(addressId);
        assertThat(address.getLatitude()).isEqualTo(PENHA_LAT);
        assertThat(address.getLongitude()).isEqualTo(PENHA_LNG);
    }

    @Test
    @Order(20)
    @DisplayName("Geocoder EXPLODIU -> cadastro segue 201 e o endereco fica sem coordenada")
    void geocoderFailureDoesNotBreakRegistration() throws Exception {
        when(geocoder.geocode(any())).thenThrow(new RuntimeException("BrasilAPI fora do ar"));

        UUID addressId = createAddress(); // ja exige 201: a falha nao pode virar erro de cadastro

        Address address = awaitSettled(addressId);
        assertThat(address.getLatitude()).isNull();
        assertThat(address.getLongitude()).isNull();
    }

    @Test
    @Order(30)
    @DisplayName("Geocoder nao encontrou -> cadastro 201, endereco sem coordenada (invisivel no raio)")
    void unresolvedAddressIsRegisteredWithoutCoordinates() throws Exception {
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        UUID addressId = createAddress();

        Address address = awaitSettled(addressId);
        assertThat(address.getLatitude()).isNull();
        assertThat(address.getLongitude()).isNull();
    }

    @Test
    @Order(40)
    @DisplayName("A consulta sai com o CEP em 8 digitos e a rua/cidade do cadastro")
    void queryCarriesTheRegisteredAddress() throws Exception {
        when(geocoder.geocode(any())).thenReturn(Optional.of(new Coordinates(PENHA_LAT, PENHA_LNG)));

        UUID addressId = createAddress();
        awaitCoordinates(addressId);

        org.mockito.ArgumentCaptor<GeocodeQuery> query =
                org.mockito.ArgumentCaptor.forClass(GeocodeQuery.class);
        org.mockito.Mockito.verify(geocoder, org.mockito.Mockito.atLeastOnce()).geocode(query.capture());
        GeocodeQuery last = query.getValue();
        assertThat(last.zipCode()).isEqualTo("21012000");
        assertThat(last.hasUsableZipCode()).isTrue();
        assertThat(last.street()).isEqualTo("Avenida Bras de Pina");
        assertThat(last.city()).isEqualTo("Rio de Janeiro");
    }

    /** Cria owner + loja + endereco (Penha) e devolve o id do {@code Address} criado. */
    private UUID createAddress() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var establishment = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        AddressRequest request = new AddressRequest(
                "Avenida Bras de Pina", "100", null, "Penha",
                "Rio de Janeiro", "RJ", "21012000", null, null, null);

        mvc.perform(post("/establishments/" + establishment.id() + "/addresses")
                        // token() do TestUserUtils JA vem com o prefixo "Bearer ".
                        .header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // O EstablishmentAddressResponse nao expoe o id do Address — busca-se pelo vinculo da loja,
        // que e recem-criada e portanto tem exatamente um endereco.
        UUID establishmentId = UUID.fromString(establishment.id());
        return establishmentAddressRepository.findAllByEstablishmentId(establishmentId)
                .get(0).getAddress().getId();
    }

    /** Espera o worker gravar a coordenada (a resolucao e assincrona, pos-commit). */
    private Address awaitCoordinates(UUID addressId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Address address = addressRepository.findById(addressId).orElseThrow();
            if (address.getLatitude() != null && address.getLongitude() != null) {
                return address;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("coordenada nao foi gravada em 5s para o endereco " + addressId);
    }

    /**
     * Da tempo ao worker de tentar e desistir. Sem coordenada esperada nao ha o que aguardar, entao
     * a espera e fixa e curta — o assert e justamente que ela NAO aparece.
     */
    private Address awaitSettled(UUID addressId) throws InterruptedException {
        Thread.sleep(500);
        return addressRepository.findById(addressId).orElseThrow();
    }
}
