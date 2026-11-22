'use strict';

/*
 * Ranga Web — a página que roda no navegador da TV.
 *
 * Sem framework e sem build: um arquivo, DOM puro. A TV que motivou isto
 * (Fire TV com Vega OS) tem navegador modesto, e o que pesa numa TV fraca é
 * JavaScript demais, não de menos.
 *
 * Regra de segurança deste arquivo: texto que vem do painel IPTV (nome de
 * canal, título de filme) entra sempre como texto (textContent), nunca como
 * HTML. Um nome de canal com <script> dentro vira só um nome esquisito.
 */

const topBar = document.getElementById('top');
const app = document.getElementById('app');

const SECTIONS = { LIVE: 'Ao vivo', MOVIES: 'Filmes', SERIES: 'Séries', FREE: 'Grátis', FAV: 'Favoritos' };

const S = {
  status: null,
  search: { section: 'LIVE', q: '' },
  library: { favorites: [], resume: [] },   // guardado no PC; vale para todos os aparelhos
  tab: remember('tab') || 'LIVE',
  categories: {},       // seção -> [{id, name}]
  queue: null,          // { items: [...], index }
  cleanups: [],         // desfazer o que a tela atual montou (player, observers)
  view: '',
};

// ------------------------------------------------------------------ utilidades

function remember(key, value) {
  // localStorage pode não existir (aba anônima, TV com armazenamento
  // bloqueado). Sem ele, o site só esquece a última aba aberta.
  try {
    if (value === undefined) return localStorage.getItem('ranga.' + key);
    localStorage.setItem('ranga.' + key, value);
  } catch (_) { return null; }
}

/** Cria elemento. Filhos string viram nó de texto — nunca HTML. */
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

function img(src) {
  if (!src) return h('div', { class: 'noimg' });
  // Capa de painel IPTV quebra o tempo todo; quebrou, some sem ícone feio.
  return h('img', {
    src, alt: '', loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer',
    onerror: (e) => { e.target.style.visibility = 'hidden'; },
  });
}

async function api(path, body) {
  const opts = body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
  const res = await fetch('/api/' + path, opts);
  let data = {};
  try { data = await res.json(); } catch (_) { /* resposta sem JSON */ }
  if (res.status === 401 && !path.startsWith('access/')) {
    // A sessão deste aparelho caiu (senha trocada, "sair" em outro lugar).
    S.status = await (await fetch('/api/status')).json();
    route();
    // A tela de senha já está aberta; quem chamou não deve pôr erro por cima.
    const err = new Error(data.error || 'Entre com a senha de acesso.');
    err.handled = true;
    throw err;
  }
  if (!res.ok) throw new Error(data.error || ('Erro ' + res.status));
  return data;
}

/**
 * Aceita listas aninhadas e pula false/null/'' — é o que sai de
 * `cond && h(...)` e de `.map()` dentro de `.map()`. Sem isto, o
 * replaceChildren escrevia "false" e "[object HTMLDivElement]" na tela
 * (foi o que aconteceu com a lista de temporadas).
 */
function show(...nodes) {
  app.replaceChildren(...nodes.flat(Infinity).filter((n) => n != null && n !== false && n !== ''));
  focusFirst();
}

function loading() { app.replaceChildren(h('p', { class: 'hint' }, 'Carregando…')); }

function failure(err, retry) {
  if (err && err.handled) return;
  show(
    h('p', { class: 'error' }, err.message || String(err)),
    retry && h('button', { class: 'f', onclick: retry }, 'Tentar de novo'),
  );
}

/** Foca o primeiro item da tela, pulando campos de texto (na TV, abririam o teclado). */
function focusFirst() {
  const el = [...app.querySelectorAll('.f')].find((e) => e.tagName !== 'INPUT' && e.tagName !== 'TEXTAREA');
  if (el) el.focus({ preventScroll: true });
}

/**
 * Lista longa em pedaços. Categoria de filme passa de milhares de títulos, e
 * montar todos de uma vez trava a TV por segundos. Monta 120, e mais 120
 * quando o fim chega perto da tela.
 */
function fillIncrementally(container, items, make, step = 120) {
  let i = 0;
  const sentinel = h('div', { class: 'sentinel' });
  const io = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) more();
  }, { rootMargin: '1200px' });
  function more() {
    const frag = document.createDocumentFragment();
    const end = Math.min(items.length, i + step);
    for (; i < end; i++) frag.append(make(items[i], i));
    container.append(frag);
    if (i >= items.length) { io.disconnect(); sentinel.remove(); }
  }
  more();
  container.after(sentinel);
  io.observe(sentinel);
  S.cleanups.push(() => io.disconnect());
}

// ------------------------------------------------- itens que o player toca

