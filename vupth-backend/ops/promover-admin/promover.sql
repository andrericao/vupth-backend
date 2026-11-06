-- admin-da-producao (05/11): da o papel ADMIN a uma conta que JA EXISTE (cadastrada pelo app como pessoa). Nao cria conta nem senha.
-- Na producao nenhuma migracao faz isto (a 040-admin-staging tem context "!prod"); o primeiro admin nasce por este script.
-- Por padrao DESFAZ no fim (ensaio). So grava com -v aplicar=1. O e-mail vai por variavel, nunca colado no chat:
--   psql "$DATABASE_URL" -v email="$ADMIN_EMAIL" -f ops/promover-admin/promover.sql               (ensaio)
--   psql "$DATABASE_URL" -v email="$ADMIN_EMAIL" -v aplicar=1 -f ops/promover-admin/promover.sql  (grava)
-- Sem linha em admin_permission a conta e "dona" do painel (pode tudo). Depois de gravar, sair e entrar de novo no Painel:
-- o token antigo nao traz o papel. No primeiro login o painel pede o 2o fator (ADMIN_TOTP_REQUIRED, ligado na producao).
\set ON_ERROR_STOP on
\if :{?email}
\else
  \echo 'Falta o e-mail: -v email="$ADMIN_EMAIL"'
  \quit
\endif
\if :{?aplicar}
\else
  \set aplicar 0
\endif
BEGIN;
\echo '== a conta (tem que vir UMA linha; zero = o e-mail nao esta cadastrado) =='
SELECT u.id, u.first_name, u.created_at,
       (SELECT string_agg(r.role_type, ',' ORDER BY r.role_type) FROM user_roles r WHERE r.base_user_id = u.id AND r.is_active) AS papeis
FROM base_user u WHERE lower(u.email) = lower(:'email');

\echo '== quantas contas ja sao ADMIN (antes) =='
SELECT count(DISTINCT base_user_id) AS admins FROM user_roles WHERE role_type = 'ADMIN' AND is_active;

-- reativa o papel, se ja existia desligado; senao cria
UPDATE user_roles r SET is_active = true
FROM base_user u
WHERE r.base_user_id = u.id AND r.role_type = 'ADMIN' AND NOT r.is_active AND lower(u.email) = lower(:'email');
INSERT INTO user_roles (id, base_user_id, role_type, is_active)
SELECT gen_random_uuid(), u.id, 'ADMIN', true
FROM base_user u
WHERE lower(u.email) = lower(:'email')
  AND NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = u.id AND r.role_type = 'ADMIN');

\echo '== depois: a conta, os papeis e se ela e dona do painel (sem linha de permissao) =='
SELECT u.id,
       (SELECT string_agg(r.role_type, ',' ORDER BY r.role_type) FROM user_roles r WHERE r.base_user_id = u.id AND r.is_active) AS papeis,
       NOT EXISTS (SELECT 1 FROM admin_permission p WHERE p.user_id = u.id) AS dona_do_painel,
       EXISTS (SELECT 1 FROM admin_totp t WHERE t.user_id = u.id AND t.enabled) AS segundo_fator_cadastrado
FROM base_user u WHERE lower(u.email) = lower(:'email');
\if :aplicar
  COMMIT;
  \echo 'GRAVADO.'
\else
  ROLLBACK;
  \echo 'ENSAIO: nada foi gravado (rode com -v aplicar=1 para gravar).'
\endif
