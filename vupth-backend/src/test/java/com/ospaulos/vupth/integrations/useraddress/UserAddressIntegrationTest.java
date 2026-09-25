package com.ospaulos.vupth.integrations.useraddress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UserAddressRequest;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — domain: UserAddress
 *
 * zipCode: 8 dígitos puros, sem hífen ou ponto (ex: "01310100").
 * Frontend formata para exibição ("01310-100").
 *
 * @compat zipCode format updated v14
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class UserAddressIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private EstablishmentRepository establishmentRepository;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    private final Faker faker = new Faker();

    String userToken, userId;

    @BeforeAll
    void setup() throws Exception {
        log.info("STARTING SETUP AUTH TEST");
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.userId = user.id();
        this.userToken = user.token();
    }

    /**
     * CEP como 8 dígitos puros, sem formatação.
     * Regex: ^\\d{8}$ — frontend formata para exibição.
     */
    private String validZip() {
        return String.format("%08d", faker.number().numberBetween(1000000, 99999999));
    }

    private String createAddressAndReturnId() throws Exception {

        UserAddressRequest request = new UserAddressRequest(
                faker.address().streetName(),
                faker.number().digits(10),
                faker.address().secondaryAddress(),
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                validZip(),
                "Next to " + faker.company().name(),
                faker.number().randomDouble(6, -90, 90),
                faker.number().randomDouble(6, -180, 180)
        );

        final MvcResult responseCreatedAddress = mvc.perform(post("/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andDo(print())
                .andReturn();

        JSONObject addressResponseJson = new JSONObject(responseCreatedAddress.getResponse().getContentAsString());
        JSONObject userAddressId = addressResponseJson.getJSONObject("addressResponse");
        String id = userAddressId.getString("userAddressId");

        log.info("Response test: " + id);
        return id;
    }

    @Test
    void shouldCreateUserAddress() throws Exception {
        log.info("STARTING CREATE ADDRESS TEST");

        UserAddressRequest request = new UserAddressRequest(
                faker.address().streetName(),
                faker.number().digits(10),
                faker.address().secondaryAddress(),
                faker.address().streetSuffix(),
                faker.address().city(),
                faker.address().stateAbbr(),
                validZip(),
                "Next to " + faker.company().name(),
                faker.number().randomDouble(6, -90, 90),
                faker.number().randomDouble(6, -180, 180)
        );

        mvc.perform(post("/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andDo(print())
                .andReturn();
    }

    @Test
    void shouldUpdateUserAddress() throws Exception {
        log.info("STARTING UPDATE ADDRESS TEST");

        String userAddressId = createAddressAndReturnId();
        UUID idFromString = UUID.fromString(userAddressId);

        UpdateAddressRequest updateAddressRequest = new UpdateAddressRequest(
                idFromString,
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        mvc.perform(patch("/users/me/addresses/{userAddressId}", userAddressId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken)
                        .content(mapper.writeValueAsString(updateAddressRequest)))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();
    }

    @Test
    public void shouldDeleteUserAddress() throws Exception {
        log.info("STARTING DELETE ADDRESS TEST");

        String userAddressId = createAddressAndReturnId();

        mvc.perform(delete("/users/me/addresses/{userAddressId}", userAddressId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();
    }

    @Test
    public void shouldGetAllUserAddress() throws Exception {
        log.info("GET ALL USER-ADDRESSES TEST");

        createAddressAndReturnId();
        createAddressAndReturnId();
        createAddressAndReturnId();

        mvc.perform(get("/users/me/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();
    }

    @Test
    public void shouldSetUserAddressMain() throws Exception {
        log.info("SET USER-ADDRESSES LIKE MAIN TEST");

        createAddressAndReturnId();
        String userAddressId2 = createAddressAndReturnId();
        createAddressAndReturnId();

        mvc.perform(patch("/users/me/addresses/{userAddressId}/main", userAddressId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();
    }

    @Test
    public void shouldGetMainUserAddress() throws Exception {
        log.info("GET MAIN USER-ADDRESSES TEST");

        createAddressAndReturnId();
        String userAddressId2 = createAddressAndReturnId();
        createAddressAndReturnId();

        log.info("TEST 5 - " + userAddressId2);

        mvc.perform(patch("/users/me/addresses/{userAddressId}/main", userAddressId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();

        mvc.perform(get("/users/me/addresses/main")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", this.userToken))
                .andExpect(status().isOk())
                .andDo(print())
                .andReturn();
    }

    @AfterAll
    void down() {
        userRepository.deleteAll();
    }
}
