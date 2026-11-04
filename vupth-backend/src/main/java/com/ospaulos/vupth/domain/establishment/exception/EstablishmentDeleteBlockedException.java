package com.ospaulos.vupth.domain.establishment.exception;

import java.util.List;
import java.util.UUID;

/**
 * excluir-loja-com-profissionais (DEC-173): a loja so e apagada sem profissional alem do dono, sem fila ativa e sem agendamento
 * futuro. Vira 409 com o codigo do primeiro impedimento (ESTABLISHMENT_HAS_PROFESSIONALS, ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES ou
 * ESTABLISHMENT_HAS_FUTURE_BOOKINGS) e a lista de TODOS em {@code blockers}, para a tela mostrar o que falta e oferecer, por
 * profissional, "Remover da loja".
 */
public class EstablishmentDeleteBlockedException extends RuntimeException {

    public static final String PROFESSIONALS = "ESTABLISHMENT_HAS_PROFESSIONALS";
    public static final String ACTIVE_ATTENDANCES = "ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES";
    public static final String FUTURE_BOOKINGS = "ESTABLISHMENT_HAS_FUTURE_BOOKINGS";

    /** Um item que impede: o profissional ({@code id} e {@code name}) ou o atendimento ({@code id} e o cliente em {@code name}). */
    public record Item(UUID id, String name) {}

    /** Um tipo de impedimento, com a contagem e os itens. */
    public record Blocker(String code, int count, List<Item> items) {}

    private final List<Blocker> blockers;

    public EstablishmentDeleteBlockedException(UUID establishmentId, List<Blocker> blockers) {
        super("A loja " + establishmentId + " nao pode ser apagada: " + blockers.stream().map(Blocker::code).toList());
        this.blockers = List.copyOf(blockers);
    }

    public List<Blocker> getBlockers() {
        return blockers;
    }

    /** O codigo do primeiro impedimento (a ordem: profissionais, fila ativa, agendamento futuro). */
    public String getCode() {
        return blockers.get(0).code();
    }
}
