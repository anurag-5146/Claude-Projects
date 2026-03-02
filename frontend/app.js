/* ── CIRCUIT — app.js ───────────────────────────────────────────────────── */
'use strict';

// ── State ─────────────────────────────────────────────────────────────────────
const state = {
  token:           null,
  ws:              null,
  mode:            'general',
  currentModel:    null,
  streaming:       false,
  paused:          false,
  countdownTimer:  null,
  countdownTotal:  5,
  currentAssistantBubble: null,
  fullResponse:    '',
  tokenCount:      0,
  msgCount:        0,
};

// ── DOM refs ──────────────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);

// ── Marked config ─────────────────────────────────────────────────────────────
marked.setOptions({
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) return hljs.highlight(code, { language: lang }).value;
    return hljs.highlightAuto(code).value;
  },
  breaks: true,
  gfm:    true,
});

// ── Circuit personality ────────────────────────────────────────────────────────
const CIRCUIT_GREETINGS = [
  "Aaya bhai! CIRCUIT hazir hai. Kya kaam hai?",
  "Bol bhai bol — Circuit sab handle karega!",
  "Bhai ki seva mein Circuit taiyyar hai!",
  "Hukum karo bhai, Circuit 100% aapka hai!",
  "Bhai aage badhoge, Circuit peechhe hai — always!",
];
const BOOT_LINES = [
  "> CIRCUIT OS v2.0 LOADING...",
  "> NEURAL CORES: ONLINE",
  "> BHAI DETECTION: ACTIVE",
  "> LOYALTY MODULE: 100%",
  "> READY — Haazir hoon bhai!",
];

// ── Helpers ───────────────────────────────────────────────────────────────────
function setStatus(s) {
  const dot   = $('status-dot');
  const label = $('status-label');
  const sfDot = $('sf-status');
  dot.className = `status-dot ${s}`;
  sfDot.className = `sf-dot ${s}`;
  label.textContent = s.toUpperCase();
}

function showScreen(name) {
  ['login', 'mode', 'chat'].forEach(n => {
    $(`${n}-screen`).classList.add('hidden');
  });
  $(`${name}-screen`).classList.remove('hidden');
}

function scrollBottom() {
  const m = $('messages');
  m.scrollTop = m.scrollHeight;
}

function updateHUD() {
  $('hud-model').textContent  = `MODEL: ${state.currentModel || '—'}`;
  $('hud-tokens').textContent = `TOKENS: ${state.tokenCount}`;
  $('hud-msgs').textContent   = `MSGS: ${state.msgCount}`;
  $('hud-mode').textContent   = `MODE: ${state.mode.toUpperCase()}`;
  $('sf-model').textContent   = state.currentModel || '—';
}

// ── Boot sequence (login screen) ──────────────────────────────────────────────
function runBootSequence() {
  const log = $('boot-log');
  if (!log) return;
  log.innerHTML = '';
  let i = 0;
  const interval = setInterval(() => {
    if (i >= BOOT_LINES.length) { clearInterval(interval); return; }
    const line = document.createElement('div');
    line.textContent = BOOT_LINES[i++];
    log.appendChild(line);
  }, 280);
}

// ── Login ─────────────────────────────────────────────────────────────────────
async function doLogin() {
  $('login-error').textContent = '';
  const pw = $('pw-input').value.trim();
  if (!pw) return;

  try {
    const res = await fetch('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: pw }),
    });
    if (!res.ok) { $('login-error').textContent = '[ ACCESS DENIED ]'; return; }
    const { token } = await res.json();
    state.token = token;
    localStorage.setItem('circuit_token', token);
    showScreen('mode');
  } catch {
    $('login-error').textContent = '[ SERVER UNREACHABLE ]';
  }
}

$('login-btn').onclick = doLogin;
$('pw-input').onkeydown = e => { if (e.key === 'Enter') doLogin(); };

// ── Mode picker ───────────────────────────────────────────────────────────────
document.querySelectorAll('.mode-btn').forEach(btn => {
  btn.onclick = () => {
    state.mode = btn.dataset.mode;
    $('mode-badge').textContent = state.mode;
    $('hud-mode').textContent   = `MODE: ${state.mode.toUpperCase()}`;
    showScreen('chat');
    connectWS();
    setTimeout(() => $('msg-input').focus(), 150);
  };
});

$('btn-change-mode').onclick = () => {
  if (state.ws) { state.ws.close(); state.ws = null; }
  showScreen('mode');
};

