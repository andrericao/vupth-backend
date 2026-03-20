package com.ospaulos.vupth.infrastructure.config;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final EstablishmentRepository establishmentRepository;
    private final OwnerRepository ownerRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (establishmentRepository.count() > 0) {
            log.info("[DataSeeder] Establishments already exist, skipping seed");
            return;
        }

        log.info("[DataSeeder] Seeding test data...");

        // Create seed user + owner
        BaseUser seedUser = new BaseUser();
        seedUser.setEmail("seed-owner@vupth.local");
        seedUser.setPassword(passwordEncoder.encode("seed123456"));
        seedUser.setFirstName("Seed");
        seedUser.setLastName("Owner");
        seedUser = userRepository.save(seedUser);

        Owner owner = new Owner(seedUser);
        owner = ownerRepository.save(owner);

        // Seed establishments
        createEstablishment("Barbearia do Ze", "https://placehold.co/400x300?text=Barber",
                Set.of(ServiceType.BARBER), OpenStatus.OPEN, owner);

        createEstablishment("Salao Beleza Pura", "https://placehold.co/400x300?text=Salon",
                Set.of(ServiceType.SALON_BEAUTY), OpenStatus.OPEN, owner);

        createEstablishment("Studio Sobrancelha Perfeita", "https://placehold.co/400x300?text=Eyebrow",
                Set.of(ServiceType.EYEBROW), OpenStatus.OPEN, owner);

        createEstablishment("Manicure & Pedicure Top", "https://placehold.co/400x300?text=Manicure",
                Set.of(ServiceType.MANICURE), OpenStatus.OPEN, owner);

        createEstablishment("Espaco Beleza Total", "https://placehold.co/400x300?text=Multi",
                Set.of(ServiceType.SALON_BEAUTY, ServiceType.MANICURE, ServiceType.EYEBROW), OpenStatus.OPEN, owner);

        log.info("[DataSeeder] Seed complete: 5 establishments created");
    }

    private void createEstablishment(String name, String imageUrl, Set<ServiceType> services,
                                     OpenStatus openStatus, Owner owner) {
        Establishment e = new Establishment();
        e.setName(name);
        e.setUrlImage(imageUrl);
        e.setServices(services);
        e.setStatus(EstablishmentStatus.ACTIVE);
        e.setOpenStatus(openStatus);
        e.setOwner(owner);
        establishmentRepository.save(e);
    }
}
