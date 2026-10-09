'use strict';

// ---------------------------------------------------------------- helpers
const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const safeStore = (area) => ({
  get(k) { try { return area.getItem(k); } catch { return null; } },
  set(k, v) { try { area.setItem(k, v); } catch { /* storage may be blocked */ } },
});
const local = safeStore(window.localStorage);
const tab = safeStore(window.sessionStorage);

const makeKey = () => (window.crypto && crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2) + Date.now());
const myKey = tab.get('eswKey') || makeKey();
tab.set('eswKey', myKey);

// Pictures. Everything comes from the server's assets folder; if a picture is missing the page draws a plain stand-in.
const PIECE = (name) => `/art/Pieces/${name}`;
const BACKS = {
  main: '/art/Card%20Back%20and%20Wild%20Card/IMG_20261008_0003',
  treasure: '/art/Card%20Back%20and%20Wild%20Card/IMG_20261008_0001',
  dead: '/art/Card%20Back%20and%20Wild%20Card/IMG_20261008_0002',
};

// Where each hit point sits on a hero board (measured from the real boards, as fractions of the board image).
const TRACK = {
  cols: [0.616, 0.724, 0.832, 0.940],
  rows: [58, 176, 293, 409, 526].map((v) => v / 702),
  bottomX: [601, 693, 781, 866, 953].map((v) => v / 1000),
  bottomY: 644 / 702,
};
function skullPos(hp) {
  if (hp >= 6) {
    const i = Math.min(25, hp) <= 25 ? 25 - Math.min(25, hp) : 0;
    return [TRACK.cols[i % 4] * 100, TRACK.rows[Math.floor(i / 4)] * 100];
  }
  if (hp >= 1) return [TRACK.bottomX[5 - hp] * 100, TRACK.bottomY * 100];
  return [50, 50];
}

// ---------------------------------------------------------------- state
let ws = null;
let myName = local.get('eswName') || '';
let screen = myName ? 'connecting' : 'name';
let lobby = { games: [], leaderboard: [] };
let room = null;
let game = null;
let logLines = [];
let prompt = null;
let promptSent = false;
let build = { source: null, quality: null, delivery: null, target: null };
let activePid = null;
let resolving = null;     // the card whose effect is going off right now: { player, card }
let focus = null;         // whose spell holds the middle of the screen: { player, animateFlip }
let feed = [];            // plain-words narration of what is happening this turn
let locked = [];          // the spells that were locked in this round: { player, components, initiative }
let vote = null;
let winnerName = null;
const hpShown = {};       // the hit points last drawn on my board, so the skull can slide
const cardCache = {};
let muted = local.get('eswMute') === '1';

// ---------------------------------------------------------------- sound (made with the browser's own synthesiser)
let audio = null;
function audioCtx() {
  if (audio) return audio;
  try { audio = new (window.AudioContext || window.webkitAudioContext)(); } catch { audio = null; }
  return audio;
}
function tone(freq, dur, type = 'square', vol = 0.06, delay = 0, slideTo = null) {
  if (muted) return;
  const ctx = audioCtx();
  if (!ctx) return;
  const t = ctx.currentTime + delay;
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t);
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t + dur);
  gain.gain.setValueAtTime(vol, t);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  osc.connect(gain).connect(ctx.destination);
  osc.start(t);
  osc.stop(t + dur + 0.02);
}
const sound = {
  round() { tone(392, 0.12, 'triangle', 0.08); tone(523, 0.18, 'triangle', 0.08, 0.12); },
  reveal() { tone(200, 0.45, 'sawtooth', 0.05, 0, 900); },
  card() { tone(520, 0.08, 'triangle', 0.07); tone(780, 0.14, 'triangle', 0.07, 0.07); },
  dice() { for (let i = 0; i < 9; i++) tone(260 + Math.random() * 500, 0.05, 'square', 0.04, i * 0.08); tone(180, 0.2, 'sine', 0.08, 0.8); },
  hit() { tone(140, 0.28, 'sawtooth', 0.1, 0, 45); tone(90, 0.2, 'square', 0.06, 0.03); },
  heal() { tone(660, 0.12, 'sine', 0.07); tone(880, 0.2, 'sine', 0.07, 0.1); },
  death() { tone(300, 0.9, 'sawtooth', 0.09, 0, 40); },
  win() { [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.25, 'triangle', 0.09, i * 0.16)); },
};
let lastHitSound = 0;

// ---------------------------------------------------------------- connection
function connect() {
  ws = new WebSocket((location.protocol === 'https:' ? 'wss' : 'ws') + '://' + location.host + '/ws');
  ws.onopen = () => { if (myName) send({ t: 'hello', name: myName, key: myKey }); };
  ws.onmessage = (e) => { try { handle(JSON.parse(e.data)); } catch (err) { console.error(err); } };
  ws.onclose = () => { toast('Connection lost. Trying to reconnect...'); setTimeout(connect, 1500); };
}
function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

