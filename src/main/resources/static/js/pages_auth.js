/* Login + Register */
(function () {
  'use strict';
  const { el, api, Auth, errText, esc } = CB;
  const page = document.body.dataset.page;
  CB.renderNav(page);
  const next = () => { const n = CB.qs('next'); return n && /^[A-Za-z0-9_\-]+\.html(\?[^\s]*)?$/.test(n) ? n : 'index.html'; };

  function showError(e) {
    let h = `<div class="alert error" role="alert">${esc(e.status === 401 ? 'Invalid email or password.' : errText(e))}</div>`;
    el('msg').innerHTML = h;
  }
  async function submit(path, body, form) {
    const btn = el('submit'); btn.disabled = true; const label = btn.textContent; btn.innerHTML = '<span class="spinner inline"></span> Please wait';
    el('msg').innerHTML = '';
    try { return await api(path, { method: 'POST', body, auth: false, noRedirect: true }); }
    catch (e) { showError(e); return null; }
    finally { btn.disabled = false; btn.textContent = label; }
  }

  if (page === 'login') {
    if (Auth.get()) location.replace(next());
    el('loginForm').addEventListener('submit', async ev => {
      ev.preventDefault();
      const email = el('email').value.trim(), password = el('password').value;
      if (!email || !password) { el('msg').innerHTML = CB.alertBox('error', 'Please enter your email and password.'); return; }
      const r = await submit('/users/login', { email, password });
      if (r) { Auth.set(r); location.href = r.user.role === 'ADMIN' && !CB.qs('next') ? 'admin.html' : next(); }
    });
  } else {
    el('regForm').addEventListener('submit', async ev => {
      ev.preventDefault();
      const body = { name: el('name').value.trim(), email: el('email').value.trim(), password: el('password').value };
      if (!body.name || !body.email || !body.password) { el('msg').innerHTML = CB.alertBox('error', 'All fields are required.'); return; }
      const r = await submit('/users/register', body);
      if (r) {
        // register returns the user only, so log in with the same credentials to get a token
        const login = await submit('/users/login', { email: body.email, password: body.password });
        if (login) { Auth.set(login); location.href = 'index.html'; }
        else location.href = 'login.html';
      }
    });
  }
})();
