# Proposta — Entrar com Google e Apple

Proposta do time do front-client (repo `Almar-cyber/vupth-frontend`) para avaliação. **Nada foi implementado**: o objetivo é você decidir se vale incluir antes de alguém escrever código de autenticação.

Escrito em 2026-08-12, a partir do estado real do backend na `dev`.

---

## Por que estamos trazendo isso

O app do cliente **já tem os dois botões na tela de login**, no topo, acima do formulário. Ao toque eles respondem *"Entrar com Google ainda não está disponível. Use seu e-mail e senha."*

Não falham em silêncio, mas ocupam o lugar de maior destaque da primeira tela prometendo um caminho que não existe. Duas saídas: implementar, ou remover. Esta proposta existe para você decidir qual.

### Uma restrição de loja que muda a conta

A Apple exige **Sign in with Apple** em qualquer app que ofereça login social de terceiros. Implementar só o Google **reprova na App Store**. Então não é "começar pelo Google": é os dois ou nenhum.

---

## O que torna isso mais barato do que parece

**Não precisa de dependência nova.** O `pom.xml` já traz `spring-boot-starter-oauth2-resource-server`, e o `SecurityConfig` já usa `NimbusJwtDecoder`. A mesma biblioteca que valida o nosso JWT valida o id-token do Google e da Apple — os dois publicam JWKS:

| Provider | JWKS | Issuer |
|---|---|---|
| Google | `https://www.googleapis.com/oauth2/v3/certs` | `https://accounts.google.com` |
| Apple | `https://appleid.apple.com/auth/keys` | `https://appleid.apple.com` |

```java
NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
        .build();
// + validators: issuer, audience (client id), expiração
```

Isso importa porque **validação de id-token escrita à mão é onde moram os bugs de bypass**. Usando o decoder que já está no projeto, a verificação criptográfica, o cache de chaves e a rotação vêm de graça e testados.

---

## Contrato proposto

Um endpoint público, espelhando a resposta do `/auth/login` para o front não precisar de tratamento separado:

```
POST /auth/social
{
  "provider": "GOOGLE" | "APPLE",
  "id_token": "<jwt do provider>"
}

200 → LoginResponse { tokens, user }   (idêntico ao /auth/login)
401   INVALID_SOCIAL_TOKEN      assinatura, issuer, audience ou expiração inválidos
409   EMAIL_ALREADY_REGISTERED  se a política for recusar vínculo (ver decisão 1)
422   SOCIAL_EMAIL_NOT_VERIFIED e-mail não verificado no provider
```

O fluxo do app é: SDK do provider devolve o id-token → app manda para cá → backend valida e emite **os nossos** tokens. O app nunca recebe token do Google/Apple para uso próprio, e o resto da API não muda.

### Validações obrigatórias

1. assinatura contra o JWKS do provider
2. `iss` exatamente o do provider
3. `aud` igual a um dos nossos client ids — **Google tem um por plataforma** (Android, iOS, web), então é lista, não valor único
4. `exp` não vencido
5. `email_verified == true`

A 3 e a 5 são as que costumam ser esquecidas. Sem a 3, um id-token emitido para **qualquer outro app** do Google é aceito — é bypass de autenticação, não detalhe.

---

## Três decisões que são suas

### 1. E-mail social que já existe com senha

Alguém se cadastrou com `maria@gmail.com` + senha e depois toca em "Entrar com Google" com o mesmo e-mail.

- **Vincular** (recomendação): com `email_verified == true`, o Google já provou a posse do e-mail. É o comportamento que o usuário espera e evita conta duplicada.
- **Recusar** com `409`: mais conservador, mas gera "já existe conta com esse e-mail" sem caminho de saída óbvio.

### 2. Login social cria conta?

Se sim, o primeiro toque em "Entrar com Google" de quem nunca se cadastrou cria a conta com `ROLE_CLIENT`. Se não, precisa de fluxo de cadastro antes — e aí o botão na tela de login mente de novo.

### 3. Senha é `NOT NULL` hoje

`base_user.password` é `VARCHAR(72) NOT NULL` (changeset `00-foundation/002-create-base_user.yaml`). Usuário só-social não cabe no schema atual. Duas saídas:

- **migration tornando a coluna nullable** (limpo, mas exige conferir todo caminho que lê senha)
- **gravar um hash inutilizável** (rápido, mas cria usuário que "tem senha" e nunca poderá usá-la — e o esqueci-senha passaria a oferecer redefinição para conta social)

Preferimos a migration, mas é decisão de quem mantém o domínio.

---

## Persistência sugerida

Um usuário pode ter Google **e** Apple. Uma tabela em vez de colunas no `base_user`:

```
user_social_identity
  id                UUID PK
  base_user_id      UUID FK → base_user
  provider          VARCHAR(16)   GOOGLE | APPLE
  provider_user_id  VARCHAR(255)  o `sub` do id-token
  created_at        TIMESTAMPTZ
  UNIQUE (provider, provider_user_id)
```

O `UNIQUE` é o que impede duas contas nossas apontando para a mesma identidade social. Changeset seria `20-auth/024-create-user_social_identity.yaml`.

**Guardar o `sub`, não o e-mail, como chave.** O e-mail da Apple pode ser um relay (`@privaterelay.appleid.com`) e o usuário pode desativá-lo; o `sub` é estável.

---

## O que não é código, e trava antes

| Item | Onde |
|---|---|
| Client IDs do Google (um por plataforma) | Google Cloud Console |
| Service ID + chave privada da Apple | Conta de desenvolvedor Apple — **paga** |
| `Sign in with Apple` habilitado no bundle id | Apple Developer |
| Apple só funciona em **build nativa** | não roda no Expo Go nem na web |

Esse último ponto vale destacar: hoje o app é testado por navegador e por Expo Go. O caminho da Apple só é verificável num `eas build`.

---

## O lado do app, se aprovado

`expo-auth-session` para o Google e `expo-apple-authentication` para a Apple, os dois já compatíveis com Expo SDK 52. Os componentes visuais **já existem** no nosso repo — `SocialButton` e as marcas oficiais no `BrandMark` — então o trabalho é o fluxo, não a UI.

Se a resposta for "não vale agora", nós **removemos os dois botões** da tela de login. Os componentes ficam no repositório e não se perdem.

---

## Estimativa honesta

O backend é pequeno: um endpoint, um verificador por provider, uma tabela, um caso de uso de vínculo. O que pesa é a **superfície de segurança** — as cinco validações acima — e a configuração externa, que depende de contas e não de código.

Não implementamos porque autenticação é a parte do sistema em que errar custa mais caro, e porque as três decisões acima mudam o desenho. Se você aprovar o contrato e responder as três, podemos escrever com você revisando — mas o ideal é que a validação do id-token seja escrita por quem mantém o domínio de auth.