function toast(text) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = text;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

function handle(m) {
  switch (m.t) {
    case 'welcome': break;
    case 'lobby':
      lobby = m; room = null; game = null; prompt = null; vote = null; winnerName = null; focus = null; feed = [];
      screen = 'lobby'; render(); break;
    case 'room':
      room = m;
      if (m.state === 'LOBBY') { screen = 'room'; game = null; prompt = null; logLines = []; winnerName = null; vote = null; focus = null; feed = []; }
      else screen = 'table';
      render(); break;
    case 'state': game = m.state; render(); break;
    case 'log': logLines = m.lines; render(); break;
    case 'ev':
      game = m.state;
      if (m.event.text) logLines.push(m.event.text);
      preEvent(m.event);
      render();
      postEvent(m.event);
      break;
    case 'prompt':
      prompt = m;
      promptSent = false;
      if (m.kind === 'spell') { build = { source: null, quality: null, delivery: null, target: null }; focus = null; }
      render(); break;
    case 'promptDone':
      if (prompt && prompt.pid === m.pid) { prompt = null; promptSent = false; render(); }
      break;
    case 'vote': vote = m; render(); break;
    case 'error': toast(m.message); break;
    default: break;
  }
}

// ---------------------------------------------------------------- what is happening (narration) and effects
function nameOf(pid) { return game && game.players[pid] ? game.players[pid].name : 'Someone'; }

const NOT_NARRATED = new Set(['roundStarted', 'gameStarted', 'matchStarted', 'spellsLocked', 'handsDealt', 'gameWon', 'matchWon']);

function cardTextFor(e) {
  const p = game && game.players[e.player];
  const sc = p && p.spell ? p.spell.cards.find((c) => c.card && c.card.name === e.card) : null;
  const text = sc ? sc.card.text : '';
  return text.length > 170 ? text.slice(0, 167) + '...' : text;
}

function narration(e) {
  switch (e.k) {
    case 'turnStarted': return `${nameOf(e.player)} begins to cast...`;
    case 'spellRevealed': return `${nameOf(e.player)} reveals ${e.cards.join(' + ') || 'nothing'}`;
    case 'cardResolving': return `${e.card}: ${cardTextFor(e)}`;
    default: return e.text;
  }
}

/** Updates what the screen is about to show. Runs before the table is drawn. */
function preEvent(e) {
  switch (e.k) {
    case 'roundStarted': focus = null; feed = []; locked = []; resolving = null; activePid = null; break;
    case 'gameStarted': focus = null; feed = []; winnerName = null; break;
    case 'spellsLocked': locked = e.spells; break;
    case 'turnStarted': focus = { player: e.player, animateFlip: false }; feed = []; resolving = null; activePid = e.player; break;
    case 'spellRevealed': if (focus) focus.animateFlip = true; break;
    case 'cardResolving': resolving = { player: e.player, card: e.card }; break;
    default: break;
  }
  if (focus && e.text !== '' && !NOT_NARRATED.has(e.k)) feed.push({ text: narration(e), k: e.k });
  if (feed.length > 12) feed.shift();
}

function banner(text, big = false, ms = 1700) {
  const el = $('#banner');
  el.textContent = text;
  el.className = big ? 'big' : '';
  clearTimeout(banner.timer);
  banner.timer = setTimeout(() => el.classList.add('hidden'), ms);
}

function seatEl(pid) { return document.querySelector(`[data-pid="${pid}"]`); }

function floatText(pid, text, cls) {
  const el0 = seatEl(pid);
  if (!el0) return;
  const r = el0.getBoundingClientRect();
  const el = document.createElement('div');
  el.className = 'float ' + cls;
  el.textContent = text;
  el.style.left = (r.left + r.width / 2) + 'px';
  el.style.top = (r.top + r.height * 0.25) + 'px';
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 1900);
}

function shake(pid) {
  const el = seatEl(pid);
  if (el) { el.classList.add('hit'); setTimeout(() => el.classList.remove('hit'), 400); }
}

function flash(kind) {
  const el = document.createElement('div');
  el.className = 'flash ' + kind;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 700);
}

