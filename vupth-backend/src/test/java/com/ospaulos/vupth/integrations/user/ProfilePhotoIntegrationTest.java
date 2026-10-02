package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — foto de perfil (S22). O R2 e trocado por um storage em memoria: o que se prova
 * aqui e o contrato (upload, validacao, a URL nas respostas do profissional, remocao), nao a Cloudflare.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProfilePhotoIntegrationTest.InMemoryStorageConfig.class)
class ProfilePhotoIntegrationTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @TestConfiguration
    static class InMemoryStorageConfig {
        @Bean
        @Primary
        InMemoryImageStorage inMemoryImageStorage() {
            return new InMemoryImageStorage();
        }
    }

    static class InMemoryImageStorage implements ImageStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String store(String key, byte[] content, String contentType) {
            objects.put(key, content);
            return "https://fotos.test/" + key;
        }

        @Override
        public void deleteByUrl(String publicUrl) {
            objects.remove(publicUrl.replace("https://fotos.test/", ""));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository professionalRepository;
    @Autowired InMemoryImageStorage storage;

    private final Faker faker = new Faker();
    String token, establishmentId, firstUrl;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.token = owner.token();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, token);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, token, establishmentId, "Prof Foto");
        // a vitrine so lista quem esta em expediente e tem servico
        TestUserUtils.startShift(professionalRepository, establishmentId);
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, token,
                com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType.CORTE_MAQUINA, "Corte",
                java.math.BigDecimal.valueOf(30));
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] content) {
        MockMultipartHttpServletRequestBuilder b = multipart("/profile/me/photo")
                .file(new MockMultipartFile("file", "foto.png", "image/png", content));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }

    @Test
    @Order(10)
    @DisplayName("PUT /profile/me/photo com PNG -> 200 com a URL publica, e o GET /profile/me passa a trazer")
    void upload_png() throws Exception {
        MvcResult r = mvc.perform(upload(PNG).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        this.firstUrl = new JSONObject(r.getResponse().getContentAsString()).getString("profile_image_url");
        assertThat(firstUrl).startsWith("https://fotos.test/profile/");

        mvc.perform(get("/profile/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_image_url").value(firstUrl));
    }

    @Test
    @Order(20)
    @DisplayName("A foto aparece para o cliente na fila da loja (professionals-with-queue.profile_image_url)")
    void photo_inProfessionalsWithQueue() throws Exception {
        MvcResult r = mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JSONArray pros = new JSONObject(r.getResponse().getContentAsString()).getJSONArray("professionals");
        assertThat(pros.getJSONObject(0).getString("profile_image_url")).isEqualTo(firstUrl);
    }

    @Test
    @Order(30)
    @DisplayName("Trocar a foto apaga a anterior do storage")
    void replace_deletesPrevious() throws Exception {
        mvc.perform(upload(PNG).header("Authorization", token)).andExpect(status().isOk());

        assertThat(storage.objects).hasSize(1);
        assertThat(storage.objects.keySet()).noneMatch(k -> firstUrl.endsWith(k));
    }

    @Test
    @Order(40)
    @DisplayName("Arquivo que nao e imagem -> 400 INVALID_PROFILE_PHOTO")
    void notImage_400() throws Exception {
        mvc.perform(upload("nao sou imagem".getBytes()).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROFILE_PHOTO"));
    }

    @Test
    @Order(50)
    @DisplayName("Sem token -> 401")
    void noToken_401() throws Exception {
        mvc.perform(upload(PNG)).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(60)
    @DisplayName("DELETE /profile/me/photo -> 204, sem URL e sem arquivo")
    void remove() throws Exception {
        mvc.perform(delete("/profile/me/photo").header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/profile/me").header("Authorization", token))
                .andExpect(jsonPath("$.profile_image_url").isEmpty());
        assertThat(storage.objects).isEmpty();
    }

    // ── foto da LOJA (Andre, 26/09) — mesma mecanica, so o dono ──

    private MockMultipartHttpServletRequestBuilder uploadStore(byte[] content) {
        MockMultipartHttpServletRequestBuilder b = multipart("/establishment/{id}/photo", establishmentId)
                .file(new MockMultipartFile("file", "loja.png", "image/png", content));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }

    @Test
    @Order(70)
    @DisplayName("PUT /establishment/{id}/photo (dono) -> 200 url_image, e o detalhe da loja passa a trazer")
    void storePhoto_upload() throws Exception {
        MvcResult r = mvc.perform(uploadStore(PNG).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        String url = new JSONObject(r.getResponse().getContentAsString()).getString("url_image");
        assertThat(url).startsWith("https://fotos.test/establishment/" + establishmentId + "/");

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                // o detalhe da loja expoe urlImage (camelCase) — e o nome que o Client ja le
                .andExpect(jsonPath("$.urlImage").value(url));
    }

    @Test
    @Order(71)
    @DisplayName("Foto da loja por quem nao e o dono -> 403")
    void storePhoto_notOwner_403() throws Exception {
        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeOwner(mvc, mapper, faker, other.token()); // dono de OUTRA loja
        String otherToken = "Bearer " + new JSONObject(mvc.perform(post("/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest(
                                other.email(), other.password()))))
                .andReturn().getResponse().getContentAsString()).getJSONObject("tokens").getString("access_token");

        mvc.perform(uploadStore(PNG).header("Authorization", otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(72)
    @DisplayName("Foto da loja que nao e imagem -> 400; DELETE -> 204 e sai do storage")
    void storePhoto_invalid_thenRemove() throws Exception {
        mvc.perform(uploadStore("texto".getBytes()).header("Authorization", token))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/establishment/{id}/photo", establishmentId).header("Authorization", token))
                .andExpect(status().isNoContent());
        assertThat(storage.objects.keySet()).noneMatch(k -> k.startsWith("establishment/"));
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
