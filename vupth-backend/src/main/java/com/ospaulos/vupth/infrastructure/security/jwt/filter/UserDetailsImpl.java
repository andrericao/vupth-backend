package com.ospaulos.vupth.infrastructure.security.jwt.filter;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserRole;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class UserDetailsImpl implements UserDetails {

    @Getter private final BaseUser user;
    @Getter private final Set<RoleType> roles;

    public UserDetailsImpl(BaseUser user) {

        log.info("USER DETAILS START - " + user.getEmail());

        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new IllegalStateException("User with no password: " + user.getEmail());
        }

        this.user = user;
        this.roles = user.getRoles()
                .stream()
                .filter(UserRole::getIsActive)
                .map(UserRole::getRoleType)
                .collect(Collectors.toSet());

        log.info("USER DETAILS END!");
    }

    public UUID getUserId(){
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {

        //Set<GrantedAuthority> authorities = new HashSet<>();

        return user.getRoles().stream()
                .filter(UserRole::getIsActive)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getRoleType().name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
