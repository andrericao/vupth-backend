package com.ospaulos.vupth.domain.address.model;

import com.ospaulos.vupth.domain.address.valueObject.LocationSource;

import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateEstablishmentAddressRequest;
import org.hibernate.annotations.BatchSize;
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
// @BatchSize (B10): EstablishmentAddress.address e LAZY, e a listagem publica le o endereco de
// cada loja para montar a resposta. Sem o lote, cada proxy vira uma consulta — N+1 por tras de
// getMainAddress(). No nivel da CLASSE porque quem se resolve em lote aqui e o proxy da entidade,
// nao uma colecao.
@BatchSize(size = 100)
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

    /**
     * O geocoder tentou e nao achou o endereco (ou caiu). Volta a {@code false} quando a coordenada
     * chega ou quando o dono corrige o endereco. Ver {@code LocationStatus}.
     */
    @Column(name = "geocoding_failed", nullable = false)
    private boolean geocodingFailed;

    /** De onde veio a coordenada (origem-da-coordenada, Partner S39); nulo = sem coordenada ou desconhecida. */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_source", length = 16)
    private LocationSource locationSource;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Correcao do endereco do estabelecimento (backlog #3). {@code null} = nao mexe.
     *
     * <p>Diferenca proposital em relacao ao {@link #update(UpdateAddressRequest)}: nos dois campos
     * que a base aceita vazios ({@code complement} e {@code reference}), <b>string em branco
     * LIMPA</b>. Sao justamente os que o dono digita errado e quer apagar — tratar branco como "nao
     * mexe" nos dois deixaria um complemento errado preso para sempre. Nos campos obrigatorios,
     * branco continua sendo ignorado (a coluna e {@code NOT NULL}).</p>
     */
    public void update(UpdateEstablishmentAddressRequest request) {
        if (request.street() != null && !request.street().isBlank()) {
            this.street = request.street();
        }
        if (request.number() != null && !request.number().isBlank()) {
            this.number = request.number();
        }
        if (request.complement() != null) {
            this.complement = request.complement().isBlank() ? null : request.complement();
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
        if (request.reference() != null) {
            this.reference = request.reference().isBlank() ? null : request.reference();
        }
        // A coordenada so muda quando vem explicita no corpo — editar o texto nao move o ponto.
        if (request.latitude() != null) {
            this.latitude = request.latitude();
        }
        if (request.longitude() != null) {
            this.longitude = request.longitude();
        }
        markGpsIfCoordinatesSent(request.latitude(), request.longitude());
    }

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
        markGpsIfCoordinatesSent(request.latitude(), request.longitude());
    }

    /** A coordenada veio no corpo do pedido = do aparelho do dono. */
    public void markGpsIfCoordinatesSent(Double latitude, Double longitude) {
        if (latitude != null && longitude != null) {
            this.locationSource = LocationSource.GPS;
        }
    }
}