const PIPS = {
  1: [[2, 2]], 2: [[1, 1], [3, 3]], 3: [[1, 1], [2, 2], [3, 3]], 4: [[1, 1], [1, 3], [3, 1], [3, 3]],
  5: [[1, 1], [1, 3], [2, 2], [3, 1], [3, 3]], 6: [[1, 1], [2, 1], [3, 1], [1, 3], [2, 3], [3, 3]],
};
const face = (n) => `<div class="face f${n}">${PIPS[n].map(([r, c]) => `<i style="grid-row:${r};grid-column:${c}"></i>`).join('')}</div>`;
const cubeHtml = () => `<div class="cube rolling" style="animation-duration:${0.45 + Math.random() * 0.25}s">${[1, 2, 3, 4, 5, 6].map(face).join('')}</div>`;
const FACE_TURN = { 1: 'rotateY(0deg)', 6: 'rotateY(180deg)', 3: 'rotateY(-90deg)', 4: 'rotateY(90deg)', 2: 'rotateX(-90deg)', 5: 'rotateX(90deg)' };

function showDice(e) {
  const box = $('#dice');
  box.className = '';
  box.innerHTML = `<div class="who">${esc(nameOf(e.player))} &mdash; ${esc(e.reason)}</div>
    <div class="dice3d">${e.dice.map(cubeHtml).join('')}</div><div class="total"></div>`;
  sound.dice();
  clearTimeout(showDice.settle);
  showDice.settle = setTimeout(() => {
    box.querySelectorAll('.cube').forEach((c, i) => {
      c.classList.remove('rolling');
      c.style.transform = `rotateX(-24deg) rotateY(-28deg) ${FACE_TURN[e.dice[i]] || ''}`;
    });
    const sum = e.dice.reduce((a, b) => a + b, 0);
    const t = box.querySelector('.total');
    if (t) t.textContent = e.dice.length > 1 || e.total !== sum ? `Total ${e.total}` : '';
  }, 950);
  clearTimeout(showDice.timer);
  showDice.timer = setTimeout(() => box.classList.add('hidden'), 2500);
}

/** Runs after the table is drawn: banners, floating numbers, sounds and dice. */
function postEvent(e) {
  const mine = game && game.you ? game.you.index : -1;
  switch (e.k) {
    case 'roundStarted': banner(e.text, false, 1200); sound.round(); break;
    case 'gameStarted': banner(e.text, true, 1400); break;
    case 'spellsLocked': banner('All spells are locked in!', false, 1200); break;
    case 'turnStarted': banner(`${nameOf(e.player)}'s turn`, false, 900); break;
    case 'spellRevealed': sound.reveal(); break;
    case 'cardResolving': sound.card(); break;
    case 'dice': showDice(e); break;
    case 'damage':
      floatText(e.target, `-${e.amount}`, 'dmg'); shake(e.target);
      if (e.target === mine) flash('dmg');
      if (Date.now() - lastHitSound > 350) { sound.hit(); lastHitSound = Date.now(); }
      break;
    case 'heal': floatText(e.player, `+${e.amount}`, 'heal'); if (e.player === mine) flash('heal'); sound.heal(); break;
    case 'treasureGained': floatText(e.player, `+ ${e.treasure}`, 'gain'); break;
    case 'died': banner(`${nameOf(e.player)} is dead!`, true, 2100); sound.death(); break;
    case 'gameWon': banner(e.text, true, 3200); sound.win(); break;
    case 'matchWon': winnerName = nameOf(e.winner); sound.win(); render(); break;
    default: break;
  }
}

// ---------------------------------------------------------------- cards
function cardHtml(c, extra = '', width = 300) {
  if (!c) return '';
  cardCache[c.id] = c;
  const noart = !c.art;
  const img = c.art ? `<img src="${esc(c.art)}?w=${width}" alt="" onerror="this.parentNode.classList.add('noart');this.remove()">` : '';
  const ini = c.initiative != null ? `<div class="ini">${c.initiative}</div>` : '';
  const type = { SOURCE: 'Source', QUALITY: 'Quality', DELIVERY: 'Delivery', TREASURE: 'Treasure', DEAD_WIZARD: 'Dead Wizard', WILD_MAGIC: 'Wild Magic' }[c.type] || c.type;
  const glyph = c.glyph ? ` &middot; ${c.glyph[0] + c.glyph.slice(1).toLowerCase()}` : '';
  return `<div class="card t-${c.type} g-${c.glyph || 'NONE'} ${noart ? 'noart' : ''} ${extra}" data-cid="${esc(c.id)}" data-uid="${c.uid ?? ''}">
    ${img}<div class="txt"><div class="nm">${esc(c.name)}</div><div class="ty">${type}${glyph}</div><div>${esc(c.text)}</div>${ini}</div><div class="gl"></div></div>`;
}