// ── WebSocket ─────────────────────────────────────────────────────────────────
function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url   = `${proto}://${location.host}/ws?token=${state.token}`;
  const ws    = new WebSocket(url);
  state.ws    = ws;

  ws.onopen = () => {
    setStatus('idle');
    ws.send(JSON.stringify({ type: 'get_sessions' }));
    // Show Circuit welcome message
    const greeting = CIRCUIT_GREETINGS[Math.floor(Math.random() * CIRCUIT_GREETINGS.length)];
    appendMsg('assistant', greeting, 'circuit');
    // Fetch model health on connect
    fetchModelHealth();
    // Keepalive ping every 20s
    state.pingInterval = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ping' }));
    }, 20000);
    // Refresh health every 2 mins
    state.healthInterval = setInterval(fetchModelHealth, 120000);
  };

  ws.onclose  = () => {
    clearInterval(state.pingInterval);
    clearInterval(state.healthInterval);
    setStatus('error');
  };

  ws.onerror  = () => setStatus('error');
  ws.onmessage = e => handleServerMsg(JSON.parse(e.data));
}

// ── Server message handler ────────────────────────────────────────────────────
function handleServerMsg(msg) {
  switch (msg.type) {

    case 'routing_decision':
      showRoutingBar(msg);
      break;

    case 'token':
      handleToken(msg.delta, msg.model);
      break;

    case 'done':
      handleDone(msg.model);
      break;

    case 'error':
      addSysNote(`ERROR: ${msg.message}`, true);
      setStatus('error');
      state.streaming = false;
      $('send-btn').disabled = false;
      break;

    case 'paused':
      state.paused    = true;
      state.streaming = false;
      setStatus('paused');
      $('send-btn').disabled = false;
      $('btn-pause').classList.add('paused-active');
      $('pause-icon').textContent = '▶';
      showPausedBanner();
      break;

    case 'resumed':
      state.paused = false;
      hidePausedBanner();
      setStatus('idle');
      $('btn-pause').classList.remove('paused-active');
      $('pause-icon').textContent = '⏸';
      break;

    case 'saved':
      addSysNote('SESSION SAVED.');
      break;

    case 'priority_set':
      updateCpuBtn(msg.level);
      break;

    case 'sessions':
      renderSessions(msg.sessions);
      break;

    case 'history':
      $('messages').innerHTML = '';
      state.fullResponse = '';
      (msg.messages || []).forEach(m => {
        if (m.content) appendMsg(m.role, m.content, m.role === 'assistant' ? 'circuit' : null);
      });
      if (msg.messages?.length) hljs.highlightAll();
      scrollBottom();
      break;

    case 'pipeline_line':
      appendPipelineLine(msg.text);
      break;

    case 'pipeline_done':
      const ok = msg.returncode === 0;
      appendPipelineLine(
        ok ? '✓ PIPELINE COMPLETE.' : `✗ PIPELINE FAILED (exit ${msg.returncode})`, ok ? 'done' : 'error'
      );
      setStatus('idle');
      break;

    case 'github_sync_start':
      $('git-sync-status').textContent = '⟳ Syncing to GitHub...';
      $('git-sync-status').style.color = 'var(--cyan)';
      break;

    case 'github_synced':
      $('git-sync-status').textContent =
        `✓ ${msg.pushed} files pushed to GitHub`;
      $('git-sync-status').style.color = 'var(--green, #39ff14)';
      if (msg.failed > 0) {
        $('git-sync-status').textContent += ` (${msg.failed} failed)`;
        $('git-sync-status').style.color = 'var(--orange)';
      }
      break;

    case 'pong': break;
  }
}

// ── Routing bar + countdown ring ──────────────────────────────────────────────
function showRoutingBar(msg) {
  $('routing-model').textContent  = (msg.labels?.[msg.chosen] || msg.chosen).toUpperCase();
  $('routing-reason').textContent = `— ${msg.reason}`;
  $('override-btns').innerHTML    = '';

  const total = msg.countdown;
  state.countdownTotal = total;
  updateRing(total, total);
  $('countdown-num').textContent = total;

  (msg.alternatives || []).forEach(altKey => {
    const label = msg.labels?.[altKey] || altKey;
    const btn   = document.createElement('button');
    btn.className   = 'override-btn';
    btn.textContent = label.toUpperCase();
    btn.onclick = () => {
      clearRoutingBar();
      send({ type: 'override_model', model: altKey });
    };
    $('override-btns').appendChild(btn);
  });

  $('routing-bar').classList.remove('hidden');
  setStatus('thinking');

  let remaining = total;
  state.countdownTimer = setInterval(() => {
    remaining--;
    $('countdown-num').textContent = remaining;
    updateRing(remaining, total);
    if (remaining <= 0) clearRoutingBar();
  }, 1000);
}

