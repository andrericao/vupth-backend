# Teste de regressão (Backend)

A regressão é o `testar.sh` completo (mvn + Newman). O teste de fumaça (a pasta `essencial` do Newman) roda primeiro; se falha, nada mais roda. A fumaça ainda está em desenho (DEC-157).

**Regra (André, 01/11):** todo bug achado entra aqui. O caminho que quebrou vira um teste (Newman ou unitário/integração) **no mesmo commit da correção**, e a linha abaixo diz qual.

**Quarentena:** teste que só passa na repetição sai da suíte, é anotado aqui com o motivo e volta quando for consertado.

| Bug (apelido) | Caminho que quebrou | Teste que o guarda | Commit |
|---|---|---|---|
| push-quase-a-vez-nao-chega | `QueuePushService.notifyNextInLine` marcava `nextNotified` mesmo sem aparelho registrado; o aviso se perdia | teste de unidade do retorno de `notifyUser` e do não-marcar | f090785 |
| estorno-deixava-PAID | depois do estorno o atendimento seguia `PAID` | testes de unidade do estorno e do webhook | ebe2365 |
