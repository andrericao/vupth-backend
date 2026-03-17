package com.ospaulos.vupth.api.user.specification;

import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class EmailAvailabilitySpecification {

    private final UserRepository userRepository;

    public boolean isAvaliable(String email){
        return !userRepository.existsByEmail(email);
    }
}
