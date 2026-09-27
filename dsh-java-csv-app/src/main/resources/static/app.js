const state = {
  csvPath: localStorage.getItem('dsh.csv.path') || '',
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.csv.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.csv.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- analysis panels ----------

async function loadStats() {
  if (!state.csvPath) return;
  const data = await request('/api/csv/profile', { method: 'POST', body: JSON.stringify({ path: state.csvPath }) });
  $('#statsCard').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.dataRows}</b><span>数据行</span></div>
      <div class="metric"><b>${(data.columns || []).length}</b><span>列数</span></div>
    </div>` +
    (data.columns || []).map(col => {
      const numeric = col.numeric ? ` min ${col.numeric.min} ~ max ${col.numeric.max}` : '';
      return `<div class="bar-row">
        <span class="label" title="${escapeHtml(col.name)}">${escapeHtml(col.name)}</span>
        <span class="badge info">${escapeHtml(col.type)}</span>
        <span class="num">空 ${col.emptyCount} · 唯一 ${col.distinct}${numeric}</span>
      </div>`;
    }).join('');
}

async function loadErrors() {
  if (!state.csvPath) return;
  const data = await request('/api/csv/clean-plan', { method: 'POST', body: JSON.stringify({ path: state.csvPath }) });
  $('#errorList').innerHTML =
    `<div class="small muted">${data.dataRows} 行数据</div>` +
    (data.actions || []).map(item => `
      <div class="branch-row">
        <b>${escapeHtml(item.type)}</b>
        <span class="badge ${item.severity === 'safe' ? 'ok' : item.severity === 'info' ? 'info' : 'warn'}">${escapeHtml(item.severity)}</span>
        <div class="muted small">${escapeHtml(item.detail)}</div>
      </div>`).join('') || '<div class="muted small">数据很干净，无需清洗</div>' +
    `<div class="small muted" style="margin-top:8px">${escapeHtml(data.note || '')}</div>`;
}

async function loadStacks() {
  if (!state.csvPath) return;
  const data = await request('/api/csv/pivot', { method: 'POST', body: JSON.stringify({ path: state.csvPath }) });
  const key = data.groupBy;
  const max = Math.max(...(data.groups || []).map(g => g.sum || 0), 1);
  $('#stackList').innerHTML =
    `<div class="small muted">按 ${escapeHtml(key)} 汇总 ${escapeHtml(data.valueColumn)}</div>` +
    (data.groups || []).map(g => `
      <div class="bar-row">
        <span class="label">${escapeHtml(String(g[key]))}</span>
        <span class="bar" style="width:${Math.max(4, (g.sum || 0) / max * 110)}px"></span>
        <span class="num">${g.sum}（${g.count} 笔）</span>
      </div>`).join('');
}

async function loadTimeline() {
  if (!state.csvPath) return;
  const data = await request('/api/csv/outliers', { method: 'POST', body: JSON.stringify({ path: state.csvPath }) });
  if (data.message) {
    $('#timelineCard').innerHTML = `<div class="muted">${escapeHtml(data.message)}</div>`;
    return;
  }
  const b = data.bounds || {};
  $('#timelineCard').innerHTML =
    `<div class="small muted">列 ${escapeHtml(data.column)} · 正常区间 [${b.low} ~ ${b.high}]（IQR）</div>` +
    (data.outliers || []).map(o => `
      <div class="branch-row">
        <b>第 ${o.rowNo} 行</b>
        <span class="badge warn">值 ${o.value}</span>
      </div>`).join('') || '<div class="muted small">无离群值</div>';
}

async function loadTail() {
  if (!state.csvPath) return;
  const data = await request('/api/csv/head', { method: 'POST', body: JSON.stringify({ path: state.csvPath }) });
  const header = data.header || [];
  $('#tailCard').innerHTML =
    `<div class="small muted">共 ${data.totalRows} 行</div>
     <table style="border-collapse:collapse;font-size:11.5px;margin-top:6px">
       <thead><tr>${header.map(h => `<th style="border:1px solid var(--border);padding:2px 6px">${escapeHtml(h)}</th>`).join('')}</tr></thead>
       <tbody>${(data.rows || []).map(row => `<tr>${row.map(cell => `<td style="border:1px solid var(--border);padding:2px 6px">${escapeHtml(cell)}</td>`).join('')}</tr>`).join('')}</tbody>
     </table>`;
}

async function refreshAll() {
  await Promise.all([loadStats(), loadErrors(), loadStacks(), loadTimeline(), loadTail()]
    .map(p => p.catch(e => toast(e.message, true))));
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.csvPath) return toast('请先填写 CSV 路径并点击分析', true);
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(state.csvPath, text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(csvPath, message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message, csvPath }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#loadCsv').onclick = async () => {
  state.csvPath = $('#csvPath').value.trim();
  if (!state.csvPath) return toast('请填写 CSV 路径', true);
  localStorage.setItem('dsh.csv.path', state.csvPath);
  await refreshAll();
  toast('数据已分析');
};
$('#refreshResource').onclick = async () => {
  if (!state.csvPath) return toast('请先填写 CSV 路径', true);
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.csv.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.csv.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  if (state.csvPath) {
    $('#csvPath').value = state.csvPath;
    await refreshAll();
  }
})();
