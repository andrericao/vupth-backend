package com.ospaulos.vupth.infrastructure.persistence.jpa.user;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<BaseUser, UUID> {

    Optional<BaseUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsById(UUID id);

    /*
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.establishment WHERE u.id = :id")
    Optional<User> findByIdWithEstablishments(@Param("id") UUID id);
     */

    List<BaseUser> findByRolesContaining(RoleType role);

    @Query("SELECT u FROM BaseUser u LEFT JOIN FETCH u.roles WHERE u.email = :email")
    Optional<BaseUser> findByEmailWithRoles(@Param("email") String email);


    @Query("SELECT u FROM BaseUser u LEFT JOIN FETCH u.roles WHERE u.id = :id")
    Optional<BaseUser> findByIdWithRoles(@Param("id") UUID id);

    @Query("""
        SELECT bu FROM BaseUser bu
        JOIN bu.roles r
        WHERE bu.id = :userId
        AND r.roleType = :roleType
        """)
    Optional<BaseUser> findByIdAndRoleType(
            @Param("userId") UUID userId,
            @Param("roleType") RoleType roleType);

    // Alternative with DERIVED QUERY (if using a simple collection)
    Optional<BaseUser> findByIdAndRolesContaining(UUID id, RoleType role);
}