/*
 * Um lugar só monta o que vai para a fila do player. `key` é a mesma chave do
 * app ("live:12", "movie:7", "episode:901"): é por ela que favorito e
 * "continuar assistindo" reconhecem o item. `fav` são os dados que o servidor
 * guarda quando você favorita.
 */
function liveItem(c) {
  return {
    key: 'live:' + c.id, title: c.title, subtitle: c.number ? 'Canal ' + c.number : '',
    image: c.image, epgId: c.id,
    src: '/stream/live/' + c.id + '.m3u8', kind: 'hls',
    // Painel que recusa HLS quase sempre entrega o mesmo canal em .ts.
    fallback: { src: '/stream/live/' + c.id + '.ts', kind: 'ts' },
    live: true,
    fav: { section: 'LIVE', id: c.id, title: c.title, image: c.image },
  };
}

function movieItem(m) {
  return {
    key: 'movie:' + m.id, title: m.title, image: m.image,
    src: '/stream/movie/' + m.id + '.' + encodeURIComponent(m.ext || 'mp4'), kind: 'file', live: false,
    fav: { section: 'MOVIES', id: m.id, title: m.title, image: m.image, ext: m.ext || 'mp4' },
  };
}

function isFavorite(key) { return S.library.favorites.some((f) => f.key === key); }

async function toggleFavorite(key, fav) {
  S.library = await api('favorites/toggle', { key, ...fav });
}

async function loadLibrary() {
  try { S.library = await api('library'); } catch (_) { /* fica com o que tinha */ }
}

/** 3725000 -> "1:02:05"; 65000 -> "1:05". */
function clock(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const hh = Math.floor(t / 3600);
  const mm = Math.floor((t % 3600) / 60);
  const ss = String(t % 60).padStart(2, '0');
  return hh ? hh + ':' + String(mm).padStart(2, '0') + ':' + ss : mm + ':' + ss;
}

/** Hora do guia em 24 h, sem AM/PM, qualquer que seja o idioma da TV. */
function hourMinute(epochSecs) {
  const d = new Date(epochSecs * 1000);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

// --------------------------------------------------------------------- rotas

window.addEventListener('hashchange', route);

async function start() {
  try {
    S.status = await (await fetch('/api/status')).json();
  } catch (err) {
    return failure(new Error('O servidor do Ranga não respondeu. O PC está ligado?'), start);
  }
  route();
}

function route() {
  S.cleanups.splice(0).forEach((fn) => fn());
  topBar.hidden = false;
  const st = S.status;
  if (!st) return start();
  if (!st.authed) return st.needsSetup ? viewSetup(st) : viewAccess();
  if (!S.libraryLoaded) { S.libraryLoaded = true; loadLibrary(); }

  const [page, a, b] = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  if (page === 'play') return viewPlayer();
  if (page === 'conta') return viewAccount();
  if (page === 'cat') return viewCategory(a, b);
  if (page === 'serie') return viewSeries(a);
  if (page === 'busca') return viewSearch();
  if (page === 'tab' && SECTIONS[a]) return viewHome(a);
  return viewHome(S.tab);
}

function renderTop(active) {
  topBar.replaceChildren(
    h('span', { class: 'brand' }, 'Ranga'),
    ...Object.entries(SECTIONS).map(([key, label]) => h('button', {
      class: 'f tab' + (key === active ? ' active' : ''),
      onclick: () => { location.hash = '#/tab/' + key; },
    }, label)),
    h('span', { class: 'spacer' }),
    h('button', {
      class: 'f tab' + (active === 'busca' ? ' active' : ''),
      onclick: () => { location.hash = '#/busca'; },
    }, 'Buscar'),
    fullscreenButton('f tab'),
    h('button', {
      class: 'f tab' + (active === 'conta' ? ' active' : ''),
      onclick: () => { location.hash = '#/conta'; },
    }, 'Conta'),
  );
}

// ------------------------------------------------------- senha de acesso

function viewSetup(st) {
  S.view = 'setup';
  topBar.replaceChildren(h('span', { class: 'brand' }, 'Ranga'));
  if (!st.canSetup) {
    return show(h('div', { class: 'panel' },
      h('h1', {}, 'Falta criar a senha de acesso'),
      h('p', { class: 'hint' }, 'Por segurança, a senha só pode ser criada de dentro de casa. ' +
        'Abra o Ranga pelo endereço da rede local (o que aparece na janela do servidor no PC).'),
    ));
  }
  const pass = h('input', { class: 'f', type: 'password', autocomplete: 'new-password', required: true, minlength: '6' });
  const again = h('input', { class: 'f', type: 'password', autocomplete: 'new-password', required: true });
  const msg = h('p', { class: 'error' });
  show(h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      if (pass.value !== again.value) { msg.textContent = 'As duas senhas não batem.'; return; }
      try {
        S.status = await api('access/setup', { password: pass.value });
        location.hash = '#/';
        route();
      } catch (err) { msg.textContent = err.message; }
    },
  },
  h('h1', {}, 'Crie a senha de acesso'),
  h('p', { class: 'hint' }, 'Ela protege o Ranga se ele for aberto fora de casa. ' +
    'Cada aparelho pede a senha uma vez só.'),
  h('label', {}, 'Senha (mínimo 6 caracteres)', pass),
  h('label', {}, 'Repita a senha', again),
  msg,
  h('button', { class: 'f primary', type: 'submit' }, 'Criar senha'),
  ));
}

