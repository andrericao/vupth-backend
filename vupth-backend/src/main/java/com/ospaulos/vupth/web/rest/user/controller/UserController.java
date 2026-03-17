package com.ospaulos.vupth.web.rest.user.controller;

import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("users")
public class UserController {

    private final UserService service;
    private final UserMapper userMapper;

    @GetMapping("/email/{email}")
    public ResponseEntity<UserRegisterResponse> findByEmail(@PathVariable String email){
        UserRegisterResponse response = service.findByEmail(email);
        return ResponseEntity.status(HttpStatus.OK).body((response));
    }

    @GetMapping("/id/{id}")
    public ResponseEntity<UserRegisterResponse> findById(@PathVariable UUID id) {
        UserRegisterResponse response = service.findById(id);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }


    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<UserRegisterResponse>> findAll(){
        List<UserRegisterResponse> responses = service.findAll().stream()
                .map(userMapper::toRegisterResponse)
                .toList();
        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }
}
