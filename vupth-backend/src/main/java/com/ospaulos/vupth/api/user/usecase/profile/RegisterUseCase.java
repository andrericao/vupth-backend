package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.policy.PasswordPolicy;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.exception.EmailNotAvailableException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.api.user.specification.EmailAvailabilitySpecification;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@Transactional
@AllArgsConstructor
public class RegisterUseCase {

    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailAvailabilitySpecification emailAvailabilitySpecification;

    public UserRegisterResponse executeRegister(UserRegisterRequest request){

        log.info("RegisterUserUseCase.execute() - BEGIN!");

        PasswordPolicy.check(request.password());

        if(!emailAvailabilitySpecification.isAvaliable(request.email())){
            log.error("RegisterUserUseCase.execute() - Email already exists: {}", request.email());
            throw new EmailNotAvailableException(request.email());
        }

        BaseUser user = userMapper.toUserEntity(request);
        user.setPassword(passwordEncoder.encode(request.password()));

        user.addClientProfile();

        var userSaved = userRepository.save(user);

        log.info("EXECUTE REGISTER USECASE USER - ROLES " + userSaved.getRoles());

        return userMapper.toRegisterResponse(userSaved);
    }
}