function viewAccess() {
  S.view = 'access';
  topBar.replaceChildren(h('span', { class: 'brand' }, 'Ranga'));
  const pass = h('input', { class: 'f', type: 'password', autocomplete: 'current-password', required: true });
  const msg = h('p', { class: 'error' });
  show(h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      try {
        S.status = await api('access/login', { password: pass.value });
        route();
      } catch (err) { msg.textContent = err.message; }
    },
  },
  h('h1', {}, 'Senha de acesso'),
  h('label', {}, 'Senha', pass),
  msg,
  h('button', { class: 'f primary', type: 'submit' }, 'Entrar'),
  ));
}

// ------------------------------------------------------------------ navegar

async function viewHome(tab) {
  S.view = 'home';
  S.tab = tab;
  remember('tab', tab);
  renderTop(tab);

  if (tab === 'FAV') return viewLibrary();

  if (tab === 'FREE') {
    loading();
    try {
      const channels = await api('free');
      const queue = channels.map((c) => ({
        title: c.title, subtitle: 'Stream oficial · ' + c.source,
        src: '/stream/free/' + encodeURIComponent(c.id) + '.m3u8', kind: 'hls', live: true,
      }));
      show(
        h('p', { class: 'hint' }, 'Canais abertos, com o stream que a própria emissora publica. Sem conta.'),
        h('div', { class: 'list' }, channels.map((c, i) => h('a', {
          class: 'f row', href: '#', onclick: (e) => { e.preventDefault(); play(queue, i); },
        }, img(c.image), h('span', { class: 't' }, h('b', {}, c.title), h('small', {}, queue[i].subtitle))))),
      );
    } catch (err) { failure(err, () => viewHome(tab)); }
    return;
  }

  if (!S.status.loggedIn) {
    return show(h('div', { class: 'panel' },
      h('p', { class: 'hint' }, 'Canais ao vivo, filmes e séries vêm da sua conta IPTV. A aba Grátis funciona sem ela.'),
      h('button', { class: 'f primary', onclick: () => { location.hash = '#/conta'; } }, 'Entrar com a conta IPTV'),
    ));
  }

  loading();
  try {
    const cats = S.categories[tab] || (S.categories[tab] = await api('categories?section=' + tab));
    if (!cats.length) return show(h('p', { class: 'hint' }, 'Seu plano não tem nada nesta seção.'));
    const grid = h('div', { class: 'grid' });
    show(grid);
    fillIncrementally(grid, cats, (c) => h('a', {
      class: 'f card', href: '#/cat/' + tab + '/' + encodeURIComponent(c.id),
    }, c.name));
    focusFirst();
  } catch (err) { failure(err, () => viewHome(tab)); }
}

async function viewCategory(section, id) {
  S.view = 'category';
  renderTop(section);
  loading();
  try {
    const items = await api('items?section=' + section + '&category=' + encodeURIComponent(id));
    const name = (S.categories[section] || []).find((c) => c.id === id)?.name || 'Categoria';
    if (!items.length) return show(h('h1', {}, name), h('p', { class: 'hint' }, 'Nada aqui.'));

    if (section === 'LIVE') {
      const queue = items.map(liveItem);
      const list = h('div', { class: 'list' });
      show(h('h1', {}, name), list);
      fillIncrementally(list, items, (c, i) => h('a', {
        class: 'f row', href: '#', onclick: (e) => { e.preventDefault(); play(queue, i); },
      }, img(c.image), h('span', { class: 't' }, h('b', {}, c.title), queue[i].subtitle && h('small', {}, queue[i].subtitle))));
    } else {
      const grid = h('div', { class: 'grid posters' });
      show(h('h1', {}, name), grid);
      const queue = section === 'MOVIES' ? items.map(movieItem) : null;
      fillIncrementally(grid, items, (m, i) => h('a', {
        class: 'f poster',
        href: section === 'SERIES' ? '#/serie/' + m.id : '#',
        onclick: queue ? (e) => { e.preventDefault(); play(queue, i); } : null,
      }, img(m.image), h('span', {}, m.title)));
    }
    focusFirst();
  } catch (err) { failure(err, () => viewCategory(section, id)); }
}

