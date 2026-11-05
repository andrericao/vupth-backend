-- 3) GRAVAR, CONSOLE SQL DO RAILWAY: so depois da listagem (1A = 5 lojas, travas = 0) e do ensaio OK. Backup de menos de 24 h antes.
-- Uma transacao so: qualquer erro desfaz tudo. Depois, rode a 1A de novo (5 lojas) e a 1B (lojas_que_saem = 0, contas_que_saem = 0).
-- Criterio de 05/11 (Andre): ficam so as lojas gueibinails, barbearia do Allan, Nath sempre bela, alexia cortes e Rede Propria (sem
-- diferenciar maiusculas) e os profissionais delas; o resto sai com as linhas dependentes.
-- Contas de dono e de profissional fora dessas 5 (muda a DEC-162): perdem os papeis OWNER/PROFESSIONAL e os perfis; as que nunca foram
-- cliente de um atendimento SAEM, anonimizadas como o "excluir conta" do app (DeleteAccountUseCase / scripts/reaplicar-exclusoes.sql).
-- Nunca: conta ADMIN, a conta do Andre, conta com atendimento como cliente (essa so perde o papel).
-- Ordem das chaves igual ao ops/limpar-lojas-teste/apagar.sql (psql), em SQL puro: cole o arquivo inteiro numa consulta so.
DO $$
DECLARE
  lojas0 bigint; prof0 bigint; att0 bigint;
BEGIN
  CREATE TEMP TABLE keep_est ON COMMIT DROP AS
    SELECT id FROM establishment WHERE lower(trim(name)) IN ('gueibinails', 'barbearia do allan', 'nath sempre bela', 'alexia cortes', 'rede própria');
  IF (SELECT count(*) FROM keep_est) <> 5 THEN
    RAISE EXCEPTION 'PAREI: achei % lojas para ficar (esperado 5). Nada foi apagado.', (SELECT count(*) FROM keep_est);
  END IF;
  -- contas de dono/profissional fora das 5 (nunca ADMIN nem o Andre)
  CREATE TEMP TABLE conta_alvo ON COMMIT DROP AS
    SELECT u.id FROM base_user u
    WHERE u.deleted_at IS NULL
      AND EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = u.id AND r.is_active AND r.role_type IN ('OWNER', 'PROFESSIONAL'))
      AND NOT EXISTS (SELECT 1 FROM establishment e WHERE e.owner_id = u.id AND e.id IN (SELECT id FROM keep_est))
      AND NOT EXISTS (SELECT 1 FROM professional p WHERE p.id = u.id AND p.establishment_id IN (SELECT id FROM keep_est))
      AND NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.base_user_id = u.id AND r.role_type = 'ADMIN')
      AND lower(u.email) <> 'andrericao49@gmail.com';
  -- das contas acima, saem as que nunca foram cliente de um atendimento; as outras so perdem o papel
  CREATE TEMP TABLE conta_sai ON COMMIT DROP AS
    SELECT id FROM conta_alvo c WHERE NOT EXISTS (SELECT 1 FROM attendance a WHERE a.client_id = c.id);
  CREATE TEMP TABLE alvo_est ON COMMIT DROP AS
    SELECT id FROM establishment WHERE id NOT IN (SELECT id FROM keep_est);
  CREATE TEMP TABLE alvo_prof ON COMMIT DROP AS
    SELECT id FROM professional
    WHERE establishment_id IN (SELECT id FROM alvo_est) OR (establishment_id IS NULL AND id IN (SELECT id FROM conta_alvo));
  CREATE TEMP TABLE alvo_att ON COMMIT DROP AS
    SELECT id FROM attendance
    WHERE establishment_id IN (SELECT id FROM alvo_est) OR professional_id IN (SELECT id FROM alvo_prof);
  -- travas: nada das 5 lojas pode ser tocado
  IF EXISTS (SELECT 1 FROM attendance WHERE id IN (SELECT id FROM alvo_att) AND establishment_id IN (SELECT id FROM keep_est))
     OR EXISTS (SELECT 1 FROM cut_request WHERE establishment_id IN (SELECT id FROM keep_est) AND professional_id IN (SELECT id FROM alvo_prof))
     OR EXISTS (SELECT 1 FROM swap_offer WHERE establishment_id IN (SELECT id FROM keep_est) AND professional_id IN (SELECT id FROM alvo_prof))
     OR EXISTS (SELECT 1 FROM fit_in_request WHERE establishment_id IN (SELECT id FROM keep_est) AND professional_id IN (SELECT id FROM alvo_prof)) THEN
    RAISE EXCEPTION 'PAREI: uma das 5 lojas tem atendimento ou pedido com profissional que sai. Nada foi apagado.';
  END IF;
  SELECT count(*) INTO lojas0 FROM establishment;
  SELECT count(*) INTO prof0 FROM professional;
  SELECT count(*) INTO att0 FROM attendance;

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
  DELETE FROM test_run_store             WHERE establishment_id IN (SELECT id FROM alvo_est);
  DELETE FROM test_run                   WHERE establishment_id IN (SELECT id FROM alvo_est);
  DELETE FROM establishment WHERE id IN (SELECT id FROM alvo_est);

  -- contas: perdem os papeis de dono e profissional e o perfil de dono (as lojas e o perfil de profissional ja sairam acima)
  DELETE FROM owner WHERE id IN (SELECT id FROM conta_alvo)
    AND NOT EXISTS (SELECT 1 FROM establishment e WHERE e.owner_id = owner.id);
  DELETE FROM user_roles WHERE base_user_id IN (SELECT id FROM conta_alvo) AND role_type IN ('OWNER', 'PROFESSIONAL');
  -- as que saem: o mesmo desmonte do "excluir conta" (ledger da LGPD, para a exclusao sobreviver a um restore do backup)
  INSERT INTO account_deletion_ledger (id_hash, deleted_at)
    SELECT encode(sha256(id::text::bytea), 'hex'), now() FROM conta_sai
    ON CONFLICT (id_hash) DO NOTHING;
  DELETE FROM device_token WHERE user_id IN (SELECT id FROM conta_sai);
  DELETE FROM refresh_token WHERE base_user_id IN (SELECT id FROM conta_sai);
  DELETE FROM password_reset_code WHERE user_id IN (SELECT id FROM conta_sai);
  DELETE FROM account_deletion_token WHERE user_id IN (SELECT id FROM conta_sai);
  DELETE FROM user_roles WHERE base_user_id IN (SELECT id FROM conta_sai);
  CREATE TEMP TABLE sai_addresses ON COMMIT DROP AS
    SELECT address_id FROM user_addresses WHERE user_id IN (SELECT id FROM conta_sai);
  DELETE FROM user_addresses WHERE user_id IN (SELECT id FROM conta_sai);
  DELETE FROM addresses WHERE id IN (SELECT address_id FROM sai_addresses);
  UPDATE base_user u SET
      first_name = 'Usuario', last_name = 'removido', nickname = NULL,
      email = 'deleted-' || u.id || '@deleted.invalid', phone = NULL, birth_date = NULL, gender = NULL,
      profile_image_url = NULL, password = md5(random()::text) || md5(random()::text),
      pix_key_type = NULL, pix_key = NULL, pix_holder_name = NULL, cpf = NULL,
      is_active = FALSE, deleted_at = now()
  WHERE u.id IN (SELECT id FROM conta_sai);

  IF (SELECT count(*) FROM establishment) <> 5 THEN
    RAISE EXCEPTION 'PAREI: sobraram % lojas (esperado 5). Nada foi gravado.', (SELECT count(*) FROM establishment);
  END IF;
END $$;
