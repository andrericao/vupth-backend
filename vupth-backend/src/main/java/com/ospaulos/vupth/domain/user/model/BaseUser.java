package com.ospaulos.vupth.domain.user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.exception.InvalidBirthDateException;
import com.ospaulos.vupth.api.user.exception.UserNotDeletedException;
import com.ospaulos.vupth.api.user.exception.NoFieldsToUpdateException;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.user.exception.NotFoundUserException;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.*;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "base_user",
        indexes = {
        @Index(name = "idx_person_email", columnList = "email", unique = true),
        @Index(name = "idx_person_active", columnList = "is_active")
})
public class BaseUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Email
    @Column(name = "email", unique = true, nullable = false, length = 150)
    private String email;

    @Column(name = "password", nullable = false, length = 72)
    private String password;

    @Column(name = "phone", length = 20)
    private String phone;

    @Past
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @OneToMany(mappedBy = "baseUser", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference("user-address")
    private List<UserAddress> userAddresses = new ArrayList<>();

    @Column(name = "last_token_cleanup_at")
    private OffsetDateTime lastTokenCleanupAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "baseUser", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserRole> roles = new HashSet<>();

    @JsonIgnore
    @OneToOne(mappedBy = "baseUser",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            optional = true )
    private Owner owner;

    @JsonIgnore
    @OneToOne(mappedBy = "baseUser",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            optional = true )
    private Professional professional;

    @JsonIgnore
    @OneToOne(mappedBy = "baseUser",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            optional = true )
    private Client client;

    public BaseUser(@Email String email) {
        this.email = email;
    }

    @PrePersist
    private void prePersist(){
        this.createdAt = OffsetDateTime.now();
    }

    public List<String> updateIfPresent(UserProfileGeneralInfosRequest request) {
        List<String> modified = new ArrayList<>();

        if (request.firstName() != null && !request.firstName().isBlank()) {
            this.firstName = request.firstName();
            modified.add("first_name");
        }

        if (request.lastName() != null && !request.lastName().isBlank()) {
            this.lastName = request.lastName();
            modified.add("last_name");
        }

        // nickname (apelido): provido (non-null) atualiza; em branco limpa para null
        if (request.nickname() != null) {
            String trimmed = request.nickname().trim();
            this.nickname = trimmed.isBlank() ? null : trimmed;
            modified.add("nickname");
        }

        if (request.phone() != null && !request.phone().isBlank()) {
            this.phone = request.phone();
            modified.add("phone");
        }

        if (request.birthDate() != null) {
            LocalDate bd = request.birthDate();
            if (bd.isAfter(LocalDate.now())) {
                throw new InvalidBirthDateException(bd);
            }
            int age = Period.between(bd, LocalDate.now()).getYears();
            if (age < InvalidBirthDateException.MIN_AGE || age > InvalidBirthDateException.MAX_AGE) {
                throw new InvalidBirthDateException(bd, age);
            }
            this.birthDate = bd;
            modified.add("birth_date");
        }

        if (request.gender() != null) {
            this.gender = request.gender();
            modified.add("gender");
        }

        if (request.profileImageUrl() != null && !request.profileImageUrl().isBlank()) {
            this.profileImageUrl = request.profileImageUrl();
            modified.add("profile_image_url");
        }

        if (modified.isEmpty()) {
            throw new NoFieldsToUpdateException();
        }

        return modified;
    }

    public UserAddress addAddress(Address address) {
        UserAddress ua = new UserAddress(address);
        ua.setBaseUser(this);
        this.userAddresses.add(ua);
        ensureMainAddress();

        return ua;
    }

    public void ensureMainAddress(){
        long mains = userAddresses.stream()
                .filter(UserAddress::getIsMain)
                .count();

        if(mains > 1){
            throw new RuntimeException(
                    "User can't have more than one main address!"
            );
        }

        if(mains == 0 && userAddresses.size() == 1){
            userAddresses.getFirst().setIsMain(true);
        }
    }

    public void updateUserAddress(UpdateAddressRequest request){

        UserAddress userAddress = getUserAddress(request.userAddressId());

        if(Boolean.TRUE.equals(request.isMain())){
            this.userAddresses.forEach(ua -> ua.setIsMain(false));
        }

        userAddress.update(request);

        ensureMainAddress();
    }

    public UserAddress getUserAddress(UUID userAddressId){
        return userAddresses.stream()
                .filter(ua -> ua.getId().equals(userAddressId))
                .findFirst()
                .orElseThrow(
                        () -> new EntityNotFoundException("Address not exists")
                );
    }

    public void addAddress(UserAddress address) {
        userAddresses.add(address);
        address.setBaseUser(this);
    }

    public void removeAddress(UUID userAddressId){

        boolean removed =  this.userAddresses.removeIf(
                ua -> ua.getId().equals(userAddressId)
        );

        if(!removed){
            throw new EntityNotFoundException("User Address not deleted!");
        }

        ensureMainAddress();
    }

    public void setMainAddress(UUID userAddressId) {

        // TODO: Improve exception
        UserAddress newMain = getUserAddress(userAddressId);

        if(Boolean.TRUE.equals(newMain.getIsMain())){
            throw new RuntimeException("UserAddress already is main");
        }

        userAddresses.forEach(ua -> ua.setIsMain(false));
        newMain.setIsMain(true);
    }

    public UserAddress getMainAddress(){

        return userAddresses.stream()
                .filter(ua -> Boolean.TRUE.equals(ua.getIsMain()))
                .findFirst()
                .orElseThrow(() ->
                        new EntityNotFoundException("User has no main address")
                        );
    }

    public List<UserAddress> getAddress() {
        return Collections.unmodifiableList(userAddresses);
    }

    public void addClientProfile() {
        if (this.client == null) {
            this.client = new Client(this);
            addRole(RoleType.CLIENT);
        }
    }

    public void addProfessionalProfile() {
        if (this.professional == null) {
            this.professional = new Professional(this);
            addRole(RoleType.PROFESSIONAL);
        }
    }

    public void addOwnerProfile() {
        if (this.owner == null) {
            this.owner = new Owner(this);
            addRole(RoleType.OWNER);
        }
    }

    public void addRole(RoleType roleType) {
        if (hasRole(roleType)) {
            return;
        }

        UserRole role = new UserRole(this, roleType);
        role.activate();
        roles.add(role);

        //initializeRoleData(roleType);
    }

    public void removeRole(RoleType roleType) {
        roles.removeIf(role ->
                role.getRoleType() == roleType/* &&
                        Objects.equals(role_type.getEstablishmentId(), establishmentId)*/);

        // Clear role-specific data if the role_type was removed
        //cleanupRoleDataIfNeeded(roleType);
    }

    public boolean hasRole(RoleType roleType) {
        return roles.stream().anyMatch(role -> role.getRoleType().equals(roleType));
    }


    /*
    public boolean hasRole(RoleType roles, UUID establishmentId) {
        return roles.stream().anyMatch(role_type ->
                role_type.getRoleType().equals(roles) &&
                        Objects.equals(role_type.getEstablishmentId(), establishmentId));
    }
    */

    // TODO: improve methods
    public boolean isProfessional() { return hasRole(RoleType.PROFESSIONAL); }
    public boolean isClient() { return hasRole(RoleType.CLIENT); }
    public boolean isOwner() { return hasRole(RoleType.OWNER); }
    //public boolean isSecretary() { return hasRole(RoleType.SECRETARY); }

    public boolean hasClientProfile(){
        return this.client != null;
    }

    public boolean hasProfessionalProfile(){
        return this.professional != null;
    }

    public boolean hasOwnerProfile(){
        return this.owner != null;
    }

    // ========== ROLE-SPECIFIC DATA ACCESS ==========

    public Professional getProfessional() {
        if (!isProfessional()) {
            throw new IllegalStateException("User is not a professional");
        }

        if (professional == null) {
            professional = new Professional(this);
        }

        return professional;
    }

    public Client getClient() {
        if (!isClient()) {
            throw new IllegalStateException("User is not a client");
        }

        if (client == null) {
            client = new Client(this);
        }

        return client;
    }

    public Owner getOwner() {
        if (!isOwner()) {
            throw new IllegalStateException("User is not an owner");
        }

        if (owner == null) {
            owner = new Owner(this);
        }

        return owner;
    }

    private void initializeRoleData(RoleType roleType) {
        switch (roleType) {
            case PROFESSIONAL -> {
                if (professional == null) {
                    professional = new Professional(this);
                }
            }
            case CLIENT -> {
                if (client == null) {
                    client = new Client(this);
                }
            }
            case OWNER -> {
                if (owner == null) {
                    owner = new Owner(this);
                }
            }
        }
    }

    public void deactivateRole(RoleType roleType) {
        roles.stream()
                .filter(r -> r.getRoleType() == roleType)
                .forEach(UserRole::deactivate);

        cleanupRoleDataIfNeeded(roleType);
    }

    private void cleanupRoleDataIfNeeded(RoleType roleType) {
        switch (roleType) {
            case PROFESSIONAL -> {
                if (!hasRole(RoleType.PROFESSIONAL)) {
                    professional = null; // Will be removed by cascade
                }
            }
            case CLIENT -> {
                if (!hasRole(RoleType.CLIENT)) {
                    client = null;
                }
            }
            case OWNER -> {
                if (!hasRole(RoleType.OWNER)) {
                    owner = null;
                }
            }
        }
    }

    // ========== BUSINESS METHODS ==========

    public int getAge() {
        if (birthDate == null) return 0;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    public void activate() { this.isActive = true; }
    public void deactivate() { this.isActive = false; }
}