// Hovering a card in the hand, spotlight or builder already enlarges it; elsewhere a big preview appears.
document.addEventListener('mouseover', (e) => {
  const el = e.target.closest ? e.target.closest('[data-cid]') : null;
  const zoom = $('#zoom');
  if (!el || el.closest('.hand, .fc, .choices, .bslot')) { zoom.classList.add('hidden'); return; }
  const c = cardCache[el.dataset.cid];
  if (!c) return;
  zoom.innerHTML = cardHtml(c, '', 640);
  zoom.classList.remove('hidden');
});
document.addEventListener('mouseout', (e) => {
  if (!e.relatedTarget || !(e.relatedTarget.closest && e.relatedTarget.closest('[data-cid]'))) $('#zoom').classList.add('hidden');
});

// ---------------------------------------------------------------- screens
function render() {
  const app = $('#app');
  if (screen === 'name') return renderName(app);
  if (screen === 'connecting') { app.innerHTML = '<div class="center-box"><h1>Epic Spell Wars</h1><p class="muted">Connecting...</p></div>'; return; }
  if (screen === 'lobby') return renderLobby(app);
  if (screen === 'room') return renderRoom(app);
  if (screen === 'table') return renderTable(app);
}

function renderName(app) {
  if ($('#nameInput')) return;
  app.innerHTML = `<div class="center-box"><h1>Epic Spell Wars</h1><p class="muted">Pick a wizard name. Others will see it at the table.</p>
    <div class="row"><input id="nameInput" type="text" maxlength="20" placeholder="Your name" autocomplete="off"><button id="nameGo" class="btn">Enter</button></div></div>`;
  const go = () => {
    const v = $('#nameInput').value.trim();
    if (!v) return;
    myName = v; local.set('eswName', v); screen = 'connecting'; render(); send({ t: 'hello', name: myName, key: myKey });
  };
  $('#nameGo').onclick = go;
  $('#nameInput').onkeydown = (e) => { if (e.key === 'Enter') go(); };
  $('#nameInput').focus();
}

function topActions(extra = '') {
  return `<div class="top-actions"><h1 style="margin:0">Epic Spell Wars</h1><span class="spacer"></span>${extra}
    <span class="muted">Playing as <b style="color:var(--ink)">${esc(myName)}</b></span>
    <button class="btn secondary" id="rename">Change name</button><button class="btn secondary" id="mute">${muted ? 'Sound off' : 'Sound on'}</button></div>`;
}
function wireTop() {
  const r = $('#rename'); if (r) r.onclick = () => { local.set('eswName', ''); myName = ''; screen = 'name'; render(); };
  const m = $('#mute'); if (m) m.onclick = () => { muted = !muted; local.set('eswMute', muted ? '1' : '0'); audioCtx(); m.textContent = muted ? 'Sound off' : 'Sound on'; };
}

function renderLobby(app) {
  if (!$('#gamesList')) {
    app.innerHTML = `<div class="screen">${topActions()}
      <div class="grid2"><div class="panel"><h2>Games</h2>
        <div class="top-actions"><input id="title" type="text" maxlength="30" placeholder="Name your game (optional)"><button id="create" class="btn">Create game</button></div>
        <div id="gamesList"></div></div>
      <div class="panel"><h2>Most wins</h2><div id="board"></div></div></div></div>`;
    wireTop();
    $('#create').onclick = () => send({ t: 'createGame', title: $('#title').value });
    $('#title').onkeydown = (e) => { if (e.key === 'Enter') $('#create').click(); };
  }
  const games = lobby.games || [];
  $('#gamesList').innerHTML = games.length ? games.map((g) => {
    const open = g.state === 'LOBBY' && g.players.length < g.max;
    return `<div class="game-row"><div class="grow"><b>${esc(g.title)}</b><small>${g.players.map(esc).join(', ')} &middot; ${g.players.length}/${g.max}</small></div>
      <span class="tag">${g.state === 'LOBBY' ? 'waiting' : g.state === 'PLAYING' ? 'in progress' : 'finished'}</span>
      <button class="btn" data-join="${esc(g.id)}" ${open ? '' : 'disabled'}>Join</button></div>`;
  }).join('') : '<p class="muted">No games yet. Create one and invite your friends.</p>';
  document.querySelectorAll('[data-join]').forEach((b) => { b.onclick = () => send({ t: 'joinGame', id: b.dataset.join }); });
  const lb = lobby.leaderboard || [];
  $('#board').innerHTML = lb.length ? lb.map((r, i) => `<div class="lb-row"><span>${i + 1}. ${esc(r.name)}</span><b>${r.wins}</b></div>`).join('') : '<p class="muted">Nobody has won a match yet.</p>';
}