function updateRing(remaining, total) {
  const circumference = 94.2;
  const offset = circumference - (remaining / total) * circumference;
  const fill   = $('ring-fill');
  if (fill) {
    fill.style.strokeDashoffset = offset;
    fill.style.stroke = remaining <= 2 ? 'var(--orange)' : 'var(--cyan)';
  }
}

function clearRoutingBar() {
  clearInterval(state.countdownTimer);
  $('routing-bar').classList.add('hidden');
  $('override-btns').innerHTML = '';
}

// ── Token streaming ───────────────────────────────────────────────────────────
function handleToken(delta, model) {
  if (!state.streaming) {
    state.streaming   = true;
    state.fullResponse = '';
    state.currentModel = model;
    clearRoutingBar();
    setStatus('streaming');
    $('model-badge').textContent = model;
    $('sf-model').textContent    = model;

    const { bubble } = appendMsg('assistant', '', model);
    state.currentAssistantBubble = bubble;
  }
  state.fullResponse += delta;
  state.tokenCount   += delta.split(/\s+/).length;

  state.currentAssistantBubble.innerHTML =
    marked.parse(state.fullResponse) + '<span class="streaming-cursor"></span>';
  scrollBottom();
  updateHUD();
}

function handleDone(model) {
  state.streaming = false;
  state.msgCount  += 2; // user + assistant
  setStatus('idle');
  $('send-btn').disabled = false;

  if (state.currentAssistantBubble) {
    state.currentAssistantBubble.innerHTML = marked.parse(state.fullResponse);
    hljs.highlightAll();
    state.currentAssistantBubble = null;
  }
  updateHUD();
  scrollBottom();
}

// ── Message rendering ─────────────────────────────────────────────────────────
function appendMsg(role, text, model) {
  const wrap   = document.createElement('div');
  wrap.className = `msg ${role}`;

  const avatar = document.createElement('div');
  avatar.className = 'msg-avatar';
  avatar.textContent = role === 'user' ? 'BH' : 'CX';

  const right = document.createElement('div');
  right.className = 'msg-right';

  const bubble = document.createElement('div');
  bubble.className = 'msg-content';
  if (text) bubble.innerHTML = marked.parse(text);
  right.appendChild(bubble);

  if (role === 'assistant') {
    const meta = document.createElement('div');
    meta.className   = 'msg-meta';
    meta.textContent = model ? model.toUpperCase() : 'CIRCUIT';
    right.appendChild(meta);
  }

  if (role === 'user') { wrap.appendChild(right); wrap.appendChild(avatar); }
  else                 { wrap.appendChild(avatar); wrap.appendChild(right); }

  $('messages').appendChild(wrap);
  scrollBottom();
  return { wrap, bubble };
}

function addSysNote(text, isError = false) {
  const div = document.createElement('div');
  div.className   = `sys-note${isError ? ' error' : ''}`;
  div.textContent = text;
  $('messages').appendChild(div);
  scrollBottom();
}

// ── Model health ──────────────────────────────────────────────────────────────
async function fetchModelHealth() {
  try {
    const res = await fetch('/models', {
      headers: { 'Authorization': `Bearer ${state.token}` },
    });
    if (!res.ok) return;
    const data = await res.json();
    state.availableModels = data.available || [];
    state.degradedModels  = data.degraded  || [];

    if (state.degradedModels.length > 0) {
      const names = state.degradedModels
        .map(m => (data.labels?.[m] || m).toUpperCase())
        .join(', ');
      showHealthBanner(`⚠ ${names} DOWN — Circuit using fallback models. Tension not bhai!`);
    } else {
      hideHealthBanner();
    }
  } catch { /* network error — ignore */ }
}

function showHealthBanner(text) {
  let banner = $('health-banner');
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'health-banner';
    banner.style.cssText = [
      'position:fixed;top:0;left:0;right:0;z-index:200',
      'background:#150800;border-bottom:1px solid var(--orange)',
      'padding:5px 16px;font-family:var(--font-mono);font-size:11px',
      'color:var(--orange);text-align:center',
    ].join(';');
    document.body.appendChild(banner);
  }
  banner.textContent = text;
  banner.style.display = 'block';
}

function hideHealthBanner() {
  const b = $('health-banner');
  if (b) b.style.display = 'none';
}

