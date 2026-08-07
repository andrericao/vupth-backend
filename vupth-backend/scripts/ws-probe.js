/**
 * VUPTH — prova headless do WebSocket (STOMP) contra o backend REAL.
 *
 * Fecha os 3 buracos da prova de 01/08 (que rodou so no navegador, so no topico
 * publico e so com token valido):
 *   T1  CONNECTED devolve heart-beat negociado (esperado 10000,10000 apos be5bdaa)
 *   T2  ENTREGA no topico PUBLICO  /topic/establishment/{id}/queue
 *   T3  ENTREGA no canal PESSOAL   /user/queue/notifications   <-- nunca provado
 *   T4  heartbeat vivo em conexao OCIOSA (>35s sem trafego de aplicacao)
 *   T5  CONNECT com token INVALIDO (comportamento atual = anonimo, backlog S14)
 *
 * Uso: node ws-probe.js [baseUrl]
 */
const path = require('path');
const NM = '/home/andre/Documentos/programacao/vupth/node_modules';
const { Client } = require(path.join(NM, '@stomp/stompjs'));
Object.assign(global, { WebSocket: require(path.join(NM, 'ws')) });

const BASE = process.argv[2] || 'https://vupth-backend-ia.onrender.com';
const WS = BASE.replace(/^http/, 'ws') + '/ws/websocket';
// Autossuficiente como as collections do Postman: cria os proprios usuarios.
// (Os usuarios de seed NAO servem: o hash do 012-seed-data.yaml nao casa com a
//  senha que o proprio arquivo documenta — login devolve 401.)
const PASS = 'Senha123!';
const stamp = Date.now();

