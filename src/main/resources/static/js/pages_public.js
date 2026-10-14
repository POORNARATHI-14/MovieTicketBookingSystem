/* Home, Movies, Movie details, Theatres */
(function () {
  'use strict';
  const { esc, el, api, loading, emptyState, alertBox, posterHtml, errText, fmtDate, fmtTime, money, dateKey, qs } = CB;
  const page = document.body.dataset.page;
  CB.renderNav(page === 'movie' ? 'movies' : page);

  function movieCard(m) {
    return `<article class="movie-card">${posterHtml(m)}<div class="body"><h3>${esc(m.title)}</h3>
      <div class="tags"><span class="tag">${esc(m.genre)}</span><span class="tag">${esc(m.language)}</span><span class="tag">${m.durationMin} min</span></div>
      <div class="spacer"></div><a class="btn primary block" href="movie.html?id=${m.id}">Book Tickets</a></div></article>`;
  }

  // ---------------------------------------------------------------- HOME
  async function home() {
    const box = el('featured');
    box.innerHTML = loading();
    try {
      const [movies, allShows] = await Promise.all([api('/movies', { auth: false }), api('/shows', { auth: false })]);
      const shows = allShows.filter(s => new Date(s.showTime).getTime() > Date.now()); // /api/shows also returns past shows
      const withShows = new Set(shows.map(s => s.movieId));
      const sorted = movies.slice().sort((a, b) => (withShows.has(b.id) ? 1 : 0) - (withShows.has(a.id) ? 1 : 0));
      box.innerHTML = sorted.length ? sorted.slice(0, 8).map(movieCard).join('') : emptyState('No movies yet', 'An administrator needs to add movies first.');
      const upcoming = shows.slice(0, 6);
      el('upcoming').innerHTML = upcoming.length
        ? upcoming.map(s => `<a class="card" style="display:block;text-decoration:none;color:inherit" href="seats.html?showId=${s.id}"><b>${esc(s.movieTitle)}</b>
            <div class="muted small">${esc(s.theatreName)}, ${esc(s.city)} &middot; Screen ${s.screenNo}</div>
            <div class="row" style="margin-top:8px"><span class="tag info">${fmtDate(s.showTime)}, ${fmtTime(s.showTime)}</span><span class="tag ${s.availableSeats ? 'ok' : 'bad'}">${s.availableSeats}/${s.totalSeats} seats</span><span class="spacer"></span><b>${money(s.price)}</b></div></a>`).join('')
        : emptyState('No shows scheduled yet', 'Once an admin creates shows, they appear here.');
    } catch (e) { box.innerHTML = alertBox('error', errText(e)); }
  }

  // ---------------------------------------------------------------- MOVIES (filters)
  async function movies() {
    const grid = el('movieGrid');
    const f = { title: el('fTitle'), genre: el('fGenre'), language: el('fLanguage') };
    let all = [];
    try { all = await api('/movies', { auth: false }); } catch (e) { grid.innerHTML = alertBox('error', errText(e)); return; }
    const fill = (sel, vals) => { sel.innerHTML = '<option value="">All</option>' + [...new Set(vals)].sort().map(v => `<option>${esc(v)}</option>`).join(''); };
    fill(f.genre, all.map(m => m.genre)); fill(f.language, all.map(m => m.language));
    let t;
    async function run() {
      grid.innerHTML = loading();
      const p = new URLSearchParams();
      Object.entries(f).forEach(([k, i]) => { if (i.value.trim()) p.set(k, i.value.trim()); });
      try { // server-side filtering through the existing GET /api/movies?genre&language&title
        const list = await api('/movies' + (p.toString() ? '?' + p : ''), { auth: false });
        grid.innerHTML = list.length ? list.map(movieCard).join('') : emptyState('No movies match your filters', 'Try clearing the search.');
      } catch (e) { grid.innerHTML = alertBox('error', errText(e)); }
    }
    f.title.addEventListener('input', () => { clearTimeout(t); t = setTimeout(run, 300); });
    f.genre.onchange = f.language.onchange = run;
    el('fClear').onclick = () => { Object.values(f).forEach(i => (i.value = '')); run(); };
    run();
  }

  // ---------------------------------------------------------------- MOVIE DETAILS
  async function movieDetails() {
    const id = qs('id');
    const root = el('movieRoot');
    if (!id) { root.innerHTML = alertBox('error', 'No movie selected.'); return; }
    root.innerHTML = loading();
    try {
      const [m, shows] = await Promise.all([api('/movies/' + id, { auth: false }), api('/shows?movieId=' + encodeURIComponent(id), { auth: false })]);
      document.title = m.title + ' - CineBook';
      const now = Date.now();
      const upcoming = shows.filter(s => new Date(s.showTime).getTime() > now);
      const days = [...new Set(upcoming.map(s => dateKey(s.showTime)))].sort();
      root.innerHTML = `<div class="card two-col" style="grid-template-columns:240px 1fr"><div>${posterHtml(m)}</div><div>
          <h1>${esc(m.title)}</h1><div class="tags"><span class="tag">${esc(m.genre)}</span><span class="tag">${esc(m.language)}</span><span class="tag">${m.durationMin} min</span></div>
          <p class="muted" style="margin-top:14px">${esc(m.description || 'No description available.')}</p></div></div>
        <h2 style="margin-top:30px">Select a show</h2><div id="showArea"></div>`;
      const area = el('showArea');
      if (!days.length) { area.innerHTML = emptyState('No shows available', 'There are no upcoming shows for this movie yet. Please check back later.'); return; }
      let active = days[0];
      function draw() {
        const list = upcoming.filter(s => dateKey(s.showTime) === active);
        const byTheatre = {};
        list.forEach(s => (byTheatre[s.theatreId] = byTheatre[s.theatreId] || []).push(s));
        area.innerHTML = `<div class="date-tabs" role="tablist">${days.map(d => `<button class="date-tab ${d === active ? 'active' : ''}" role="tab" aria-selected="${d === active}" data-d="${d}">${fmtDate(d + 'T00:00:00')}</button>`).join('')}</div>` +
          Object.values(byTheatre).map(arr => `<div class="theatre-block"><b>${esc(arr[0].theatreName)}</b> <span class="muted small">${esc(arr[0].city)}</span>
            <div class="showtimes">${arr.sort((a, b) => a.showTime.localeCompare(b.showTime)).map(s => s.availableSeats > 0
              ? `<a class="showtime" href="seats.html?showId=${s.id}"><b>${fmtTime(s.showTime)}</b><small>Screen ${s.screenNo} &middot; ${money(s.price)} &middot; ${s.availableSeats} left</small></a>`
              : `<span class="showtime full"><b>${fmtTime(s.showTime)}</b><small>Sold out</small></span>`).join('')}</div></div>`).join('');
        area.querySelectorAll('.date-tab').forEach(b => (b.onclick = () => { active = b.dataset.d; draw(); }));
      }
      draw();
    } catch (e) { root.innerHTML = e.status === 404 ? emptyState('Movie not found', 'It may have been removed.', '<a class="btn" href="movies.html">Browse movies</a>') : alertBox('error', errText(e)); }
  }

  // ---------------------------------------------------------------- THEATRES
  async function theatres() {
    const box = el('theatreList');
    const city = el('fCity');
    let all = [];
    try { all = await api('/theatres', { auth: false }); } catch (e) { box.innerHTML = alertBox('error', errText(e)); return; }
    city.innerHTML = '<option value="">All cities</option>' + [...new Set(all.map(t => t.city))].sort().map(c => `<option>${esc(c)}</option>`).join('');
    async function run() {
      box.innerHTML = loading();
      try {
        const [list, allShows] = await Promise.all([api('/theatres' + (city.value ? '?city=' + encodeURIComponent(city.value) : ''), { auth: false }), api('/shows', { auth: false })]);
        const shows = allShows.filter(s => new Date(s.showTime).getTime() > Date.now());
        box.innerHTML = list.length ? list.map(t => {
          const mine = shows.filter(s => s.theatreId === t.id);
          const titles = [...new Set(mine.map(s => s.movieTitle))];
          return `<div class="card"><h3>${esc(t.name)}</h3><p class="muted">${esc(t.address)}</p>
            <div class="tags"><span class="tag">${esc(t.city)}</span><span class="tag">${t.totalScreens} screens</span></div>
            <p class="small" style="margin-top:12px"><b>Now showing:</b> ${titles.length ? titles.map(esc).join(', ') : '<span class="muted">no shows scheduled</span>'}</p></div>`;
        }).join('') : emptyState('No theatres found', 'Try another city.');
      } catch (e) { box.innerHTML = alertBox('error', errText(e)); }
    }
    city.onchange = run; run();
  }

  ({ home, movies, movie: movieDetails, theatres }[page] || (() => {}))();
})();
