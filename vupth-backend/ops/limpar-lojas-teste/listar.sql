-- limpar-lojas-de-teste-producao (DEC-162), passo (b): SO LEITURA. Uso: psql "$DATABASE_URL" -f ops/limpar-lojas-teste/listar.sql
\set ON_ERROR_STOP on
BEGIN READ ONLY;
\ir alvo.sql
\echo '== lojas no alvo (id, nome, profissionais, atendimentos, pagos) =='
SELECT e.id, e.name,
       (SELECT count(*) FROM professional p WHERE p.establishment_id = e.id) AS profissionais,
       (SELECT count(*) FROM attendance a WHERE a.establishment_id = e.id) AS atendimentos,
       (SELECT count(*) FROM attendance a WHERE a.establishment_id = e.id AND a.payment_status = 'PAID') AS pagos
FROM alvo_est e ORDER BY e.name;
\echo '== totais: no alvo x todas as lojas (pare se o alvo passar de 10% fora dos padroes) =='
SELECT (SELECT count(*) FROM alvo_est) AS lojas_no_alvo, (SELECT count(*) FROM establishment) AS lojas_total,
       (SELECT count(*) FROM alvo_prof) AS profissionais, (SELECT count(*) FROM alvo_att) AS atendimentos;
ROLLBACK;
