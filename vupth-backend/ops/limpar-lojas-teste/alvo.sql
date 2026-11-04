-- limpar-lojas-de-teste-producao (DEC-162): o ALVO, com o criterio que o Andre autorizou em 03/11. Incluido por listar.sql e apagar.sql.
-- (1) nome casa EXATAMENTE ^Loja (C2|C13|S1) [0-9]+$  (2) nome igual a "Barbearia Premium" ou "Barbearia do Ricardo".
DROP TABLE IF EXISTS alvo_est, alvo_prof, alvo_att;
CREATE TEMP TABLE alvo_est AS
  SELECT id, name FROM establishment
  WHERE name ~ '^Loja (C2|C13|S1) [0-9]+$' OR name IN ('Barbearia Premium', 'Barbearia do Ricardo');
CREATE TEMP TABLE alvo_prof AS
  SELECT p.id FROM professional p WHERE p.establishment_id IN (SELECT id FROM alvo_est);
CREATE TEMP TABLE alvo_att AS
  SELECT a.id FROM attendance a
  WHERE a.establishment_id IN (SELECT id FROM alvo_est) OR a.professional_id IN (SELECT id FROM alvo_prof);