function renderRoom(app) {
  if (!room) return;
  const seats = room.seats.map((s) => `<div class="seat-row"><div class="seat-num">${s.index + 1}</div>
    <div class="grow"><b>${esc(s.name)}</b> ${s.bot ? '<span class="tag">bot</span>' : ''} ${s.host ? '<span class="tag">host</span>' : ''} ${s.index === room.you ? '<span class="tag">you</span>' : ''}</div>
    ${room.owner && s.index !== room.you ? `<button class="btn secondary" data-remove="${s.index}">Remove</button>` : ''}</div>`).join('');
  const canStart = room.owner && room.seats.length >= 2;
  app.innerHTML = `<div class="screen">${topActions()}
    <div class="panel" style="max-width:640px;margin:0 auto"><h2>${esc(room.title)}</h2>
      <p class="muted">${room.seats.length} of ${room.max} seats taken. ${room.owner ? 'You are the host.' : 'Waiting for the host to start.'}</p>
      <div class="seat-list">${seats}</div>
      <div class="top-actions" style="margin-top:16px">
        ${room.owner ? `<button class="btn" id="addBot" ${room.seats.length >= room.max ? 'disabled' : ''}>Add a bot</button><button class="btn" id="start" ${canStart ? '' : 'disabled'}>Start the game</button>` : ''}
        <span class="spacer"></span><button class="btn secondary" id="leave">Leave</button></div>
      ${room.owner && !canStart ? '<p class="muted">Add at least one more wizard (or a bot) to start.</p>' : ''}</div></div>`;
  wireTop();
  const ab = $('#addBot'); if (ab) ab.onclick = () => send({ t: 'addBot' });
  const st = $('#start'); if (st) st.onclick = () => send({ t: 'start' });
  $('#leave').onclick = () => send({ t: 'leaveGame' });
  document.querySelectorAll('[data-remove]').forEach((b) => { b.onclick = () => send({ t: 'removeSeat', seat: Number(b.dataset.remove) }); });
}

// ---------------------------------------------------------------- the table
function hpColor(p) { const r = p.hp / p.maxHp; return r > 0.5 ? 'var(--green)' : r > 0.25 ? 'var(--gold)' : 'var(--red)'; }

function lwsHtml(n) {
  return `<span class="lws" title="Last Wizard Standing tokens">${[0, 1].map((i) => (i < n
    ? `<span class="tk full"><img src="${PIECE('lws')}" alt="" onerror="this.style.display='none';this.parentNode.style.background='var(--gold)'"></span>`
    : '<span class="tk"></span>')).join('')}</span>`;
}

function chipsHtml(p) {
  return p.treasures.map((t) => { cardCache[t.id] = t; return `<span class="chip" data-cid="${esc(t.id)}">${esc(t.name)}</span>`; }).join('')
    + (p.deadCards ? `<span class="chip dead">${p.deadCards} dead wizard card${p.deadCards > 1 ? 's' : ''}</span>` : '');
}

function foeSeatHtml(p, pickable) {
  const cls = ['fseat', p.alive ? '' : 'dead', activePid === p.id ? 'active' : '', pickable ? 'pickable' : ''].join(' ');
  const pct = Math.max(0, Math.round((p.hp / p.maxHp) * 100));
  const tags = `${p.bot ? 'bot' : ''}${!p.connected && !p.bot ? 'disconnected' : ''}${p.away ? ' (bot is playing)' : ''}`;
  const mini = p.spell ? p.spell.cards.map(() => `<i class="${p.spell.revealed ? 'up' : ''}"></i>`).join('') : '';
  return `<div class="${cls}" data-pid="${p.id}">
    <div class="top"><img class="portrait" src="${esc(p.hero.art)}?w=140" alt="" onerror="this.style.visibility='hidden'">
      <div class="who"><b>${esc(p.name)}</b><small>${esc(p.hero.name)}${p.hero.title ? ', ' + esc(p.hero.title) : ''}</small></div>${lwsHtml(p.tokens)}</div>
    <div class="hprow"><img class="sk" src="${PIECE('skull')}" alt="" onerror="this.style.display='none'">
      <div class="hpbar"><i style="width:${pct}%;background:${hpColor(p)}"></i><span>${p.alive ? `${p.hp} / ${p.maxHp} HP` : 'DEAD'}</span></div></div>
    <div class="chips">${chipsHtml(p)}</div>
    <div class="fmeta"><span>${p.alive ? 'Hand ' + p.handCount : ''}</span><span class="mini-spell">${mini}</span><span>${esc(tags)}</span></div></div>`;
}