const results = [];
const pass = (t, d) => { results.push(['PASS', t, d]); console.log(`  ✅ ${t} — ${d}`); };
const fail = (t, d) => { results.push(['FAIL', t, d]); console.log(`  ❌ ${t} — ${d}`); };
const info = (m) => console.log(`     ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, urlPath, { token, body } = {}) {
  const res = await fetch(BASE + urlPath, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (_) { /* nao-JSON */ }
  return { status: res.status, json, text };
}

async function login(email) {
  const r = await api('POST', '/auth/login', { body: { email, password: PASS } });
  if (r.status !== 200) throw new Error(`login ${email} -> ${r.status} ${r.text.slice(0, 200)}`);
  const t = r.json?.tokens?.access_token || r.json?.access_token;
  if (!t) throw new Error(`login ${email}: sem token -> ${JSON.stringify(r.json).slice(0, 200)}`);
  return t;
}

async function register(email) {
  const r = await api('POST', '/auth/register', { body: { email, password: PASS } });
  if (r.status !== 201) throw new Error(`register ${email} -> ${r.status} ${r.text.slice(0, 200)}`);
  return r.json.id;
}

/** Conecta uma sessao STOMP e devolve o cliente + o frame CONNECTED (ou o erro). */
function connect(token, label) {
  return new Promise((resolve, reject) => {
    const frames = [];
    const client = new Client({
      brokerURL: WS,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      reconnectDelay: 0, // sem reconexao automatica: queremos ver a queda
      debug: (s) => { frames.push(`${new Date().toISOString().slice(11, 23)} ${s}`); },
    });
    const timer = setTimeout(() => reject(new Error(`${label}: CONNECT sem resposta em 25s`)), 25000);
    client.onConnect = (frame) => { clearTimeout(timer); resolve({ client, frame, frames }); };
    client.onStompError = (frame) => {
      clearTimeout(timer);
      reject(Object.assign(new Error(`${label}: ERROR STOMP`), { frame, frames }));
    };
    client.onWebSocketError = (e) => { clearTimeout(timer); reject(new Error(`${label}: erro no socket: ${e.message || e}`)); };
    client.activate();
  });
}

(async () => {
  console.log(`\n=== VUPTH WS probe — ${BASE} ===\n`);

  // ── setup REST (dono = profissional, como no fluxo quick-create) ──────────
  console.log('[setup] cria dono + loja + servico + cliente');
  const ownerEmail = `wsowner_${stamp}@vupth.test`;
  const clientEmail = `wsclient_${stamp}@vupth.test`;

  const ownerId = await register(ownerEmail);
  let ownerToken = await login(ownerEmail);

  const estab = await api('POST', '/establishment/quick-create', {
    token: ownerToken, body: { name: `Loja WS ${stamp}`, serviceType: 'BARBER' },
  });
  if (estab.status !== 201) throw new Error(`quick-create loja -> ${estab.status} ${estab.text.slice(0, 200)}`);
  const ESTAB = estab.json.id;

  const profCreate = await api('POST', '/professional/quick-create', {
    token: ownerToken, body: { name: 'Prof WS', establishmentId: ESTAB, specialty: 'Barber' },
  });
  if (profCreate.status !== 201) throw new Error(`prof quick-create -> ${profCreate.status} ${profCreate.text.slice(0, 200)}`);
  // o dono acumulou ROLE_PROFESSIONAL DEPOIS do login: token novo para carregar a role
  ownerToken = await login(ownerEmail);
  const professionalId = ownerId; // MapsId: professional.id == baseUser.id

  const item = await api('POST', `/establishment/${ESTAB}/service-items`, {
    token: ownerToken,
    body: { serviceItemType: 'CORTE_MAQUINA', customName: 'Corte WS', price: 35.0, estimatedMinutes: 30 },
  });
  if (item.status !== 201) throw new Error(`service-item -> ${item.status} ${item.text.slice(0, 200)}`);
  const serviceItemId = item.json.id;

  const clientId = await register(clientEmail);
  const clientToken = await login(clientEmail);
  info(`estab=${ESTAB} prof=${professionalId} item=${serviceItemId} client=${clientId}`);

  // loja aberta + profissional WORKING (pre-requisito do walk-in)
  const working = await api('PATCH', '/professional/me/status', {
    token: ownerToken, body: { status: 'WORKING' },
  });
  const open = await api('PATCH', `/establishment/${ESTAB}/open-status`, {
    token: ownerToken, body: { openStatus: 'OPEN' },
  });
  info(`prof WORKING -> ${working.status} | loja OPEN -> ${open.status} ${open.status === 200 ? '' : open.text.slice(0, 160)}`);

  const client = { token: clientToken, userId: clientId };
  const prof = { token: ownerToken };

  // ── T1: heartbeat negociado ───────────────────────────────────────────────
  console.log('\n[T1] heartbeat negociado no CONNECTED');
  const sess = await connect(client.token, 'client');
  const hb = sess.frame.headers['heart-beat'];
  const userName = sess.frame.headers['user-name'];
  info(`CONNECTED headers: ${JSON.stringify(sess.frame.headers)}`);
  if (hb === '10000,10000') pass('T1 heartbeat', `servidor respondeu heart-beat:${hb}`);
  else fail('T1 heartbeat', `esperado 10000,10000, veio "${hb}" (deploy sem o fix be5bdaa?)`);
  if (userName === client.userId) pass('T1b principal', `user-name=${userName} = userId do cliente`);
  else fail('T1b principal', `user-name="${userName}" != userId ${client.userId}`);

  // ── subscricoes ───────────────────────────────────────────────────────────
  const publicMsgs = [];
  const personalMsgs = [];
  sess.client.subscribe(`/topic/establishment/${ESTAB}/queue`, (m) => publicMsgs.push(m.body));
  sess.client.subscribe('/user/queue/notifications', (m) => personalMsgs.push(m.body));
  await sleep(500);

  // ── T2: entrega no topico publico ─────────────────────────────────────────
  console.log('\n[T2] entrega no topico PUBLICO (cliente entra na fila)');
  const before = publicMsgs.length;
  const created = await api('POST', '/attendances', {
    token: client.token,
    body: {
      professionalId,
      establishmentId: ESTAB,
      serviceItemIds: [serviceItemId],
      scheduledTime: new Date().toISOString(),
    },
  });
  info(`POST /attendances -> ${created.status}`);
  if (created.status !== 201 && created.status !== 200) {
    fail('T2 publico', `nao entrou na fila: ${created.status} ${created.text.slice(0, 200)}`);
  } else {
    for (let i = 0; i < 20 && publicMsgs.length === before; i++) await sleep(250);
    if (publicMsgs.length > before) pass('T2 publico', `${publicMsgs.length - before} broadcast(s) recebido(s), ${publicMsgs[publicMsgs.length - 1].length} bytes`);
    else fail('T2 publico', 'nenhum broadcast em 5s apos entrar na fila');
  }
  const attendanceId = created.json?.id || created.json?.attendance_id;
  info(`attendanceId=${attendanceId}`);

  // ── T3: entrega no canal PESSOAL (o buraco da prova de 01/08) ─────────────
  console.log('\n[T3] entrega no canal PESSOAL (profissional remove o cliente da fila)');
  if (!attendanceId) {
    fail('T3 pessoal', 'sem attendanceId — nao deu para disparar o evento pessoal');
  } else {
    const removed = await api('DELETE', `/professional-queue/${attendanceId}/remove`, { token: prof.token });
    info(`DELETE /professional-queue/${attendanceId}/remove -> ${removed.status}`);
    for (let i = 0; i < 24 && personalMsgs.length === 0; i++) await sleep(250);
    if (personalMsgs.length > 0) pass('T3 pessoal', `recebido: ${personalMsgs[0].slice(0, 180)}`);
    else fail('T3 pessoal', 'nada em /user/queue/notifications em 6s (remove retornou ' + removed.status + ')');
  }

  // ── T4: conexao ociosa sobrevive por heartbeat ────────────────────────────
  console.log('\n[T4] conexao OCIOSA por 40s (heartbeat mantendo viva)');
  let closed = null;
  sess.client.onWebSocketClose = (e) => { closed = e; };
  const framesBefore = sess.frames.length;
  await sleep(40000);
  const win = sess.frames.slice(framesBefore);
  const pongs = win.filter((f) => /<<< PONG/.test(f)).length; // batida VINDA do servidor
  const pings = win.filter((f) => />>> PING/.test(f)).length; // batida do cliente
  info(`${win.length} linhas de debug no periodo — ${pongs} PONG (servidor) / ${pings} PING (cliente)`);
  if (closed) {
    fail('T4 ociosa', `socket caiu: code=${closed.code} reason=${closed.reason}`);
  } else if (!sess.client.connected) {
    fail('T4 ociosa', 'cliente reporta desconectado, sem evento de close');
  } else if (pongs < 2) {
    // Sem batida do servidor a conexao so "sobrevive" porque localhost nao tem
    // proxy cortando ocioso — nao prova nada. Em 40s deviam vir ~4 PONGs.
    fail('T4 ociosa', `viva, mas so ${pongs} PONG(s) do servidor em 40s — nao ha heartbeat de fato`);
  } else {
    pass('T4 ociosa', `viva apos 40s ociosos com ${pongs} PONG(s) do servidor (~1 a cada 10s)`);
  }

  sess.client.deactivate();
  await sleep(500);

  // ── T5: CONNECT com token invalido ───────────────────────────────────────
  console.log('\n[T5] CONNECT com token INVALIDO (Authorization presente e podre)');
  try {
    const bad = await connect('eyJhbGciOiJSUzI1NiJ9.token.invalido', 'token-invalido');
    const bn = bad.frame.headers['user-name'];
    info(`CONNECTED headers: ${JSON.stringify(bad.frame.headers)}`);
    if (bn) fail('T5 token invalido', `virou sessao AUTENTICADA como ${bn} — falha grave`);
    else info('comportamento atual: CONNECT aceito como ANONIMO (sem user-name) — backlog S14');
    // canal pessoal nao pode entregar nada a um anonimo
    const anon = [];
    bad.client.subscribe('/user/queue/notifications', (m) => anon.push(m.body));
    await sleep(1500);
    if (anon.length === 0) pass('T5 isolamento', 'anonimo nao recebe nada no canal pessoal');
    else fail('T5 isolamento', `anonimo recebeu ${anon.length} mensagem(ns) pessoais — VAZAMENTO`);
    bad.client.deactivate();
  } catch (e) {
    if (e.frame) pass('T5 token invalido', `servidor RECUSOU o CONNECT: ${e.frame.headers?.message}`);
    else fail('T5 token invalido', e.message);
  }

  // ── resumo ────────────────────────────────────────────────────────────────
  await sleep(500);
  const failed = results.filter((r) => r[0] === 'FAIL');
  console.log(`\n=== RESUMO: ${results.length - failed.length} PASS / ${failed.length} FAIL ===`);
  results.forEach(([s, t, d]) => console.log(`${s === 'PASS' ? '✅' : '❌'} ${t} — ${d}`));
  process.exit(failed.length ? 1 : 0);
})().catch((e) => {
  console.error('\n💥 ERRO FATAL:', e.message);
  if (e.frame) console.error('frame:', JSON.stringify(e.frame.headers));
  process.exit(2);
});
