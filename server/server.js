const express = require('express');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data.json');
const MAX_HISTORY = 2000; // số điểm lịch sử giữ lại cho mỗi điện thoại

let db = { devices: {} };
try {
  const saved = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  if (saved.devices) db = saved;
} catch {
  // chưa có dữ liệu
}

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.writeFile(DATA_FILE, JSON.stringify(db), (err) => {
      if (err) console.error('Không lưu được dữ liệu:', err.message);
    });
  }, 500);
}

const app = express();
app.use(express.json({ limit: '100kb' }));
app.use(express.static(path.join(__dirname, 'public')));

const deviceSockets = new Map(); // id điện thoại -> WebSocket
const viewers = new Set(); // các trang web đang mở

function publicDevice(d) {
  const { history, ...rest } = d;
  return { ...rest, online: deviceSockets.has(d.id) };
}

function sendJson(ws, msg) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function broadcast(msg) {
  for (const v of viewers) sendJson(v, msg);
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

function saveLocation(d, b) {
  const lat = num(b.lat);
  const lng = num(b.lng);
  if (lat === null || lng === null || Math.abs(lat) > 90 || Math.abs(lng) > 180) return false;
  const point = {
    lat,
    lng,
    accuracy: num(b.accuracy),
    speed: num(b.speed),
    battery: num(b.battery),
    time: num(b.time) || Date.now(),
    received: Date.now(),
  };
  d.last = point;
  d.history.push(point);
  if (d.history.length > MAX_HISTORY) d.history.splice(0, d.history.length - MAX_HISTORY);
  save();
  return true;
}

/** Gửi yêu cầu định vị tới một điện thoại (hoặc tất cả nếu không có id). */
function requestLocate(id, viewer) {
  const ids = id ? [id] : [...deviceSockets.keys()];
  for (const did of ids) {
    const ws = deviceSockets.get(did);
    if (ws) {
      sendJson(ws, { type: 'locate' });
      broadcast({ type: 'locating', id: did });
    } else if (viewer) {
      sendJson(viewer, { type: 'offline', id: did });
    }
  }
}

function handleDevice(ws, url) {
  const id = (url.searchParams.get('id') || '').slice(0, 64);
  if (!id) return ws.close(1008, 'missing id');

  const d = db.devices[id] || (db.devices[id] = { id, history: [] });
  d.name = (url.searchParams.get('name') || d.name || 'Điện thoại').slice(0, 50);
  d.device = (url.searchParams.get('device') || d.device || '').slice(0, 80);
  d.lastSeen = Date.now();
  save();

  const old = deviceSockets.get(id);
  if (old && old !== ws) old.terminate();
  deviceSockets.set(id, ws);
  broadcast({ type: 'device', device: publicDevice(d) });

  // Có người đang xem web thì lấy vị trí ngay khi điện thoại vừa kết nối lại.
  if (viewers.size) requestLocate(id);

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    d.lastSeen = Date.now();
    if (msg.type === 'location' && saveLocation(d, msg)) {
      broadcast({ type: 'device', device: publicDevice(d) });
    } else if (msg.type === 'error') {
      broadcast({ type: 'error', id, message: String(msg.message || 'Lỗi không rõ').slice(0, 200) });
    }
  });

  ws.on('close', () => {
    if (deviceSockets.get(id) !== ws) return;
    deviceSockets.delete(id);
    d.lastSeen = Date.now();
    save();
    broadcast({ type: 'device', device: publicDevice(d) });
  });
}

function handleViewer(ws) {
  viewers.add(ws);
  sendJson(ws, { type: 'devices', devices: Object.values(db.devices).map(publicDevice) });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === 'locate') requestLocate(msg.id ? String(msg.id) : null, ws);
  });

  ws.on('close', () => viewers.delete(ws));
}

app.get('/api/devices', (req, res) => {
  res.json(Object.values(db.devices).map(publicDevice));
});

app.get('/api/history/:id', (req, res) => {
  const d = db.devices[req.params.id];
  if (!d) return res.status(404).json({ error: 'Không tìm thấy' });
  const since = Number(req.query.since) || Date.now() - 24 * 3600 * 1000;
  res.json(d.history.filter((p) => p.time >= since));
});

app.delete('/api/devices/:id', (req, res) => {
  delete db.devices[req.params.id];
  save();
  res.json({ ok: true });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/ws/device') handleDevice(ws, url);
  else if (url.pathname === '/ws/viewer') handleViewer(ws);
  else ws.close(1008, 'unknown path');
});

// Ngắt các kết nối đã chết (điện thoại mất mạng mà không báo).
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

server.listen(PORT, () => {
  console.log(`Gia Đình SK server chạy tại http://localhost:${PORT}`);
});
