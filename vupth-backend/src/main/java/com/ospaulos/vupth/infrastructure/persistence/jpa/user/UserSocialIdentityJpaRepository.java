package com.ospaulos.vupth.infrastructure.persistence.jpa.user;

import com.ospaulos.vupth.domain.user.model.SocialProvider;
import com.ospaulos.vupth.domain.user.model.UserSocialIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSocialIdentityJpaRepository
        extends JpaRepository<UserSocialIdentity, UUID> {

    Optional<UserSocialIdentity> findByProviderAndProviderUserId(
            SocialProvider provider, String providerUserId);
}
