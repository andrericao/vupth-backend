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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: EstablishmentAddress
 *
 * Scenarios covered:
 * - POST /establishments/{id}/addresses — 201 happy path
 * - POST /establishments/{id}/addresses — 401 no token
 * - POST /establishments/{id}/addresses — 422 missing required fields (street, city)
 * - POST /establishments/{id}/addresses — 422 invalid ZIP code format
 * - POST /establishments/{id}/addresses — 404 establishment not found
 *
 * Root bug fixed: test was using "/establishments/{establishmentId}/addresses" as a literal
 * URI template string but not passing the path variable → IllegalArgumentException at runtime.
 * Fixed to: post("/establishments/{id}/addresses", establishmentId)
 *
 * @since v8
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

        MvcResult result = mvc.perform(post("/establishments/{id}/addresses", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andDo(print())  // print before assertion to capture 500 body
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.establishmenID").value(establishmentId))
                .andExpect(jsonPath("$.street").value(request.street()))
                .andExpect(jsonPath("$.city").value(request.city()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        log.info("[EstabAddress] createAddress_success — created OK");
        assertThat(body).contains(establishmentId);
    }

    // ─────────────────────────────────────────────────────────────
    // Auth errors
    // ─────────────────────────────────────────────────────────────

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
    @DisplayName("POST /establishments/{id}/addresses — 422 invalid ZIP code format")
    void createAddress_invalidZip() throws Exception {
        log.info("[EstabAddress] createAddress_invalidZip");

        AddressRequest request = new AddressRequest(
                faker.address().streetName(),
                faker.address().buildingNumber(),
                null,
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                "not-a-zip",
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
                Double.parseDouble(faker.address().latitude()),
                Double.parseDouble(faker.address().longitude()));
    }

    /** Returns a ZIP matching the pattern \d{2}.\d{3}-\d{3} */
    private String validZip() {
        return String.format("%02d.%03d-%03d",
                faker.number().numberBetween(0, 100),
                faker.number().numberBetween(0, 1000),
                faker.number().numberBetween(0, 1000));
    }
}
