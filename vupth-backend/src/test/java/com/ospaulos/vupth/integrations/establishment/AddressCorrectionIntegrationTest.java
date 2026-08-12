package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateEstablishmentAddressRequest;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.repository.AddressRepository;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import com.ospaulos.vupth.infrastructure.geocoding.ReverseGeocodeResult;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — conferencia e conserto do endereco (backlog #2 fatia A2 + backlog #3).
 *
 * <p>Cobre as duas pontas do cadastro pelo GPS do dono:</p>
 * <ol>
 *   <li><b>{@code GET /geocoding/reverse}</b> — o dono esta DENTRO da loja, entao a coordenada do
 *       aparelho dele e mais precisa que o CEP; o reverso devolve o endereco por extenso para ele
 *       <b>ler e reconhecer</b> antes de salvar.</li>
 *   <li><b>{@code PATCH .../addresses/{addressId}}</b> — ate aqui endereco errado nao tinha conserto
 *       pela API, e quem descobre o erro e o dono, quando a loja nao aparece na busca.</li>
 * </ol>
 *
 * <p><b>Invariante central testada aqui:</b> texto e coordenada vem de fontes diferentes e
 * <b>ambos ficam</b> — corrigir o numero da porta NAO move o ponto no mapa.</p>
 *
 * <p>O {@link Geocoder} e mockado: nenhuma suite pode bater em Nominatim/BrasilAPI de verdade.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddressCorrectionIntegrationTest {

    private static final double PENHA_LAT = -22.8422;
    private static final double PENHA_LNG = -43.2764;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AddressRepository addressRepository;

    @MockBean Geocoder geocoder;

    private final Faker faker = new Faker();

    private String ownerToken;
    private String establishmentId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
    }

    @BeforeEach
    void resetGeocoder() {
        // Default explicito: sem isso o mock devolveria null e o worker so registraria o NPE no log.
        when(geocoder.geocode(any())).thenReturn(Optional.empty());
        when(geocoder.reverse(any())).thenReturn(Optional.empty());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /geocoding/reverse — o dono confere o endereco (fatia A2)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /geocoding/reverse — 200 devolve o endereco por extenso e ECOA a coordenada enviada")
    void reverseReturnsAddress() throws Exception {
        when(geocoder.reverse(any())).thenReturn(Optional.of(new ReverseGeocodeResult(
                "Avenida Bras de Pina", "100", "Penha", "Rio de Janeiro", "RJ", "21012000",
                "Avenida Bras de Pina, 100 - Penha, Rio de Janeiro - RJ, 21012-000, Brasil")));

        mvc.perform(get("/geocoding/reverse")
                        .param("lat", String.valueOf(PENHA_LAT))
                        .param("lng", String.valueOf(PENHA_LNG))
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Avenida Bras de Pina"))
                .andExpect(jsonPath("$.neighborhood").value("Penha"))
                .andExpect(jsonPath("$.city").value("Rio de Janeiro"))
                .andExpect(jsonPath("$.state").value("RJ"))
                .andExpect(jsonPath("$.zipCode").value("21012000"))
                .andExpect(jsonPath("$.displayName").exists())
                // A coordenada volta como ENTROU: ela e o dado preciso (o dono esta em pe no lugar),
                // o texto e que e aproximado.
                .andExpect(jsonPath("$.latitude").value(PENHA_LAT))
                .andExpect(jsonPath("$.longitude").value(PENHA_LNG));
    }

    @Test
    @Order(11)
    @DisplayName("GET /geocoding/reverse — campos que o servico nao sabe vem null (o front tem de aguentar)")
    void reverseToleratesMissingFields() throws Exception {
        // O reverso quase nunca sabe o numero da casa — e justamente o que o dono digita na mao.
        when(geocoder.reverse(any())).thenReturn(Optional.of(new ReverseGeocodeResult(
                "Avenida Bras de Pina", null, "Penha", "Rio de Janeiro", "RJ", null,
                "Avenida Bras de Pina - Penha, Rio de Janeiro - RJ, Brasil")));

        mvc.perform(get("/geocoding/reverse")
                        .param("lat", String.valueOf(PENHA_LAT))
                        .param("lng", String.valueOf(PENHA_LNG))
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(nullValue()))
                .andExpect(jsonPath("$.zipCode").value(nullValue()))
                .andExpect(jsonPath("$.street").value("Avenida Bras de Pina"));
    }

    @Test
    @Order(12)
    @DisplayName("GET /geocoding/reverse — 404 GEOCODING_ADDRESS_NOT_FOUND quando nao resolve")
    void reverseNotFound() throws Exception {
        when(geocoder.reverse(any())).thenReturn(Optional.empty());

        mvc.perform(get("/geocoding/reverse")
                        .param("lat", String.valueOf(PENHA_LAT))
                        .param("lng", String.valueOf(PENHA_LNG))
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GEOCODING_ADDRESS_NOT_FOUND"));
    }

    @Test
    @Order(13)
    @DisplayName("GET /geocoding/reverse — 422 na 'ilha nula' (0,0) e fora do globo")
    void reverseRejectsInvalidCoordinates() throws Exception {
        mvc.perform(get("/geocoding/reverse")
                        .param("lat", "0").param("lng", "0")
                        .header("Authorization", ownerToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));

        mvc.perform(get("/geocoding/reverse")
                        .param("lat", "-91").param("lng", "0")
                        .header("Authorization", ownerToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(14)
    @DisplayName("GET /geocoding/reverse — 401 sem token (o login e o que segura a cota do Nominatim)")
    void reverseRequiresAuthentication() throws Exception {
        mvc.perform(get("/geocoding/reverse")
                        .param("lat", String.valueOf(PENHA_LAT))
                        .param("lng", String.valueOf(PENHA_LNG)))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // POST — validacao da coordenada que chega no cadastro (fatia A2)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /establishments/{id}/addresses — 422 na coordenada (0,0)")
    void createRejectsNullIsland() throws Exception {
        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(addressAt(0.0, 0.0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(21)
    @DisplayName("POST /establishments/{id}/addresses — 422 com so uma das duas coordenadas")
    void createRejectsHalfCoordinate() throws Exception {
        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(addressAt(PENHA_LAT, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(22)
    @DisplayName("POST /establishments/{id}/addresses — 201 com a coordenada do aparelho e expoe o addressId")
    void createAcceptsDeviceCoordinates() throws Exception {
        MvcResult result = mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(addressAt(PENHA_LAT, PENHA_LNG))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latitude").value(PENHA_LAT))
                .andExpect(jsonPath("$.longitude").value(PENHA_LNG))
                // Sem o addressId no corpo o front nao teria como chamar o PATCH (backlog #3).
                .andExpect(jsonPath("$.addressId").exists())
                .andReturn();

        assertThat(addressIdOf(result)).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────
    // PATCH — o conserto do endereco (backlog #3)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("PATCH — corrigir o NUMERO nao move a coordenada (texto e ponto sao fontes diferentes)")
    void patchTextKeepsCoordinates() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("250")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("250"))
                .andExpect(jsonPath("$.latitude").value(PENHA_LAT))
                .andExpect(jsonPath("$.longitude").value(PENHA_LNG));

        Address stored = addressRepository.findById(fixture.addressId).orElseThrow();
        assertThat(stored.getLatitude()).isEqualTo(PENHA_LAT);
        assertThat(stored.getLongitude()).isEqualTo(PENHA_LNG);
    }

    @Test
    @Order(31)
    @DisplayName("PATCH — campo omitido nao e apagado (null = nao mexe)")
    void patchLeavesOmittedFieldsAlone() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("250")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Avenida Bras de Pina"))
                .andExpect(jsonPath("$.city").value("Rio de Janeiro"))
                .andExpect(jsonPath("$.zipCode").value("21012000"));
    }

    @Test
    @Order(32)
    @DisplayName("PATCH — coordenada nova SUBSTITUI a antiga quando vem explicita no corpo")
    void patchReplacesCoordinatesWhenSent() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        double newLat = -22.9068;   // Centro do Rio
        double newLng = -43.1729;

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateEstablishmentAddressRequest(
                                null, null, null, null, null, null, null, null, newLat, newLng))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(newLat))
                .andExpect(jsonPath("$.longitude").value(newLng));
    }

    @Test
    @Order(33)
    @DisplayName("PATCH — string vazia LIMPA o complemento (campo opcional digitado errado)")
    void patchClearsComplementWithBlank() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateEstablishmentAddressRequest(
                                null, null, "", null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complement").value(nullValue()));
    }

    @Test
    @Order(34)
    @DisplayName("PATCH — endereco SEM coordenada e regeocodificado (o conserto do CEP errado)")
    void patchRetriesGeocodingWhenCoordinatesAreMissing() throws Exception {
        // Cadastrado enquanto o geocoder nao resolvia: ficou sem coordenada e invisivel na busca por
        // raio. Corrigir o CEP tem de dar uma nova chance, senao a loja fica invisivel para sempre.
        var fixture = ownerWithAddressAt(null, null);
        Address before = addressRepository.findById(fixture.addressId).orElseThrow();
        assertThat(before.getLatitude()).isNull();

        when(geocoder.geocode(any())).thenReturn(Optional.of(new Coordinates(PENHA_LAT, PENHA_LNG)));

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withZipCode("21012000")))))
                .andExpect(status().isOk());

        Address resolved = awaitCoordinates(fixture.addressId);
        assertThat(resolved.getLatitude()).isEqualTo(PENHA_LAT);
        assertThat(resolved.getLongitude()).isEqualTo(PENHA_LNG);
    }

    @Test
    @Order(35)
    @DisplayName("PATCH — endereco QUE JA TEM coordenada nao e regeocodificado")
    void patchDoesNotRegeocodeWhenCoordinatesExist() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        // Se o worker regeocodificasse, a coordenada do aparelho do dono (a precisa) seria trocada
        // pela do CEP (a da faixa do logradouro) — perda silenciosa de precisao.
        when(geocoder.geocode(any())).thenReturn(Optional.of(new Coordinates(-1.0, -1.0)));

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("777")))))
                .andExpect(status().isOk());

        Thread.sleep(500); // da tempo ao worker de rodar, caso ele fosse rodar
        Address stored = addressRepository.findById(fixture.addressId).orElseThrow();
        assertThat(stored.getLatitude()).isEqualTo(PENHA_LAT);
        assertThat(stored.getLongitude()).isEqualTo(PENHA_LNG);
    }

    @Test
    @Order(40)
    @DisplayName("PATCH — 422 na coordenada invalida enviada no corpo")
    void patchRejectsInvalidCoordinates() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateEstablishmentAddressRequest(
                                null, null, null, null, null, null, null, null, 0.0, 0.0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(41)
    @DisplayName("PATCH — 422 no CEP com mascara (a base guarda 8 digitos puros)")
    void patchRejectsFormattedZipCode() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .header("Authorization", fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withZipCode("21012-000")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.zipCode").exists());
    }

    @Test
    @Order(50)
    @DisplayName("PATCH — 404 quando o dono de OUTRA loja tenta editar (posse conferida no service)")
    void patchFromAnotherOwnerIsRejected() throws Exception {
        var mine = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);
        var stranger = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        // Id de endereco existente e valido — o que barra e o vinculo com a loja do chamador.
        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", mine.establishmentId, mine.addressId)
                        .header("Authorization", stranger.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("999")))))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(51)
    @DisplayName("PATCH — 404 quando o addressId nao e daquela loja")
    void patchWithForeignAddressIdIsRejected() throws Exception {
        var mine = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);
        var other = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", mine.establishmentId, other.addressId)
                        .header("Authorization", mine.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("999")))))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(52)
    @DisplayName("PATCH — 401 sem token")
    void patchRequiresAuthentication() throws Exception {
        var fixture = ownerWithAddressAt(PENHA_LAT, PENHA_LNG);

        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", fixture.establishmentId, fixture.addressId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patchWith(request -> request.withNumber("999")))))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private record OwnerAddressFixture(String token, String establishmentId, UUID addressId) {}

    /** Dono novo + loja nova + endereco na Penha, com ou sem coordenada. */
    private OwnerAddressFixture ownerWithAddressAt(Double latitude, Double longitude) throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var establishment = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        MvcResult result = mvc.perform(post("/establishments/{id}/addresses", establishment.id())
                        .header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(addressAt(latitude, longitude))))
                .andExpect(status().isCreated())
                .andReturn();

        return new OwnerAddressFixture(owner.token(), establishment.id(), addressIdOf(result));
    }

    private UUID addressIdOf(MvcResult result) throws Exception {
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get("addressId").asText());
    }

    private AddressRequest addressAt(Double latitude, Double longitude) {
        return new AddressRequest(
                "Avenida Bras de Pina", "100", "Sala 2", "Penha",
                "Rio de Janeiro", "RJ", "21012000", null, latitude, longitude);
    }

    /** PATCH vazio (tudo null = nao mexe em nada) com uma alteracao aplicada por cima. */
    private UpdateEstablishmentAddressRequest patchWith(
            java.util.function.UnaryOperator<PatchBuilder> change) {
        return change.apply(new PatchBuilder()).build();
    }

    /** Acucar para nao repetir dez {@code null} em cada request de um campo so. */
    private static final class PatchBuilder {
        private String number;
        private String zipCode;

        PatchBuilder withNumber(String value) {
            this.number = value;
            return this;
        }

        PatchBuilder withZipCode(String value) {
            this.zipCode = value;
            return this;
        }

        UpdateEstablishmentAddressRequest build() {
            return new UpdateEstablishmentAddressRequest(
                    null, number, null, null, null, null, zipCode, null, null, null);
        }
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
}