function mineHtml(p, pickable) {
  const [x, y] = skullPos(hpShown[p.id] ?? p.hp);
  const cls = ['mine', p.alive ? '' : 'dead', pickable ? 'pickable' : ''].join(' ');
  return `<div class="${cls}" data-pid="${p.id}"><div class="board">
      <img class="bd" src="${esc(p.hero.board)}?w=700" alt="" onerror="this.style.minHeight='140px'">
      ${p.alive ? `<img class="skull" id="mySkull" src="${PIECE('skull')}" alt="" style="left:${x}%;top:${y}%" onerror="this.style.display='none'">` : ''}</div>
    <div class="row"><span class="hp">${p.alive ? `${p.hp} / ${p.maxHp} HP` : 'DEAD'}</span>${lwsHtml(p.tokens)}</div>
    <div class="chips">${chipsHtml(p)}</div></div>`;
}

function turnbarHtml() {
  const chips = locked.map((s) => {
    const p = game.players[s.player];
    const cls = ['tchip', activePid === s.player ? 'now' : '', p && p.acted ? 'done' : ''].join(' ');
    return `<span class="${cls}">${esc(nameOf(s.player))} &middot; ${s.components} card${s.components === 1 ? '' : 's'} &middot; Initiative ${s.initiative}</span>`;
  }).join('');
  const d = game.decks;
  const pile = (img, n, label) => `<div class="pile"><img src="${img}?w=80" alt="" onerror="this.style.display='none'">${label} ${n}</div>`;
  return `<div class="turnbar">${chips}<span class="piles">${pile(BACKS.main, d.main, 'Deck')}${pile(BACKS.treasure, d.treasure, 'Treasures')}${pile(BACKS.dead, d.deadWizard, 'Dead')}<div class="pile">Discard<br>${d.mainDiscard}</div></span></div>`;
}

function fcHtml(sc, flipped) {
  const active = resolving && focus && resolving.player === focus.player && sc.card && sc.card.name === resolving.card;
  const done = sc.resolved && !active;
  const cls = ['fc', flipped ? 'flipped' : '', active ? 'active' : '', done ? 'done' : ''].join(' ');
  return `<div class="${cls}"><div class="fc-in">
    <div class="fc-back"><img src="${BACKS.main}?w=400" alt=""></div>
    <div class="fc-front">${sc.card ? cardHtml(sc.card, '', 560) : ''}</div></div><div class="badge">&#10003;</div></div>`;
}

function focusHtml() {
  const p = game.players[focus.player];
  const order = { SOURCE: 0, QUALITY: 1, DELIVERY: 2 };
  const live = p.spell ? [...p.spell.cards].sort((a, b) => order[a.slot] - order[b.slot]) : [];
  // The engine clears a spell the moment its turn ends; keep showing the last cards until the next turn begins.
  if (live.length) focus.last = live;
  const cards = live.length ? live : (focus.last || []).map((c) => ({ ...c, resolved: true }));
  const flipped = (live.length ? !!(p.spell && p.spell.revealed) : true) && !focus.animateFlip;
  const ini = locked.find((s) => s.player === focus.player);
  const last = feed.length - 1;
  return `<div class="focus"><div class="fhead"><img src="${esc(p.hero.art)}?w=120" alt="" onerror="this.style.visibility='hidden'">
      <span>${esc(p.name)} casts!</span>${ini ? `<small>Initiative ${ini.initiative}</small>` : ''}</div>
    <div class="fbody"><div class="fcards" id="fcards">${cards.map((sc) => fcHtml(sc, flipped)).join('') || '<span class="muted">No cards played.</span>'}</div>
    <div class="feed">${feed.slice(-6).map((f, i, arr) => `<div class="k-${f.k} ${i === arr.length - 1 && feed.length - 1 === last ? 'new' : ''}">${esc(f.text)}</div>`).join('')}</div></div></div>`;
}

function stageHtml() {
  const parts = [];
  if (vote) parts.push(voteHtml());
  const building = prompt && prompt.kind === 'spell';
  if (building) { parts.push(promptHtml()); return parts.join(''); }
  parts.push(turnbarHtml());
  if (prompt) parts.push(promptHtml());
  if (focus && game.players[focus.player]) parts.push(focusHtml());
  else if (!prompt) parts.push(`<div class="waiting">${game.round ? 'Round ' + game.round + ': ' : ''}the wizards are choosing their spells...</div>`);
  return parts.join('');
}