async function viewSeries(id) {
  S.view = 'series';
  renderTop('SERIES');
  loading();
  try {
    const s = await api('series?id=' + encodeURIComponent(id));
    const all = [];
    s.seasons.forEach((season) => season.episodes.forEach((e) => all.push({
      title: s.title,
      subtitle: 'T' + season.season + ' · E' + e.number + ' · ' + e.title,
      key: 'episode:' + e.id, image: e.image || s.image,
      src: '/stream/series/' + encodeURIComponent(e.id) + '.' + encodeURIComponent(e.ext || 'mp4'),
      kind: 'file', live: false,
    })));
    const favKey = 'series:' + id;
    const favButton = h('button', {
      class: 'f',
      onclick: async () => {
        try {
          await toggleFavorite(favKey, { section: 'SERIES', id: Number(id), title: s.title, image: s.image });
        } catch (_) { /* a sessão caiu: a tela de senha já abriu */ }
        favLabel();
      },
    });
    const favLabel = () => { favButton.textContent = isFavorite(favKey) ? '★ Nos favoritos' : '☆ Favoritar'; };
    favLabel();
    let n = 0;
    show(
      h('h1', {}, s.title),
      h('div', { class: 'actions' }, favButton),
      s.plot && h('p', { class: 'hint' }, s.plot),
      !all.length && h('p', { class: 'hint' }, 'Esta série não tem episódios no painel.'),
      s.seasons.map((season) => [
        h('h2', {}, 'Temporada ' + season.season),
        h('div', { class: 'list' }, season.episodes.map((e) => {
          const index = n++;
          return h('a', {
            class: 'f row', href: '#', onclick: (ev) => { ev.preventDefault(); play(all, index); },
          }, img(e.image), h('span', { class: 't' }, h('b', {}, 'E' + e.number + ' · ' + e.title)));
        })),
      ]),
    );
  } catch (err) { failure(err, () => viewSeries(id)); }
}

// -------------------------------------------------------------- favoritos

async function viewLibrary() {
  S.view = 'library';
  renderTop('FAV');
  loading();
  await loadLibrary();
  const { favorites, resume } = S.library;
  const lives = favorites.filter((f) => f.section === 'LIVE');
  const movies = favorites.filter((f) => f.section === 'MOVIES');
  const series = favorites.filter((f) => f.section === 'SERIES');
  if (!favorites.length && !resume.length) {
    return show(h('p', { class: 'hint' },
      'Nada aqui ainda. Com um canal ou filme tocando, use "☆ Favoritar". ' +
      'Filme e episódio parados no meio aparecem aqui sozinhos.'));
  }
  const liveQueue = lives.map(liveItem);
  const movieQueue = movies.map(movieItem);
  show(
    resume.length > 0 && [
      h('h2', {}, 'Continuar assistindo'),
      h('div', { class: 'list' }, resume.map((r) => h('a', {
        class: 'f row', href: '#',
        onclick: (e) => {
          e.preventDefault();
          play([{ key: r.key, title: r.title, subtitle: r.subtitle, image: r.image, src: r.src, kind: 'file', live: false }], 0);
        },
      }, img(r.image), h('span', { class: 't' },
        h('b', {}, r.title),
        h('small', {}, (r.subtitle ? r.subtitle + ' · ' : '') + 'parou em ' + clock(r.pos) + (r.dur ? ' de ' + clock(r.dur) : '')))))),
      h('div', { class: 'actions' }, h('button', {
        class: 'f',
        onclick: async () => { try { S.library = await api('resume/clear', {}); } catch (_) { return; } viewLibrary(); },
      }, 'Limpar "continuar assistindo"')),
    ],
    lives.length > 0 && [
      h('h2', {}, 'Canais'),
      h('div', { class: 'list' }, lives.map((c, i) => h('a', {
        class: 'f row', href: '#', onclick: (e) => { e.preventDefault(); play(liveQueue, i); },
      }, img(c.image), h('span', { class: 't' }, h('b', {}, c.title))))),
    ],
    movies.length > 0 && [
      h('h2', {}, 'Filmes'),
      h('div', { class: 'grid posters' }, movies.map((m, i) => h('a', {
        class: 'f poster', href: '#', onclick: (e) => { e.preventDefault(); play(movieQueue, i); },
      }, img(m.image), h('span', {}, m.title)))),
    ],
    series.length > 0 && [
      h('h2', {}, 'Séries'),
      h('div', { class: 'grid posters' }, series.map((m) => h('a', {
        class: 'f poster', href: '#/serie/' + m.id,
      }, img(m.image), h('span', {}, m.title)))),
    ],
  );
}

// ------------------------------------------------------------------ busca

