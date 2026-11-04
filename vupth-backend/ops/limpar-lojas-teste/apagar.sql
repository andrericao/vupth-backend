-- limpar-lojas-de-teste-producao (DEC-162), passo (d): apaga o alvo e as linhas dependentes numa transacao so.
-- Por padrao DESFAZ no fim (ensaio). So grava com:  psql "$DATABASE_URL" -v aplicar=1 -f ops/limpar-lojas-teste/apagar.sql
-- Antes: backup cifrado de menos de 24 h e a listagem (listar.sql) aprovada pelo Andre. As contas (base_user) ficam: so saem os
-- perfis de profissional dessas lojas; a pessoa segue podendo entrar como cliente.
\set ON_ERROR_STOP on
\if :{?aplicar}
\else
  \set aplicar 0
\endif
BEGIN;
\ir alvo.sql
\echo '== antes =='
SELECT (SELECT count(*) FROM establishment) AS lojas, (SELECT count(*) FROM professional) AS profissionais,
       (SELECT count(*) FROM attendance) AS atendimentos, (SELECT count(*) FROM alvo_est) AS lojas_no_alvo;

-- dependentes dos atendimentos
DELETE FROM attendance_service_item   WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM attendance_locked_intervals WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM attendance_rating         WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM queue_swap_request        WHERE target_attendance_id IN (SELECT id FROM alvo_att) OR proposer_attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM app_ledger                WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM client_absence            WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM coupon_credit             WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM referral_credit           WHERE attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM professional_debt         WHERE attendance_id IN (SELECT id FROM alvo_att) OR professional_id IN (SELECT id FROM alvo_prof);
-- a multa nascida num atendimento do alvo sai; a de outro lugar que so foi cobrada aqui volta a ficar pendente
DELETE FROM client_penalty_event      WHERE attendance_id IN (SELECT id FROM alvo_att);
UPDATE client_penalty_event SET charged_attendance_id = NULL WHERE charged_attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM cut_request    WHERE establishment_id IN (SELECT id FROM alvo_est) OR professional_id IN (SELECT id FROM alvo_prof)
                              OR resulting_attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM swap_offer     WHERE establishment_id IN (SELECT id FROM alvo_est) OR professional_id IN (SELECT id FROM alvo_prof)
                              OR resulting_attendance_id IN (SELECT id FROM alvo_att) OR requester_attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM fit_in_request WHERE establishment_id IN (SELECT id FROM alvo_est) OR professional_id IN (SELECT id FROM alvo_prof)
                              OR resulting_attendance_id IN (SELECT id FROM alvo_att);
DELETE FROM attendance WHERE id IN (SELECT id FROM alvo_att);

-- dependentes dos profissionais
DELETE FROM service_item              WHERE professional_id IN (SELECT id FROM alvo_prof);
DELETE FROM professional_intervals    WHERE professional_id IN (SELECT id FROM alvo_prof);
DELETE FROM professional_weekly_hours WHERE professional_id IN (SELECT id FROM alvo_prof);
DELETE FROM professional_date_hours   WHERE professional_id IN (SELECT id FROM alvo_prof);
DELETE FROM professional_shift        WHERE professional_id IN (SELECT id FROM alvo_prof) OR establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM affiliation_request       WHERE professional_id IN (SELECT id FROM alvo_prof) OR establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM ownership_transfer        WHERE establishment_id IN (SELECT id FROM alvo_est) OR to_professional_id IN (SELECT id FROM alvo_prof);
DELETE FROM professional WHERE id IN (SELECT id FROM alvo_prof);

-- dependentes da loja
DELETE FROM establishment_services     WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM establishment_addresses    WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM establishment_weekly_hours WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM establishment_date_hours   WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM email_invitation           WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM test_run                   WHERE establishment_id IN (SELECT id FROM alvo_est);
DELETE FROM establishment WHERE id IN (SELECT id FROM alvo_est);

\echo '== depois =='
SELECT (SELECT count(*) FROM establishment) AS lojas, (SELECT count(*) FROM professional) AS profissionais,
       (SELECT count(*) FROM attendance) AS atendimentos,
       (SELECT count(*) FROM establishment WHERE id IN (SELECT id FROM alvo_est)) AS lojas_no_alvo_restantes;
\if :aplicar
  COMMIT;
  \echo 'GRAVADO.'
\else
  ROLLBACK;
  \echo 'ENSAIO: nada foi gravado (rode com -v aplicar=1 para gravar).'
\endif
