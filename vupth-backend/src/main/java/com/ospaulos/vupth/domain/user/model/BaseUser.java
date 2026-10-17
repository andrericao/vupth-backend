package com.ospaulos.vupth.domain.user.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.exception.InvalidBirthDateException;
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

    /**
     * Instante em que o titular pediu para apagar a conta (Partner S19 / LGPD).
     * {@code null} = conta viva. Preenchido pelo {@link #anonymize(String)}, junto com
     * {@code isActive = false} — os dois sempre andam juntos.
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Conta desativada pelo titular (desativar-conta): {@code null} = em uso. Diferente da apagada, a
     * desativada continua entrando e lendo ("so consultar"); o que mexe na operacao da 403.
     */
    @Column(name = "deactivated_at")
    private OffsetDateTime deactivatedAt;

    @OneToMany(mappedBy = "baseUser", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserRole> roles = new HashSet<>();

    /**
     * A chave Pix da PESSOA (DEC-65, pix-por-pessoa, 15/10; antes ficava no profissional): o tipo, a chave
     * normalizada e o nome que o banco mostra — as tres juntas ou nenhuma. O cliente paga na do profissional
     * que atendeu; sem ela, na do dono da loja.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pix_key_type", length = 10)
    private com.ospaulos.vupth.domain.payment.PixKeyType pixKeyType;

    /** Cifrados no banco (S4, DEC-81): AES-256-GCM, ver {@code DataCipher}. A coluna comporta o texto cifrado. */
    @Convert(converter = com.ospaulos.vupth.infrastructure.crypto.EncryptedStringConverter.class)
    @Column(name = "pix_key", length = 400)
    private String pixKey;

    @Convert(converter = com.ospaulos.vupth.infrastructure.crypto.EncryptedStringConverter.class)
    @Column(name = "pix_holder_name", length = 400)
    private String pixHolderName;

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

    /** Nome exibido no lugar do titular apagado — o outro lado do atendimento ve isto. */
    public static final String ANONYMIZED_FIRST_NAME = "Usuario";
    public static final String ANONYMIZED_LAST_NAME = "removido";

    /** Dominio reservado pela RFC 2606: nunca existe, entao nao colide com e-mail de ninguem. */
    private static final String ANONYMIZED_EMAIL_DOMAIN = "@deleted.invalid";

    /**
     * Apaga o que e PESSOAL do titular, preservando a linha — porque um atendimento pertence
     * tambem ao profissional e ao estabelecimento, e cada envolvido continua com direito ao
     * proprio historico. E o que a LGPD (art. 16) e o GDPR (art. 17.3) chamam de anonimizacao:
     * sem o dado identificante, o registro deixa de ser dado pessoal e pode ficar.
     *
     * <p>O que sai daqui: nome, apelido, e-mail, telefone, nascimento, genero, foto e senha.
     * O que sai FORA daqui (o orquestrador cuida): enderecos, device tokens, refresh tokens,
     * codigos de reset e os papeis. O que FICA: as linhas {@code client}/{@code professional}/
     * {@code owner}, que sao a chave estrangeira do historico dos outros.</p>
     *
     * <p>O e-mail vira {@code deleted-<id>@deleted.invalid}: libera o endereco real para um
     * cadastro novo (o titular pode voltar) sem violar a unicidade da coluna.</p>
     *
     * @param scrambledPassword hash de uma senha aleatoria — a antiga nao pode sobreviver
     */
    public void anonymize(String scrambledPassword) {
        this.firstName = ANONYMIZED_FIRST_NAME;
        this.lastName = ANONYMIZED_LAST_NAME;
        this.nickname = null;
        this.email = "deleted-" + this.id + ANONYMIZED_EMAIL_DOMAIN;
        this.phone = null;
        this.birthDate = null;
        this.gender = null;
        this.profileImageUrl = null;
        this.pixKeyType = null; // a chave Pix e dado pessoal (DEC-65): sai com a conta
        this.pixKey = null;
        this.pixHolderName = null;
        this.password = scrambledPassword;
        this.isActive = false;
        this.deletedAt = OffsetDateTime.now();
    }

    /** {@code true} quando o titular desativou a conta e ainda nao reativou. */
    public boolean isAccountDeactivated() {
        return this.deactivatedAt != null;
    }

    /** {@code true} quando a conta ja foi apagada pelo titular. */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