/*
 * A busca roda no PC: ele carrega a seção inteira, uma categoria por vez, e
 * procura lá. Na primeira busca de uma seção isso leva um tempo (painel com
 * milhares de filmes), então a tela mostra o progresso e vai atualizando os
 * resultados sozinha enquanto a carga anda.
 */
function viewSearch() {
  S.view = 'search';
  renderTop('busca');
  if (!S.status.loggedIn) {
    return show(h('div', { class: 'panel' },
      h('p', { class: 'hint' }, 'A busca procura no catálogo da sua conta IPTV.'),
      h('button', { class: 'f primary', onclick: () => { location.hash = '#/conta'; } }, 'Entrar com a conta IPTV'),
    ));
  }

  const input = h('input', {
    class: 'f', type: 'search', value: S.search.q, placeholder: 'Nome do canal, filme ou série',
    autocomplete: 'off', enterkeyhint: 'search',
  });
  const progress = h('p', { class: 'hint' });
  const results = h('div');
  const sectionButtons = h('div', { class: 'actions' });
  let timer = 0;
  let poll = 0;
  let seq = 0;

  function renderSections() {
    sectionButtons.replaceChildren(...['LIVE', 'MOVIES', 'SERIES'].map((key) => h('button', {
      class: 'f tab' + (S.search.section === key ? ' active' : ''),
      onclick: () => { S.search.section = key; renderSections(); run(); },
    }, SECTIONS[key])));
  }

  async function run() {
    clearTimeout(poll);
    const q = input.value.trim();
    S.search.q = input.value;
    if (q.length < 2) {
      progress.textContent = 'Digite pelo menos 2 letras.';
      results.replaceChildren();
      return;
    }
    const mine = ++seq;
    const section = S.search.section;
    try {
      const r = await api('search?section=' + section + '&q=' + encodeURIComponent(q));
      if (mine !== seq) return; // chegou a resposta de uma busca antiga
      progress.textContent = r.finished
        ? r.items.length + ' resultado(s).'
        : 'Carregando o catálogo no PC (' + r.done + ' de ' + (r.total || '?') + ' categorias)… ' +
          'os resultados vão aparecendo.';
      renderResults(section, r.items);
      if (!r.finished) poll = setTimeout(run, 2000);
    } catch (err) {
      if (!err.handled) progress.textContent = err.message;
    }
  }

  function renderResults(section, items) {
    if (!items.length) { results.replaceChildren(h('p', { class: 'hint' }, 'Nada encontrado ainda.')); return; }
    if (section === 'LIVE') {
      const queue = items.map(liveItem);
      results.replaceChildren(h('div', { class: 'list' }, items.map((c, i) => h('a', {
        class: 'f row', href: '#', onclick: (e) => { e.preventDefault(); play(queue, i); },
      }, img(c.image), h('span', { class: 't' }, h('b', {}, c.title))))));
      return;
    }
    const queue = section === 'MOVIES' ? items.map(movieItem) : null;
    results.replaceChildren(h('div', { class: 'grid posters' }, items.map((m, i) => h('a', {
      class: 'f poster',
      href: section === 'SERIES' ? '#/serie/' + m.id : '#',
      onclick: queue ? (e) => { e.preventDefault(); play(queue, i); } : null,
    }, img(m.image), h('span', {}, m.title)))));
  }

  // Espera parar de digitar: cada busca percorre milhares de títulos no PC.
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 400); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); clearTimeout(timer); run(); input.blur(); focusFirstResult(); }
  });
  function focusFirstResult() {
    const first = results.querySelector('.f');
    if (first) first.focus({ preventScroll: true });
  }

  S.cleanups.push(() => { clearTimeout(timer); clearTimeout(poll); seq++; });
  renderSections();
  app.replaceChildren(h('div', { class: 'panel wide' },
    h('h1', {}, 'Buscar'),
    sectionButtons,
    h('label', {}, 'Buscar em', input),
    progress,
  ), results);
  // Aqui o teclado abrir é o esperado: entrou na busca para digitar.
  input.focus();
  if (S.search.q.trim().length >= 2) run();
}

// ---------------------------------------------------------------- player

function play(items, index) {
  S.queue = { items, index };
  // Tem que ser aqui, dentro do clique/OK: o navegador só aceita tela cheia
  // pedida em resposta a uma ação do usuário, e a troca de rota já não conta.
  enterFullscreen();
  location.hash = '#/play';
}

/*
 * Tela cheia da página inteira, e não só do <video>: assim ela continua ao
 * voltar do vídeo para a lista, e a TV fica sem a barra do navegador o tempo
 * todo. Navegador que não suporta (alguns de TV) simplesmente ignora.
 */
