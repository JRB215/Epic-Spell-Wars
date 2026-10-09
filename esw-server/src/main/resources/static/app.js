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
let resolving = null;
let vote = null;
let winnerName = null;
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
  reveal() { tone(200, 0.35, 'sawtooth', 0.05, 0, 800); },
  dice() { for (let i = 0; i < 6; i++) tone(300 + Math.random() * 500, 0.05, 'square', 0.04, i * 0.07); },
  hit() { tone(140, 0.25, 'sawtooth', 0.09, 0, 50); },
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
      lobby = m; room = null; game = null; prompt = null; vote = null; winnerName = null;
      screen = 'lobby'; render(); break;
    case 'room':
      room = m;
      if (m.state === 'LOBBY') { screen = 'room'; game = null; prompt = null; logLines = []; winnerName = null; vote = null; }
      else screen = 'table';
      render(); break;
    case 'state': game = m.state; render(); break;
    case 'log': logLines = m.lines; render(); break;
    case 'ev':
      game = m.state;
      if (m.event.text) logLines.push(m.event.text);
      render();
      applyEvent(m.event);
      break;
    case 'prompt':
      prompt = m;
      promptSent = false;
      if (m.kind === 'spell') build = { source: null, quality: null, delivery: null, target: null };
      render(); break;
    case 'promptDone':
      if (prompt && prompt.pid === m.pid) { prompt = null; promptSent = false; render(); }
      break;
    case 'vote': vote = m; render(); break;
    case 'error': toast(m.message); break;
    default: break;
  }
}

// ---------------------------------------------------------------- effects
function banner(text, big = false, ms = 1700) {
  const el = $('#banner');
  el.textContent = text;
  el.className = big ? 'big' : '';
  clearTimeout(banner.timer);
  banner.timer = setTimeout(() => el.classList.add('hidden'), ms);
}

function seatRect(pid) {
  const el = document.querySelector(`.seat[data-pid="${pid}"]`);
  return el ? el.getBoundingClientRect() : null;
}

function floatText(pid, text, cls) {
  const r = seatRect(pid);
  if (!r) return;
  const el = document.createElement('div');
  el.className = 'float ' + cls;
  el.textContent = text;
  el.style.position = 'fixed';
  el.style.left = (r.left + r.width / 2) + 'px';
  el.style.top = (r.top + r.height * 0.3) + 'px';
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 1700);
}

function shake(pid) {
  const el = document.querySelector(`.seat[data-pid="${pid}"]`);
  if (el) { el.classList.add('hit'); setTimeout(() => el.classList.remove('hit'), 400); }
}

function nameOf(pid) { return game && game.players[pid] ? game.players[pid].name : 'Someone'; }

function showDice(e) {
  const box = $('#dice');
  const faces = e.dice.length;
  box.className = '';
  const who = `${esc(nameOf(e.player))} &mdash; ${esc(e.reason)}`;
  const dieHtml = (v, rolling) => `<div class="die ${rolling ? 'rolling' : ''}">${v}</div>`;
  box.innerHTML = `<div class="who">${who}</div><div class="row">${e.dice.map(() => dieHtml('?', true)).join('')}</div><div class="total"></div>`;
  sound.dice();
  let n = 0;
  const spin = setInterval(() => {
    n++;
    box.querySelectorAll('.die').forEach((d) => { d.textContent = 1 + Math.floor(Math.random() * 6); });
    if (n > 6) {
      clearInterval(spin);
      box.querySelector('.row').innerHTML = e.dice.map((v) => dieHtml(v, false)).join('');
      const bonus = e.total !== e.dice.reduce((a, b) => a + b, 0);
      box.querySelector('.total').textContent = (faces > 1 || bonus) ? `Total ${e.total}` : '';
    }
  }, 100);
  clearTimeout(showDice.timer);
  showDice.timer = setTimeout(() => { clearInterval(spin); box.classList.add('hidden'); }, 1650);
}

function showSpotlight(pid) {
  const p = game && game.players[pid];
  if (!p || !p.spell) return;
  const cards = p.spell.cards.filter((c) => c.card);
  if (!cards.length) return;
  const box = $('#spotlight');
  box.className = '';
  box.innerHTML = `<div class="who">${esc(p.name)} casts!</div><div class="cards">${cards.map((c) => cardHtml(c.card, '', 420)).join('')}</div>`;
  sound.reveal();
  clearTimeout(showSpotlight.timer);
  showSpotlight.timer = setTimeout(() => box.classList.add('hidden'), 2200);
}

