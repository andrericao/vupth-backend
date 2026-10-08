/**
 * VUPTH — usuarios de teste para montar filas no staging (usuarios-de-teste, bateria conjunta).
 *
 * Fala com a API como um app faria: nada disso existe no codigo do servidor. Idempotente — rodar de
 * novo reaproveita as contas (login antes de cadastrar).
 *
 *   node scripts/fila-de-teste.js criar  [--n 8]   # dono + loja de teste + N clientes (cli01..cliNN)
 *   node scripts/fila-de-teste.js encher [--n 5]   # cli01..cli05 entram na fila (os que ja estao ficam)
 *   node scripts/fila-de-teste.js estado           # a fila, na ordem, com quem e quem
 *   node scripts/fila-de-teste.js trocar cli02 cli04   # cli02 propoe a troca e cli04 aceita
 *   node scripts/fila-de-teste.js sair cli03       # cli03 sai da fila
 *
 * Ambiente: VUPTH_BASE (padrao: staging no Render) e VUPTH_CI_SECRET (opcional — o mesmo segredo do
 * Newman, header X-Vupth-CI, passa por fora do limite de login; sem ele, os pedidos de login/cadastro
 * saem espacados de 7 s para nao levar 429).
 * Contas: teste+dono@vupth.app e teste+cliNN@vupth.app, todas com a senha Senha123!
 */
const BASE = (process.env.VUPTH_BASE || 'https://vupth-backend-ia.onrender.com').replace(/\/$/, '');
const CI_SECRET = process.env.VUPTH_CI_SECRET || '';
const PASS = 'Senha123!';
const OWNER = 'teste+dono@vupth.app';
const STORE_NAME = 'Loja de Teste (bateria)';
const AUTH_GAP_MS = CI_SECRET ? 0 : 7000; // limite de auth: 10/min por IP

const clientEmail = (i) => `teste+cli${String(i).padStart(2, '0')}@vupth.app`;
const clientLabel = (email) => (email.match(/cli\d+/) || [email])[0];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, { token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (CI_SECRET && path.startsWith('/auth/')) headers['X-Vupth-CI'] = CI_SECRET;
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: res.status, json };
}

function must(r, expected, what) {
  if (!expected.includes(r.status)) {
    throw new Error(`${what}: HTTP ${r.status} ${JSON.stringify(r.json)}`);
  }
  return r.json;
}

let lastAuth = 0;
async function authCall(method, path, body) {
  const wait = lastAuth + AUTH_GAP_MS - Date.now();
  if (wait > 0) await sleep(wait);
  lastAuth = Date.now();
  return api(method, path, { body });
}

/** Login; sem conta, cadastra e entra. Devolve { id, token }. */
async function account(email) {
  let r = await authCall('POST', '/auth/login', { email, password: PASS });
  if (r.status !== 200) {
    must(await authCall('POST', '/auth/register', { email, password: PASS }), [201], `cadastro de ${email}`);
    r = await authCall('POST', '/auth/login', { email, password: PASS });
  }
  const body = must(r, [200], `login de ${email}`);
  const token = body.tokens.access_token;
  const id = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).sub;
  return { id, token };
}

/** Dono + loja de teste + o dono como profissional, trabalhando, com um servico de 30 min. */
async function store({ create }) {
  const owner = await account(OWNER);
  // 403 = ainda nao e dono de loja nenhuma (o papel nasce com a primeira loja).
  const mine = must(await api('GET', '/establishment/mine', { token: owner.token }), [200, 403], 'lojas do dono');
  let estab = (Array.isArray(mine) ? mine : []).find((e) => e.name === STORE_NAME);
  if (!estab) {
    if (!create) throw new Error('loja de teste nao existe — rode "criar" antes');
    estab = must(await api('POST', '/establishment/quick-create',
      { token: owner.token, body: { name: STORE_NAME, serviceType: 'BARBER' } }), [201], 'criar a loja');
  }
  const prof = await api('GET', '/professional/me', { token: owner.token });
  if (prof.status !== 200) {
    must(await api('POST', '/professional/quick-create', {
      token: owner.token, body: { name: 'Dono de Teste', establishmentId: estab.id, specialty: 'Barber' },
    }), [201], 'dono vira profissional');
  }
  const items = must(await api('GET', `/professional/${owner.id}/service-items`, { token: owner.token }),
    [200], 'servicos do profissional');
  let item = (Array.isArray(items) ? items : items.content || [])[0];
  if (!item) {
    item = must(await api('POST', `/establishment/${estab.id}/service-items`, {
      token: owner.token,
      body: { serviceItemType: 'CORTE_MAQUINA', customName: 'Corte de teste', price: 30.0, estimatedMinutes: 30 },
    }), [201], 'criar o servico');
  }
  if (create) {
    must(await api('PATCH', '/professional/me/status', { token: owner.token, body: { status: 'WORKING' } }),
      [200, 204], 'profissional trabalhando');
    must(await api('PATCH', '/professional/me/accepting-new', { token: owner.token, body: { value: true } }),
      [200, 204], 'fila aberta');
  }
  return { owner, estabId: estab.id, profId: owner.id, itemId: item.id };
}

