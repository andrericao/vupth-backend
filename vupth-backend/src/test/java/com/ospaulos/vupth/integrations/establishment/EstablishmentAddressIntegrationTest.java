package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: EstablishmentAddress
 *
 * zipCode: 8 dígitos puros, sem hífen ou ponto (ex: "01310100").
 * Frontend é responsável por formatar para exibição ("01310-100").
 *
 * @since v8
 * @compat zipCode format updated v14
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentAddressIntegrationTest {

    @Autowired private EstablishmentRepository establishmentRepository;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    private final Faker faker = new Faker();

    String ownerToken, establishmentId;
    AddressRequest createdAddress;
    String createdAddressId;

    @BeforeAll
    void setup() throws Exception {
        log.info("[EstabAddress] setup — registering owner and establishment");
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        var establishment = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = establishment.id();
        log.info("[EstabAddress] setup — establishmentId={}", establishmentId);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /establishments/{id}/addresses — 201 creates address successfully")
    void createAddress_success() throws Exception {
        log.info("[EstabAddress] createAddress_success — estab={}", establishmentId);

        AddressRequest request = buildValidAddress();
        this.createdAddress = request;

        MvcResult result = mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.establishmentId").value(establishmentId))
                .andExpect(jsonPath("$.street").value(request.street()))
                .andExpect(jsonPath("$.city").value(request.city()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        this.createdAddressId = mapper.readTree(body).get("addressId").asText();
        log.info("[EstabAddress] createAddress_success — created OK");
        assertThat(body).contains(establishmentId);
    }

    // ─────────────────────────────────────────────────────────────
    // Read-back do endereco no EstablishmentResponse (Partner BUG4)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(15)
    @DisplayName("GET /establishment/mine — inclui data.address da loja (path fora de tx, OSIV off)")
    void mine_includesAddress() throws Exception {
        log.info("[EstabAddress] mine_includesAddress — estab={}", establishmentId);

        // Regressao do Partner BUG4: /mine mapeia fromEntity NO CONTROLLER (sem @Transactional).
        // Sem o FETCH de addresses+address no findByOwnerId, acessar getMainAddress() estouraria
        // LazyInitializationException (OSIV off). O endereco tem de vir embutido.
        mvc.perform(get("/establishment/mine")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(establishmentId))
                .andExpect(jsonPath("$[0].address.street").value(createdAddress.street()))
                .andExpect(jsonPath("$[0].address.city").value(createdAddress.city()))
                .andExpect(jsonPath("$[0].address.zipCode").value(createdAddress.zipCode()))
                // S38: o id embutido e o mesmo que o POST devolveu — o que o PATCH do endereco recebe
                .andExpect(jsonPath("$[0].address.addressId").value(createdAddressId))
                .andDo(print());
    }

    @Test
    @Order(16)
    @DisplayName("GET /establishment/id/{id} — inclui data.address da loja (path transacional)")
    void findById_includesAddress() throws Exception {
        log.info("[EstabAddress] findById_includesAddress — estab={}", establishmentId);

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(establishmentId))
                .andExpect(jsonPath("$.address.street").value(createdAddress.street()))
                .andExpect(jsonPath("$.address.neighborhood").value(createdAddress.neighborhood()))
                .andExpect(jsonPath("$.address.addressId").value(createdAddressId))
                .andDo(print());
    }

    @Test
    @Order(17)
    @DisplayName("GET /establishment/mine — address == null quando a loja nao tem endereco")
    void mine_nullAddressWhenNone() throws Exception {
        log.info("[EstabAddress] mine_nullAddressWhenNone — fresh owner without address");

        var freshOwner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var freshEstablishment = TestUserUtils.becomeOwner(mvc, mapper, faker, freshOwner.token());

        mvc.perform(get("/establishment/mine")
                        .header("Authorization", freshOwner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(freshEstablishment.id()))
                .andExpect(jsonPath("$[0].address").value(nullValue()))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Auth errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(18)
    @DisplayName("gps-depois-do-cadastro: PATCH so com latitude/longitude troca a coordenada e mantem o resto do endereco")
    void patchOnlyCoordinates_overridesLocation() throws Exception {
        mvc.perform(patch("/establishments/{id}/addresses/{addressId}", establishmentId, createdAddressId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"latitude\":-23.561684,\"longitude\":-46.655981}"))
                .andExpect(status().isOk());

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address.addressId").value(createdAddressId))
                .andExpect(jsonPath("$.address.latitude").value(-23.561684))
                .andExpect(jsonPath("$.address.longitude").value(-46.655981))
                .andExpect(jsonPath("$.address.locationStatus").value("LOCATED"))
                // origem-da-coordenada (Partner S39): veio do aparelho, vale em qualquer aparelho do dono
                .andExpect(jsonPath("$.address.locationSource").value("GPS"))
                .andExpect(jsonPath("$.address.street").value(createdAddress.street()));
    }

    @Test
    @Order(20)
    @DisplayName("POST /establishments/{id}/addresses — 401 no token")
    void createAddress_noToken() throws Exception {
        log.info("[EstabAddress] createAddress_noToken");

        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(buildValidAddress())))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Validation errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("POST /establishments/{id}/addresses — 422 street is blank")
    void createAddress_blankStreet() throws Exception {
        log.info("[EstabAddress] createAddress_blankStreet");

        AddressRequest request = new AddressRequest(
                "",
                faker.address().buildingNumber(),
                null,
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                validZip(),
                null, null, null);

        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.street").exists())
                .andDo(print());
    }

    @Test
    @Order(31)
    @DisplayName("POST /establishments/{id}/addresses — 422 city is blank")
    void createAddress_blankCity() throws Exception {
        log.info("[EstabAddress] createAddress_blankCity");

        AddressRequest request = new AddressRequest(
                faker.address().streetName(),
                faker.address().buildingNumber(),
                null,
                faker.address().streetSuffix(),
                "",
                faker.address().stateAbbr(),
                validZip(),
                null, null, null);

        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.city").exists())
                .andDo(print());
    }

    @Test
    @Order(32)
    @DisplayName("POST /establishments/{id}/addresses — 422 invalid ZIP (with formatting chars)")
    void createAddress_invalidZip() throws Exception {
        log.info("[EstabAddress] createAddress_invalidZip");

        AddressRequest request = new AddressRequest(
                faker.address().streetName(),
                faker.address().buildingNumber(),
                null,
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                "01310-100",  // formatted ZIP — must be rejected (only raw digits accepted)
                null, null, null);

        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.zipCode").exists())
                .andDo(print());
    }

    @Test
    @Order(33)
    @DisplayName("POST /establishments/{id}/addresses — 422 ZIP with wrong digit count")
    void createAddress_zipWrongLength() throws Exception {
        log.info("[EstabAddress] createAddress_zipWrongLength");

        AddressRequest request = new AddressRequest(
                faker.address().streetName(),
                faker.address().buildingNumber(),
                null,
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                "1234",  // only 4 digits — must be rejected
                null, null, null);

        mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.zipCode").exists())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Not found
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /establishments/{id}/addresses — 404 establishment not found")
    void createAddress_establishmentNotFound() throws Exception {
        String fakeId = "00000000-0000-0000-0000-000000000099";
        log.info("[EstabAddress] createAddress_establishmentNotFound — fakeId={}", fakeId);

        mvc.perform(post("/establishments/{id}/addresses", fakeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(buildValidAddress())))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[EstabAddress] tearDown — cleaning up");
        establishmentRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private AddressRequest buildValidAddress() {
        return new AddressRequest(
                faker.address().streetName(),
                faker.address().buildingNumber(),
                faker.address().secondaryAddress(),
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                validZip(),
                "Next to " + faker.company().name(),
                faker.number().randomDouble(6, -90, 90),
                faker.number().randomDouble(6, -180, 180));
    }

    /**
     * CEP como 8 dígitos puros, sem formatação.
     * O frontend formata para exibição ("01310-100").
     */
    private String validZip() {
        return String.format("%08d", faker.number().numberBetween(1000000, 99999999));
    }
}
