package com.ospaulos.vupth.integrations.utils.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@TestComponent
@Profile("test")
public class TestUtilsClient {

    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final UserMapper userMapper;

    private final Faker faker = new Faker();

    public TestUtilsClient(MockMvc mvc, ObjectMapper mapper, UserMapper userMapper){
        this.mvc = mvc;
        this.mapper = mapper;
        this.userMapper = userMapper;
    }

    public UserProfileGeneralInfosAndToken registerAndAuthUser() throws Exception  {

        String email = faker.internet().emailAddress();
        String password = faker.internet().password();

        // todo: verificar response e criar liquibase para refresh token
        UserRegisterResponse registerResponse = registerUser(email, password);

        log.info("REGISTER AND AUTH USER UTILS END");

        return loginUser(email, password);
    }


    public UserProfileGeneralInfosAndToken loginUser(String email, String password) throws Exception{

        log.info("LOGIN USER UTILS START");

        LoginRequest request = new LoginRequest(email, password);

        MvcResult loginResponse = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        return mapper.readValue(
                loginResponse.getResponse().getContentAsString(),
                UserProfileGeneralInfosAndToken.class
        );
    }

    public UserRegisterResponse registerUser(String email, String password) throws Exception{

        log.info("REGISTER USER UTILS START");

        UserRegisterRequest userRegisterRequest = new UserRegisterRequest(email, password);

        MvcResult registerResponse = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(userRegisterRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String json = registerResponse.getResponse().getContentAsString();

        UserRegisterResponse response = mapper.readValue(json, UserRegisterResponse.class);

        log.info("REGISTER USER UTILS END");

        return response;
    }
}