/** Atendimento do cliente na fila (SCHEDULED/LATE) nesta loja, ou null. */
async function attendanceOf(client, estabId) {
  const list = must(await api('GET', '/attendances/client/me', { token: client.token }), [200], 'atendimentos do cliente');
  const items = Array.isArray(list) ? list : list.content || [];
  return items.find((a) => ['SCHEDULED', 'LATE'].includes(a.status)
    && (a.establishmentId || (a.establishment && a.establishment.id)) === estabId) || null;
}

async function criar(n) {
  const s = await store({ create: true });
  console.log(`loja ${s.estabId} · profissional ${s.profId} · servico ${s.itemId}`);
  for (let i = 1; i <= n; i++) {
    const c = await account(clientEmail(i));
    console.log(`  ${clientLabel(clientEmail(i))}  ${clientEmail(i)}  ${PASS}  (id ${c.id})`);
  }
  console.log(`dono: ${OWNER}  ${PASS}`);
}

async function encher(n) {
  const s = await store({ create: false });
  for (let i = 1; i <= n; i++) {
    const email = clientEmail(i);
    const c = await account(email);
    if (await attendanceOf(c, s.estabId)) { console.log(`  ${clientLabel(email)} ja esta na fila`); continue; }
    must(await api('POST', '/attendances', {
      token: c.token,
      body: { professionalId: s.profId, establishmentId: s.estabId, serviceItemIds: [s.itemId],
        scheduledTime: new Date().toISOString() },
    }), [201], `${clientLabel(email)} entra na fila`);
    console.log(`  ${clientLabel(email)} entrou`);
  }
  await estado();
}

async function estado() {
  const s = await store({ create: false });
  const q = must(await api('GET', `/client-queue/${s.profId}/queue?establishmentId=${s.estabId}`,
    { token: s.owner.token }), [200], 'fila');
  console.log(`fila de ${q.professionalName} em ${q.establishmentName}: ${q.size} pessoa(s)`);
  (q.attendancesInQueue || []).forEach((a, i) => console.log(
    `  ${i + 1}. cliente ${a.clientId}  ${a.status}  entrada ${a.scheduledTime}`));
}

async function trocar(a, b) {
  const s = await store({ create: false });
  const ca = await account(clientEmail(Number(a.replace(/\D/g, ''))));
  const cb = await account(clientEmail(Number(b.replace(/\D/g, ''))));
  const [aa, ab] = [await attendanceOf(ca, s.estabId), await attendanceOf(cb, s.estabId)];
  if (!aa || !ab) throw new Error('os dois precisam estar na fila');
  const swap = must(await api('POST', '/swap-requests', {
    token: ca.token, body: { proposerAttendanceId: aa.id, targetAttendanceId: ab.id },
  }), [201], `${a} propoe a troca`);
  must(await api('PATCH', `/swap-requests/${swap.id}?accept=true`, { token: cb.token }), [200], `${b} aceita`);
  console.log(`  ${a} <-> ${b} trocados`);
}

async function sair(x) {
  const s = await store({ create: false });
  const c = await account(clientEmail(Number(x.replace(/\D/g, ''))));
  const a = await attendanceOf(c, s.estabId);
  if (!a) throw new Error(`${x} nao esta na fila`);
  must(await api('DELETE', `/attendances/${a.id}/leave-queue`, { token: c.token }), [200, 204], `${x} sai da fila`);
  console.log(`  ${x} saiu`);
}

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  const nIdx = rest.indexOf('--n');
  const n = nIdx >= 0 ? Number(rest[nIdx + 1]) : null;
  console.log(`# ${BASE}${CI_SECRET ? ' (com X-Vupth-CI)' : ''}`);
  switch (cmd) {
    case 'criar': return criar(n || 8);
    case 'encher': return encher(n || 5);
    case 'estado': return estado();
    case 'trocar': return trocar(rest[0], rest[1]);
    case 'sair': return sair(rest[0]);
    default:
      console.log('uso: node scripts/fila-de-teste.js criar|encher|estado|trocar cliA cliB|sair cliX [--n N]');
      process.exitCode = 1;
  }
}

main().catch((e) => { console.error(`❌ ${e.message}`); process.exitCode = 1; });
