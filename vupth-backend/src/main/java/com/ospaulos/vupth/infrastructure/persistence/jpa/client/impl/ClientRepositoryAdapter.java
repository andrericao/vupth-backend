package com.ospaulos.vupth.infrastructure.persistence.jpa.client.impl;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.infrastructure.persistence.jpa.client.ClientJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ClientRepositoryAdapter implements ClientRepository {

    private final ClientJpaRepository jpaRepository;

    @Override
    public List<Client> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public Optional<Client> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Client> findByIdWithClient(UUID id) {
        return jpaRepository.findByIdWithClient(id);
    }

    @Override
    public List<Client> findActiveClients() {
        return jpaRepository.findActiveClients();
    }

    @Override
    public Optional<Client> findByPhone(String phone) {
        return jpaRepository.findByPhone(phone);
    }

    @Override
    public Optional<Client> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public Optional<Client> findByIdWithBaseUser(UUID id) {
        return jpaRepository.findByIdWithBaseUser(id);
    }

    @Override
    public List<Client> findClientsWithAttendanceStatus(AttendanceStatus status) {
        return jpaRepository.findClientsWithAttendanceStatus(status);
    }
}

