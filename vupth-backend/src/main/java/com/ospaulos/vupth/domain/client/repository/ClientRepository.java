package com.ospaulos.vupth.domain.client.repository;

import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {

    List<Client> findAll();

    Optional<Client> findById(UUID id);

    Optional<Client> findByIdWithClient(UUID id);

    List<Client> findActiveClients();

    Optional<Client> findByPhone(@Param("phone") String phone);

    Optional<Client> findByEmail(@Param("email") String email);

    Optional<Client> findByIdWithBaseUser(@Param("id") UUID id);

    List<Client> findClientsWithAttendanceStatus(@Param("status") AttendanceStatus status);
}
