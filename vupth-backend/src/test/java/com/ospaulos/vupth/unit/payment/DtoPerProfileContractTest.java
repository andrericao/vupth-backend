package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.api.attendance.dto.response.ClientAttendanceResponse;
import com.ospaulos.vupth.api.attendance.dto.response.StoreAttendanceResponse;
import com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relatorios-por-perfil (DEC-94): um DTO por perfil, e este teste falha se o campo de outro perfil entrar. Cobre o
 * DTO inteiro (nao so o que o endpoint preenche hoje), entao um campo novo no lugar errado quebra aqui.
 */
@DisplayName("Um DTO por perfil: nenhum campo de outro perfil (DEC-94)")
class DtoPerProfileContractTest {

    private static List<String> fields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    @DisplayName("o cliente nunca ve o valor do profissional, as taxas nem a margem do app")
    void clientNeverSeesProfessionalOrAppMoney() {
        assertThat(fields(ClientAttendanceResponse.class))
                .doesNotContain("proReceives", "intermediaryFee", "platformFee", "appProfit", "appShare", "gatewayFee");
        assertThat(fields(PenaltyChargeResponse.class)).doesNotContain("gatewayFee", "appShare", "appProfit");
        assertThat(fields(ClientAttendanceResponse.class)).contains("totalValue", "penalties", "penaltyTotal");
    }

    @Test
    @DisplayName("a loja ve o que recebe e as taxas embutidas, mas nunca a multa, o lucro do app nem dado pessoal do cliente")
    void storeNeverSeesPenaltyOrAppProfit() {
        assertThat(fields(StoreAttendanceResponse.class))
                .doesNotContain("penalties", "penaltyTotal", "rated", "appProfit", "appShare", "gatewayFee",
                        "clientEmail", "clientPhone", "email", "phone");
        assertThat(fields(StoreAttendanceResponse.class)).contains("proReceives", "intermediaryFee", "platformFee");
    }

    @Test
    @DisplayName("sao DTOs diferentes (nunca um so com campos escondidos)")
    void differentTypes() {
        assertThat(ClientAttendanceResponse.class).isNotEqualTo(StoreAttendanceResponse.class);
    }
}
