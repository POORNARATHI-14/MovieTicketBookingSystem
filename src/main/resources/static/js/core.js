/* CineBook core: API client, auth session, navbar, toasts, dialogs, helpers. No secrets live here. */
(function () {
  'use strict';
  const AUTH_KEY = 'cb_auth';
  const CUR = '₹'; // the backend stores plain decimals; shown in rupees

  // ------------------------------------------------------------------ helpers
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  const money = n => CUR + Number(n || 0).toFixed(2);
  const parseDT = s => (s ? new Date(s) : null);
  const fmtDate = s => { const d = parseDT(s); return d ? d.toLocaleDateString(undefined, { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' }) : '-'; };
  const fmtTime = s => { const d = parseDT(s); return d ? d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '-'; };
  const fmtDateTime = s => (s ? fmtDate(s) + ', ' + fmtTime(s) : '-');
  const dateKey = s => String(s || '').slice(0, 10);
  const qs = name => new URLSearchParams(location.search).get(name);
  const el = id => document.getElementById(id);
  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12);
  }
  function posterStyle(title) {
    let h = 0; for (const c of String(title)) h = (h * 31 + c.charCodeAt(0)) % 360;
    return `background:linear-gradient(145deg,hsl(${h},65%,32%),hsl(${(h + 55) % 360},70%,18%))`;
  }
  function posterHtml(m) {
    // The backend has no poster field, so a generated placeholder is shown (no data is invented).
    return `<div class="poster" style="${posterStyle(m.title)}" role="img" aria-label="Poster placeholder for ${esc(m.title)}">
      <b class="initial">${esc(String(m.title).charAt(0).toUpperCase())}</b><span>${esc(m.title)}</span></div>`;
  }

  // ------------------------------------------------------------------ session
  const Auth = {
    get() {
      try {
        const a = JSON.parse(localStorage.getItem(AUTH_KEY) || 'null');
        if (a && a.expiresAt && Date.now() > a.expiresAt) { localStorage.removeItem(AUTH_KEY); return null; }
        return a;
      } catch (e) { return null; }
    },
    set(resp) {
      try {
        localStorage.setItem(AUTH_KEY, JSON.stringify({ token: resp.token, user: resp.user, expiresAt: Date.now() + resp.expiresInSeconds * 1000 }));
      } catch (e) { /* storage blocked: user simply has to log in again */ }
    },
    clear() { try { localStorage.removeItem(AUTH_KEY); } catch (e) { /* ignore */ } },
    user() { const a = this.get(); return a ? a.user : null; },
    isAdmin() { const u = this.user(); return !!u && u.role === 'ADMIN'; },
  };

  // ------------------------------------------------------------------ API client
  class ApiError extends Error {
    constructor(status, data) {
      super((data && data.message) || ('Request failed (' + status + ')'));
      this.status = status; this.data = data || {}; this.code = this.data.code; this.fieldErrors = this.data.fieldErrors;
    }
  }
  async function api(path, opts) {
    opts = opts || {};
    const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
    const a = Auth.get();
    if (a && opts.auth !== false) headers.Authorization = 'Bearer ' + a.token;
    let body;
    if (opts.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.body); }
    let res;
    try {
      res = await fetch('/api' + path, { method: opts.method || 'GET', headers, body });
    } catch (e) {
      throw new ApiError(0, { code: 'NETWORK', message: 'Cannot reach the server. Is the application running on this address?' });
    }
    let data = null;
    const text = await res.text();
    if (text) { try { data = JSON.parse(text); } catch (e) { data = { message: text }; } }
    if (!res.ok) {
      if (res.status === 401 && a && !opts.noRedirect) {
        Auth.clear();
        toast('Your session has expired. Please log in again.', 'error');
        setTimeout(() => { location.href = 'login.html?next=' + encodeURIComponent(location.pathname.replace(/^\//, '') + location.search); }, 900);
      }
      throw new ApiError(res.status, data);
    }
    // keep the HTTP status for flows that depend on it (201/202/402/409 on bookings)
    return opts.withStatus ? { status: res.status, data } : data;
  }
  function errText(e) {
    let m = (e && e.message) || 'Something went wrong';
    if (e && e.fieldErrors) m += ' - ' + Object.entries(e.fieldErrors).map(([k, v]) => k + ': ' + v).join('; ');
    return m;
  }

  // ------------------------------------------------------------------ UI
  function toast(msg, type) {
    let box = el('toasts');
    if (!box) { box = document.createElement('div'); box.id = 'toasts'; box.setAttribute('aria-live', 'polite'); document.body.appendChild(box); }
    const t = document.createElement('div');
    t.className = 'toast ' + (type || '');
    t.textContent = msg;
    box.appendChild(t);
    setTimeout(() => t.remove(), 5000);
  }
  function confirmDialog(title, message, okLabel) {
    return new Promise(resolve => {
      const bg = document.createElement('div');
      bg.className = 'modal-bg';
      bg.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="dlgT"><h3 id="dlgT">${esc(title)}</h3><p class="muted">${esc(message)}</p>
        <div class="actions"><button class="btn" data-r="0">Cancel</button><button class="btn danger" data-r="1">${esc(okLabel || 'Confirm')}</button></div></div>`;
      const done = v => { bg.remove(); document.removeEventListener('keydown', onKey); resolve(v); };
      const onKey = e => { if (e.key === 'Escape') done(false); };
      bg.addEventListener('click', e => { if (e.target === bg) done(false); const r = e.target.dataset && e.target.dataset.r; if (r) done(r === '1'); });
      document.addEventListener('keydown', onKey);
      document.body.appendChild(bg);
      bg.querySelector('[data-r="0"]').focus();
    });
  }
  const loading = () => '<div class="spinner" role="status" aria-label="Loading"></div>';
  const emptyState = (title, text, action) => `<div class="empty"><h3>${esc(title)}</h3><p>${esc(text || '')}</p>${action || ''}</div>`;
  const alertBox = (type, msg) => `<div class="alert ${type}" role="alert">${esc(msg)}</div>`;
  const statusTag = s => {
    const m = { CONFIRMED: 'ok', COMPLETED: 'ok', BOOKED: 'ok', AVAILABLE: 'ok', PENDING: 'warn', TEMPORARILY_LOCKED: 'warn', AWAITING_PAYMENT: 'warn', IN_PROGRESS: 'info', CANCELLED: 'bad', FAILED: 'bad', COMPENSATED: 'bad', REFUNDED: 'info' };
    return `<span class="tag ${m[s] || ''}">${esc(String(s || '-').replace(/_/g, ' '))}</span>`;
  };

  // ------------------------------------------------------------------ navbar / footer
  function renderNav(active) {
    const u = Auth.user();
    const link = (href, label, key) => `<a href="${href}" class="${active === key ? 'active' : ''}"${active === key ? ' aria-current="page"' : ''}>${label}</a>`;
    const right = u
      ? `<span class="muted small">Hi, ${esc(u.name)}</span>${u.role === 'ADMIN' ? '<a class="btn sm gold" href="admin.html">Admin</a>' : ''}<button class="btn sm" id="logoutBtn">Logout</button>`
      : `<a class="btn sm" href="login.html">Login</a><a class="btn sm primary" href="register.html">Register</a>`;
    const nav = el('nav');
    if (!nav) return;
    nav.className = 'nav';
    nav.innerHTML = `<div class="container"><a class="brand" href="index.html">Cine<span>Book</span></a>
      <button class="nav-toggle" id="navToggle" aria-label="Toggle menu" aria-expanded="false">&#9776;</button>
      <nav class="nav-links" aria-label="Main">${link('index.html', 'Home', 'home')}${link('movies.html', 'Movies', 'movies')}${link('theatres.html', 'Theatres', 'theatres')}${link('bookings.html', 'My Bookings', 'bookings')}</nav>
      <div class="nav-user">${right}</div></div>`;
    el('navToggle').onclick = () => { const o = nav.classList.toggle('open'); el('navToggle').setAttribute('aria-expanded', o); };
    const lo = el('logoutBtn');
    if (lo) lo.onclick = () => { Auth.clear(); location.href = 'index.html'; };
    const f = el('footer');
    if (f) f.innerHTML = '<div class="container">CineBook &middot; Movie Ticket Booking System &middot; payments are simulated (no real money)</div>';
  }

  /** Redirects to the login page when there is no valid session. Returns true when logged in. */
  function requireLogin() {
    if (Auth.get()) return true;
    location.href = 'login.html?next=' + encodeURIComponent(location.pathname.replace(/^\//, '') + location.search);
    return false;
  }

  // simple shared caches so list pages do not re-fetch the same show/movie repeatedly
  const cache = { shows: {}, movies: null };
  async function getShow(id) { return cache.shows[id] || (cache.shows[id] = await api('/shows/' + id, { auth: false })); }
  async function getMovies() { return cache.movies || (cache.movies = await api('/movies', { auth: false })); }

  window.CB = { esc, money, fmtDate, fmtTime, fmtDateTime, dateKey, qs, el, uuid, posterHtml, Auth, api, ApiError, errText, toast,
    confirmDialog, loading, emptyState, alertBox, statusTag, renderNav, requireLogin, getShow, getMovies };
})();
