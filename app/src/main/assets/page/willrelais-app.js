/* WillRelais dans l'appli Interventions : dossiers d'enregistrement, fichiers de l'autre appareil, impression. */
(function () {
  var W = window.WrApi; if (!W) return;
  var CB = {}, n = 0;
  window.__wrCb = function (id, ok, r) { var f = CB[id]; delete CB[id]; if (f) f(ok, r); };
  function call(m, a, b) { return new Promise(function (res, rej) { var id = ++n; CB[id] = function (ok, r) { if (!ok) return rej(new Error(r)); try { res(JSON.parse(r)); } catch (e) { res(r); } }; W.call(id, m, a || '', b || ''); }); }
  function b64(buf) { var u = new Uint8Array(buf), s = '', i; for (i = 0; i < u.length; i += 32768) s += String.fromCharCode.apply(null, u.subarray(i, i + 32768)); return btoa(s); }
  function J(s) { try { return JSON.parse(s); } catch (e) { return {}; } }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function size(n) { return n >= 1048576 ? (n / 1048576).toFixed(1) + ' Mo' : Math.max(1, Math.round(n / 1024)) + ' Ko'; }
  function base(p) { return p.slice(p.lastIndexOf('/') + 1); }
  function up(p) { var i = p.lastIndexOf('/'); return i < 0 ? '' : p.slice(0, i); }
  function join(a, b) { return a ? a + '/' + b : b; }

  /* photos du schéma : fichier compagnon nom.photos.json cherché dans le dossier du schéma, le dossier d'enregistrement et le dossier WillRelais */
  window.__wlLoadSide = function (name) {
    if (!W.readText) return '';
    var st = J(W.state()), dirs = [window.__wlOpenDir, st.cur, st.def].filter(function (d, i, a) { return d != null && a.indexOf(d) === i; });
    var b = String(name || '').replace(/\.xrs$/i, ''), bases = [b, b.replace(/(_modifié)+$/, '')];
    for (var i = 0; i < dirs.length; i++) for (var j = 0; j < bases.length; j++) { var t = W.readText(join(dirs[i], bases[j] + '.photos.json')); if (t) return t; }
    return '';
  };
  window.print = function () { W.print(document.title || 'Schéma'); };
  window.__wrOpen = function (u, name, dir) {
    window.__wlOpenDir = dir == null ? null : dir;
    fetch(u).then(function (r) { if (!r.ok) throw new Error('fichier introuvable'); return r.arrayBuffer(); }).then(function (b) {
      var i = document.getElementById('file'), dt = new DataTransfer();
      dt.items.add(new File([b], name, { type: 'application/octet-stream' })); i.files = dt.files; i.dispatchEvent(new Event('change'));
    }).catch(function (x) { alert('Ouverture impossible : ' + x.message); });
  };

  /* Enregistrer / Nomenclature : la page produit un lien de téléchargement → choix du dossier */
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null; if (!a) return;
    e.preventDefault(); e.stopPropagation();
    var nm = a.getAttribute('download') || 'schema.xrs';
    fetch(a.href).then(function (r) { return r.arrayBuffer(); }).then(function (b) { open('save', { name: nm, data: b64(b) }); })
      .catch(function (x) { alert('Enregistrement impossible : ' + x); });
  }, true);

  var css = '#wrfs{position:fixed;inset:0;z-index:50;background:#111827;color:#e5e7eb;display:none;flex-direction:column;font:14px system-ui,sans-serif}' +
    '#wrfs.on{display:flex}#wrfs .h{display:flex;gap:6px;padding:8px;background:#1f2a37;align-items:center;flex-wrap:wrap}' +
    '#wrfs button{background:#374151;color:#e5e7eb;border:0;border-radius:8px;padding:9px 11px;font:inherit;min-height:40px}' +
    '#wrfs button.on,#wrfs button.pri{background:#3b82f6;color:#fff}#wrfs button:disabled{opacity:.4}' +
    '#wrfs input{flex:1;min-width:0;padding:9px;border-radius:8px;border:0;font:inherit;background:#e5e7eb;color:#111}' +
    '#wrfs .p{padding:6px 10px;color:#9ca3af;word-break:break-all;display:flex;gap:6px;align-items:center}' +
    '#wrfs .l{flex:1;overflow:auto;padding:0 8px 8px}#wrfs .it{display:flex;gap:8px;align-items:center;padding:10px 8px;border-bottom:1px solid #374151}' +
    '#wrfs .it .n{flex:1;min-width:0;word-break:break-all}#wrfs .it small{color:#9ca3af;display:block}#wrfs .it button{min-height:34px;padding:6px 9px}' +
    '#wrfs .f{display:flex;gap:6px;padding:8px;background:#1f2a37;flex-wrap:wrap;align-items:center}#wf-n{flex:1 1 100%}#wrfs .msg{padding:6px 10px;color:#fbbf24}';
  var st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);

  var S = { mode: 'open', side: 'here', here: '', there: '', save: null, list: null, find: null, state: {} };
  var box;
  function build() {
    box = document.createElement('div'); box.id = 'wrfs';
    box.innerHTML = '<div class="h"><button id="wf-here" type="button">📱 Cet appareil</button><button id="wf-there" type="button">🔁 Autre appareil</button><span style="flex:1"></span><button id="wf-x" type="button">✕</button></div>' +
      '<div class="h"><input id="wf-q" type="search" placeholder="ex : tofer four"><button id="wf-go" type="button">🔍 Chercher</button></div>' +
      '<div class="p"><button id="wf-up" type="button">⬆</button><span id="wf-path"></span></div><div class="msg" id="wf-msg"></div><div class="l" id="wf-l"></div><div class="f" id="wf-f"></div>';
    document.body.appendChild(box);
    $$('wf-x').onclick = close;
    $$('wf-here').onclick = function () { S.side = 'here'; S.find = null; load(); };
    $$('wf-there').onclick = function () { S.side = 'there'; S.find = null; load(); };
    $$('wf-up').onclick = function () { if (S.find) { S.find = null; return load(); } setPath(up(path())); load(); };
    $$('wf-go').onclick = search;
    $$('wf-q').addEventListener('keydown', function (e) { if (e.key === 'Enter') search(); });
  }
  function $$(id) { return document.getElementById(id); }
  function path() { return S.side === 'here' ? S.here : S.there; }
  function setPath(p) { if (S.side === 'here') S.here = p; else S.there = p; }
  function msg(t) { $$('wf-msg').textContent = t || ''; }
  function open(mode, save) {
    if (!box) build();
    S.mode = mode; S.save = save || null; S.find = null; S.state = J(W.state());
    if (!S.here) S.here = S.state.cur || '';
    S.side = 'here'; box.classList.add('on'); load();
  }
  function close() { box.classList.remove('on'); }
  window.__wrFiles = open;
  var b0 = window.__appBack; window.__appBack = function () { if (box && box.classList.contains('on')) { close(); return true; } var l = $$('lib'); if (l && getComputedStyle(l).display !== 'none') { l.style.display = 'none'; return true; } return b0 ? b0() : false; };

  function load() {
    $$('wf-here').classList.toggle('on', S.side === 'here'); $$('wf-there').classList.toggle('on', S.side === 'there');
    msg(''); footer();
    if (S.side === 'here') { S.list = J(W.ls(S.here)); S.here = S.list.path || ''; render(); }
    else { $$('wf-l').innerHTML = '<div class="msg">Connexion à l\'autre appareil…</div>'; $$('wf-path').textContent = '';
      call('rls', S.there).then(function (r) { S.list = r; S.there = r.path || ''; $$('wf-there').textContent = '🔁 ' + (r.name || 'Autre appareil'); render(); })
        .catch(function (e) { $$('wf-l').innerHTML = ''; msg(e.message); }); }
  }
  function search() {
    var q = $$('wf-q').value.trim(); if (!q) return;
    $$('wf-l').innerHTML = '<div class="msg">Recherche de « ' + esc(q) + ' » dans ' + esc('/' + path()) + '…</div>';
    call(S.side === 'here' ? 'lfind' : 'rfind', q, path()).then(function (r) { S.find = r; S.find.q = q; render(); }).catch(function (e) { $$('wf-l').innerHTML = ''; msg(e.message); });
  }
  function isX(n) { return /\.xrs$/i.test(n); }
  function render() {
    var L = $$('wf-l'), h = '', dirs = (S.state.dirs || []);
    if (S.find) {
      $$('wf-path').textContent = (S.find.hits || []).length + ' résultat(s) pour « ' + S.find.q + ' » dans /' + path() + (S.find.more ? ' (liste limitée)' : '');
      (S.find.hits || []).forEach(function (x, i) {
        h += '<div class="it"><span>' + (x.dir ? '📁' : isX(x.path) ? '📐' : '📄') + '</span><div class="n">' + esc(base(x.path)) + '<small>/' + esc(up(x.path)) + (x.dir ? '' : ' · ' + size(x.size) + ' · ' + new Date(x.mtime).toLocaleString('fr-FR')) + '</small></div>' + acts(x.path, x.dir, i) + '</div>';
      });
    } else {
      var l = S.list || {}; $$('wf-path').textContent = '/' + path() + (S.side === 'there' ? '  (autre appareil)' : '');
      (l.dirs || []).forEach(function (d, i) { var p = join(path(), d), sy = S.side === 'here' && dirs.some(function (x) { return p === x || p.indexOf(x + '/') === 0; });
        h += '<div class="it" data-d="' + esc(p) + '"><span>📁</span><div class="n">' + esc(d) + (sy ? ' <small>🔁 partagé avec l\'autre appareil</small>' : '') + '</div></div>'; });
      (l.files || []).forEach(function (f, i) { var p = join(path(), f.name);
        h += '<div class="it"><span>' + (isX(f.name) ? '📐' : '📄') + '</span><div class="n">' + esc(f.name) + '<small>' + size(f.size) + ' · ' + new Date(f.mtime).toLocaleString('fr-FR') + '</small></div>' + acts(p, false) + '</div>'; });
      if (!h) h = '<div class="msg" style="color:#9ca3af">' + (S.side === 'here' ? 'Aucun schéma ici.' : 'Dossier vide.') + '</div>';
    }
    L.innerHTML = h;
    L.querySelectorAll('[data-d]').forEach(function (e) { e.onclick = function () { S.find = null; setPath(e.dataset.d); load(); }; });
    L.querySelectorAll('[data-a]').forEach(function (e) { e.onclick = function (ev) { ev.stopPropagation(); act(e.dataset.a, e.dataset.p); }; });
  }
  function acts(p, dir) {
    var a = '';
    if (dir) return '<button type="button" data-a="cd" data-p="' + esc(p) + '">Ouvrir</button>';
    if (isX(p) && S.mode === 'open') a += '<button type="button" class="pri" data-a="open" data-p="' + esc(p) + '">Ouvrir</button>';
    if (S.side === 'there') a += '<button type="button" data-a="copy" data-p="' + esc(p) + '">⬇ Copier ici</button>';
    else if (S.mode === 'save') a += '<button type="button" data-a="name" data-p="' + esc(p) + '">Ce nom</button>';
    return a;
  }
  function act(a, p) {
    if (a === 'cd') { S.find = null; setPath(p); return load(); }
    if (a === 'name') { $$('wf-n').value = base(p); return; }
    if (a === 'open') {
      if (S.side === 'here') { close(); W.setDir(up(p)); S.here = up(p); return window.__wrOpen('/wrfile?p=' + encodeURIComponent(p), base(p), up(p)); }
      msg('Réception de ' + base(p) + '…');
      return call('rget', p).then(function (r) { close(); window.__wrOpen('/api/inbox?name=' + encodeURIComponent(r.inbox), r.name); }).catch(function (e) { msg(e.message); });
    }
    if (a === 'copy') {
      var to = S.here || S.state.cur || '';
      msg('Copie de ' + base(p) + ' vers /' + to + '…');
      return call('rcopy', p, to).then(function (r) { msg('Copié : /' + r.rel); }).catch(function (e) { msg(e.message); });
    }
  }
  function footer() {
    var F = $$('wf-f'), h = '';
    if (S.side === 'there') h = '<span style="color:#9ca3af">« ⬇ Copier ici » recopie dans /' + esc(S.here || S.state.cur || '') + ' (dossier de cet appareil)</span>';
    else if (S.mode === 'save') h = '<input id="wf-n" value="' + esc(S.save.name) + '"><button type="button" id="wf-mk">📁＋</button><button type="button" class="pri" id="wf-sv">💾 Enregistrer ici</button><button type="button" id="wf-sh">Envoyer…</button>';
    else h = '<button type="button" id="wf-mk">📁＋ Nouveau dossier</button><button type="button" class="pri" id="wf-cur">✔ Dossier d\'enregistrement</button>';
    F.innerHTML = h;
    if ($$('wf-mk')) $$('wf-mk').onclick = function () { var nm = prompt('Nom du nouveau dossier :'); if (!nm) return; var r = W.mkdir(S.here, nm); if (!r) return msg('Création impossible.'); S.here = r; load(); };
    if ($$('wf-cur')) $$('wf-cur').onclick = function () { if (W.setDir(S.here)) { S.state = J(W.state()); msg('Les schémas seront enregistrés dans /' + S.here + ' (dossier recopié sur l\'autre appareil).'); render(); } };
    if ($$('wf-sv')) $$('wf-sv').onclick = function () {
      var nm = $$('wf-n').value.trim(); if (!nm) return;
      if (S.save.name.match(/\.\w+$/) && !/\.\w+$/.test(nm)) nm += S.save.name.match(/\.\w+$/)[0];
      if ((S.list && S.list.files || []).some(function (f) { return f.name === nm; }) && !confirm('« ' + nm + ' » existe déjà dans ce dossier. Le remplacer ?')) return;
      var r = W.saveTo(S.here, nm, S.save.data); if (!r) return msg('Enregistrement impossible.');
      if (/\.xrs$/i.test(nm) && window.__wlSidecar && W.saveText) { var sc = window.__wlSidecar(); if (sc) W.saveText(S.here, nm.replace(/\.xrs$/i, '') + '.photos.json', sc); }   // photos du schéma, à côté du .xrs
      window.__wlOpenDir = S.here; close();
    };
    if ($$('wf-sh')) $$('wf-sh').onclick = function () { W.share($$('wf-n').value.trim() || S.save.name, S.save.data); };
  }

  function addBtn() {
    var s = document.getElementById('bSave'); if (!s || document.getElementById('bFiles')) return;
    var b = document.createElement('button'); b.id = 'bFiles'; b.type = 'button'; b.textContent = '📁 Fichiers'; b.onclick = function () { open('open'); };
    s.parentNode.insertBefore(b, s.nextSibling);
    var t = document.querySelector('title'); if (t) t.textContent = 'WillRelais';
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addBtn); else addBtn();
})();