function applyEvent(e) {
  switch (e.k) {
    case 'roundStarted': activePid = null; resolving = null; banner(e.text, false, 1200); sound.round(); break;
    case 'gameStarted': winnerName = null; banner(e.text, true, 1400); break;
    case 'spellsLocked': banner('All spells are locked in!', false, 1200); break;
    case 'turnStarted': activePid = e.player; resolving = null; render(); banner(`${nameOf(e.player)}'s turn`, false, 800); break;
    case 'spellRevealed': showSpotlight(e.player); break;
    case 'cardResolving': resolving = { player: e.player, card: e.card }; render(); banner(`${nameOf(e.player)}: ${e.card}`, false, 1500); break;
    case 'dice': showDice(e); break;
    case 'damage':
      floatText(e.target, `-${e.amount}`, 'dmg'); shake(e.target);
      if (Date.now() - lastHitSound > 350) { sound.hit(); lastHitSound = Date.now(); }
      break;
    case 'heal': floatText(e.player, `+${e.amount}`, 'heal'); sound.heal(); break;
    case 'treasureGained': floatText(e.player, `+ ${e.treasure}`, 'gain'); break;
    case 'died': banner(`${nameOf(e.player)} is dead!`, true, 2000); sound.death(); break;
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

document.addEventListener('mouseover', (e) => {
  const el = e.target.closest ? e.target.closest('[data-cid]') : null;
  const zoom = $('#zoom');
  if (!el) { zoom.classList.add('hidden'); return; }
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

function slotHtml(p, sc) {
  const isResolving = resolving && resolving.player === p.id && sc.card && sc.card.name === resolving.card;
  const cls = `slot s-${sc.slot} ${isResolving ? 'resolving' : ''} ${sc.resolved && !isResolving ? 'done' : ''}`;
  if (!sc.card) return `<div class="${cls}"><div class="back">?</div></div>`;
  return `<div class="${cls}">${cardHtml(sc.card, 'small', 200)}</div>`;
}

function seatHtml(p, pickable) {
  const you = game.you && game.you.index === p.id;
  const cls = ['seat', you ? 'me' : '', p.alive ? '' : 'dead', activePid === p.id ? 'active' : '', pickable ? 'pickable' : ''].join(' ');
  const pct = Math.max(0, Math.round((p.hp / p.maxHp) * 100));
  const tokens = [0, 1].map((i) => `<span class="token ${i < p.tokens ? '' : 'empty'}"></span>`).join('');
  const chips = p.treasures.map((t) => { cardCache[t.id] = t; return `<span class="chip" data-cid="${esc(t.id)}">${esc(t.name)}</span>`; }).join('')
    + (p.deadCards ? `<span class="chip dead">${p.deadCards} dead wizard card${p.deadCards > 1 ? 's' : ''}</span>` : '');
  const order = { SOURCE: 0, QUALITY: 1, DELIVERY: 2 };
  const spell = p.spell ? [...p.spell.cards].sort((a, b) => order[a.slot] - order[b.slot]) : [];
  const tags = `${p.bot ? '<span class="tag">bot</span>' : ''}${!p.connected && !p.bot ? '<span class="tag">disconnected</span>' : ''}${p.away ? '<span class="tag">bot is playing</span>' : ''}`;
  return `<div class="${cls}" data-pid="${p.id}">
    <div class="seat-head"><img class="portrait" src="${esc(p.hero.art)}?w=160" alt="" onerror="this.style.visibility='hidden'">
      <div class="who"><b>${esc(p.name)}${you ? ' (you)' : ''}</b><small>${esc(p.hero.name)}${p.hero.title ? ', ' + esc(p.hero.title) : ''}</small></div>
      <div class="tokens" title="Last Wizard Standing tokens">${tokens}</div></div>
    <div class="hpbar"><i style="width:${pct}%;background:${hpColor(p)}"></i><span>${p.alive ? `${p.hp} / ${p.maxHp} HP` : 'DEAD'}</span></div>
    <div class="chips">${chips}</div>
    <div class="spell-row">${spell.map((sc) => slotHtml(p, sc)).join('')}</div>
    <div class="meta"><span>${p.alive ? 'Hand: ' + p.handCount : ''}</span><span>${tags}</span></div></div>`;
}

function renderTable(app) {
  if (!game) { app.innerHTML = '<div class="center-box"><p class="muted">Setting up the table...</p></div>'; return; }
  const youIdx = game.you ? game.you.index : -1;
  const pickIds = prompt && prompt.kind === 'player' && !promptSent ? prompt.data.candidates : [];
  const foes = game.players.filter((p) => p.id !== youIdx);
  const me = game.players[youIdx];
  const d = game.decks;
  app.innerHTML = `<div class="table">
    <div class="topbar"><b>EPIC SPELL WARS</b><span>Game ${game.game} &middot; Round ${game.round}</span>
      <span class="muted">Deck ${d.main} &middot; Discard ${d.mainDiscard} &middot; Treasures ${d.treasure}</span><span class="spacer"></span>
      <button class="btn secondary" id="mute">${muted ? 'Sound off' : 'Sound on'}</button><button class="btn secondary" id="leave">Leave</button></div>
    <div class="stage"><div class="foes">${foes.map((p) => seatHtml(p, pickIds.includes(p.id))).join('')}</div></div>
    <div class="log" id="log">${logLines.map((l) => `<div>${esc(l)}</div>`).join('')}</div>
    <div class="bottom">${voteHtml()}${promptHtml()}
      <div class="me-panel">${me ? seatHtml(me, pickIds.includes(me.id)) : ''}<div class="hand" id="hand">${handHtml()}</div></div></div></div>
    ${gameOverHtml()}`;
  const log = $('#log'); if (log) log.scrollTop = log.scrollHeight;
  wireTable();
}

function voteHtml() {
  if (!vote) return '';
  return `<div class="vote"><b>${esc(vote.name)} has dropped out.</b> Let a bot play for them until they return?
    <button class="btn" data-vote="yes">Yes</button><button class="btn secondary" data-vote="no">No</button></div>`;
}

function gameOverHtml() {
  if (!room || room.state !== 'FINISHED') return '';
  const rows = [...game.players].sort((a, b) => b.tokens - a.tokens)
    .map((p) => `<tr><td>${esc(p.name)}</td><td>${esc(p.hero.name)}</td><td>${'&#9679; '.repeat(p.tokens) || '-'}</td></tr>`).join('');
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
    .map((c) => cardHtml(c, `${choosing ? 'clickable' : ''} ${picked.has(c.uid) ? 'picked' : ''}`, 280)).join('');
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
      return `<div class="bslot s-${label.toUpperCase()} ${build.target === key ? 'target' : ''}" data-slot="${key}">${c ? cardHtml(c, '', 220) : label}</div>`;
    };
    const any = build.source != null || build.quality != null || build.delivery != null;
    return `<div class="prompt"><h3>Build your spell</h3>
      <p class="muted" style="margin:0 0 8px">Click cards in your hand to fill the slots. Wild Magic fills the highlighted slot (click a slot to choose it). You may play 1 to 3 cards, one of each type.</p>
      <div class="builder">${slotBox('source', 'Source')}${slotBox('quality', 'Quality')}${slotBox('delivery', 'Delivery')}
      <button class="btn" id="cast" ${any || !all.length ? '' : 'disabled'}>Cast it!</button></div></div>`;
  }
  const head = (text) => `<div class="prompt"><h3>${esc(text)}</h3>`;
  if (promptSent) return '';
  if (prompt.kind === 'player') {
    return `${head(d.reason)}<p class="muted" style="margin:0">Click a wizard, or pick below.</p><div class="choices">${d.candidates.map((id) => `<button class="btn" data-pick="${id}">${esc(nameOf(id))}</button>`).join('')}</div></div>`;
  }
  if (prompt.kind === 'treasure' || prompt.kind === 'card') {
    return `${head(d.reason)}<div class="choices">${d.candidates.map((c) => `<div data-pick="${c.uid}">${cardHtml(c, 'clickable', 260)}</div>`).join('')}</div></div>`;
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
    const all = [...prompt.data.hand, ...prompt.data.gems];
    document.querySelectorAll('#hand .card.clickable').forEach((el) => {
      el.onclick = () => {
        const uid = Number(el.dataset.uid);
        const c = all.find((x) => x.uid === uid);
        if (!c) return;
        const used = ['source', 'quality', 'delivery'].find((k) => build[k] === uid);
        if (used) { build[used] = null; return render(); }
        const key = { SOURCE: 'source', QUALITY: 'quality', DELIVERY: 'delivery' }[c.type];
        if (key) { build[key] = uid; build.target = null; return render(); }
        const free = build.target && build[build.target] == null ? build.target : ['source', 'quality', 'delivery'].find((k) => build[k] == null);
        if (!free) { toast('All three slots are full. Click a slot to clear it first.'); return; }
        build[free] = uid; build.target = null; render();
      };
    });
    document.querySelectorAll('.bslot').forEach((el) => {
      el.onclick = () => {
        const key = el.dataset.slot;
        if (build[key] != null) { build[key] = null; build.target = key; } else build.target = build.target === key ? null : key;
        render();
      };
    });
    const cast = $('#cast');
    if (cast) cast.onclick = () => answer({ source: build.source, quality: build.quality, delivery: build.delivery });
    return;
  }
  document.querySelectorAll('[data-pick]').forEach((el) => { el.onclick = () => answer(Number(el.dataset.pick)); });
  document.querySelectorAll('[data-yes]').forEach((el) => { el.onclick = () => answer(el.dataset.yes === '1'); });
  if (prompt.kind === 'player') {
    document.querySelectorAll('.seat.pickable').forEach((el) => { el.onclick = () => answer(Number(el.dataset.pid)); });
  }
}

// ---------------------------------------------------------------- go
connect();
render();