function renderTable(app) {
  if (!game) { app.innerHTML = '<div class="center-box"><p class="muted">Setting up the table...</p></div>'; return; }
  const youIdx = game.you ? game.you.index : -1;
  const pickIds = prompt && prompt.kind === 'player' && !promptSent ? prompt.data.candidates : [];
  const foes = game.players.filter((p) => p.id !== youIdx);
  const me = game.players[youIdx];
  const choosing = prompt && prompt.kind === 'spell' && !promptSent;
  app.innerHTML = `<div class="table">
    <div class="topbar"><b>EPIC SPELL WARS</b><span>Game ${game.game} &middot; Round ${game.round}</span><span class="spacer"></span>
      <button class="btn secondary" id="mute">${muted ? 'Sound off' : 'Sound on'}</button><button class="btn secondary" id="leave">Leave</button></div>
    <div class="foes">${foes.map((p) => foeSeatHtml(p, pickIds.includes(p.id))).join('')}</div>
    <div class="stage" id="stage">${stageHtml()}</div>
    <div class="log" id="log">${logLines.map((l) => `<div>${esc(l)}</div>`).join('')}</div>
    <div class="bottom">${me ? mineHtml(me, pickIds.includes(me.id)) : ''}<div class="hand ${choosing ? 'live' : ''}" id="hand">${handHtml()}</div></div></div>
    ${gameOverHtml()}`;
  const log = $('#log'); if (log) log.scrollTop = log.scrollHeight;
  afterDraw(me);
  wireTable();
}

/** Small animations that need the new elements to exist first. */
function afterDraw(me) {
  if (focus && focus.animateFlip) {
    focus.animateFlip = false;
    setTimeout(() => document.querySelectorAll('#fcards .fc').forEach((el, i) => setTimeout(() => el.classList.add('flipped'), i * 220)), 150);
  }
  if (me && me.alive) {
    const skull = $('#mySkull');
    if (skull && hpShown[me.id] !== undefined && hpShown[me.id] !== me.hp) {
      const [x, y] = skullPos(me.hp);
      setTimeout(() => { skull.style.left = x + '%'; skull.style.top = y + '%'; }, 60);
    }
    hpShown[me.id] = me.hp;
  }
}

function voteHtml() {
  if (!vote) return '';
  return `<div class="vote"><b>${esc(vote.name)} has dropped out.</b> Let a bot play for them until they return?
    <button class="btn" data-vote="yes">Yes</button><button class="btn secondary" data-vote="no">No</button></div>`;
}

function gameOverHtml() {
  if (!room || room.state !== 'FINISHED') return '';
  const rows = [...game.players].sort((a, b) => b.tokens - a.tokens)
    .map((p) => `<tr><td>${esc(p.name)}</td><td>${esc(p.hero.name)}</td><td>${lwsHtml(p.tokens)}</td></tr>`).join('');
  return `<div class="gameover"><div class="box"><h1>${winnerName ? esc(winnerName) + ' wins!' : 'Match over'}</h1>
    <table>${rows}</table>
    <div class="top-actions" style="justify-content:center">${room.owner ? '<button class="btn" id="rematch">Play again</button>' : '<span class="muted">Waiting for the host...</span>'}
    <button class="btn secondary" id="leave2">Leave</button></div></div></div>`;
}

function handHtml() {
  if (!game.you) return '<span class="muted">You are watching.</span>';
  const choosing = prompt && prompt.kind === 'spell' && !promptSent;
  const picked = new Set([build.source, build.quality, build.delivery].filter((x) => x != null));
  const cards = choosing ? [...prompt.data.hand, ...prompt.data.gems] : game.you.hand;
  if (!cards.length) return '<span class="muted">No cards in hand.</span>';
  const order = { SOURCE: 0, QUALITY: 1, DELIVERY: 2, WILD_MAGIC: 3, TREASURE: 4 };
  return [...cards].sort((a, b) => (order[a.type] ?? 9) - (order[b.type] ?? 9))
    .map((c) => cardHtml(c, picked.has(c.uid) ? 'picked' : '', 420)).join('');
}

function promptHtml() {
  if (!prompt) return '';
  const d = prompt.data;
  if (prompt.kind === 'spell') {
    if (promptSent) return '<div class="waiting">Spell sealed. Waiting for the other wizards...</div>';
    const all = [...d.hand, ...d.gems];
    const slotBox = (key, label) => {
      const uid = build[key];
      const c = uid != null ? all.find((x) => x.uid === uid) : null;
      return `<div class="bslot s-${label.toUpperCase()} ${build.target === key ? 'target' : ''}" data-slot="${key}">${c ? cardHtml(c, '', 520) : label}</div>`;
    };
    const any = build.source != null || build.quality != null || build.delivery != null;
    return `<div class="prompt"><h3>Build your spell</h3>
      <p class="muted" style="margin:0 0 10px">Click or drag cards from your hand into the slots. You may play 1 to 3 cards, one of each type. Wild Magic fills the highlighted slot (click a slot to choose it).</p>
      <div class="builder">${slotBox('source', 'Source')}${slotBox('quality', 'Quality')}${slotBox('delivery', 'Delivery')}
      <button class="btn big" id="cast" ${any || !all.length ? '' : 'disabled'}>Cast it!</button></div></div>`;
  }
  const head = (text) => `<div class="prompt"><h3>${esc(text)}</h3>`;
  if (promptSent) return '';
  if (prompt.kind === 'player') {
    return `${head(d.reason)}<p class="muted" style="margin:0">Click a wizard, or pick below.</p><div class="choices">${d.candidates.map((id) => `<button class="btn" data-pick="${id}">${esc(nameOf(id))}</button>`).join('')}</div></div>`;
  }
  if (prompt.kind === 'treasure' || prompt.kind === 'card') {
    return `${head(d.reason)}<div class="choices">${d.candidates.map((c) => `<div data-pick="${c.uid}">${cardHtml(c, '', 420)}</div>`).join('')}</div></div>`;
  }
  if (prompt.kind === 'option') {
    return `${head(d.prompt)}<div class="choices">${d.options.map((o, i) => `<button class="btn" data-pick="${i}">${esc(o)}</button>`).join('')}</div></div>`;
  }
  if (prompt.kind === 'yesno') {
    return `${head(d.prompt)}<div class="choices"><button class="btn" data-yes="1">Yes</button><button class="btn secondary" data-yes="0">No</button></div></div>`;
  }
  return '';
}

