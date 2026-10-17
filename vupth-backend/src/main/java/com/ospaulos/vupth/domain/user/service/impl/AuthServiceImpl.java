package com.ospaulos.vupth.domain.user.service.impl;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

        log.info("LOAD USER BY USERNAME - LOGIN() " + LogMask.email(email));

        BaseUser user =  userRepository.findByEmailWithRoles(email)
                .orElseThrow(
                        () -> new UsernameNotFoundException("User " + email + " not found!")
                );

        return new UserDetailsImpl(user);
    }
}