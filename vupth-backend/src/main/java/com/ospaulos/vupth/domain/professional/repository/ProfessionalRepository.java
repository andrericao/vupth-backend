package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.professional.model.Professional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfessionalRepository extends JpaRepository<Professional, UUID> {

    // Find by specific professional data
    List<Professional> findByIsAvailableTrue();
    List<Professional> findBySpecialtyContainingIgnoreCase(String specialty);

    // Find profissional por establishment
    List<Professional> findByEstablishmentId(UUID establishmentId);

    // Find available professionals in an establishment
    @Query("SELECT p FROM Professional p WHERE p.establishment.id = :establishmentId AND p.isAvailable = true")
    List<Professional> findAvailableByEstablishmentId(@Param("establishmentId") UUID establishmentId);

    // Find profissionais ativos (combinando dados do BaseUser e Professional)
    @Query("SELECT p FROM Professional p WHERE p.baseUser.isActive = true AND p.isAvailable = true")
    List<Professional> findActiveProfessionals();

    // Find by specialty in a specific establishment
    @Query("SELECT p FROM Professional p WHERE p.establishment.id = :establishmentId AND p.specialty = :specialty")
    List<Professional> findByEstablishmentIdAndSpecialty(@Param("establishmentId") UUID establishmentId, @Param("specialty") String specialty);

    // Check if professional is active and available
    @Query("SELECT COUNT(p) > 0 FROM Professional p WHERE p.id = :id AND p.baseUser.isActive = true AND p.isAvailable = true")
    boolean isActiveAndAvailable(@Param("id") UUID id);

    // Find professional with User eagerly loaded — usar sempre que getName() for chamado
    @Query("SELECT p FROM Professional p JOIN FETCH p.baseUser WHERE p.id = :id")
    Optional<Professional> findByIdWithBaseUser(@Param("id") UUID id);

    @Query("""
    SELECT DISTINCT p FROM Professional p
    JOIN FETCH p.baseUser
    WHERE p.establishment.id = :establishmentId
""")
    List<Professional> findByEstablishmentIdEager(@Param("establishmentId") UUID establishmentId);

}
