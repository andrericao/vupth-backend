package com.ospaulos.vupth.infrastructure.persistence.jpa.client;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientJpaRepository extends JpaRepository<Client, UUID> {

    @Query("""
        SELECT u FROM BaseUser u
        LEFT JOIN FETCH u.client c
        LEFT JOIN FETCH c.attendances a
        LEFT JOIN FETCH u.roles r
        WHERE u.id = :id
    """)
    Optional<Client> findByIdWithClient(UUID id);

    @Query("SELECT c FROM Client c WHERE c.baseUser.isActive = true")
    List<Client> findActiveClients();

    @Query("SELECT c FROM Client c WHERE c.baseUser.phone = :phone")
    Optional<Client> findByPhone(@Param("phone") String phone);

    @Query("SELECT c FROM Client c WHERE c.baseUser.email = :email")
    Optional<Client> findByEmail(@Param("email") String email);

    @Query("SELECT c FROM Client c JOIN FETCH c.baseUser WHERE c.id = :id")
    Optional<Client> findByIdWithBaseUser(@Param("id") UUID id);

    @Query("SELECT DISTINCT c FROM Client c JOIN c.attendances a WHERE a.status = :status")
    List<Client> findClientsWithAttendanceStatus(@Param("status") AttendanceStatus status);
}