-- exclusao-apos-restaurar (LGPD): reaplica, depois de restaurar um backup, a exclusao de quem apagou a conta
-- depois dele. Espera a tabela temporaria ledger_import(id_hash, deleted_at) ja carregada (o
-- reaplicar-exclusoes.sh carrega do ledger/account-deletions.csv do bucket). Faz o mesmo que o
-- DeleteAccountUseCase: anonimiza o titular, apaga o que e so dele e desliga loja e perfil profissional.
-- Idempotente: quem ja esta anonimizado so entra no ledger.

-- Backup anterior a migration 036: a tabela ainda nao existe (o Liquibase a marca como feita depois).
CREATE TABLE IF NOT EXISTS account_deletion_ledger (
    id_hash VARCHAR(64) NOT NULL CONSTRAINT pk_account_deletion_ledger PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL
);

INSERT INTO account_deletion_ledger (id_hash, deleted_at)
SELECT id_hash, deleted_at FROM ledger_import
ON CONFLICT (id_hash) DO NOTHING;

CREATE TEMP TABLE reapply_users ON COMMIT DROP AS
SELECT u.id, l.deleted_at
FROM base_user u
JOIN ledger_import l ON l.id_hash = encode(sha256(u.id::text::bytea), 'hex')
WHERE u.deleted_at IS NULL;

DELETE FROM device_token WHERE user_id IN (SELECT id FROM reapply_users);
DELETE FROM refresh_token WHERE base_user_id IN (SELECT id FROM reapply_users);
DELETE FROM password_reset_code WHERE user_id IN (SELECT id FROM reapply_users);
DELETE FROM account_deletion_token WHERE user_id IN (SELECT id FROM reapply_users);
DELETE FROM user_roles WHERE base_user_id IN (SELECT id FROM reapply_users);

CREATE TEMP TABLE reapply_addresses ON COMMIT DROP AS
SELECT address_id FROM user_addresses WHERE user_id IN (SELECT id FROM reapply_users);
DELETE FROM user_addresses WHERE user_id IN (SELECT id FROM reapply_users);
DELETE FROM addresses WHERE id IN (SELECT address_id FROM reapply_addresses);

-- A loja nao e dado pessoal: fica, desligada do dono e fora da vitrine.
UPDATE establishment SET owner_id = NULL, status = 'INACTIVE', current_open_status = 'CLOSED'
WHERE owner_id IN (SELECT id FROM reapply_users);

-- O profissional sai da loja e para de aparecer; servicos e atendimentos ficam (historico dos outros).
UPDATE professional SET establishment_id = NULL, specialty = NULL, is_available = FALSE,
       accepting_new = FALSE, work_status = 'OFF'
WHERE id IN (SELECT id FROM reapply_users);

UPDATE base_user u SET
    first_name = 'Usuario', last_name = 'removido', nickname = NULL,
    email = 'deleted-' || u.id || '@deleted.invalid', phone = NULL, birth_date = NULL, gender = NULL,
    profile_image_url = NULL, password = md5(random()::text) || md5(random()::text),
    is_active = FALSE, deleted_at = r.deleted_at
FROM reapply_users r
WHERE u.id = r.id;

SELECT count(*) AS contas_reapagadas FROM reapply_users;
