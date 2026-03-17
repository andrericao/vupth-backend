package com.ospaulos.vupth.domain.user.service.impl;

import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.domain.user.service.UserService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * User lookup service.
 *
 * Uses UserMapper (MapStruct) instead of ObjectMapper.convertValue,
 * which was causing 500s because convertValue cannot handle lazy JPA collections.
 *
 * @since v7  fixed: mapper.convertValue → userMapper (v8)
 */
@Service
@AllArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository repository;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public UserRegisterResponse findByEmail(String email) {
        BaseUser user = repository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
        return userMapper.toRegisterResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserRegisterResponse findById(UUID id) {
        BaseUser user = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return userMapper.toRegisterResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BaseUser> findAll() {
        return repository.findAll();
    }
}