function answer(value) {
  if (!prompt) return;
  send({ t: 'answer', pid: prompt.pid, value });
  promptSent = true;
  render();
}

function placeCard(uid, wantedSlot) {
  const all = [...prompt.data.hand, ...prompt.data.gems];
  const c = all.find((x) => x.uid === uid);
  if (!c) return;
  const keys = ['source', 'quality', 'delivery'];
  const used = keys.find((k) => build[k] === uid);
  if (used && !wantedSlot) { build[used] = null; return render(); }
  const typed = { SOURCE: 'source', QUALITY: 'quality', DELIVERY: 'delivery' }[c.type];
  if (typed) {
    if (wantedSlot && wantedSlot !== typed) { toast(`That is a ${c.type.toLowerCase()} card. It goes in the ${typed} slot.`); return; }
    if (used) build[used] = null;
    build[typed] = uid; build.target = null; return render();
  }
  // Wild Magic or a Proton Gem can fill any slot.
  const free = wantedSlot || (build.target && build[build.target] == null ? build.target : keys.find((k) => build[k] == null));
  if (!free) { toast('All three slots are full. Click a slot to clear it first.'); return; }
  if (used) build[used] = null;
  build[free] = uid; build.target = null; render();
}

function wireTable() {
  wireTop();
  const leave = () => { if (confirm('Leave this game? A bot will take your seat.') || (room && room.state === 'FINISHED')) send({ t: 'leaveGame' }); };
  const l1 = $('#leave'); if (l1) l1.onclick = leave;
  const l2 = $('#leave2'); if (l2) l2.onclick = () => send({ t: 'leaveGame' });
  const rm = $('#rematch'); if (rm) rm.onclick = () => send({ t: 'rematch' });
  document.querySelectorAll('[data-vote]').forEach((b) => {
    b.onclick = () => { send({ t: 'voteBot', seat: vote.seat, yes: b.dataset.vote === 'yes' }); vote = null; render(); };
  });
  if (!prompt || promptSent) return;

  if (prompt.kind === 'spell') {
    document.querySelectorAll('#hand .card').forEach((el) => {
      el.draggable = true;
      el.onclick = () => placeCard(Number(el.dataset.uid), null);
      el.ondragstart = (ev) => { ev.dataTransfer.setData('text/plain', el.dataset.uid); ev.dataTransfer.effectAllowed = 'move'; };
    });
    document.querySelectorAll('.bslot').forEach((el) => {
      const key = el.dataset.slot;
      el.onclick = () => {
        if (build[key] != null) { build[key] = null; build.target = key; } else build.target = build.target === key ? null : key;
        render();
      };
      el.ondragover = (ev) => { ev.preventDefault(); el.classList.add('over'); };
      el.ondragleave = () => el.classList.remove('over');
      el.ondrop = (ev) => { ev.preventDefault(); el.classList.remove('over'); placeCard(Number(ev.dataTransfer.getData('text/plain')), key); };
    });
    const cast = $('#cast');
    if (cast) cast.onclick = () => answer({ source: build.source, quality: build.quality, delivery: build.delivery });
    return;
  }
  document.querySelectorAll('[data-pick]').forEach((el) => { el.onclick = () => answer(Number(el.dataset.pick)); });
  document.querySelectorAll('[data-yes]').forEach((el) => { el.onclick = () => answer(el.dataset.yes === '1'); });
  if (prompt.kind === 'player') {
    document.querySelectorAll('.fseat.pickable, .mine.pickable').forEach((el) => { el.onclick = () => answer(Number(el.dataset.pid)); });
  }
}

// ---------------------------------------------------------------- go
connect();
render();