// ── Send message ──────────────────────────────────────────────────────────────
function sendChat() {
  const text = $('msg-input').value.trim();
  if (!text || state.streaming || state.paused) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    addSysNote('NOT CONNECTED — RECONNECTING...', true);
    connectWS();
    return;
  }

  appendMsg('user', text, null);
  $('msg-input').value       = '';
  $('msg-input').style.height = '';
  $('send-btn').disabled     = true;

  send({ type: 'chat', message: text, mode: state.mode });
}

function send(payload) {
  if (state.ws?.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify(payload));
  }
}

$('send-btn').onclick = sendChat;
$('msg-input').onkeydown = e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(); }
};
$('msg-input').oninput = function () {
  this.style.height = '';
  this.style.height = Math.min(this.scrollHeight, 150) + 'px';
};

// ── Pause / Resume ────────────────────────────────────────────────────────────
$('btn-pause').onclick = () => {
  if (state.paused) {
    send({ type: 'resume' });
  } else {
    send({ type: 'pause' });
  }
};

function showPausedBanner() {
  let banner = $('paused-banner');
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'paused-banner';
    banner.innerHTML = `&#9646;&#9646; CIRCUIT PAUSED &mdash; Session saved. <button id="resume-btn">RESUME</button>`;
    document.body.appendChild(banner);
  }
  banner.style.display = 'flex';
  $('resume-btn').onclick = () => send({ type: 'resume' });
}

function hidePausedBanner() {
  const b = $('paused-banner');
  if (b) b.style.display = 'none';
}

// ── Save ──────────────────────────────────────────────────────────────────────
$('btn-save').onclick = () => {
  send({ type: 'save' });
};

// ── CPU priority toggle ───────────────────────────────────────────────────────
$('btn-cpu-toggle').onclick = () => {
  const current = $('btn-cpu-toggle').dataset.level;
  const next    = current === 'low' ? 'boost' : 'low';
  send({ type: 'set_priority', level: next });
};

function updateCpuBtn(level) {
  const btn = $('btn-cpu-toggle');
  btn.dataset.level = level;
  const icon = $('sa-icon', btn) || btn.querySelector('.sa-icon');
  if (level === 'boost') {
    btn.innerHTML = '<span class="sa-icon">⚡</span> Performance Mode';
    btn.classList.add('boosted');
    $('hud-cpu').textContent = 'CPU: BOOST';
  } else {
    btn.innerHTML = '<span class="sa-icon">🎮</span> Gaming Mode';
    btn.classList.remove('boosted');
    $('hud-cpu').textContent = 'CPU: GAMING';
  }
}

// ── Pipeline ──────────────────────────────────────────────────────────────────
function runPipeline(scriptKey) {
  $('pipeline-lines').innerHTML = '';
  $('pipeline-log').classList.remove('hidden');
  setStatus('streaming');
  send({ type: 'pipeline_run', script: scriptKey });
}

$('btn-run-pipeline').onclick = () => runPipeline('reddit');
$('btn-run-batch').onclick    = () => runPipeline('reddit_batch');
$('btn-close-log').onclick    = () => $('pipeline-log').classList.add('hidden');

$('btn-git-sync').onclick = () => {
  $('git-sync-status').textContent = '⟳ Starting sync...';
  $('git-sync-status').style.color = 'var(--cyan)';
  send({ type: 'github_push_code' });
};

function appendPipelineLine(text, cls = '') {
  const div = document.createElement('div');
  if (cls) div.classList.add(cls);
  div.textContent = text;
  $('pipeline-lines').appendChild(div);
  $('pipeline-lines').scrollTop = $('pipeline-lines').scrollHeight;
}

// ── Sessions ──────────────────────────────────────────────────────────────────
function renderSessions(sessions) {
  const list = $('session-list');
  list.innerHTML = '';
  [...sessions].reverse().forEach(s => {
    const div = document.createElement('div');
    div.className   = 'session-item';
    div.textContent = s.title || 'Untitled';
    div.title       = new Date(s.created).toLocaleString();
    div.onclick     = () => {
      document.querySelectorAll('.session-item').forEach(el => el.classList.remove('active'));
      div.classList.add('active');
      send({ type: 'get_history', session_id: s.id });
    };
    list.appendChild(div);
  });
}

$('btn-new-session').onclick = () => {
  $('messages').innerHTML = '';
  state.fullResponse = '';
  state.msgCount     = 0;
  state.tokenCount   = 0;
  showScreen('mode');
};

// ── Init ──────────────────────────────────────────────────────────────────────
(function init() {
  runBootSequence();
  const stored = localStorage.getItem('circuit_token');
  if (stored) {
    state.token = stored;
    showScreen('mode');
  } else {
    showScreen('login');
  }
  updateHUD();
})();