function enterFullscreen() {
  const el = document.documentElement;
  if (document.fullscreenElement || !el.requestFullscreen) return;
  el.requestFullscreen({ navigationUI: 'hide' }).catch(() => { /* recusado: segue sem */ });
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else enterFullscreen();
}

function fullscreenButton(cls) {
  if (!document.documentElement.requestFullscreen) return null;
  const btn = h('button', { class: cls, onclick: toggleFullscreen });
  const label = () => { btn.textContent = document.fullscreenElement ? 'Sair da tela cheia' : 'Tela cheia'; };
  label();
  document.addEventListener('fullscreenchange', label);
  S.cleanups.push(() => document.removeEventListener('fullscreenchange', label));
  return btn;
}

function viewPlayer() {
  S.view = 'player';
  // Recarregou a página no meio do vídeo: a fila era só da memória.
  if (!S.queue) { location.replace('#/'); return; }
  topBar.hidden = true;

  const video = h('video', { autoplay: true, playsinline: true });
  const title = h('h1');
  const subtitle = h('p', { class: 'hint' });
  const guide = h('p', { class: 'hint' });
  const error = h('p', { class: 'error' });
  const actions = h('div', { class: 'actions' });
  const overlay = h('div', { class: 'overlay' }, title, subtitle, guide, error, actions);
  app.replaceChildren(h('div', { class: 'player' }, video, overlay));

  let engine = null;
  let hideTimer = 0;

  // ------------------------------------------------ continuar assistindo
  //
  // A posição vai para o PC a cada 10 s e na saída. O servidor aplica a
  // regra do app (menos de 1 min ou mais de 95% não guarda).
  function saveProgress() {
    const item = S.queue && S.queue.items[S.queue.index];
    if (!item || item.live || !item.key || !(video.currentTime > 0)) return;
    const body = JSON.stringify({
      key: item.key, title: item.title, subtitle: item.subtitle || '', image: item.image || '',
      src: item.src, pos: Math.floor(video.currentTime * 1000),
      dur: isFinite(video.duration) ? Math.floor(video.duration * 1000) : 0,
    });
    // keepalive: o pedido termina mesmo se a página estiver saindo do vídeo.
    fetch('/api/progress', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true,
    }).catch(() => { /* sem rede agora; o próximo tick tenta de novo */ });
  }
  const progressTimer = setInterval(saveProgress, 10000);

  // Maratona: acabou o episódio, emenda no próximo da fila.
  video.addEventListener('ended', () => {
    const q = S.queue;
    if (!q.items[q.index].live && q.index < q.items.length - 1) { q.index += 1; load(); }
  });

  function revealOverlay() {
    overlay.classList.remove('hidden');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!error.textContent) overlay.classList.add('hidden'); }, 4000);
  }

  function detach() {
    if (engine) { try { engine.destroy(); } catch (_) { /* já destruído */ } }
    engine = null;
    video.removeAttribute('src');
    video.load();
  }

  function fail(item, usedFallback) {
    if (!usedFallback && item.fallback) return attach(item, true);
    const ext = (item.src.split('.').pop() || '').toLowerCase();
    error.textContent = item.kind === 'file' && ext !== 'mp4'
      ? 'Este navegador não toca arquivos .' + ext + '. Filmes em mp4 funcionam; para os outros, use o app.'
      : 'Não foi possível tocar. O canal pode estar fora do ar.';
    revealOverlay();
  }

  function attach(item, useFallback) {
    detach();
    error.textContent = '';
    const src = useFallback ? item.fallback : item;
    video.controls = !item.live;
    if (src.kind === 'hls') {
      if (window.Hls && Hls.isSupported()) {
        const hls = new Hls({ backBufferLength: 30 });
        hls.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) fail(item, useFallback); });
        hls.loadSource(src.src);
        hls.attachMedia(video);
        engine = hls;
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = src.src;
      } else return fail(item, useFallback);
    } else if (src.kind === 'ts') {
      if (window.mpegts && mpegts.isSupported()) {
        const p = mpegts.createPlayer(
          { type: 'mpegts', isLive: true, url: new URL(src.src, location.href).href },
          { enableWorker: false, liveBufferLatencyChasing: true },
        );
        p.on(mpegts.Events.ERROR, () => fail(item, useFallback));
        p.attachMediaElement(video);
        p.load();
        engine = p;
      } else return fail(item, useFallback);
    } else {
      video.src = src.src;
      const saved = S.library.resume.find((r) => r.key === item.key);
      if (saved && saved.pos > 0) {
        video.addEventListener('loadedmetadata', () => { video.currentTime = saved.pos / 1000; }, { once: true });
      }
    }
    video.onerror = () => fail(item, useFallback);
    const playing = video.play();
    if (playing && playing.catch) playing.catch(() => { /* autoplay barrado: o usuário aperta play */ });
  }

  function load() {
    const { items, index } = S.queue;
    const item = items[index];
    title.textContent = item.title;
    subtitle.textContent = item.subtitle || '';
    const favButton = item.fav && h('button', {
      class: 'f',
      onclick: async () => {
        try { await toggleFavorite(item.key, item.fav); } catch (_) { /* sessão caiu */ }
        favLabel();
      },
    });
    const favLabel = () => { if (favButton) favButton.textContent = isFavorite(item.key) ? '★ Nos favoritos' : '☆ Favoritar'; };
    favLabel();
    actions.replaceChildren(...[
      item.live && items.length > 1 && h('button', { class: 'f', onclick: () => zap(-1) }, '▲ Anterior'),
      item.live && items.length > 1 && h('button', { class: 'f', onclick: () => zap(1) }, '▼ Próximo'),
      favButton,
      fullscreenButton('f'),
      h('button', { class: 'f', onclick: () => history.back() }, 'Voltar'),
    ].filter(Boolean));
    loadGuide(item);
    attach(item, false);
    revealOverlay();
  }

  // Guia "agora / a seguir" do canal. É enfeite: se o painel não tiver, some.
  async function loadGuide(item) {
    guide.textContent = '';
    if (!item.epgId) return;
    try {
      const list = await api('epg?id=' + item.epgId);
      if (S.queue.items[S.queue.index] !== item) return; // já trocou de canal
      const now = Date.now() / 1000;
      const airing = list.find((e) => now >= e.start && now <= e.end);
      const next = list.find((e) => e.start > now);
      guide.textContent = [
        airing && 'Agora · ' + hourMinute(airing.start) + ' — ' + airing.title,
        next && 'A seguir · ' + hourMinute(next.start) + ' — ' + next.title,
      ].filter(Boolean).join('     ');
    } catch (_) { /* sem guia */ }
  }

  function zap(step) {
    saveProgress();
    const q = S.queue;
    q.index = (q.index + step + q.items.length) % q.items.length;
    load();
  }

  function onKey(e) {
    const item = S.queue.items[S.queue.index];
    switch (e.key) {
      case 'ArrowUp': case 'ChannelUp':
        if (item.live) { e.preventDefault(); zap(-1); } else revealOverlay();
        break;
      case 'ArrowDown': case 'ChannelDown':
        if (item.live) { e.preventDefault(); zap(1); } else revealOverlay();
        break;
      case 'ArrowLeft':
        if (!item.live) { e.preventDefault(); video.currentTime = Math.max(0, video.currentTime - 10); }
        revealOverlay();
        break;
      case 'ArrowRight':
        if (!item.live) { e.preventDefault(); video.currentTime += 10; }
        revealOverlay();
        break;
      case 'Enter': case ' ': case 'MediaPlayPause':
        if (document.activeElement && document.activeElement.tagName === 'BUTTON') return;
        e.preventDefault();
        if (video.paused) video.play(); else if (!item.live) video.pause();
        revealOverlay();
        break;
      case 'Escape': case 'Backspace':
        e.preventDefault();
        history.back();
        break;
      default:
    }
  }

  document.addEventListener('keydown', onKey, true);
  app.addEventListener('pointermove', revealOverlay);
  S.cleanups.push(() => {
    document.removeEventListener('keydown', onKey, true);
    app.removeEventListener('pointermove', revealOverlay);
    clearTimeout(hideTimer);
    clearInterval(progressTimer);
    saveProgress();
    detach();
    // A lista de "continuar assistindo" mudou; a próxima tela pega a nova.
    loadLibrary();
  });
  load();
}

