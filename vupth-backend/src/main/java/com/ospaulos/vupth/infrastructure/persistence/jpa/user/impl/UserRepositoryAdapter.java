package com.ospaulos.vupth.infrastructure.persistence.jpa.user.impl;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.persistence.jpa.user.UserJpaRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;

    @Override
    public BaseUser save (@Valid BaseUser user){
        return jpaRepository.save(user);
    }

    @Override
    public List<BaseUser> findAll(){
        return jpaRepository.findAll();
    }

    @Override
    public Optional<BaseUser> findById(UUID id){
        return jpaRepository.findByIdWithRoles(id);
    }

    @Override
    public Optional<BaseUser> findByEmail(String email) {
        return jpaRepository.findByEmailWithRoles(email);
    }

    @Override
    public Optional<BaseUser> findByEmailWithRoles(String email){
        return jpaRepository.findByEmailWithRoles(email);
    }

    @Override
    public boolean existsByEmail(String email){
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsById(UUID id){
        return  jpaRepository.existsById(id);
    }

    @Override
    public void deleteById(UUID id){
        jpaRepository.deleteById(id);
    }

    @Override
    public void deleteAll(){
        jpaRepository.deleteAll();
    }
}
