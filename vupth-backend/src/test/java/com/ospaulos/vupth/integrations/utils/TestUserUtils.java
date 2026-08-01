package com.ospaulos.vupth.integrations.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static utilities for setting up data in integration tests.
 *
 * ── Regra de roles (definitiva) ──────────────────────────────────────────────
 *
 * Every user is born with the CLIENT role upon registration (automatically via RegisterUseCase).
 * Additional roles are added by the corresponding endpoints:
 *
 *   CLIENT       → automatic at registration (always)
 *   OWNER        → ao criar um establishment: POST /establishment/quick-create
 *   PROFESSIONAL → ao se filiar a um establishment: POST /professional/quick-create
 *
 * Multiple roles are valid and expected simultaneously:
 *   - OWNER + PROFESSIONAL: owner who also works at their own establishment (solo shop)
 *   - Qualquer role + CLIENT: todo mundo pode usar o app de cliente em outros establishments
 *
 * There is NO endpoint to assign roles manually.
 *
 * ── Como usar ────────────────────────────────────────────────────────────────
 *
 *   // Create user (born as CLIENT):
 *   UserAuthAndToken user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
 *
 *   // Tornar OWNER (cria establishment):
 *   EstablishmentData estab = TestUserUtils.becomeOwner(mvc, mapper, faker, user.token());
 *
 *   // Tornar PROFESSIONAL (filiar ao establishment):
 *   TestUserUtils.becomeProfessional(mvc, mapper, faker, user.token(), estab.id());
 *   // → user now has CLIENT + OWNER + PROFESSIONAL
 */
@Slf4j
public class TestUserUtils {

    // ─────────────────────────────────────────────
    // Records de resultado
    // ─────────────────────────────────────────────

    /** Data for a registered user without a token. */
    public record UserAuth(String id, String email, String password) {}

    /**
     * Data for a registered and logged-in user.
     * token — already includes "Bearer " prefix for direct use in headers.
     */
    public record UserAuthAndToken(String id, String token, String email, String password) {}

    /** Dados de um establishment created. */
    public record EstablishmentData(String id, String name) {}

    /** Dados de um service item created. */
    public record ServiceItemData(String id, String description, BigDecimal price) {}

    // ─────────────────────────────────────────────
    // User registration
    // ─────────────────────────────────────────────