// ---------------------------------------------------------------- conta

async function viewAccount() {
  S.view = 'account';
  renderTop('conta');
  const st = S.status;

  if (!st.loggedIn) {
    const host = h('input', { class: 'f', type: 'url', placeholder: 'http://seu-painel.exemplo', autocomplete: 'off', required: true });
    const user = h('input', { class: 'f', autocomplete: 'username', required: true });
    const pass = h('input', { class: 'f', type: 'password', autocomplete: 'current-password', required: true });
    const msg = h('p', { class: 'error' });
    const submit = h('button', { class: 'f primary', type: 'submit' }, 'Entrar');
    return show(h('form', {
      onsubmit: async (e) => {
        e.preventDefault();
        submit.disabled = true;
        msg.textContent = '';
        try {
          S.status = await api('login', { host: host.value, username: user.value, password: pass.value });
          S.categories = {};
          location.hash = '#/tab/LIVE';
        } catch (err) { msg.textContent = err.message; } finally { submit.disabled = false; }
      },
    },
    h('h1', {}, 'Conta IPTV'),
    h('p', { class: 'hint' }, 'Os dados ficam guardados só no PC que roda o Ranga. A TV nunca recebe a senha.'),
    h('label', {}, 'Servidor (DNS)', host),
    h('label', {}, 'Usuário', user),
    h('label', {}, 'Senha', pass),
    msg,
    submit,
    accessLogoutButton(),
    ));
  }

  const msg = h('p', { class: 'error' });
  const add = h('textarea', { class: 'f', rows: '3', placeholder: 'Cole um ou mais endereços, um por linha' });

  async function act(fn) {
    msg.textContent = '';
    try { S.status = await fn(); S.categories = {}; viewAccount(); } catch (err) { msg.textContent = err.message; }
  }

  const rows = st.servers.map((server) => {
    const note = h('small', {}, server === st.host ? 'em uso' : '');
    const test = async () => {
      note.textContent = 'testando…';
      try {
        const r = await api('servers/test', { host: server });
        note.textContent = 'respondeu em ' + r.ms + ' ms';
      } catch (err) { note.textContent = 'falhou: ' + err.message; }
    };
    return {
      test,
      el: h('div', { class: 'server' },
        h('span', { class: 'name' }, server, note),
        server !== st.host && h('button', { class: 'f primary', onclick: () => act(() => api('servers/use', { host: server })) }, 'Usar'),
        h('button', { class: 'f', onclick: test }, 'Testar'),
        server !== st.host && h('button', { class: 'f', onclick: () => act(() => api('servers/remove', { host: server })) }, 'Remover'),
      ),
    };
  });

  show(h('div', { class: 'panel' },
    h('h1', {}, 'Conta IPTV'),
    h('p', {}, 'Usuário: ', h('b', {}, st.username)),
    h('h2', {}, 'Servidor (DNS)'),
    h('p', { class: 'hint' }, 'Endereços da mesma conta. Se um parar, use outro: usuário e senha continuam os mesmos.'),
    rows.map((r) => r.el),
    h('button', { class: 'f', onclick: () => rows.forEach((r) => r.test()) }, 'Testar todos'),
    h('label', {}, 'Adicionar DNS', add),
    h('button', { class: 'f', onclick: () => act(() => api('servers/add', { text: add.value })) }, 'Adicionar'),
    msg,
    h('h2', {}, 'Sair'),
    h('button', { class: 'f danger', onclick: () => act(() => api('logout', {})) }, 'Sair da conta IPTV'),
    accessLogoutButton(),
  ));
}

