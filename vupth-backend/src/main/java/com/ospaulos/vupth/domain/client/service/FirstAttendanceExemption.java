package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * sem-multa-no-primeiro-atendimento (DEC-104, 22/10): o cliente que ainda nao tem nenhum atendimento FINALIZADO
 * nao e multado por atraso, falta nem saida da fila. Atendimento cancelado ou removido nao conta como concluido.
 *
 * <p>{@code vupth.penalty.first-attendance-exempt} (padrao ligado) existe so para a suite de testes: os testes
 * antigos de multa criam clientes novos e precisam da multa; o perfil de teste desliga e os testes da DEC-104 ligam.</p>
 */
@Component
@RequiredArgsConstructor
public class FirstAttendanceExemption {

    private final AttendanceRepository attendanceRepository;

    @Value("${vupth.penalty.first-attendance-exempt:true}")
    private boolean enabled = true;

    /** {@code true} quando o cliente (com conta) nunca concluiu um atendimento. Convidado nao tem multa de qualquer forma. */
    public boolean applies(UUID clientId) {
        return enabled && clientId != null
                && attendanceRepository.countByClient_IdAndStatus(clientId, AttendanceStatus.FINISHED) == 0;
    }
}
