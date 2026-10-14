/* Seat selection + booking flow, and My Bookings */
(function () {
  'use strict';
  const { esc, el, api, loading, emptyState, alertBox, errText, fmtDateTime, fmtDate, fmtTime, money, qs, uuid, statusTag, Auth, toast } = CB;
  const page = document.body.dataset.page;
  CB.renderNav(page === 'seats' ? 'movies' : page);

  // Test cards of the built-in payment SIMULATOR (see PaymentMethod enum). No real money is involved.
  const METHODS = [
    ['TEST_SUCCESS', 'Test card - payment succeeds'],
    ['TEST_DECLINED', 'Test card - payment declined'],
    ['TEST_TIMEOUT', 'Test card - gateway timeout'],
    ['TEST_CAPTURE_THEN_FAIL', 'Test card - charged, then fails (refund demo)'],
    ['TEST_RANDOM', 'Test card - random result'],
  ];
  const methodOptions = sel => METHODS.map(([v, l]) => `<option value="${v}" ${v === sel ? 'selected' : ''}>${l}</option>`).join('');
  const MAX_SEATS = 8;

  /** A response body from the booking/payment API that is a business result (e.g. 402/409) rather than an ApiError. */
  const resultBody = e => (e && e.data && typeof e.data.status === 'string' && e.data.id ? e.data : null);

  const paymentLabel = b => {
    if (b.status === 'CONFIRMED') return '<span class="tag ok">Paid</span>';
    if (b.status === 'PENDING') return b.sagaStatus === 'AWAITING_PAYMENT' ? '<span class="tag warn">Awaiting payment</span>' : '<span class="tag info">Processing</span>';
    const r = b.failureReason || '';
    if (/refund/i.test(r)) return '<span class="tag info">Refunded</span>';
    if (r.startsWith('PAYMENT_TIMEOUT')) return '<span class="tag bad">Timed out</span>';
    if (r.startsWith('PAYMENT')) return '<span class="tag bad">Failed</span>';
    return '<span class="tag">Not charged / reversed</span>';
  };

  // =================================================================== SEATS
  async function seatsPage() {
    const root = el('seatRoot');
    const showId = qs('showId');
    if (!showId) { root.innerHTML = alertBox('error', 'No show selected.'); return; }
    root.innerHTML = loading();
    let show, seats;
    try { [show, seats] = await Promise.all([api('/shows/' + showId, { auth: false }), api('/shows/' + showId + '/seats', { auth: false })]); }
    catch (e) { root.innerHTML = e.status === 404 ? emptyState('Show not found', '', '<a class="btn" href="movies.html">Browse movies</a>') : alertBox('error', errText(e)); return; }

    const started = new Date(show.showTime).getTime() <= Date.now();
    const selected = new Set();
    let busy = false;
    let attempt = null; // {keys:{seatId:key}} - reused when retrying so a retry can never double-book or double-charge

    root.innerHTML = `<p><a href="movie.html?id=${show.movieId}">&larr; ${esc(show.movieTitle)}</a></p>
      <div class="two-col"><div class="card"><h2 style="margin-bottom:2px">${esc(show.movieTitle)}</h2>
        <div class="muted">${esc(show.theatreName)}, ${esc(show.city)} &middot; Screen ${show.screenNo} &middot; ${fmtDateTime(show.showTime)} &middot; ${money(show.price)} per seat</div>
        ${started ? alertBox('warn', 'This show has already started. Booking is closed.') : ''}
        <div class="screen">SCREEN</div><div id="seatMap" class="seat-map" role="group" aria-label="Seat map"></div>
        <div class="legend"><span><i style="background:rgba(46,204,143,.15);border-color:#2f7a5b"></i>Available</span><span><i style="background:#ffb347;border-color:#ffb347"></i>Selected</span>
          <span><i style="background:rgba(245,185,66,.18);border-color:#7a6320"></i>Temporarily locked</span><span><i style="background:#2a2f45;border-color:#3a4060"></i>Booked</span></div></div>
      <aside class="card" aria-live="polite"><h3>Your selection</h3><div id="summary"></div>
        <label for="pm">Payment (simulator)</label><select id="pm">${methodOptions('TEST_SUCCESS')}</select>
        <label class="row" style="font-weight:400;margin-top:12px;flex-wrap:nowrap;align-items:flex-start"><input type="checkbox" id="payLater" style="width:auto;flex:none;margin-top:4px"> Reserve now, pay later (seat held for a limited time)</label>
        <button class="btn primary block" id="bookBtn" style="margin-top:16px" disabled>Book tickets</button>
        <div id="progress"></div></aside></div>`;

    function drawMap() {
      const rows = {};
      seats.forEach(s => {
        const m = /^([A-Za-z]+)(\d+)$/.exec(s.seatNumber);
        const r = m ? m[1] : s.seatNumber, n = m ? parseInt(m[2], 10) : 0;
        (rows[r] = rows[r] || []).push(Object.assign({ n }, s));
      });
      el('seatMap').innerHTML = Object.keys(rows).sort().map(r =>
        `<div class="seat-row"><span class="row-label">${esc(r)}</span>${rows[r].sort((a, b) => a.n - b.n).map(s => {
          const free = s.status === 'AVAILABLE' && !started;
          const sel = selected.has(s.id);
          const cls = sel ? 'selected' : s.status;
          return `<button type="button" class="seat ${cls}" data-id="${s.id}" ${free || sel ? '' : 'disabled'} aria-pressed="${sel}" aria-label="Seat ${esc(s.seatNumber)}, ${esc(s.status.replace('_', ' ').toLowerCase())}${sel ? ', selected' : ''}" title="${esc(s.seatNumber)} - ${esc(s.status.replace('_', ' '))}">${esc(s.seatNumber)}</button>`;
        }).join('')}<span class="row-label">${esc(r)}</span></div>`).join('');
      el('seatMap').querySelectorAll('.seat').forEach(b => (b.onclick = () => toggle(Number(b.dataset.id))));
    }
    function drawSummary() {
      const list = seats.filter(s => selected.has(s.id)).sort((a, b) => a.seatNumber.localeCompare(b.seatNumber, undefined, { numeric: true }));
      el('summary').innerHTML = list.length
        ? `<div class="summary-line"><span>Seats</span><b>${list.map(s => esc(s.seatNumber)).join(', ')}</b></div>
           <div class="summary-line"><span>${list.length} x ${money(show.price)}</span><b>${money(list.length * Number(show.price))}</b></div>
           <div class="summary-line"><span>Total</span><b style="font-size:1.2rem">${money(list.length * Number(show.price))}</b></div>`
        : '<p class="muted">Select one or more available seats.</p>';
      el('bookBtn').disabled = busy || started || !list.length;
    }
    function toggle(id) {
      if (busy) return;
      if (selected.has(id)) selected.delete(id);
      else { if (selected.size >= MAX_SEATS) { toast('You can select at most ' + MAX_SEATS + ' seats at a time.', 'error'); return; } selected.add(id); }
      attempt = null; drawMap(); drawSummary();
    }
    async function refresh(quiet) {
      try {
        seats = await api('/shows/' + showId + '/seats', { auth: false });
        let dropped = 0;
        selected.forEach(id => { const s = seats.find(x => x.id === id); if (!s || s.status !== 'AVAILABLE') { selected.delete(id); dropped++; } });
        if (dropped && !busy) toast(dropped + ' selected seat(s) were just taken by someone else and were removed from your selection.', 'error');
        drawMap(); drawSummary();
      } catch (e) { if (!quiet) toast(errText(e), 'error'); }
    }
    drawMap(); drawSummary();
    const timer = setInterval(() => { if (!busy && !document.hidden) refresh(true); }, 8000);
    window.addEventListener('beforeunload', () => clearInterval(timer));

    // ------------------------------------------------ booking (one Saga per seat: the API books ONE seat per request)
    el('bookBtn').onclick = async () => {
      if (!Auth.get()) { location.href = 'login.html?next=' + encodeURIComponent('seats.html?showId=' + showId); return; }
      const list = seats.filter(s => selected.has(s.id));
      if (!list.length) return;
      busy = true; drawMap(); drawSummary(); el('bookBtn').disabled = true;
      attempt = attempt || { keys: {} };
      const autoPay = !el('payLater').checked, paymentMethod = el('pm').value;
      const rows = list.map(s => ({ seat: s, state: 'queued', text: 'Waiting...' }));
      const draw = () => {
        el('progress').innerHTML = `<h3 style="margin-top:20px">Booking progress</h3><ul class="progress-list">${rows.map(r =>
          `<li><span><b>${esc(r.seat.seatNumber)}</b><br><span class="small muted">${esc(r.text)}</span></span><span>${r.state === 'working' ? '<span class="spinner inline"></span>' : r.tag || ''}</span></li>`).join('')}</ul>`;
      };
      draw();
      for (const r of rows) {
        r.state = 'working'; r.text = autoPay ? 'Locking seat and processing payment...' : 'Reserving seat...'; draw();
        const key = attempt.keys[r.seat.id] = attempt.keys[r.seat.id] || uuid();
        try {
          const res = await api('/bookings', { method: 'POST', headers: { 'Idempotency-Key': key }, body: { showId: show.id, seatId: r.seat.id, paymentMethod, autoPay }, withStatus: true });
          present(r, res.data);
        } catch (e) {
          const b = resultBody(e);
          if (b) present(r, b); // 402 payment failed / 409 compensated: the body is the final booking
          else if (e.status === 0) { r.state = 'error'; r.tag = '<span class="tag warn">Unknown</span>'; r.text = 'Network problem - the result is unknown. Press "Book tickets" again to safely retry (same idempotency key).'; }
          else { r.state = 'error'; r.tag = '<span class="tag bad">Failed</span>'; r.text = e.code === 'SEAT_UNAVAILABLE' ? 'Someone else booked or is holding this seat. It has not been charged.' : errText(e); }
        }
        draw();
      }
      busy = false;
      const unknown = rows.some(r => r.tag && r.tag.includes('Unknown'));
      if (!unknown) attempt = null;
      rows.forEach(r => { if (r.final) selected.delete(r.seat.id); });
      await refresh(true); // always re-read the real state from the server
      const ok = rows.filter(r => r.final === 'CONFIRMED').length;
      if (ok) el('progress').insertAdjacentHTML('beforeend', `<div class="alert success">${ok} seat(s) confirmed. <a href="bookings.html">View My Bookings</a></div>`);
      const pend = rows.filter(r => r.final === 'PENDING').length;
      if (pend) el('progress').insertAdjacentHTML('beforeend', `<div class="alert info">Seat(s) reserved but not paid yet. <a href="bookings.html">Pay from My Bookings</a> before the hold expires.</div>`);
    };

    function present(r, b) {
      r.final = b.status;
      if (b.status === 'CONFIRMED') { r.tag = '<span class="tag ok">Confirmed</span>'; r.text = 'Booking #' + b.id + ' confirmed. Amount: ' + money(b.totalAmount); }
      else if (b.status === 'PENDING') {
        r.tag = '<span class="tag warn">Pending</span>';
        r.text = 'Booking #' + b.id + (b.sagaStatus === 'AWAITING_PAYMENT' ? ' reserved. Pay before ' + fmtTime(b.lockExpiresAt) + '.' : ' is still being processed. Check My Bookings shortly.');
      } else {
        r.tag = '<span class="tag bad">Cancelled</span>';
        r.text = 'Booking #' + b.id + ' was cancelled and the seat released' + (b.failureReason ? ' - ' + b.failureReason : '') + '. (Saga: ' + (b.sagaStatus || '-') + ')';
      }
    }
  }

  // =================================================================== MY BOOKINGS
  async function bookingsPage() {
    if (!CB.requireLogin()) return;
    const root = el('bookingRoot');
    let timer;
    async function load(showSpinner) {
      if (showSpinner) root.innerHTML = loading();
      let list;
      try { list = await api('/bookings'); } catch (e) { root.innerHTML = alertBox('error', errText(e)); return; }
      const info = {};
      await Promise.all([...new Set(list.map(b => b.showId))].map(async id => { try { info[id] = await CB.getShow(id); } catch (e) { /* show may be gone */ } }));
      if (!list.length) { root.innerHTML = emptyState('No bookings yet', 'Your bookings will appear here.', '<a class="btn primary" href="movies.html">Book a movie</a>'); return; }
      root.innerHTML = `<div class="row" style="margin-bottom:12px"><span class="muted small">${list.length} booking(s)</span><span class="spacer"></span><button class="btn sm" id="reload">Refresh</button></div>
        <div class="table-wrap"><table><thead><tr><th>ID</th><th>Movie / Theatre</th><th>Show time</th><th>Seat</th><th>Amount</th><th>Booking</th><th>Payment</th><th>Details</th><th></th></tr></thead><tbody>
        ${list.map(b => { const s = info[b.showId];
          const canPay = b.status === 'PENDING' && b.sagaStatus === 'AWAITING_PAYMENT' && b.lockExpiresAt;
          const canCancel = b.status === 'PENDING' || b.status === 'CONFIRMED';
          const started = s && new Date(s.showTime).getTime() <= Date.now();
          return `<tr><td>#${b.id}</td><td><b>${esc(s ? s.movieTitle : 'Show #' + b.showId)}</b><br><span class="muted small">${esc(s ? s.theatreName + ', ' + s.city + ' - Screen ' + s.screenNo : '')}</span></td>
          <td>${s ? fmtDateTime(s.showTime) : '-'}</td><td>${esc(b.seatNumber || '-')}</td><td>${money(b.totalAmount)}</td><td>${statusTag(b.status)}</td><td>${paymentLabel(b)}</td>
          <td class="small muted">${canPay ? 'Hold expires ' + fmtTime(b.lockExpiresAt) + '<br>' : ''}${b.failureReason ? esc(b.failureReason) + '<br>' : ''}${b.sagaStatus ? 'Saga: ' + esc(b.sagaStatus) : ''}<br>Booked ${fmtDateTime(b.createdAt)}</td>
          <td><div class="row">${canPay ? `<button class="btn sm primary" data-pay="${b.id}" data-amt="${b.totalAmount}">Pay</button>` : ''}${canCancel && !started ? `<button class="btn sm danger" data-cancel="${b.id}">Cancel</button>` : ''}</div></td></tr>`; }).join('')}
        </tbody></table></div>`;
      el('reload').onclick = () => load(true);
      root.querySelectorAll('[data-cancel]').forEach(b => (b.onclick = () => cancel(Number(b.dataset.cancel), list)));
      root.querySelectorAll('[data-pay]').forEach(b => (b.onclick = () => pay(Number(b.dataset.pay), b.dataset.amt)));
      clearTimeout(timer);
      if (list.some(b => b.status === 'PENDING')) timer = setTimeout(() => load(false), 5000); // follow Saga progress / hold expiry
    }

    async function cancel(id, list) {
      const b = list.find(x => x.id === id);
      const ok = await CB.confirmDialog('Cancel booking #' + id + '?', b && b.status === 'CONFIRMED' ? 'The seat will be released and your payment refunded.' : 'The held seat will be released.', 'Cancel booking');
      if (!ok) return;
      try { const r = await api('/bookings/' + id + '/cancel', { method: 'POST' }); toast('Booking #' + id + ' is now ' + r.status + '.', 'success'); }
      catch (e) { toast(errText(e), 'error'); }
      load(true);
    }

    function pay(id, amount) {
      const bg = document.createElement('div');
      bg.className = 'modal-bg';
      bg.innerHTML = `<div class="modal" role="dialog" aria-modal="true"><h3>Pay for booking #${id}</h3><p class="muted">Amount: <b>${money(amount)}</b> (simulated payment)</p>
        <label for="payM">Test card</label><select id="payM">${methodOptions('TEST_SUCCESS')}</select><div id="payMsg"></div>
        <div class="actions"><button class="btn" id="payNo">Close</button><button class="btn primary" id="payGo">Pay now</button></div></div>`;
      document.body.appendChild(bg);
      const key = uuid(); // one key per dialog: a double-click or retry cannot charge twice
      el('payNo').onclick = () => bg.remove();
      el('payGo').onclick = async () => {
        const btn = el('payGo'); btn.disabled = true; btn.innerHTML = '<span class="spinner inline"></span> Processing';
        let msg = '', done = false;
        const show = p => {
          if (p.status === 'COMPLETED') { msg = alertBox('success', 'Payment completed. Booking status: ' + p.bookingStatus + '.'); done = true; }
          else if (p.status === 'FAILED') msg = alertBox('error', 'Payment failed: ' + (p.failureReason || 'declined') + '. Booking status: ' + p.bookingStatus + '.');
          else if (p.status === 'REFUNDED') { msg = alertBox('warn', 'Payment was refunded: the seat hold had expired. Booking status: ' + p.bookingStatus + '.'); done = true; }
          else msg = alertBox('info', 'Payment is still processing.');
        };
        try { show(await api('/payments', { method: 'POST', body: { bookingId: id, amount: Number(amount), paymentMethod: el('payM').value, idempotencyKey: key } })); }
        catch (e) { if (e.data && typeof e.data.status === 'string' && e.data.bookingId) show(e.data); else msg = alertBox('error', errText(e)); }
        el('payMsg').innerHTML = msg;
        btn.textContent = 'Pay now'; btn.disabled = false;
        el('payNo').onclick = () => { bg.remove(); load(true); };
        if (done) btn.classList.add('hidden');
        load(false);
      };
    }
    load(true);
  }

  ({ seats: seatsPage, bookings: bookingsPage }[page] || (() => {}))();
})();
