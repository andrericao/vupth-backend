package com.ospaulos.vupth.api.user.dto.request.auth;

public record LoginRequest(
        String email,
        String password){
}
