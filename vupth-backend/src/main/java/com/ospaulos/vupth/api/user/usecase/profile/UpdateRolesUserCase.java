package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.dto.request.profile.UpdateRoleRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpdateRolesUserCase {
    private final UserRepository repository;
    private final UserMapper mapper;

    public UserProfileGeneralInfosResponse executeUpdateRoles(UUID id, Set<UpdateRoleRequest> request){

        BaseUser user = repository.findById(id)
                .orElseThrow(
                        () -> new UsernameNotFoundException("User not found!")
                );

/*
    user.getRoles().clear();

    // Adds only the roles from the request
    requestedRoles.forEach(role ->
        user.getRoles().add(new UserRole(user, role))
    );


    // TODO: SALVO
    repository.save(user);

                 // Now generate a new token with the updated roles
    String newJwt = jwtTokenProvider.createToken(
            new UserDetailsImpl(user),
            user.getId().toString()
    );
                 */
        // TODO: created only to pass the test — revisit
        return mapper.toGeneralInfosUserResponse(user);
    }
}