function accessLogoutButton() {
  return h('button', {
    class: 'f', type: 'button',
    onclick: async () => {
      await api('access/logout', {}).catch(() => {});
      S.status = await (await fetch('/api/status')).json();
      route();
    },
  }, 'Esquecer este aparelho');
}

// ------------------------------------------------------- controle remoto

/*
 * Navegação pelas setas do controle. O navegador só anda com Tab, e controle
 * de TV não tem Tab: as setas procuram o item focável mais próximo naquela
 * direção, pelo centro de cada um na tela.
 */
document.addEventListener('keydown', (e) => {
  if (S.view === 'player') return;
  const dir = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' }[e.key];
  const active = document.activeElement;
  const typing = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
  if (!dir) {
    if ((e.key === 'Escape' || e.key === 'Backspace') && !typing && location.hash && location.hash !== '#/') {
      e.preventDefault();
      history.back();
    }
    return;
  }
  // Dentro de um campo, esquerda/direita movem o cursor do texto.
  if (typing && (dir === 'left' || dir === 'right')) return;
  const next = nearest(active, dir);
  if (next) {
    e.preventDefault();
    next.focus({ preventScroll: true });
    next.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
});

function nearest(from, dir) {
  const all = [...document.querySelectorAll('.f')].filter((el) => el.offsetParent !== null && !el.disabled);
  if (!from || !all.includes(from)) return all[0];
  const a = from.getBoundingClientRect();
  let best = null;
  let bestScore = Infinity;
  for (const el of all) {
    if (el === from) continue;
    const b = el.getBoundingClientRect();
    let main;
    let cross;
    if (dir === 'down') { if (b.top < a.bottom - 2) continue; main = b.top - a.bottom; cross = center(b).x - center(a).x; }
    if (dir === 'up') { if (b.bottom > a.top + 2) continue; main = a.top - b.bottom; cross = center(b).x - center(a).x; }
    if (dir === 'right') { if (b.left < a.right - 2) continue; main = b.left - a.right; cross = center(b).y - center(a).y; }
    if (dir === 'left') { if (b.right > a.left + 2) continue; main = a.left - b.right; cross = center(b).y - center(a).y; }
    // Peso maior no desvio lateral: descer numa grade tem que ir para o
    // item logo abaixo, e não para o mais perto na diagonal.
    const score = main + Math.abs(cross) * 3;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  return best;
}

function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

start();
