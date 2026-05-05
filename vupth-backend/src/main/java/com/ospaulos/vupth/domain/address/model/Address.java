package com.ospaulos.vupth.domain.address.model;

import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Endereço base reutilizado por UserAddress e EstablishmentAddress.
 *
 * zipCode — armazenado como 8 dígitos puros, sem formatação (ex: "01310100").
 *
 * Boas práticas:
 *   - Backend recebe, valida (8 dígitos) e persiste sem formatação.
 *   - Frontend exibe formatado (ex: "01310-100") mas envia apenas dígitos.
 *   - Buscas e comparações são simples e sem ambiguidade de formato.
 *   - Integrações com ViaCEP e Correios já retornam sem formatação.
 *
 * @Column(length = 8) — VARCHAR(8), sem hífen ou ponto.
 *
 * Histórico de correções:
 *   v14-fix: regex anterior ^\\d{5}-\\d{3}$ e length=9 aceitavam hífen no banco.
 *            Corrigido para ^\\d{8}$ e length=8 (armazenamento sem formatação).
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "ADDRESSES")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false, length = 10)
    @Size(max = 10)
    private String number;

    private String complement;

    @Column(nullable = false)
    private String neighborhood;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false, length = 2)
    private String state;

    /**
     * CEP armazenado como 8 dígitos puros, sem hífen ou ponto.
     * Exemplos válidos: "01310100", "22041001", "69005010"
     * O frontend é responsável por exibir no formato "01310-100".
     */
    @Column(nullable = false, length = 8)
    @Pattern(
            regexp = "^\\d{8}$",
            message = "Invalid ZIP code. Send only 8 digits, no punctuation (e.g. 01310100)"
    )
    private String zipCode;

    private String reference;

    private Double latitude;

    private Double longitude;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void update(UpdateAddressRequest request) {
        if (request.street() != null && !request.street().isBlank()) {
            this.street = request.street();
        }
        if (request.number() != null && !request.number().isBlank()) {
            this.number = request.number();
        }
        if (request.complement() != null && !request.complement().isBlank()) {
            this.complement = request.complement();
        }
        if (request.neighborhood() != null && !request.neighborhood().isBlank()) {
            this.neighborhood = request.neighborhood();
        }
        if (request.city() != null && !request.city().isBlank()) {
            this.city = request.city();
        }
        if (request.state() != null && !request.state().isBlank()) {
            this.state = request.state();
        }
        if (request.zipCode() != null && !request.zipCode().isBlank()) {
            this.zipCode = request.zipCode();
        }
        if (request.reference() != null && !request.reference().isBlank()) {
            this.reference = request.reference();
        }
        if (request.latitude() != null) {
            this.latitude = request.latitude();
        }
        if (request.longitude() != null) {
            this.longitude = request.longitude();
        }
    }
}
