package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.payment.exception.AccountIncompleteException;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * virada-do-gateway (DEC-163/164): so vale com o Asaas REAL ligado e o prazo de 7 dias do aviso vencido. Antes disso (sem gateway,
 * sandbox ou dentro do prazo) o profissional abre sem CPF, como sempre.
 */
@Service
@RequiredArgsConstructor
public class AccountCompleteGate {

    private final PaymentGatewayService gateway;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public void assertComplete(UUID professionalUserId) {
        if (!gateway.cpfRequired()) {
            return;
        }
        boolean hasCpf = users.findById(professionalUserId)
                .map(u -> u.getCpf() != null && !u.getCpf().isBlank()).orElse(false);
        if (!hasCpf) {
            throw new AccountIncompleteException();
        }
    }
}
