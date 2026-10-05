<!--
Modelo de PR do vupth-backend (pedido do Andre, 18/09: "no client o titulo e a
descricao ja vem preenchidos; quero o mesmo no backend e no partner").

O GitHub preenche a descricao com este arquivo e, quando a branch tem UM commit,
usa a mensagem dele como titulo. Por isso o titulo aqui e' so uma regra.

Apague os comentarios e as linhas que nao usar.
-->

## O que muda

<!-- Uma frase: o que a API passa a fazer depois deste PR. Ex.:
     GET /establishment devolve locationStatus no endereco da loja. -->

## Por que

<!-- O sintoma, o pedido ou a decisao que originou. Cite o item e a fonte:
     "BAK geocodificacao-silenciosa, Partner S25", "DEC-6 no vupth-docs". -->

## Contrato

<!-- Endpoint, campo ou codigo de erro novo/alterado, e quem consome
     (Client, Partner). Quebra quem ainda le o formato antigo? Escreva "nenhum"
     se nada muda para os fronts. -->

## Banco e configuracao

<!-- Changeset Liquibase novo (src/main/resources/liquibase), property ou variavel de ambiente nova
     (Render/Railway) e o valor em cada ambiente. "nenhum" se nao houver. -->

## Checagens

- [ ] `mvn verify` passa (testes + catraca do JaCoCo)
- [ ] Newman local no Docker verde; collection em `vupth-backend/postman` atualizada junto com o endpoint
- [ ] Teste novo ou alterado junto com o codigo
- [ ] Registrado no [vupth-docs](https://vupth-docs.pages.dev/backend.html) — o repositorio nao guarda documentacao
- [ ] Migration roda sobre o banco de homolog sem perder dado (coluna nova com default ou nula)
