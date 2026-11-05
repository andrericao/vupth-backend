-- limpar-lojas-de-teste-producao, criterio de 05/11 (Andre: ficam so 5 lojas). CONSOLE SQL DO RAILWAY, SO LEITURA.
-- Consulta 1A: as lojas que FICAM. Tem de voltar EXATAMENTE 5 linhas; outro numero = PARE.
SELECT e.id, e.name, e.created_at, e.current_open_status AS aberta_ou_fechada,
       (SELECT count(*) FROM professional p WHERE p.establishment_id = e.id) AS profissionais,
       (SELECT count(*) FROM attendance a WHERE a.establishment_id = e.id) AS atendimentos
FROM establishment e
WHERE lower(trim(e.name)) IN ('gueibinails', 'barbearia do allan', 'nath sempre bela', 'alexia cortes', 'rede própria')
ORDER BY e.name;

-- Consulta 1B: o que SAIRIA e as travas. Rode depois da 1A, separada.
WITH keep AS (SELECT id FROM establishment WHERE lower(trim(name)) IN ('gueibinails', 'barbearia do allan', 'nath sempre bela', 'alexia cortes', 'rede própria')),
alvo_est AS (SELECT id FROM establishment WHERE id NOT IN (SELECT id FROM keep)),
conta_alvo AS (
  SELECT u.id FROM base_user u
  WHERE u.deleted_at IS NULL
    AND EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = u.id AND r.is_active AND r.role_type IN ('OWNER', 'PROFESSIONAL'))
    AND NOT EXISTS (SELECT 1 FROM establishment e WHERE e.owner_id = u.id AND e.id IN (SELECT id FROM keep))
    AND NOT EXISTS (SELECT 1 FROM professional p WHERE p.id = u.id AND p.establishment_id IN (SELECT id FROM keep))
    AND NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = u.id AND r.role_type = 'ADMIN')
    AND lower(u.email) <> 'andrericao49@gmail.com'),
conta_sai AS (SELECT id FROM conta_alvo c WHERE NOT EXISTS (SELECT 1 FROM attendance a WHERE a.client_id = c.id)),
alvo_prof AS (SELECT id FROM professional
              WHERE establishment_id IN (SELECT id FROM alvo_est) OR (establishment_id IS NULL AND id IN (SELECT id FROM conta_alvo))),
alvo_att AS (SELECT id, establishment_id, payment_status FROM attendance
             WHERE establishment_id IN (SELECT id FROM alvo_est) OR professional_id IN (SELECT id FROM alvo_prof))
SELECT
  (SELECT count(*) FROM establishment)                                   AS lojas_hoje,
  (SELECT count(*) FROM keep)                                            AS lojas_que_ficam,
  (SELECT count(*) FROM alvo_est)                                        AS lojas_que_saem,
  (SELECT count(*) FROM alvo_prof)                                       AS perfis_de_profissional_que_saem,
  (SELECT count(*) FROM alvo_att)                                        AS atendimentos_que_saem,
  (SELECT count(*) FROM alvo_att WHERE payment_status = 'PAID')          AS atendimentos_pagos_que_saem,
  (SELECT count(*) FROM referral_credit WHERE attendance_id IN (SELECT id FROM alvo_att)) AS premios_de_indicacao_que_saem,
  (SELECT count(*) FROM coupon_credit   WHERE attendance_id IN (SELECT id FROM alvo_att)) AS creditos_de_cupom_que_saem,
  (SELECT count(*) FROM conta_sai)                                       AS contas_que_saem,
  (SELECT count(*) FROM conta_alvo) - (SELECT count(*) FROM conta_sai)   AS contas_que_so_perdem_papel,
  -- TRAVAS: todas tem de ser 0; diferente de 0 = PARE
  (SELECT count(*) FROM conta_sai c WHERE EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = c.id AND r.role_type = 'ADMIN')) AS trava_admin,
  (SELECT count(*) FROM conta_sai c JOIN base_user u ON u.id = c.id WHERE lower(u.email) = 'andrericao49@gmail.com') AS trava_andre,
  (SELECT count(*) FROM conta_sai c WHERE EXISTS (SELECT 1 FROM attendance a WHERE a.client_id = c.id)) AS trava_cliente,
  (SELECT count(*) FROM alvo_att WHERE establishment_id IN (SELECT id FROM keep))   AS trava_atendimento_das_5,
  (SELECT count(*) FROM attendance WHERE establishment_id IN (SELECT id FROM keep)
       AND professional_id IN (SELECT id FROM alvo_prof))                          AS trava_profissional_que_sai_nas_5,
  (SELECT count(*) FROM cut_request WHERE establishment_id IN (SELECT id FROM keep)
       AND professional_id IN (SELECT id FROM alvo_prof))                          AS trava_pedido_nas_5;

-- Consulta 1C: as lojas que SAEM, uma por linha, com aberta ou fechada e quantos profissionais (para conferir as abertas sem ninguem).
SELECT e.name, e.created_at, e.current_open_status AS aberta_ou_fechada,
       (SELECT count(*) FROM professional p WHERE p.establishment_id = e.id) AS profissionais,
       (SELECT count(*) FROM attendance a WHERE a.establishment_id = e.id) AS atendimentos
FROM establishment e
WHERE lower(trim(e.name)) NOT IN ('gueibinails', 'barbearia do allan', 'nath sempre bela', 'alexia cortes', 'rede própria')
ORDER BY e.current_open_status, profissionais, e.name;

-- Consulta 1D: o que o Liquibase semeou (as sementes sao "!prod"; na producao tem de voltar 0 linhas).
SELECT id, author, dateexecuted, exectype FROM databasechangelog
WHERE author = 'vupth-seed' OR id LIKE 'seed-%' OR id = '040-admin-staging'
ORDER BY dateexecuted;
