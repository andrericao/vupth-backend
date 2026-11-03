package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CPF opcional do profissional (BAK-12, DEC-164): valida os digitos, guarda cifrado e so devolve mascarado ao proprio. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "vupth.security.data-encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ProfessionalCpfIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    private static String register(String name, String cpf) {
        return "{\"name\":\"" + name + "\"" + (cpf == null ? "" : ",\"cpf\":\"" + cpf + "\"") + "}";
    }

    @Test
    @DisplayName("cadastro com CPF: digitos errados 422 CPF_INVALID; certo guarda cifrado e o GET /me devolve so mascarado")
    void registerWithCpf() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        mvc.perform(post("/professional/register").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content(register("Ana", "111.111.111-11")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CPF_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("11111111111"))));

        mvc.perform(post("/professional/register").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content(register("Ana", "529.982.247-25")))
                .andExpect(status().isCreated());

        mvc.perform(get("/professional/me").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value("***.982.247-**"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("52998224725"))));

        String stored = jdbc.queryForObject("SELECT cpf FROM base_user WHERE id = ?", String.class, UUID.fromString(user.id()));
        assertThat(stored).startsWith("enc:v1:").doesNotContain("52998224725");
    }

    @Test
    @DisplayName("sem CPF o cadastro segue igual; o PATCH /professional/me poe, troca e apaga (\"\")")
    void patchCpf() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/register").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content(register("Bia", null)))
                .andExpect(status().isCreated());
        mvc.perform(get("/professional/me").header("Authorization", user.token()))
                .andExpect(jsonPath("$.cpf").doesNotExist());

        mvc.perform(patch("/professional/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content("{\"cpf\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CPF_INVALID"));

        mvc.perform(patch("/professional/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content("{\"cpf\":\"11144477735\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/professional/me").header("Authorization", user.token()))
                .andExpect(jsonPath("$.cpf").value("***.444.777-**"));

        mvc.perform(patch("/professional/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token()).content("{\"cpf\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/professional/me").header("Authorization", user.token()))
                .andExpect(jsonPath("$.cpf").doesNotExist());
    }

    /**
     * O banco dos testes e um so para a suite inteira, e este teste liga a cifragem com uma chave so dele: sem apagar o CPF
     * cifrado, a classe seguinte (sem a chave) falha ao ler esses usuarios ("Dado cifrado no banco, mas DATA_ENCRYPTION_KEY
     * nao esta configurada" — foi o CI vermelho de 03/11). Mesmo cuidado do PersonalDataEncryptionIntegrationTest.
     */
    @AfterAll
    void clearEncryptedCpf() {
        jdbc.update("UPDATE base_user SET cpf = NULL WHERE cpf IS NOT NULL");
    }
}
