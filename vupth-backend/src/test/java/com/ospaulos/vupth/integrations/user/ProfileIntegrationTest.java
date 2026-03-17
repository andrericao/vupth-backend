package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.EmailRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.PasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.exception.InvalidBirthDateException;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — domain: User profile
 *
 * Covers:
 *
 * PATCH /profile/general-infos:
 * - 200 success with valid data
 * - 401 no token
 * - 400 invalid date string
 * - 400 invalid gender enum value
 * - 400 birth_date in the future (tomorrow)
 * - 400 age too young (MIN = 12 years — today minus 11 years)
 * - 400 age too old  (MAX = 100 years — today minus 101 years)
 * - 400 age at exact boundary min (12 years old today — must pass)
 * - 400 age at exact boundary max (100 years old today — must pass)
 * - 400 impossible date: year 500 AD (500 years old)
 * - 400 impossible date: year 3000 AD (future)
 *
 * PATCH /profile/update-email/me:
 * - 200 success
 * - 400 same email
 * - 422 invalid format
 * - 401 no token
 *
 * PATCH /profile/update-password/me:
 * - 200 success, new token returned
 * - 422 blank password
 * - 401 no token
 *
 * DELETE /profile/delete/me:
 * - 200 success
 * - 401/403/404 token invalid after deletion
 * - 401 no token
 *
 * Age business rules:
 *   MIN_AGE = {@link InvalidBirthDateException#MIN_AGE}  (12 years)
 *   MAX_AGE = {@link InvalidBirthDateException#MAX_AGE} (100 years)
 *
 * @since v6
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository repository;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    String token, id, email, password;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Profile] setup — registering base user");
        var user = TestUserUtils.registerBaseUser(mvc, mapper, faker);
        this.id = user.id();
        this.email = user.email();
        this.password = user.password();
        log.info("[Profile] setup — userId={} email={}", id, email);
    }

    // ─────────────────────────────────────────────────────────────
    // LOGIN (prerequisite for all other tests)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /auth/login — obtain token for profile tests")
    void loginForToken() throws Exception {
        log.info("[Profile] loginForToken — email={}", email);

        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        this.token = "Bearer " + new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");
        assertThat(token).startsWith("Bearer ");
        log.info("[Profile] loginForToken — token obtained");
    }

    // ─────────────────────────────────────────────────────────────
    // GENERAL INFOS — happy path + auth guard
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("PATCH /profile/general-infos — 200 success with valid data")
    void updateGeneralInfos_success() throws Exception {
        Assumptions.assumeTrue(token != null, "Requires loginForToken");

        String firstName = faker.name().firstName();
        String lastName  = faker.name().lastName();
        LocalDate birthDate = faker.date().birthday(
                        InvalidBirthDateException.MIN_AGE,
                        InvalidBirthDateException.MAX_AGE - 1)
                .toInstant().atZone(ZoneOffset.UTC).toLocalDate();

        UserProfileGeneralInfosRequest request = new UserProfileGeneralInfosRequest(
                firstName, lastName,
                faker.phoneNumber().cellPhone(),
                birthDate,
                faker.options().option(Gender.class),
                faker.internet().url());

        log.info("[Profile] updateGeneralInfos_success — firstName={} birthDate={}", firstName, birthDate);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value(firstName))
                .andExpect(jsonPath("$.last_name").value(lastName))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("PATCH /profile/general-infos — 401 no token")
    void updateGeneralInfos_noToken() throws Exception {
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new UserProfileGeneralInfosRequest("John", "Doe", null, null, null, null))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("PATCH /profile/general-infos — 400 birth_date as invalid string")
    void updateGeneralInfos_invalidDateString() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] updateGeneralInfos_invalidDateString");

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"first_name\": \"John\", \"birth_date\": \"not-a-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("PATCH /profile/general-infos — 400 invalid gender enum value")
    void updateGeneralInfos_invalidGenderEnum() throws Exception {
        Assumptions.assumeTrue(token != null);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"first_name\": \"John\", \"gender\": \"ALIEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GENERAL INFOS — birthDate edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("PATCH /profile/general-infos — 400 birth_date in the future (tomorrow)")
    void updateGeneralInfos_birthDateInFuture() throws Exception {
        Assumptions.assumeTrue(token != null);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("[Profile] updateGeneralInfos_birthDateInFuture — date={}", tomorrow);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"" + tomorrow + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /profile/general-infos — 400 age too young (11 years = MIN - 1)")
    void updateGeneralInfos_ageTooYoung() throws Exception {
        Assumptions.assumeTrue(token != null);
        LocalDate tooYoung = LocalDate.now().minusYears(InvalidBirthDateException.MIN_AGE - 1);
        log.info("[Profile] updateGeneralInfos_ageTooYoung — age={}y date={}", InvalidBirthDateException.MIN_AGE - 1, tooYoung);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"" + tooYoung + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"))
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("PATCH /profile/general-infos — 200 age at minimum boundary (exactly 12 years)")
    void updateGeneralInfos_ageAtMinBoundary() throws Exception {
        Assumptions.assumeTrue(token != null);
        LocalDate exactMin = LocalDate.now().minusYears(InvalidBirthDateException.MIN_AGE);
        log.info("[Profile] updateGeneralInfos_ageAtMinBoundary — date={}", exactMin);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"" + exactMin + "\"}"))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("PATCH /profile/general-infos — 200 age at maximum boundary (exactly 100 years)")
    void updateGeneralInfos_ageAtMaxBoundary() throws Exception {
        Assumptions.assumeTrue(token != null);
        LocalDate exactMax = LocalDate.now().minusYears(InvalidBirthDateException.MAX_AGE);
        log.info("[Profile] updateGeneralInfos_ageAtMaxBoundary — date={}", exactMax);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"" + exactMax + "\"}"))
                .andExpect(status().isOk())
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("PATCH /profile/general-infos — 400 age too old (101 years = MAX + 1)")
    void updateGeneralInfos_ageTooOld() throws Exception {
        Assumptions.assumeTrue(token != null);
        LocalDate tooOld = LocalDate.now().minusYears(InvalidBirthDateException.MAX_AGE + 1);
        log.info("[Profile] updateGeneralInfos_ageTooOld — age={}y date={}", InvalidBirthDateException.MAX_AGE + 1, tooOld);

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"" + tooOld + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"))
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("PATCH /profile/general-infos — 400 impossible date: year 1500 AD (500+ years old)")
    void updateGeneralInfos_impossiblyOldDate() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] updateGeneralInfos_impossiblyOldDate — date=1500-06-15");

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"1500-06-15\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"))
                .andDo(print());
    }

    @Test
    @Order(26)
    @DisplayName("PATCH /profile/general-infos — 400 impossible date: year 3000 AD (future)")
    void updateGeneralInfos_farFutureDate() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] updateGeneralInfos_farFutureDate — date=3000-01-01");

        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"birth_date\": \"3000-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE EMAIL
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("PATCH /profile/update-email/me — 200 email updated successfully")
    void updateEmail_success() throws Exception {
        Assumptions.assumeTrue(token != null);
        String newEmail = faker.internet().emailAddress();
        log.info("[Profile] updateEmail_success — newEmail={}", newEmail);

        mvc.perform(patch("/profile/update-email/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new EmailRequest(newEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id))
                .andExpect(jsonPath("$.user.email", is(not(email))))
                .andDo(print());

        this.email = newEmail;
        log.info("[Profile] updateEmail_success — email updated to {}", this.email);
    }

    @Test
    @Order(31)
    @DisplayName("PATCH /profile/update-email/me — 400 same email as current")
    void updateEmail_sameEmail() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] updateEmail_sameEmail — email={}", email);

        mvc.perform(patch("/profile/update-email/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new EmailRequest(email))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAILS_ARE_THE_SAME"))
                .andDo(print());
    }

    @Test
    @Order(32)
    @DisplayName("PATCH /profile/update-email/me — 422 invalid email format")
    void updateEmail_invalidFormat() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] updateEmail_invalidFormat");

        mvc.perform(patch("/profile/update-email/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new EmailRequest("not-an-email"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andDo(print());
    }

    @Test
    @Order(33)
    @DisplayName("PATCH /profile/update-email/me — 401 no token")
    void updateEmail_noToken() throws Exception {
        mvc.perform(patch("/profile/update-email/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new EmailRequest("other@example.com"))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE PASSWORD
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("PATCH /profile/update-password/me — 200 password updated, new token returned")
    void updatePassword_success() throws Exception {
        Assumptions.assumeTrue(token != null);
        String newPassword = faker.internet().password(8, 20);
        log.info("[Profile] updatePassword_success — changing password");

        MvcResult result = mvc.perform(patch("/profile/update-password/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new PasswordRequest(newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.access_token", is(not(token.replace("Bearer ", "")))))
                .andExpect(jsonPath("$.user.email").value(email))
                .andDo(print())
                .andReturn();

        // Update token for subsequent tests
        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        this.token = "Bearer " + json.getJSONObject("tokens").getString("access_token");
        this.password = newPassword;
        log.info("[Profile] updatePassword_success — token and password updated");
    }

    @Test
    @Order(41)
    @DisplayName("PATCH /profile/update-password/me — 422 blank password")
    void updatePassword_blank() throws Exception {
        Assumptions.assumeTrue(token != null);

        mvc.perform(patch("/profile/update-password/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new PasswordRequest(""))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("PATCH /profile/update-password/me — 401 no token")
    void updatePassword_noToken() throws Exception {
        mvc.perform(patch("/profile/update-password/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new PasswordRequest("anyPassword123"))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // DELETE ACCOUNT
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("DELETE /profile/delete/me — 200 account deleted successfully")
    void deleteAccount_success() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] deleteAccount_success — userId={}", id);

        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andDo(print());

        log.info("[Profile] deleteAccount_success — account deleted");
    }

    @Test
    @Order(51)
    @DisplayName("DELETE /profile/delete/me — 401/403/404 token invalid after account deletion")
    void deleteAccount_tokenInvalidAfterDeletion() throws Exception {
        Assumptions.assumeTrue(token != null);
        log.info("[Profile] deleteAccount_tokenInvalidAfterDeletion — using stale token");

        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404))
                .andDo(print());
    }

    @Test
    @Order(52)
    @DisplayName("DELETE /profile/delete/me — 401 no token")
    void deleteAccount_noToken() throws Exception {
        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Profile] tearDown — cleaning up test data");
        repository.deleteAll();
    }
}