    /**
     * Registers a user with random email/password.
     * Born with CLIENT role. Does not log in.
     */
    public static UserAuth registerBaseUser(MockMvc mvc, ObjectMapper mapper, Faker faker) throws Exception {
        String email = faker.internet().emailAddress();
        String password = faker.internet().password(8, 16);

        MvcResult result = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UserRegisterRequest(email, password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        String userId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.debug("[TestUtils] registerBaseUser id={}", userId);
        return new UserAuth(userId, email, password);
    }

    /**
     * Registers and logs in a new user.
     * Nasce com a role CLIENT. Token inclui "Bearer ".
     *
     * Para roles adicionais use: becomeOwner() / becomeProfessional()
     */
    public static UserAuthAndToken registerAndLoginUser(MockMvc mvc, ObjectMapper mapper, Faker faker) throws Exception {
        String email = faker.internet().emailAddress();
        String password = faker.internet().password(8, 16);

        MvcResult reg = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UserRegisterRequest(email, password))))
                .andExpect(status().isCreated())
                .andReturn();

        String userId = new JSONObject(reg.getResponse().getContentAsString()).getString("id");

        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        String token = "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens")
                .getString("access_token");

        log.debug("[TestUtils] registerAndLoginUser id={}", userId);
        return new UserAuthAndToken(userId, token, email, password);
    }

    // ─────────────────────────────────────────────
    // Role promotion via real endpoints
    // ─────────────────────────────────────────────

    /**
     * Makes a user OWNER by creating an establishment.
     * Calls POST /establishment/quick-create with the user's token.
     * A role OWNER is adicionada automaticamente pelo backend.
     *
     * @param token Bearer token of the authenticated user (already has CLIENT)
     * @return dados do establishment created
     */
    public static EstablishmentData becomeOwner(MockMvc mvc, ObjectMapper mapper, Faker faker, String token) throws Exception {
        MvcResult result = mvc.perform(post("/establishment/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(
                                new EstablishmentQuickCreateRequest(faker.company().name(), ServiceType.BARBER))))
                .andExpect(status().isCreated())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        String id = json.getString("id");
        String name = json.getString("name");
        log.debug("[TestUtils] becomeOwner establishmentId={}", id);
        return new EstablishmentData(id, name);
    }

    /**
     * Makes a user PROFESSIONAL by linking them to an establishment.
     * Calls POST /professional/quick-create with the user's token.
     * A role PROFESSIONAL is adicionada automaticamente pelo backend.
     *
     * @param token           Bearer token of the authenticated user
     * @param establishmentId ID do establishment ao qual se filia
     * @param name            Professional name (null = random name)
     */
    public static void becomeProfessional(MockMvc mvc, ObjectMapper mapper, Faker faker,
                                          String token, String establishmentId, String name) throws Exception {
        mvc.perform(post("/professional/quick-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new ProfessionalQuickCreateRequest(
                                name != null ? name : faker.name().firstName(),
                                UUID.fromString(establishmentId),
                                null))))
                .andExpect(status().isCreated());

        log.debug("[TestUtils] becomeProfessional estab={}", establishmentId);
    }

    /** Overload with random name. */
    public static void becomeProfessional(MockMvc mvc, ObjectMapper mapper, Faker faker,
                                          String token, String establishmentId) throws Exception {
        becomeProfessional(mvc, mapper, faker, token, establishmentId, null);
    }

    /**
     * Marca o establishment como OPEN direto no banco (sem passar pelo gate de abertura, que
     * exige horario). Usado no setup de testes que precisam de um cliente ENTRANDO na fila:
     * apos o gate de entrada (BUG5 parte C1), so loja "aberta agora" recebe walk-in. Sem horario
     * definido, {@code isOpenNow()} = OPEN, entao basta o status. Idempotente.
     */
    public static void openEstablishment(EstablishmentRepository repository, String establishmentId) {
        Establishment e = repository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        repository.save(e);
        log.debug("[TestUtils] openEstablishment estab={} -> OPEN", establishmentId);
    }

    // ─────────────────────────────────────────────
    // Composite helpers for entity creation
    // ─────────────────────────────────────────────

    /**
     * Cria um service item em um establishment.
     * Requer token de OWNER ou PROFESSIONAL do establishment.
     */
    public static ServiceItemData createServiceItem(MockMvc mvc, ObjectMapper mapper,
                                                     String establishmentId, String token,
                                                     ServiceItemType type, String description,
                                                     BigDecimal price) throws Exception {
        MvcResult result = mvc.perform(post("/establishment/" + establishmentId + "/service-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new ServiceItemRequest(
                                UUID.fromString(establishmentId), type, description, price, 30, null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        log.debug("[TestUtils] createServiceItem id={}", json.optString("id"));
        return new ServiceItemData(
                json.getString("id"),
                json.optString("customName", description),
                new BigDecimal(json.getString("price")));
    }

    /**
     * Cria establishment via endpoint legado (dados completos).
     * @deprecated Use becomeOwner() para o fluxo MVP quick-create.
     */
    @Deprecated
    public static EstablishmentData createEstablishment(MockMvc mvc, ObjectMapper mapper, Faker faker,
                                                         String ownerId, Set<ServiceType> services,
                                                         String ownerToken) throws Exception {
        var request = new com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest(
                UUID.fromString(ownerId), services,
                faker.company().name(), faker.internet().url(),
                EstablishmentStatus.ACTIVE, OpenStatus.OPEN, null);

        MvcResult result = mvc.perform(post("/establishment/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JSONObject json = new JSONObject(result.getResponse().getContentAsString());
        return new EstablishmentData(json.getString("id"), json.getString("name"));
    }
}
