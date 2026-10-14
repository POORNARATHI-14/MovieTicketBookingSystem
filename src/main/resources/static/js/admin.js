/* Admin dashboard. Every request is authorised by the backend (ROLE_ADMIN); this page only hides the UI for non-admins. */
(function () {
  'use strict';
  const { esc, el, api, loading, emptyState, alertBox, errText, money, fmtDateTime, toast, statusTag } = CB;
  CB.renderNav('admin');

  const gate = el('gate');
  if (!CB.Auth.get()) { location.href = 'login.html?next=admin.html'; return; }
  if (!CB.Auth.isAdmin()) { gate.innerHTML = alertBox('error', 'Access denied. This area is only for administrators.'); return; }
  el('adminApp').classList.remove('hidden');
  const panel = el('panel');

  // verify the role with the server (the stored copy is only a hint)
  api('/users/me').then(u => { if (u.role !== 'ADMIN') { CB.Auth.clear(); location.reload(); } }).catch(() => {});

  const tabs = { dash, movies, theatres, shows, bookings };
  document.querySelectorAll('.side button').forEach(b => (b.onclick = () => {
    document.querySelectorAll('.side button').forEach(x => x.classList.toggle('active', x === b));
    tabs[b.dataset.tab]();
  }));

  // ------------------------------------------------------------ generic form modal
  function formModal(title, fields, initial, onSubmit) {
    return new Promise(resolve => {
      const bg = document.createElement('div');
      bg.className = 'modal-bg';
      const input = f => {
        const v = initial && initial[f.name] != null ? initial[f.name] : (f.value != null ? f.value : '');
        const common = `id="f_${f.name}" name="${f.name}" ${f.required ? 'required' : ''}`;
        if (f.type === 'select') return `<select ${common}>${f.options.map(o => `<option value="${esc(o.value)}" ${String(o.value) === String(v) ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`;
        if (f.type === 'textarea') return `<textarea ${common} maxlength="${f.max || 2000}">${esc(v)}</textarea>`;
        return `<input ${common} type="${f.type || 'text'}" value="${esc(v)}" ${f.min != null ? 'min="' + f.min + '"' : ''} ${f.max != null ? 'max="' + f.max + '"' : ''} ${f.step ? 'step="' + f.step + '"' : ''}>`;
      };
      bg.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="fmT"><h3 id="fmT">${esc(title)}</h3><div id="fmErr"></div>
        <form id="fm" novalidate>${fields.map(f => `<label for="f_${f.name}">${esc(f.label)}${f.required ? ' *' : ''}</label>${input(f)}<div class="field-error" id="e_${f.name}"></div>`).join('')}
        <div class="actions"><button type="button" class="btn" id="fmCancel">Cancel</button><button class="btn primary" id="fmOk">Save</button></div></form></div>`;
      document.body.appendChild(bg);
      const close = v => { bg.remove(); resolve(v); };
      el('fmCancel').onclick = () => close(false);
      bg.addEventListener('keydown', e => { if (e.key === 'Escape') close(false); });
      bg.querySelector('input,select,textarea').focus();
      el('fm').onsubmit = async ev => {
        ev.preventDefault();
        fields.forEach(f => (el('e_' + f.name).textContent = ''));
        el('fmErr').innerHTML = '';
        const data = {}; let bad = false;
        fields.forEach(f => {
          let v = el('f_' + f.name).value.trim();
          if (f.required && v === '') { el('e_' + f.name).textContent = f.label + ' is required'; bad = true; }
          data[f.name] = v === '' ? null : (f.type === 'number' || f.num ? Number(v) : v);
        });
        if (bad) return;
        const btn = el('fmOk'); btn.disabled = true;
        try { await onSubmit(data); close(true); }
        catch (e) {
          el('fmErr').innerHTML = alertBox('error', e.message || 'Failed');
          if (e.fieldErrors) Object.entries(e.fieldErrors).forEach(([k, m]) => { const n = el('e_' + k); if (n) n.textContent = m; });
          btn.disabled = false;
        }
      };
    });
  }

  // ------------------------------------------------------------ dashboard
  async function dash() {
    panel.innerHTML = loading();
    try {
      const [movies, theatres, shows, bookings] = await Promise.all([api('/movies'), api('/theatres'), api('/shows'), api('/admin/bookings?limit=500')]);
      const upcoming = shows.filter(s => new Date(s.showTime) > new Date());
      const confirmed = bookings.filter(b => b.status === 'CONFIRMED');
      const revenue = confirmed.reduce((a, b) => a + Number(b.totalAmount), 0);
      const stat = (n, l) => `<div class="stat"><b>${n}</b><span>${l}</span></div>`;
      panel.innerHTML = `<div class="stat-grid">${stat(movies.length, 'Movies')}${stat(theatres.length, 'Theatres')}${stat(upcoming.length + ' / ' + shows.length, 'Upcoming / all shows')}
        ${stat(bookings.length, 'Bookings (latest 500)')}${stat(confirmed.length, 'Confirmed bookings')}${stat(bookings.filter(b => b.status === 'PENDING').length, 'Pending')}
        ${stat(bookings.filter(b => b.status === 'CANCELLED').length, 'Cancelled / compensated')}${stat(money(revenue), 'Confirmed revenue (simulated)')}</div>
        ${shows.length ? '' : alertBox('info', 'No shows exist yet. Open the "Shows" tab and create one so customers can book seats.')}
        <h3 style="margin-top:28px">Recent bookings</h3>${bookingTable(bookings.slice(0, 8), shows, false)}`;
    } catch (e) { panel.innerHTML = alertBox('error', errText(e)); }
  }

  // ------------------------------------------------------------ movies
  const movieFields = [
    { name: 'title', label: 'Title', required: true }, { name: 'genre', label: 'Genre', required: true },
    { name: 'language', label: 'Language', required: true },
    { name: 'durationMin', label: 'Duration (minutes)', type: 'number', min: 1, max: 600, required: true },
    { name: 'description', label: 'Description', type: 'textarea' }];
  async function movies() {
    panel.innerHTML = loading();
    let list;
    try { list = await api('/movies'); } catch (e) { panel.innerHTML = alertBox('error', errText(e)); return; }
    panel.innerHTML = `<div class="row" style="margin-bottom:14px"><h2 style="margin:0">Movies</h2><span class="spacer"></span><button class="btn primary" id="add">+ Add movie</button></div>
      ${list.length ? `<div class="table-wrap"><table><thead><tr><th>ID</th><th>Title</th><th>Genre</th><th>Language</th><th>Duration</th><th></th></tr></thead><tbody>${list.map(m =>
        `<tr><td>${m.id}</td><td><b>${esc(m.title)}</b></td><td>${esc(m.genre)}</td><td>${esc(m.language)}</td><td>${m.durationMin} min</td>
        <td><div class="row"><button class="btn sm" data-edit="${m.id}">Edit</button><button class="btn sm danger" data-del="${m.id}">Delete</button></div></td></tr>`).join('')}</tbody></table></div>` : emptyState('No movies', 'Add the first movie.')}`;
    el('add').onclick = async () => { if (await formModal('Add movie', movieFields, null, d => api('/movies', { method: 'POST', body: d }))) { toast('Movie created', 'success'); movies(); } };
    panel.querySelectorAll('[data-edit]').forEach(b => (b.onclick = async () => {
      const m = list.find(x => x.id == b.dataset.edit);
      if (await formModal('Edit movie #' + m.id, movieFields, m, d => api('/movies/' + m.id, { method: 'PUT', body: d }))) { toast('Movie updated', 'success'); movies(); }
    }));
    panel.querySelectorAll('[data-del]').forEach(b => (b.onclick = async () => {
      const m = list.find(x => x.id == b.dataset.del);
      if (!(await CB.confirmDialog('Delete "' + m.title + '"?', 'This cannot be undone. Movies that still have shows cannot be deleted.', 'Delete'))) return;
      try { await api('/movies/' + m.id, { method: 'DELETE' }); toast('Movie deleted', 'success'); movies(); } catch (e) { toast(errText(e), 'error'); }
    }));
  }

  // ------------------------------------------------------------ theatres (backend supports list + create only)
  async function theatres() {
    panel.innerHTML = loading();
    let list;
    try { list = await api('/theatres'); } catch (e) { panel.innerHTML = alertBox('error', errText(e)); return; }
    panel.innerHTML = `<div class="row" style="margin-bottom:14px"><h2 style="margin:0">Theatres</h2><span class="spacer"></span><button class="btn primary" id="add">+ Add theatre</button></div>
      ${list.length ? `<div class="table-wrap"><table><thead><tr><th>ID</th><th>Name</th><th>City</th><th>Address</th><th>Screens</th></tr></thead><tbody>${list.map(t =>
        `<tr><td>${t.id}</td><td><b>${esc(t.name)}</b></td><td>${esc(t.city)}</td><td>${esc(t.address)}</td><td>${t.totalScreens}</td></tr>`).join('')}</tbody></table></div>` : emptyState('No theatres', 'Add the first theatre.')}
      <p class="muted small" style="margin-top:10px">The backend currently supports listing and creating theatres only (no edit or delete endpoint).</p>`;
    el('add').onclick = async () => {
      if (await formModal('Add theatre', [{ name: 'name', label: 'Name', required: true }, { name: 'city', label: 'City', required: true }, { name: 'address', label: 'Address', required: true },
        { name: 'totalScreens', label: 'Total screens', type: 'number', min: 1, max: 50, required: true }], null, d => api('/theatres', { method: 'POST', body: d }))) { toast('Theatre created', 'success'); theatres(); }
    };
  }

  // ------------------------------------------------------------ shows (backend supports list + create)
  async function shows() {
    panel.innerHTML = loading();
    let list, mv, th;
    try { [list, mv, th] = await Promise.all([api('/shows'), api('/movies'), api('/theatres')]); } catch (e) { panel.innerHTML = alertBox('error', errText(e)); return; }
    panel.innerHTML = `<div class="row" style="margin-bottom:14px"><h2 style="margin:0">Shows</h2><span class="spacer"></span><button class="btn primary" id="add">+ Create show</button></div>
      ${list.length ? `<div class="table-wrap"><table><thead><tr><th>ID</th><th>Movie</th><th>Theatre / Screen</th><th>Show time</th><th>Price</th><th>Seats (free/total)</th></tr></thead><tbody>${list.map(s =>
        `<tr><td>${s.id}</td><td><b>${esc(s.movieTitle)}</b></td><td>${esc(s.theatreName)}, ${esc(s.city)} - Screen ${s.screenNo}</td><td>${fmtDateTime(s.showTime)}</td><td>${money(s.price)}</td><td>${s.availableSeats}/${s.totalSeats}</td></tr>`).join('')}</tbody></table></div>`
        : emptyState('No shows yet', 'Create a show so customers can book seats. Seats are generated automatically.')}
      <p class="muted small" style="margin-top:10px">The backend supports listing and creating shows only (no edit or delete endpoint).</p>`;
    el('add').onclick = async () => {
      if (!mv.length || !th.length) { toast('Create at least one movie and one theatre first.', 'error'); return; }
      const fields = [
        { name: 'movieId', label: 'Movie', type: 'select', required: true, num: true, options: mv.map(m => ({ value: m.id, label: m.title + ' (' + m.language + ')' })) },
        { name: 'theatreId', label: 'Theatre', type: 'select', required: true, num: true, options: th.map(t => ({ value: t.id, label: t.name + ', ' + t.city + ' (' + t.totalScreens + ' screens)' })) },
        { name: 'screenNo', label: 'Screen number', type: 'number', min: 1, required: true },
        { name: 'showTime', label: 'Date & time (must be in the future)', type: 'datetime-local', required: true },
        { name: 'price', label: 'Ticket price', type: 'number', min: 0.01, step: '0.01', required: true },
        { name: 'rows', label: 'Rows (1-26, default 5)', type: 'number', min: 1, max: 26 },
        { name: 'seatsPerRow', label: 'Seats per row (1-30, default 10)', type: 'number', min: 1, max: 30 }];
      const ok = await formModal('Create show', fields, { screenNo: 1 }, d => {
        const body = { movieId: d.movieId, theatreId: d.theatreId, screenNo: d.screenNo, showTime: d.showTime && (d.showTime.length === 16 ? d.showTime + ':00' : d.showTime), price: d.price };
        if (d.rows != null) body.rows = d.rows;
        if (d.seatsPerRow != null) body.seatsPerRow = d.seatsPerRow;
        return api('/shows', { method: 'POST', body });
      });
      if (ok) { toast('Show created with seats', 'success'); shows(); }
    };
  }

  // ------------------------------------------------------------ bookings & payments overview
  function bookingTable(list, showList, actions) {
    if (!list.length) return emptyState('No bookings', 'Bookings made by customers appear here.');
    const sm = {}; (showList || []).forEach(s => (sm[s.id] = s));
    return `<div class="table-wrap"><table><thead><tr><th>ID</th><th>User</th><th>Movie / Show</th><th>Seat</th><th>Amount</th><th>Status</th><th>Saga</th><th>Reason</th><th>Created</th>${actions ? '<th></th>' : ''}</tr></thead><tbody>${list.map(b => {
      const s = sm[b.showId];
      return `<tr><td>#${b.id}</td><td>${b.userId}</td><td>${s ? '<b>' + esc(s.movieTitle) + '</b><br><span class="muted small">' + esc(s.theatreName) + ' - ' + fmtDateTime(s.showTime) + '</span>' : 'Show #' + b.showId}</td>
      <td>${esc(b.seatNumber || '-')}</td><td>${money(b.totalAmount)}</td><td>${statusTag(b.status)}</td><td>${b.sagaStatus ? statusTag(b.sagaStatus) : '-'}<br><span class="muted small">${esc(b.sagaStep || '')}</span></td>
      <td class="small muted">${esc(b.failureReason || '')}</td><td class="small">${fmtDateTime(b.createdAt)}</td>
      ${actions ? `<td>${b.status !== 'CANCELLED' ? `<button class="btn sm danger" data-cancel="${b.id}">Cancel</button>` : ''}</td>` : ''}</tr>`; }).join('')}</tbody></table></div>`;
  }
  async function bookings() {
    panel.innerHTML = loading();
    try {
      const [list, showList] = await Promise.all([api('/admin/bookings?limit=200'), api('/shows')]);
      panel.innerHTML = `<div class="row" style="margin-bottom:14px"><h2 style="margin:0">Bookings &amp; payments</h2><span class="spacer"></span><button class="btn sm" id="rl">Refresh</button></div>
        <p class="muted small">Latest 200 bookings from all customers. Payment outcome is shown through booking status, Saga state and failure reason (refunds are recorded in the reason).</p>${bookingTable(list, showList, true)}`;
      el('rl').onclick = bookings;
      panel.querySelectorAll('[data-cancel]').forEach(b => (b.onclick = async () => {
        if (!(await CB.confirmDialog('Cancel booking #' + b.dataset.cancel + '?', 'The seat is released and any captured payment is refunded.', 'Cancel booking'))) return;
        try { await api('/bookings/' + b.dataset.cancel + '/cancel', { method: 'POST' }); toast('Booking cancelled', 'success'); bookings(); } catch (e) { toast(errText(e), 'error'); }
      }));
    } catch (e) { panel.innerHTML = alertBox('error', errText(e)); }
  }

  dash();
})();
