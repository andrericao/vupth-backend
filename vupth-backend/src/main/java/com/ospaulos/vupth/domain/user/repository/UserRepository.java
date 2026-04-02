package com.ospaulos.vupth.domain.user.repository;

import com.ospaulos.vupth.domain.user.model.BaseUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    BaseUser save(BaseUser user);

    public List<BaseUser> findAll();

    Optional<BaseUser> findById(UUID id);

    Optional<BaseUser> findByEmail(String email);

    Optional<BaseUser> findByEmailWithRoles(String email);

    //Optional<BaseUser> findByIdWithRoles(UUID id);

    boolean existsByEmail(String email);

    boolean existsById(UUID id);

    void deleteById(UUID id);

    void deleteAll();

}
