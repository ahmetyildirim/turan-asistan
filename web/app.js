/* Turan Asistan – uygulama */
(function () {
  'use strict';
  var KEY = 'eet-state-v1';
  var pending = []; // henüz bir işe bağlanmamış ekler (state.pending ile kalıcı)
  var N = window.TuranNative || null; // Android uygulaması içindeyse yerel köprü
  if (N) document.documentElement.classList.add('native');
  // Linux masaüstü (Electron) sürümü: preload yalnız {platform, version} verir; Android köprüsü yoktur
  var D = (!N && window.TuranDesktop) || null;
  if (D) document.documentElement.classList.add('desktop');
  var NA = D ? 'Linux sürümünde desteklenmiyor' : 'Android uygulamasında çalışır';
  var $ = function (id) { return document.getElementById(id); };
  var SEED = ['Haftalık rapor hazırlığı', 'Toplantı notlarını düzenle', 'Fatura ödemesi kontrolü',
    'Sözleşme yenileme takibi', 'Ofis malzemesi siparişi'];

  function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  // ---------- Durum ----------
  var state;
  function load() {
    var raw = null;
    if (N) { try { raw = N.getState() || null; } catch (e) {} }
    if (!raw) { try { raw = localStorage.getItem(KEY); } catch (e) {} }
    if (raw) { try { state = JSON.parse(raw); } catch (e) { state = null; } }
    if (!state) {
      // İlk açılış: mevcut açık işlerle başla
      state = { version: 1, items: [], settings: { schedule: clone(Sched.DEFAULT_SCHEDULE), autoSpeak: false }, lastShown: {} };
      SEED.forEach(function (t, i) {
        state.items.push({ id: 'seed' + i, text: t, created: '2026-11-14T12:0' + i + ':00+03:00', status: 'open', closed: null });
      });
      save();
    }
    state.settings = state.settings || {};
    state.settings.schedule = state.settings.schedule || clone(Sched.DEFAULT_SCHEDULE);
    state.lastShown = state.lastShown || {};
    state.attachments = state.attachments || {};
    pending = (state.pending || []).slice();
  }
  // Hiçbir işe bağlı olmayan ekleri sil (yalnızca açılışta; geri alma güvenli kalsın)
  function cleanupOrphans() {
    if (!N) return;
    var used = {};
    state.items.forEach(function (i) { (i.att || []).forEach(function (a) { used[a] = 1; }); });
    (state.pending || []).forEach(function (a) { used[a] = 1; });
    var changed = false;
    Object.keys(state.attachments).forEach(function (id) {
      if (!used[id]) { try { N.deleteAtt(id); } catch (e) {} delete state.attachments[id]; changed = true; }
    });
    if (changed) save();
  }
  function save() {
    if (typeof pending !== 'undefined') state.pending = pending.slice();
    var json = JSON.stringify(state);
    if (N) { try { N.setState(json); } catch (e) { alert('Kayıt yapılamadı: ' + e.message); } }
    try { localStorage.setItem(KEY, json); } catch (e) { if (!N) alert('Kayıt yapılamadı: ' + e.message); }
    if (!N) mirror();
  }
  // Yerel taraf (bildirimden not alma, alarm kaydı) durumu değiştirmiş olabilir: her işlemden önce tazele
  function refresh() {
    if (!N) return;
    try { var raw = N.getState(); if (raw) { var s2 = JSON.parse(raw); if (s2 && Array.isArray(s2.items)) { state = s2; state.lastShown = state.lastShown || {}; state.attachments = state.attachments || {}; pending = (state.pending || []).slice(); } } } catch (e) {}
  }
  // Service worker'ın okuyabilmesi için Cache Storage'a kopya
  function mirror() {
    if (!('caches' in window)) return;
    caches.open('eet-data').then(function (c) {
      return c.put('./__state.json', new Response(JSON.stringify(state), { headers: { 'Content-Type': 'application/json' } }));
    }).catch(function () {});
    scheduleTriggers();
  }

  // ---------- Yardımcılar ----------
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  var toastTimer, undoFn;
  function toast(msg, undo) {
    $('toastText').textContent = msg;
    undoFn = undo || null;
    $('toastUndo').classList.toggle('hidden', !undo);
    $('toast').classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { $('toast').classList.add('hidden'); undoFn = null; }, undo ? 6000 : 2500);
  }
  $('toastUndo').onclick = function () { if (undoFn) undoFn(); $('toast').classList.add('hidden'); undoFn = null; };

  // ---------- Görünümler ----------
  function show(view) {
    document.querySelectorAll('.view').forEach(function (v) { v.classList.toggle('active', v.id === 'view-' + view); });
    document.querySelectorAll('.tabs button').forEach(function (b) { b.classList.toggle('active', b.dataset.view === view); });
    window.scrollTo(0, 0);
  }
  document.querySelectorAll('.tabs button').forEach(function (b) { b.onclick = function () { show(b.dataset.view); }; });

  // ---------- Arama / filtre ----------
  var filt = 'all';
  function norm(t) { return String(t || '').toLocaleLowerCase('tr-TR').replace(/ı/g, 'i').replace(/\u0307/g, '').replace(/ş/g, 's').replace(/ğ/g, 'g').replace(/ü/g, 'u').replace(/ö/g, 'o').replace(/ç/g, 'c'); }
  function matches(it, q) {
    if (!q) return true;
    var hay = norm(it.text + ' ' + (it.att || []).map(function (a) { return state.attachments[a] ? state.attachments[a].name : ''; }).join(' '));
    return norm(q).split(/\s+/).every(function (w) { return !w || hay.indexOf(w) >= 0; });
  }
  function passFilter(it) {
    if (filt === 'acil') return it.prio === 'acil';
    if (filt === 'due') return !!it.due;
    if (filt === 'rem') return !!it.remindAt && !it.remindFired;
    if (filt === 'att') return (it.att || []).length > 0;
    return true;
  }
  $('filters').onclick = function (e) {
    var b = e.target.closest('button[data-f]'); if (!b) return;
    filt = b.dataset.f;
    [].forEach.call(this.querySelectorAll('button'), function (x) { x.classList.toggle('on', x === b); });
    render();
  };
  $('q').oninput = render; $('qa').oninput = render;
  function fmtDT(iso) {
    return new Intl.DateTimeFormat('tr-TR', { timeZone: Sched.TZ, day: 'numeric', month: 'short', weekday: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(iso));
  }
  function badges(i) {
    var h = '';
    if (i.prio === 'acil') h += '<span class="tag acil">ACİL</span>';
    var d = Sched.dueInfo(i);
    if (d) h += '<span class="tag ' + (d.over ? 'over' : d.today ? 'today' : 'due') + '">📅 ' + (d.over ? 'Süresi geçti: ' : d.today ? 'Bugün: ' : 'Son: ') + esc(d.text) + '</span>';
    if (i.remindAt && !i.remindFired && i.status === 'open') h += '<span class="tag rem">⏰ ' + esc(fmtDT(i.remindAt)) + '</span>';
    return h ? '<div class="badges">' + h + '</div>' : '';
  }

  function render() {
    var allOpen = state.items.filter(function (i) { return i.status === 'open'; });
    var urg = allOpen.filter(function (i) { return i.prio === 'acil'; }).length;
    var q = $('q').value.trim(), qa = $('qa').value.trim();
    var open = allOpen.filter(function (i) { return matches(i, q) && passFilter(i); })
      .sort(function (a, b) { return ((b.prio === 'acil') - (a.prio === 'acil')) || (a.created < b.created ? -1 : 1); });
    var allArch = state.items.filter(function (i) { return i.status !== 'open'; });
    var arch = allArch.filter(function (i) { return matches(i, qa); }).sort(function (a, b) { return (b.closed || '') < (a.closed || '') ? -1 : 1; });
    $('openCount').textContent = allOpen.length;
    $('urgCount').textContent = urg + ' acil'; $('urgCount').classList.toggle('hidden', !urg);
    $('archCount').textContent = allArch.length;
    $('openEmpty').classList.toggle('hidden', allOpen.length > 0);
    $('openNoMatch').classList.toggle('hidden', !allOpen.length || open.length > 0);
    $('archEmpty').classList.toggle('hidden', arch.length > 0);
    $('openList').innerHTML = open.map(function (i) {
      return '<li class="item' + (i.prio === 'acil' ? ' acil' : '') + '" data-id="' + i.id + '"><div class="t">' + esc(i.text) + '</div>' + badges(i) +
        '<div class="d">' + Sched.fmtDate(i.created) + ' tarihinde eklendi</div>' + chipsHTML(i.att, i.id) +
        '<div class="b"><button class="ok" data-act="done">✔ Tamamlandı</button>' +
        '<button data-act="dropped">Takibe gerek yok</button>' +
        '<button class="edit" data-act="more" aria-label="Diğer işlemler">⋯</button></div></li>';
    }).join('');
    $('archList').innerHTML = arch.map(function (i) {
      return '<li class="item" data-id="' + i.id + '"><div class="t">' + esc(i.text) +
        (i.status === 'done' ? '<span class="tag done">Tamamlandı</span>' : '<span class="tag dropped">Takibe gerek yok</span>') + '</div>' +
        '<div class="d">Eklendi: ' + Sched.fmtDate(i.created) + (i.closed ? ' · Kapandı: ' + Sched.fmtDate(i.closed) : '') + '</div>' + chipsHTML(i.att, i.id) +
        '<div class="b"><button data-act="restore">↩ Geri yükle</button><button class="edit" data-act="delete" aria-label="Kalıcı sil">🗑</button></div></li>';
    }).join('');
    renderPending();
    renderNext();
  }
  function attIcon(m) { return /^image\//.test(m) ? '🖼️' : /pdf/.test(m) ? '📕' : /word|docx/.test(m) ? '📘' : /^text\//.test(m) ? '📄' : '📎'; }
  function chipsHTML(ids, owner) {
    ids = (ids || []).filter(function (a) { return state.attachments[a]; });
    if (!ids.length) return '';
    return '<div class="atts">' + ids.map(function (a) {
      var m = state.attachments[a];
      return '<button type="button" class="chip" data-att="' + esc(a) + '" data-owner="' + esc(owner) + '">' + attIcon(m.mime) + '<span>' + esc(m.name) + '</span></button>';
    }).join('') + '</div>';
  }
  function renderPending() {
    var el = $('pendingAtts'); if (!el) return;
    pending = pending.filter(function (a) { return state.attachments[a]; });
    el.innerHTML = pending.length ? chipsHTML(pending, 'pending').replace(/^<div class="atts">|<\/div>$/g, '') : '';
  }
  function renderNext() {
    var n = Sched.upcoming(state.settings.schedule, 8)[0];
    if (!n) { $('nextInfo').textContent = 'Program kapalı'; return; }
    var d = new Intl.DateTimeFormat('tr-TR', { timeZone: Sched.TZ, weekday: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(n.at));
    $('nextInfo').textContent = 'Sıradaki: ' + d + ' · ' + (n.type === 'arz' ? 'arz' : 'not alma');
  }

  function find(id) { return state.items.filter(function (i) { return i.id === id; })[0]; }
  function revert(before) { refresh(); var cur = find(before.id); if (cur) Object.assign(cur, before); else state.items.push(before); save(); render(); }
  function onListClick(e) {
    var chip = e.target.closest('.chip'); if (chip) { chipMenu(chip.dataset.att, chip.dataset.owner); return; }
    var btn = e.target.closest('button[data-act]'); if (!btn) return;
    refresh();
    var li = btn.closest('.item'), it = find(li.dataset.id); if (!it) { render(); return; }
    var act = btn.dataset.act, before = clone(it);
    if (act === 'done' || act === 'dropped') {
      it.status = act; it.closed = new Date().toISOString(); save(); render();
      toast(act === 'done' ? 'Emredersiniz komutanım, tamamlandı olarak kapatıldı.' : 'Emredersiniz komutanım, takipten çıkarıldı.',
        function () { revert(before); });
    } else if (act === 'restore') {
      it.status = 'open'; it.closed = null; save(); render();
      toast('Emredersiniz komutanım, konu yeniden takipte.', function () { revert(before); });
    } else if (act === 'delete') {
      if (!confirm('"' + it.text + '" kalıcı olarak silinsin mi?')) return;
      state.items = state.items.filter(function (x) { return x !== it; }); save(); render();
      toast('Kalıcı olarak silindi.', function () { revert(before); });
    } else if (act === 'more') {
      var opts = [{ label: '✎ Düzenle · öncelik · son tarih · hatırlatma', fn: function () { startEdit(it); } },
        { label: it.prio === 'acil' ? '⬇ Normal önceliğe al' : '🔺 Acil olarak işaretle', fn: function () { refresh(); var x = find(it.id); if (x) { x.prio = x.prio === 'acil' ? '' : 'acil'; save(); render(); toast('Emredersiniz komutanım.'); } } },
        { label: '⏰ 1 saat sonra hatırlat', fn: function () { refresh(); var x = find(it.id); if (x) { x.remindAt = new Date(Date.now() + 3600000).toISOString(); x.remindFired = false; save(); render(); toast('Emredersiniz komutanım, ' + fmtDT(x.remindAt) + ' hatırlatacağım.'); } } }];
      if (N) opts.push(
        { label: '📷 Fotoğraf çek ve ekle', fn: function () { N.takePhoto(it.id); } },
        { label: '🖼️ Galeriden ekle', fn: function () { N.pickFiles(it.id, 'gallery'); } },
        { label: '📎 Dosya ekle (PDF, Word, TXT…)', fn: function () { N.pickFiles(it.id, 'file'); } },
        { label: '↗ Paylaş (konu ve ekleri)', fn: function () { shareItem(it); } });
      sheet(it.text, opts);
    } else if (act === 'edit') {
      editing = it.id; $('editInput').value = it.text; $('editModal').classList.remove('hidden'); $('editInput').focus();
    }
  }
  $('openList').onclick = onListClick;
  $('archList').onclick = onListClick;

  // İstanbul yerel saat <-> ISO
  function istLocal(iso) {
    if (!iso) return '';
    var p = Sched.istParts(new Date(iso));
    return Sched.ymd(p) + 'T' + String(p.h).padStart(2, '0') + ':' + String(p.min).padStart(2, '0');
  }
  function fromIstLocal(v) { return v ? new Date(v + ':00+03:00').toISOString() : ''; }
  function startEdit(it) {
    editing = it.id; $('editInput').value = it.text;
    $('editPrio').value = it.prio || ''; $('editDue').value = it.due || '';
    $('editRem').value = it.remindAt && !it.remindFired ? istLocal(it.remindAt) : '';
    $('editModal').classList.remove('hidden'); $('editInput').focus();
  }
  $('remQuick').onclick = function (e) {
    var b = e.target.closest('button[data-q]'); if (!b) return;
    if (b.dataset.q === 'clear') $('editRem').value = '';
    else if (b.dataset.q === '60') $('editRem').value = istLocal(new Date(Date.now() + 3600000).toISOString());
    else { var p = Sched.istParts(new Date(Date.now() + 86400000)); $('editRem').value = Sched.ymd(p) + 'T09:00'; }
  };
  var editing = null;
  $('editForm').onsubmit = function (e) {
    e.preventDefault();
    var v = $('editInput').value.trim();
    refresh(); var it = editing && find(editing);
    var r = fromIstLocal($('editRem').value);
    if (it && v) {
      it.text = v; it.prio = $('editPrio').value; it.due = $('editDue').value || '';
      var prev = it.remindAt && !it.remindFired ? istLocal(it.remindAt) : '';
      if ($('editRem').value !== prev) { it.remindAt = r; it.remindFired = false; }
      save(); render();
      toast('Emredersiniz komutanım.' + ($('editRem').value !== prev && r ? ' ' + fmtDT(r) + ' hatırlatacağım.' : ''));
    }
    editing = null; $('editModal').classList.add('hidden');
  };
  $('btnEditCancel').onclick = function () { editing = null; $('editModal').classList.add('hidden'); };

  // ---------- Not alma ----------
  $('addForm').onsubmit = function (e) {
    e.preventDefault();
    var v = $('addInput').value.trim();
    refresh(); renderPending();
    if (!v && pending.length) v = pending.map(function (a) { return state.attachments[a].name.replace(/\.[^.]+$/, ''); }).join(', ');
    if (!v) { $('addInput').focus(); return; }
    var it = { id: uid(), text: v, created: new Date().toISOString(), status: 'open', closed: null };
    if (pending.length) { it.att = pending.slice(); pending = []; }
    state.items.push(it); save(); render();
    $('addInput').value = '';
    $('banner').classList.add('hidden');
    toast('Emredersiniz komutanım.', function () { refresh(); state.items = state.items.filter(function (x) { return x.id !== it.id; }); save(); render(); });
  };
  function openNotAlma() {
    show('open');
    $('banner').textContent = Sched.NOT_TEXT;
    $('banner').classList.remove('hidden');
    setTimeout(function () { $('addInput').focus(); }, 50);
  }

  // Sesle not alma (Web Speech API)
  // Masaüstünde Chromium'un konuşma tanıma servisi (Google API anahtarı gerektirir) çalışmaz → kapalı
  var SR = D ? null : (window.SpeechRecognition || window.webkitSpeechRecognition), rec = null;
  window.__turanVoice = function (t) {
    if (!t) return; t = t.charAt(0).toLocaleUpperCase('tr-TR') + t.slice(1);
    $('addInput').value = ($('addInput').value ? $('addInput').value + ' ' : '') + t; $('addInput').focus();
  };
  if (N) {
    $('micBtn').classList.remove('hidden');
    $('micBtn').onclick = function () { N.listen(); };
  } else if (SR) {
    $('micBtn').classList.remove('hidden');
    $('micBtn').onclick = function () {
      if (rec) { rec.stop(); return; }
      rec = new SR(); rec.lang = 'tr-TR'; rec.interimResults = false; rec.maxAlternatives = 1;
      rec.onresult = function (ev) {
        var t = ev.results[0][0].transcript; t = t.charAt(0).toLocaleUpperCase('tr-TR') + t.slice(1);
        $('addInput').value = ($('addInput').value ? $('addInput').value + ' ' : '') + t;
      };
      rec.onerror = function (ev) { toast('Ses alınamadı (' + ev.error + ')'); };
      rec.onend = function () { rec = null; $('micBtn').classList.remove('rec'); };
      $('micBtn').classList.add('rec'); rec.start();
    };
  }

  // ---------- Arz ----------
  function arzHTML() {
    var g = Sched.arzGroups(state.items);
    if (!g.length) return '<p>Emir eri Turan, tekmil veriyorum. Komutanım, takipte açık iş bulunmuyor. Arz ederim.</p>';
    return '<p>' + Sched.ARZ_HEAD + '</p>' + g.map(function (x) {
      return '<p><b>' + x.date + ' tarihli:</b></p><ul>' + x.items.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>';
    }).join('') + '<p>' + Sched.ARZ_TAIL + '</p>';
  }
  function openArz(auto) {
    $('arzText').innerHTML = arzHTML();
    $('arzModal').classList.remove('hidden');
    if (auto && state.settings.autoSpeak) speak();
  }
  $('btnArz').onclick = function () { openArz(false); };
  $('btnArzOk').onclick = function () { $('arzModal').classList.add('hidden'); if (N) N.stopSpeak(); else if (window.speechSynthesis) speechSynthesis.cancel(); };
  function speak() {
    if (N) { N.speak(Sched.arzPlain(state.items).replace(/•/g, '') || 'Emir eri Turan, tekmil veriyorum. Komutanım, takipte açık iş bulunmuyor. Arz ederim.'); return; }
    if (!window.speechSynthesis) { toast('Bu cihaz sesli okumayı desteklemiyor.'); return; }
    speechSynthesis.cancel();
    var text = Sched.arzPlain(state.items) || 'Emir eri Turan, tekmil veriyorum. Komutanım, takipte açık iş bulunmuyor. Arz ederim.';
    var u = new SpeechSynthesisUtterance(text.replace(/•/g, '')); u.lang = 'tr-TR'; u.rate = 0.95;
    var v = speechSynthesis.getVoices().filter(function (x) { return /^tr/i.test(x.lang); })[0];
    if (v) u.voice = v;
    speechSynthesis.speak(u);
  }
  $('btnSpeak').onclick = speak;

  // ---------- Bildirimler ----------
  function notify(type) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    var title = type === 'arz' ? 'Turan Asistan – Arz' : 'Turan Asistan – Not alma';
    var body = type === 'arz' ? Sched.arzPlain(state.items) : Sched.NOT_TEXT;
    var opts = { body: body, tag: type, icon: 'icons/icon-192.png', badge: 'icons/icon-192.png', data: { url: './index.html?view=' + type } };
    if (navigator.serviceWorker && navigator.serviceWorker.controller) {
      navigator.serviceWorker.ready.then(function (r) { r.showNotification(title, opts); });
    } else { try { new Notification(title, opts); } catch (e) {} }
  }
  // Saati gelmiş ve bugün gösterilmemiş slotları gösterir (açıkken + açılışta telafi)
  function checkDue(isOpening) {
    refresh();
    var now = Date.now(), p = Sched.istParts(new Date(now)), changed = false;
    Sched.slotsForDay(state.settings.schedule, p).forEach(function (s) {
      if (now < s.at || state.lastShown[s.type] === s.day) return;
      state.lastShown[s.type] = s.day; changed = true;
      if (s.type === 'arz') {
        if (!Sched.arzGroups(state.items).length) return; // açık iş yoksa rahatsız etme
        if (document.visibilityState !== 'visible') notify('arz');
        openArz(true);
      } else {
        if (document.visibilityState !== 'visible') notify('not');
        openNotAlma();
      }
    });
    // Web sürümünde tek seferlik hatırlatmalar (Android'de yerel alarm gösterir)
    if (!N) state.items.forEach(function (it) {
      if (it.status !== 'open' || !it.remindAt || it.remindFired || Date.parse(it.remindAt) > now) return;
      it.remindFired = true; changed = true;
      if ('Notification' in window && Notification.permission === 'granted') { try { new Notification('Turan Asistan – Hatırlatma', { body: Sched.itemLine(it), icon: 'icons/icon-192.png' }); } catch (e) {} }
      toast('⏰ Hatırlatma: ' + it.text);
    });
    if (changed) { save(); render(); }
  }

  // Notification Triggers (yalnızca destekleyen tarayıcılarda; çoğu desteklemez)
  function scheduleTriggers() {
    if (N || !('Notification' in window) || !('showTrigger' in Notification.prototype) || typeof TimestampTrigger === 'undefined') return;
    if (Notification.permission !== 'granted' || !navigator.serviceWorker) return;
    navigator.serviceWorker.ready.then(function (reg) {
      return reg.getNotifications({ includeTriggered: true }).then(function (list) {
        list.forEach(function (n) { if (/^sched-/.test(n.tag)) n.close(); });
        Sched.upcoming(state.settings.schedule, 7).forEach(function (s) {
          var body = s.type === 'arz' ? Sched.arzPlain(state.items) : Sched.NOT_TEXT;
          if (!body) return;
          reg.showNotification(s.type === 'arz' ? 'Turan Asistan – Arz' : 'Turan Asistan – Not alma', {
            body: body, tag: 'sched-' + s.type + '-' + s.day, icon: 'icons/icon-192.png',
            showTrigger: new TimestampTrigger(s.at), data: { url: './index.html?view=' + s.type } });
        });
      });
    }).catch(function () {});
  }
  function registerPeriodic() {
    if (!navigator.serviceWorker) return;
    navigator.serviceWorker.ready.then(function (reg) {
      if (!reg.periodicSync) return;
      return navigator.permissions.query({ name: 'periodic-background-sync' }).then(function (st) {
        if (st.state === 'granted') return reg.periodicSync.register('turan-check', { minInterval: 60 * 60 * 1000 });
      });
    }).catch(function () {});
  }
  function notifStatus() {
    if (N) {
      var st = {}; try { st = JSON.parse(N.status()); } catch (e) {}
      $('nNotif').textContent = st.notif ? 'verildi ✔' : 'verilmedi ✖';
      $('nExact').textContent = st.exact ? 'verildi ✔' : 'verilmedi ✖';
      $('nBattery').textContent = st.battery ? 'kapalı ✔' : 'açık (önerilmez)';
      $('nNext').textContent = st.next || '—';
      $('btnNPerm').classList.toggle('hidden', !!st.notif);
      $('btnNExact').classList.toggle('hidden', !!st.exact);
      $('btnNBattery').classList.toggle('hidden', !!st.battery);
      try { var asst = N.isAssistant(); $('nAssist').textContent = asst ? 'Turan Asistan ✔' : 'başka uygulama'; $('btnAssist').textContent = asst ? '🎙 Asistan ayarlarını aç' : '🎙 Turan Asistan\'ı varsayılan asistan yap'; } catch (e) {}
      try { $('persistOn').checked = N.getPersistent(); } catch (e) {}
      return;
    }
    var lines = [];
    if (!('Notification' in window)) lines.push('Bu tarayıcı bildirim desteklemiyor. (iPhone\'da uygulamayı önce Ana Ekrana ekleyip oradan açın.)');
    else lines.push('Bildirim izni: ' + ({ granted: 'verildi ✔', denied: 'reddedildi ✖ (tarayıcı ayarlarından açın)', default: 'henüz verilmedi' })[Notification.permission]);
    lines.push('Uygulama kapalıyken saatli bildirim: ' + (('showTrigger' in (window.Notification ? Notification.prototype : {})) ? 'zamanlı bildirim destekleniyor' :
      'bu cihazda garanti değil → “Takvime ekle”yi kullanın'));
    $('notifStatus').innerHTML = lines.map(esc).join('<br>');
    $('btnPerm').classList.toggle('hidden', !('Notification' in window) || Notification.permission === 'granted');
  }
  window.__turanStatus = notifStatus;
  if (N) {
    $('btnNPerm').onclick = function () { N.requestNotif(); };
    $('btnNExact').onclick = function () { N.requestExact(); };
    $('btnNBattery').onclick = function () { N.requestBattery(); };
    $('btnNTest').onclick = function () { N.test(); toast('Deneme bildirimi gönderildi.'); };
  }
  $('btnPerm').onclick = function () {
    Notification.requestPermission().then(function () { notifStatus(); scheduleTriggers(); registerPeriodic(); });
  };
  $('btnTest').onclick = function () {
    if (!('Notification' in window) || Notification.permission !== 'granted') { toast('Önce bildirim izni verin.'); return; }
    notify(Sched.arzGroups(state.items).length ? 'arz' : 'not'); toast('Deneme bildirimi gönderildi.');
  };

  // ---------- Takvime ekle (.ics) ----------
  function icsEsc(s) { return String(s).replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/,/g, '\\,').replace(/;/g, '\\;'); }
  function fold(line) {
    var out = [], bytes = new TextEncoder().encode(line);
    if (bytes.length <= 74) return line;
    var cur = '', len = 0;
    for (var ch of line) {
      var l = new TextEncoder().encode(ch).length;
      if (len + l > 73) { out.push(cur); cur = ' '; len = 1; }
      cur += ch; len += l;
    }
    out.push(cur); return out.join('\r\n');
  }
  function buildICS() {
    var appUrl = location.href.split('?')[0].split('#')[0];
    var groups = { wd: ['MO', 'TU', 'WE', 'TH', 'FR'], sa: ['SA'], su: ['SU'] };
    var codes = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];
    var stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+/, '');
    var L = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Turan Asistan//TR', 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH',
      'X-WR-CALNAME:Turan Asistan', 'X-WR-TIMEZONE:Europe/Istanbul',
      'BEGIN:VTIMEZONE', 'TZID:Europe/Istanbul', 'BEGIN:STANDARD', 'DTSTART:19700101T000000',
      'TZOFFSETFROM:+0300', 'TZOFFSETTO:+0300', 'TZNAME:+03', 'END:STANDARD', 'END:VTIMEZONE'];
    var sch = state.settings.schedule, count = 0;
    Object.keys(groups).forEach(function (k) {
      if (!sch[k] || !sch[k].on) return;
      // İlk uygun günü bul
      var first = null;
      for (var i = 0; i < 8 && !first; i++) {
        var p = Sched.istParts(new Date(Date.now() + i * 86400000));
        if (groups[k].indexOf(codes[p.wd]) >= 0) first = p;
      }
      ['arz', 'not'].forEach(function (t) {
        if (!sch[k][t]) return;
        var hm = sch[k][t].replace(':', '') + '00';
        var dt = first.y + String(first.m).padStart(2, '0') + String(first.d).padStart(2, '0') + 'T' + hm;
        var title = t === 'arz' ? 'Turan: Arz saati' : 'Turan: Not alma saati';
        var desc = (t === 'arz' ? 'Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle açık işleri arz etmek istiyorum. Arz ederim.'
          : Sched.NOT_TEXT) + '\n' + appUrl + '?view=' + t;
        L.push('BEGIN:VEVENT', 'UID:eet-' + t + '-' + k + '@emir-eri-turan', 'DTSTAMP:' + stamp,
          'DTSTART;TZID=Europe/Istanbul:' + dt, 'DURATION:PT10M',
          'RRULE:FREQ=WEEKLY;BYDAY=' + groups[k].join(','),
          'SUMMARY:' + icsEsc(title), 'DESCRIPTION:' + icsEsc(desc), 'URL:' + appUrl + '?view=' + t,
          'TRANSP:TRANSPARENT',
          'BEGIN:VALARM', 'ACTION:DISPLAY', 'DESCRIPTION:' + icsEsc(title), 'TRIGGER:PT0M', 'END:VALARM',
          'END:VEVENT');
        count++;
      });
    });
    L.push('END:VCALENDAR');
    return { text: L.map(fold).join('\r\n') + '\r\n', count: count };
  }
  $('btnIcs').onclick = function () {
    var ics = buildICS();
    if (!ics.count) { toast('Program kapalı, eklenecek etkinlik yok.'); return; }
    var isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    if (isIOS) {
      // iOS Safari .ics'i doğrudan "Takvime ekle" ekranında açar
      location.href = 'data:text/calendar;charset=utf-8,' + encodeURIComponent(ics.text);
    } else {
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([ics.text], { type: 'text/calendar;charset=utf-8' }));
      a.download = 'turan-hatirlatmalar.ics'; document.body.appendChild(a); a.click(); a.remove();
      toast('Takvim dosyası indirildi; açıp takviminize ekleyin.');
    }
  };
  window.__buildICS = buildICS; // test için
  // Google Takvim şablon bağlantıları (Android'de .ics içe aktarma yoksa)
  function renderGLinks() {
    var appUrl = location.href.split('?')[0].split('#')[0];
    var groups = { wd: ['MO', 'TU', 'WE', 'TH', 'FR'], sa: ['SA'], su: ['SU'] }, names = { wd: 'Hafta içi', sa: 'Cumartesi', su: 'Pazar' };
    var codes = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'], sch = state.settings.schedule, html = '';
    Object.keys(groups).forEach(function (k) {
      if (!sch[k] || !sch[k].on) return;
      var first = null;
      for (var i = 0; i < 8 && !first; i++) { var p = Sched.istParts(new Date(Date.now() + i * 86400000)); if (groups[k].indexOf(codes[p.wd]) >= 0) first = p; }
      ['arz', 'not'].forEach(function (t) {
        if (!sch[k][t]) return;
        var d = first.y + String(first.m).padStart(2, '0') + String(first.d).padStart(2, '0');
        var hm = sch[k][t].split(':'), end = new Date(Date.UTC(2000, 0, 1, +hm[0], +hm[1] + 10));
        var e = String(end.getUTCHours()).padStart(2, '0') + String(end.getUTCMinutes()).padStart(2, '0');
        var title = t === 'arz' ? 'Turan: Arz saati' : 'Turan: Not alma saati';
        var url = 'https://calendar.google.com/calendar/render?action=TEMPLATE&text=' + encodeURIComponent(title) +
          '&details=' + encodeURIComponent((t === 'arz' ? 'Emir eri Turan, tekmil veriyorum. Komutanım, açık işleri arz etmek istiyorum. Arz ederim.' : Sched.NOT_TEXT) + '\n' + appUrl + '?view=' + t) +
          '&dates=' + d + 'T' + hm.join('') + '00/' + d + 'T' + e + '00&ctz=Europe/Istanbul&recur=' + encodeURIComponent('RRULE:FREQ=WEEKLY;BYDAY=' + groups[k].join(','));
        html += '<a target="_blank" rel="noopener" href="' + url + '">+ ' + names[k] + ' ' + sch[k][t] + ' – ' + (t === 'arz' ? 'Arz' : 'Not alma') + '</a>';
      });
    });
    $('glinks').innerHTML = html || '<span class="muted">Program kapalı.</span>';
  }

  // ---------- Ekler, paylaşım, analiz ----------
  function sheet(title, opts) {
    $('sheetTitle').textContent = title;
    var body = $('sheetBody'); body.innerHTML = '';
    opts.forEach(function (o) {
      if (o.html) { var d = document.createElement('div'); d.innerHTML = o.html; body.appendChild(d); return; }
      var b = document.createElement('button'); b.textContent = o.label; if (o.primary) b.className = 'primary';
      b.onclick = function () { if (!o.keep) closeSheet(); o.fn(); }; body.appendChild(b);
    });
    $('sheetModal').classList.remove('hidden');
  }
  function closeSheet() { $('sheetModal').classList.add('hidden'); }
  $('sheetClose').onclick = closeSheet;

  if (N) $('attBtn').onclick = function () {
    sheet('Fotoğraf / dosya ekle', [
      { label: '📷 Fotoğraf çek', fn: function () { N.takePhoto('new'); } },
      { label: '🖼️ Galeriden seç', fn: function () { N.pickFiles('new', 'gallery'); } },
      { label: '📎 Dosya seç (PDF, Word, TXT…)', fn: function () { N.pickFiles('new', 'file'); } }]);
  };
  $('pendingAtts').onclick = function (e) { var c = e.target.closest('.chip'); if (c) chipMenu(c.dataset.att, 'pending'); };

  function registerFiles(files) {
    files.forEach(function (m) { state.attachments[m.id] = m; });
    return files.map(function (m) { return m.id; });
  }
  // Yerel taraftan gelen dosyalar (kamera, galeri, dosya seçici)
  window.__turanFiles = function (target, files, err) {
    refresh();
    if (err && !files.length) { toast('Dosya alınamadı: ' + err); return; }
    var ids = registerFiles(files);
    if (target === 'new') {
      pending = pending.concat(ids); save(); show('open'); render();
      toast('Emredersiniz komutanım, ' + ids.length + ' dosya alındı. Konuyu yazıp “Not al”a basın ya da dosyaya dokunup analiz ettirin.');
      if (ids.length === 1 && /^image\//.test(files[0].mime)) setTimeout(function () { chipMenu(ids[0], 'pending'); }, 400);
    } else {
      var it = find(target);
      if (!it) { pending = pending.concat(ids); save(); render(); return; }
      it.att = (it.att || []).concat(ids); save(); render();
      toast('Emredersiniz komutanım, ' + ids.length + ' dosya “' + it.text + '” konusuna eklendi.');
    }
  };

  function chipMenu(attId, owner) {
    var m = state.attachments[attId]; if (!m) return;
    var size = m.size > 1048576 ? (m.size / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(m.size / 1024)) + ' KB';
    var opts = [
      { label: '👁 Aç', fn: function () { N.openAtt(m.id, m.name, m.mime); } },
      { label: '🔍 Analiz et ve not çıkar', fn: function () { analyzeAtt(m.id); } },
      { label: '↗ Paylaş', fn: function () { N.share('', JSON.stringify([m])); } },
      { label: '✕ Kaldır', fn: function () {
        refresh();
        if (owner === 'pending') { pending = pending.filter(function (a) { return a !== attId; }); save(); render(); return; }
        var it = find(owner); if (!it) return;
        var before = clone(it);
        it.att = (it.att || []).filter(function (a) { return a !== attId; }); save(); render();
        toast('Ek kaldırıldı.', function () { revert(before); });
      } }];
    if (!N) opts = [];
    sheet(m.name + ' (' + size + ')', opts);
  }

  function shareItem(it) {
    var files = (it.att || []).map(function (a) { return state.attachments[a]; }).filter(Boolean);
    var text = 'Takip konusu: ' + it.text + ' (' + Sched.fmtDate(it.created) + ')';
    N.share(text, JSON.stringify(files));
  }

  // Paylaş > Turan Asistan
  window.__turanShared = function (d) {
    refresh();
    var ids = registerFiles(d.files || []); save();
    var text = (d.text || '').trim(), subject = (d.subject || '').trim();
    if (!ids.length && !text) { toast('Paylaşılan içerik alınamadı. ' + (d.error || '')); return; }
    var open = state.items.filter(function (i) { return i.status === 'open'; });
    var list = ids.map(function (a) { var m = state.attachments[a]; return attIcon(m.mime) + ' ' + esc(m.name); }).join('<br>');
    var preview = text ? '<p class="small muted">' + esc(text.slice(0, 300)) + (text.length > 300 ? '…' : '') + '</p>' : '';
    var opts = [{ html: '<p>Komutanım, paylaşılan içerik alındı:</p><p class="small">' + list + '</p>' + preview }];
    opts.push({ label: '➕ Yeni iş olarak ekle', primary: true, fn: function () {
      pending = pending.concat(ids); save(); show('open'); render();
      $('addInput').value = (subject || (ids.length ? '' : text)).slice(0, 200);
      if (!ids.length && text) $('addInput').value = text.slice(0, 200);
      $('addInput').focus(); toast('Konuyu kontrol edip “Not al”a basın komutanım.');
    } });
    if (open.length) opts.push({ label: '📌 Mevcut işe ekle', fn: function () {
      sheet('Hangi işe eklensin?', open.map(function (it) {
        return { label: it.text, fn: function () {
          refresh(); var cur = find(it.id); if (!cur) return;
          if (ids.length) { cur.att = (cur.att || []).concat(ids); save(); render(); toast('Emredersiniz komutanım, “' + cur.text + '” konusuna eklendi.'); }
          if (text) N.saveText(it.id, text, (subject || 'Paylasilan_metin').replace(/[^\wçğıöşüÇĞİÖŞÜ -]/g, '').slice(0, 40) + '.txt');
        } };
      }));
    } });
    var sharedFiles = ids.map(function (a) { return state.attachments[a]; });
    if (N && sharedFiles.some(function (m) { return /pdf/i.test(m.mime) || /\.pdf$/i.test(m.name); })) opts.push({ label: '📚 Arşive ekle (araştırma botu)', fn: function () { shareToArchive(sharedFiles); } });
    opts.push({ label: '🔍 Analiz et ve not çıkar', fn: function () {
      if (ids.length) { pending = pending.concat(ids); save(); render(); analyzeAtt(ids[0]); }
      else analyzeText(text, subject || 'Paylaşılan metin');
    } });
    sheet('Paylaş → Turan Asistan', opts);
  };

  // ----- Analiz -----
  var ana = null, anaSeq = 0;
  var KW = /(yapılacak|yapılması|yapılacaktır|gönderilecek|gönderilmesi|gönderilmiştir|iletilecek|iletilmesi|takip|hazırlanacak|hazırlanması|aranacak|aranması|teslim|kontrol|imzalanacak|imzalanması|onaylanacak|onaylanması|bildirilecek|bildirilmesi|düzenlenecek|düzenlenmesi|tamamlanacak|tamamlanması|gerekmektedir|gerekiyor|gereklidir|son tarih|en geç|toplantı|ödenecek|ödenmesi|alınacak|alınması|verilecek|verilmesi|yazılacak|getirilecek|unutma|sunulacak|sunulması|yetiştirilecek|planlanacak|sorulacak|bekleniyor)/;
  var FUT = /[a-zçğıöşü]{2,}(acak|ecek|acaktır|ecektir|ması|mesi)\b/;
  var DATE = /(\b\d{1,2}[.\/-]\d{1,2}([.\/-]\d{2,4})?\b|\b\d{1,2}\s+(ocak|şubat|mart|nisan|mayıs|haziran|temmuz|ağustos|eylül|ekim|kasım|aralık)|\b(pazartesi|salı|çarşamba|perşembe|cuma|cumartesi|pazar|yarın|bugün|hafta(ya|sonu)?)\b|\b\d{1,2}[:.]\d{2}\b)/;
  var NAME = /(\b[A-ZÇĞİÖŞÜ][a-zçğıöşü]+\s+[A-ZÇĞİÖŞÜ][a-zçğıöşü]+\b|\b(bey|hanım|komutan|müdür|başkan|amir|astsubay|yüzbaşı|binbaşı|albay|üsteğmen|teğmen|çavuş)\w*)/;
  function suggest(text, name) {
    // Satır sonunda bölünmüş cümleleri birleştir (PDF/OCR satır kaydırmaları)
    var joined = String(text || '').replace(/\r/g, '').replace(/([^.!?:;\n])[ \t]*\n(?=[ \t]*[a-zçğıöşü0-9(])/g, '$1 ');
    var parts = joined.split(/\n+|(?<=[.!?;])\s+/);
    var seen = {}, cands = [];
    parts.forEach(function (raw, idx) {
      var l = raw.replace(/\s+/g, ' ').replace(/^[-•*\d.)\s]+(?=\D)/, '').trim();
      if (l.length < 8) return;
      if (l.length > 160) l = l.slice(0, 157) + '…';
      var low = l.toLocaleLowerCase('tr-TR'), key = low.replace(/[^a-zçğıöşü0-9]/g, '');
      if (seen[key]) return; seen[key] = 1;
      if (/^(bilgilerinize|gereğini|saygılarımla|arz ederim)/.test(low)) return; // kapanış kalıpları
      var kw = KW.test(low), fut = FUT.test(low);
      if (!kw && !fut) return;                       // eylem ifadesi yoksa öneri değil
      if (!/[a-zçğıöşü]/.test(l) && !fut) return;    // tamamı büyük harf başlıklar
      var sc = (kw ? 3 : 0) + (fut ? 2 : 0) + (DATE.test(low) ? 2 : 0) + (NAME.test(l) ? 1 : 0);
      if (sc >= 2) cands.push({ t: l, s: sc, i: idx });
    });
    cands.sort(function (a, b) { return b.s - a.s || a.i - b.i; });
    var out = cands.slice(0, 8).sort(function (a, b) { return a.i - b.i; }).map(function (c) { return c.t; });
    if (!out.length) out = [(name || 'Belge').replace(/\.[^.]+$/, '') + ' incelenecek'];
    return out;
  }
  function aiCfg() { try { return JSON.parse(N.aiConfig()); } catch (e) { return {}; } }
  function openAna(name) {
    $('anaInfo').textContent = '“' + name + '” okunuyor…';
    $('anaSummary').classList.add('hidden'); $('anaList').innerHTML = ''; $('anaText').textContent = '';
    $('anaModal').classList.remove('hidden');
  }
  function renderSug(list, checked) {
    $('anaList').innerHTML = list.map(function (t) {
      return '<label class="sug"><input type="checkbox"' + (checked === false ? '' : ' checked') + '><input type="text" value="' + esc(t) + '"></label>';
    }).join('');
  }
  $('anaAddRow').onclick = function () {
    var d = document.createElement('div'); d.innerHTML = '<label class="sug"><input type="checkbox" checked><input type="text" placeholder="Yeni madde"></label>';
    $('anaList').appendChild(d.firstChild); $('anaList').lastChild.querySelector('input[type=text]').focus();
  };
  function analyzeAtt(id) {
    var m = state.attachments[id]; if (!m || !N) return;
    ana = { req: 'r' + (++anaSeq), attId: id, name: m.name, mime: m.mime };
    openAna(m.name);
    N.extract(ana.req, id, m.mime, m.name);
  }
  function analyzeText(text, name) {
    ana = { req: 'r' + (++anaSeq), attId: null, name: name, mime: 'text/plain' };
    openAna(name);
    window.__turanAnalysis(ana.req, { ok: true, text: text, method: 'Paylaşılan metin' });
  }
  window.__turanAnalysis = function (req, r) {
    if (!ana || ana.req !== req) return;
    ana.text = r.ok ? (r.text || '') : '';
    var info = r.ok ? (r.method + ' · ' + ana.text.length + ' karakter') : ('Metin çıkarılamadı: ' + r.error);
    if (r.ok && !ana.text) info = r.method + ' · metin bulunamadı';
    $('anaText').textContent = ana.text || '(boş)';
    renderSug(suggest(ana.text, ana.name));
    var cfg = N ? aiCfg() : {};
    if (cfg.hasKey && (ana.text || /^image\//.test(ana.mime))) {
      info += ' · 🤖 Yapay zekâ analiz ediyor…';
      N.ai(req, ana.text, ana.attId || '', ana.mime);
    }
    $('anaInfo').textContent = info;
  };
  window.__turanAI = function (req, r) {
    if (!ana || ana.req !== req) return;
    var info = $('anaInfo').textContent.replace(' · 🤖 Yapay zekâ analiz ediyor…', '');
    if (!r.ok) { $('anaInfo').textContent = info + ' · Yapay zekâ kullanılamadı: ' + r.error; return; }
    $('anaInfo').textContent = info + ' · 🤖 Yapay zekâ önerileri';
    if (r.summary) { $('anaSummary').textContent = 'Özet: ' + r.summary; $('anaSummary').classList.remove('hidden'); }
    var items = (r.items || []).map(function (x) { return typeof x === 'string' ? x : (x.text || x.madde || JSON.stringify(x)); }).filter(Boolean);
    if (items.length) renderSug(items);
  };
  $('anaCancel').onclick = function () { ana = null; $('anaModal').classList.add('hidden'); };
  $('anaOk').onclick = function () {
    if (!ana) return;
    refresh();
    var texts = [].slice.call($('anaList').querySelectorAll('.sug')).filter(function (l) { return l.querySelector('input[type=checkbox]').checked; })
      .map(function (l) { return l.querySelector('input[type=text]').value.trim(); }).filter(Boolean);
    if (!texts.length) { toast('Seçili madde yok komutanım.'); return; }
    var now = Date.now();
    texts.forEach(function (t, k) {
      var it = { id: uid(), text: t, created: new Date(now + k).toISOString(), status: 'open', closed: null };
      if (ana.attId && state.attachments[ana.attId]) it.att = [ana.attId];
      state.items.push(it);
    });
    if (ana.attId) pending = pending.filter(function (a) { return a !== ana.attId; });
    save(); render(); show('open');
    $('anaModal').classList.add('hidden'); ana = null;
    toast('Emredersiniz komutanım, ' + texts.length + ' konu takibe alındı.');
  };

  // Yapay zekâ ayarları
  function fillAi() {
    if (!N) return;
    var c = aiCfg();
    $('aiUrl').value = c.url || ''; $('aiModel').value = c.model || ''; $('aiKey').value = '';
    $('aiKey').placeholder = c.hasKey ? '•••••• (kayıtlı)' : 'sk-…';
    $('aiStatus').textContent = c.hasKey ? 'Anahtar kayıtlı: analizlerde yapay zekâ kullanılacak.' : 'Anahtar yok: analiz tamamen telefonda yapılır.';
  }
  if (N) {
    $('btnAiSave').onclick = function () { N.setAiConfig($('aiUrl').value, $('aiModel').value, $('aiKey').value, false); fillAi(); toast('Emredersiniz komutanım, kaydedildi.'); };
    $('btnAiClear').onclick = function () { N.setAiConfig($('aiUrl').value, $('aiModel').value, '', true); fillAi(); toast('Anahtar silindi.'); };
  }

  // ---------- Ayarlar ----------
  var KEYS = ['wd', 'sa', 'su'];
  function fillSched() {
    var s = state.settings.schedule;
    KEYS.forEach(function (k) { $(k + '_arz').value = s[k].arz || ''; $(k + '_not').value = s[k].not || ''; $(k + '_on').checked = !!s[k].on; });
    $('autoSpeak').checked = !!state.settings.autoSpeak;
  }
  $('btnSaveSched').onclick = function () {
    refresh();
    KEYS.forEach(function (k) { state.settings.schedule[k] = { arz: $(k + '_arz').value, not: $(k + '_not').value, on: $(k + '_on').checked }; });
    save(); render(); renderGLinks(); toast('Emredersiniz komutanım, program kaydedildi.');
  };
  $('autoSpeak').onchange = function () { refresh(); state.settings.autoSpeak = this.checked; save(); };
  if (N) $('voiceStatus').textContent = 'Sesle not alma ve sesli okuma telefonun Türkçe ses hizmetlerini kullanır.'; else $('voiceStatus').textContent = 'Sesle not alma: ' + (SR ? 'destekleniyor' : 'bu tarayıcıda yok') + ' · Sesli okuma: ' + (window.speechSynthesis ? 'destekleniyor' : 'yok');

  if (D) $('voiceStatus').textContent = 'Sesle not alma (konuşma tanıma) Linux sürümünde desteklenmiyor; komutları yazarak verebilirsiniz. Sesli okuma: ' + (window.speechSynthesis ? 'sistemde Türkçe ses varsa çalışır' : 'yok');
  if (D) (function () {
    var na = /^(Hızlı erişim|Güncelleme|Arşiv tarama|Arama ve mesaj komutları|WhatsApp otomatik gönderme|Gizli Hat \(Tor\)|X \(Twitter\) zaman tüneli|Kurulum)$/;
    Array.prototype.forEach.call(document.querySelectorAll('#view-help h2'), function (h) {
      var c = h.nextElementSibling;
      if (!na.test(h.textContent.trim()) || !c) return;
      var p = document.createElement('p'); p.className = 'desk-na'; p.textContent = '⚠️ Linux sürümünde desteklenmiyor.';
      c.insertBefore(p, c.firstChild);
    });
  })();

  window.__turanImport = function (text) { importText(text); };
  $('btnExport').onclick = function () {
    if (N) { N.exportBackup(JSON.stringify(state, null, 2), 'turan-yedek-' + Sched.ymd(Sched.istParts()) + '.json'); return; }
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' }));
    a.download = 'turan-yedek-' + Sched.ymd(Sched.istParts()) + '.json'; document.body.appendChild(a); a.click(); a.remove();
  };
  $('importFile').onchange = function () {
    var f = this.files[0]; if (!f) return;
    var r = new FileReader();
    r.onload = function () { importText(r.result); };
    r.readAsText(f); this.value = '';
  };
  if (N) document.querySelector('.filebtn').onclick = function (e) { e.preventDefault(); N.importBackup(); };
  function importText(text) {
      try {
        var d = JSON.parse(text);
        if (!d || !Array.isArray(d.items)) throw new Error('Geçersiz dosya');
        if (!confirm('Mevcut liste yedektekiyle (' + d.items.length + ' kayıt) değiştirilsin mi?')) return;
        state = d; state.settings = state.settings || { schedule: clone(Sched.DEFAULT_SCHEDULE) };
        state.settings.schedule = state.settings.schedule || clone(Sched.DEFAULT_SCHEDULE); state.lastShown = state.lastShown || {}; state.attachments = state.attachments || {};
        save(); fillSched(); render(); toast('Emredersiniz komutanım, yedek geri yüklendi.');
      } catch (e) { alert('Yedek okunamadı: ' + e.message); }
  }

  // ---------- Ses yardımcıları ----------
  function say(text) {
    if (N) { try { N.speak(text); } catch (e) {} return; }
    if (!window.speechSynthesis) return;
    speechSynthesis.cancel();
    var u = new SpeechSynthesisUtterance(text); u.lang = 'tr-TR'; u.rate = 0.95;
    var v = speechSynthesis.getVoices().filter(function (x) { return /^tr/i.test(x.lang); })[0]; if (v) u.voice = v;
    speechSynthesis.speak(u);
  }
  function cap(t) { t = String(t || '').trim(); return t.charAt(0).toLocaleUpperCase('tr-TR') + t.slice(1); }

  // ---------- Türkçe zaman ifadesi çözümleme ----------
  var NUMW = { bir: 1, iki: 2, 'üç': 3, 'dört': 4, 'beş': 5, 'altı': 6, yedi: 7, sekiz: 8, dokuz: 9, on: 10, 'onbeş': 15, 'on beş': 15, yirmi: 20, otuz: 30, 'kırk': 40, 'kırk beş': 45, elli: 50, 'yarım': 0.5 };
  var DAYS = { pazar: 0, pazartesi: 1, 'salı': 2, 'çarşamba': 3, 'perşembe': 4, cuma: 5, cumartesi: 6 };
  function numOf(w) { return /^\d+$/.test(w) ? +w : NUMW[w]; }
  function parseWhen(text) {
    var t = ' ' + text.toLocaleLowerCase('tr-TR') + ' ', now = Date.now(), cut = [];
    function take(m) { if (m) cut.push(m[0]); return m; }
    // N dakika/saat sonra
    var m = take(t.match(/\s(\d+|yarım|bir|iki|üç|dört|beş|altı|yedi|sekiz|dokuz|on beş|onbeş|on|yirmi|otuz|kırk beş|kırk|elli)\s*(dakika|dk|saat|gün)\s*(sonra|içinde)\s/));
    if (m) {
      var n = numOf(m[1]), unit = m[2] === 'saat' ? 3600000 : m[2] === 'gün' ? 86400000 : 60000;
      if (m[1] === 'yarım' && m[2] !== 'saat') n = 0.5;
      return { at: now + n * unit, rest: strip(text, cut) };
    }
    var p = Sched.istParts(new Date(now)), dayOff = null, h = null, mi = 0;
    if (take(t.match(/\s(öbür gün|ertesi gün|yarından sonra)\s/))) dayOff = 2;
    else if (take(t.match(/\syarın(ki)?\s/))) dayOff = 1;
    else if (take(t.match(/\sbugün(kü)?\s/))) dayOff = 0;
    else {
      var dm = take(t.match(/\s(haftaya\s)?(pazartesi|salı|çarşamba|perşembe|cumartesi|cuma|pazar)(\sgünü)?('?(ye|ya|e|a|de|da|te|ta))?\s/));
      if (dm) { var wd = DAYS[dm[2]]; dayOff = (wd - p.wd + 7) % 7; if (dayOff === 0 || dm[1]) dayOff += 7; }
    }
    var part = take(t.match(/\s(sabah|öğle(n|den sonra)?|akşam|gece)\s/));
    var tm = take(t.match(/\ssaat\s*(\d{1,2})(?:[:.\s](\d{2}))?('?(de|da|te|ta|e|a|ye|ya))?\s/)) ||
      take(t.match(/\s(\d{1,2})[:.](\d{2})('?(de|da|te|ta|e|a|ye|ya))?\s/)) ||
      take(t.match(/\s(\d{1,2})'?(de|da|te|ta)\s/));
    if (tm) { h = +tm[1]; mi = tm[2] && /^\d+$/.test(tm[2]) ? +tm[2] : 0; }
    if (part) {
      var pw = part[1];
      if (h === null) h = pw === 'sabah' ? 9 : /^öğle/.test(pw) ? (pw === 'öğleden sonra' ? 14 : 12) : pw === 'akşam' ? 18 : 21;
      else if ((pw === 'akşam' || pw === 'gece' || pw === 'öğleden sonra') && h < 12) h += 12;
    } else if (h !== null && h >= 1 && h <= 6 && dayOff === null) h += 12; // "saat 3" → 15:00
    if (h === null && dayOff === null) return null;
    if (h === null) { h = 9; mi = 0; }
    if (h > 23 || mi > 59) return null;
    var base = Date.UTC(p.y, p.m - 1, p.d, h, mi) - 180 * 60000;
    var at = base + (dayOff || 0) * 86400000;
    if (dayOff === null && at <= now) at += 86400000;
    return { at: at, rest: strip(text, cut) };
  }
  function strip(text, cut) {
    var t = ' ' + text + ' ';
    cut.forEach(function (c) { var i = t.toLocaleLowerCase('tr-TR').indexOf(c); if (i >= 0) t = t.slice(0, i) + ' ' + t.slice(i + c.length); });
    return t.replace(/\b(bana|lütfen)\b/gi, ' ').replace(/\s+/g, ' ').trim();
  }

  // ---------- Sesli komut ----------
  var voiceAuto = false;
  function openVoice(listen) {
    $('voiceInput').value = ''; $('voiceReply').classList.add('hidden');
    $('voiceModal').classList.remove('hidden');
    if (listen) startListen(); else setTimeout(function () { $('voiceInput').focus(); }, 50);
  }
  function startListen() {
    if (N) { voiceAuto = true; try { N.listenCommand(); } catch (e) { $('voiceInput').focus(); } return; }
    if (!SR) { $('voiceInput').focus(); return; }
    var r = new SR(); r.lang = 'tr-TR'; r.interimResults = false; r.maxAlternatives = 1;
    r.onresult = function (ev) { var t = ev.results[0][0].transcript; $('voiceInput').value = t; runCommand(t); };
    r.onerror = function () { $('voiceInput').focus(); };
    r.onend = function () { $('voiceMic').classList.remove('rec'); };
    $('voiceMic').classList.add('rec'); r.start();
  }
  window.__turanCommand = function (t, err) {
    if (err === 'nomic') { reply('Komutanım, bu telefonda ses tanıma bulunamadı; komutu yazabilirsiniz.', true); $('voiceInput').focus(); return; }
    if (!t) { $('voiceInput').focus(); return; }
    $('voiceModal').classList.remove('hidden');
    $('voiceInput').value = t; runCommand(t);
  };
  $('btnVoice').onclick = function () { openVoice(true); };
  $('voiceMic').onclick = startListen;
  $('voiceClose').onclick = function () { $('voiceModal').classList.add('hidden'); };
  $('voiceForm').onsubmit = function (e) { e.preventDefault(); runCommand($('voiceInput').value); };
  function reply(msg, silent) {
    $('voiceReply').textContent = msg; $('voiceReply').classList.remove('hidden');
    if (!silent) say(msg);
  }
  function addItem(text, extra) {
    refresh();
    var it = Object.assign({ id: uid(), text: cap(text), created: new Date().toISOString(), status: 'open', closed: null }, extra || {});
    state.items.push(it); save(); render();
    return it;
  }
  function undoAdd(it) { return function () { refresh(); state.items = state.items.filter(function (x) { return x.id !== it.id; }); save(); render(); }; }
  function stem(w) { return norm(w).replace(/[^a-z0-9]/g, '').slice(0, 5); }
  function bestOpen(q) {
    var qs = norm(q).split(/\s+/).map(stem).filter(function (w) { return w.length >= 3; });
    if (!qs.length) return null;
    var best = null, bs = 0;
    state.items.filter(function (i) { return i.status === 'open'; }).forEach(function (it) {
      var ws = norm(it.text).split(/\s+/).map(stem).filter(function (w) { return w.length >= 3; });
      var hit = qs.filter(function (w) { return ws.some(function (x) { return x.indexOf(w) === 0 || w.indexOf(x) === 0; }); }).length;
      var sc = hit / qs.length + hit / Math.max(ws.length, 1) * 0.5;
      if (hit && sc > bs) { bs = sc; best = it; }
    });
    return bs >= 0.6 ? best : null;
  }
  // Komut çözümleyici (test edilebilir olsun diye ayrı)
  // Türkçe klavyesiz yazımları (hatirlat, yarin, arastir…) düzelt
  var ASCII_FIX = [[/\bhatirlat/gi, 'hatırlat'], [/\barastir/gi, 'araştır'], [/\bhaftalik ozet/gi, 'haftalık özet'], [/\btamamlandi\b/gi, 'tamamlandı'],
    [/\byarin/gi, 'yarın'], [/\bbugun/gi, 'bugün'], [/\baksam/gi, 'akşam'], [/\bogle(n|den)?/gi, 'öğle$1'], [/\bcarsamba/gi, 'çarşamba'],
    [/\bpersembe/gi, 'perşembe'], [/\bsali\b/gi, 'salı'], [/\bobur gun/gi, 'öbür gün'], [/\bgun\b/gi, 'gün'], [/\bicinde\b/gi, 'içinde'], [/\bnasil\b/gi, 'nasıl'], [/\bhalledildi\b/gi, 'halledildi']];
  function parseCommand(raw) {
    var t = String(raw || '').trim().replace(/[.!]+$/, ''), m;
    ASCII_FIX.forEach(function (f) { t = t.replace(f[0], f[1]); });
    var l = t.toLocaleLowerCase('tr-TR');
    if (!t) return { type: 'none' };
    if (/^(arz et|arz ver|arz|tekmil ver|tekmil|listeyi oku|açık işler(i)?( oku)?|neler var|işleri oku)\b/.test(l)) return { type: 'arz' };
    if (/haftalık özet|haftayı özetle|haftanın özeti/.test(l)) return { type: 'weekly' };
    if ((m = t.match(/^(?:araştır|internette ara(?:ştır)?|webde ara|web'de ara|google'?la)\s*[:,]?\s+(.+)$/i))) return { type: 'research', q: m[1] };
    if ((m = t.match(/^(.+?)\s+(?:araştır|hakkında araştırma yap|hakkında bilgi ver)$/i))) return { type: 'research', q: m[1] };
    if (/\s(nedir|kimdir|ne demek|ne demektir|nerededir|nerede|nasıl yapılır|nasıl alınır|ne zaman)\??$/i.test(l) || /\?$/.test(l)) return { type: 'research', q: t.replace(/\?$/, '') };
    if ((m = t.match(/^(?:kapat|tamamla|bitir)\s*[:,]?\s+(.+)$/i)) || (m = t.match(/^(.+?)\s+(?:tamamlandı|bitti|kapat|kapatıldı|yapıldı|halledildi|tamam)$/i))) return { type: 'close', q: m[1] };
    var acil = false;
    if ((m = t.match(/^(?:acil(?:\s+olarak)?(?:\s+not(?:\s+al)?)?)\s*[:,]?\s+(.+)$/i))) { acil = true; t = m[1]; l = t.toLocaleLowerCase('tr-TR'); }
    if ((m = t.match(/^(?:not al|not et|not|ekle|kaydet|yaz|takibe al)\s*[:,]?\s+(.+)$/i))) { t = m[1]; l = t.toLocaleLowerCase('tr-TR'); }
    if (/hatırlat/.test(l)) {
      var body = t.replace(/\s*(bana\s+)?(?:hatırlat(?:ır mısın|ır mısınız|ma kur|ma ekle|ma)?|hatırlatın)\s*/i, ' ').trim();
      var w = parseWhen(body);
      if (!w) return { type: 'remind-notime', text: body, acil: acil };
      var txt = w.rest.replace(/^(?:için|de|da)\s+/i, '').replace(/\s+(?:için)$/i, '').trim();
      return { type: 'remind', text: txt || 'Hatırlatma', at: w.at, acil: acil };
    }
    return { type: 'add', text: t, acil: acil };
  }
  window.__turanParse = parseCommand; // test için
  function runCommand(raw) {
    var r0 = String(raw || '').trim();
    // v1.5: "Gizli hattı aç" → Gizli Hat ekranı (mesaj içeriği sesle alınmaz; yazışma yalnız o ekranda)
    if (N && N.openGizli && /^gizli\s*hat(t[ıi]|a)?(\s+(a[çc]|g[öo]ster|ba[şs]lat))?\.?$/i.test(r0)) { closeVoice(); N.openGizli(); return; }
    if (window.TuranComm && !/^(acil\s+)?(not al|not et|not|ekle|kaydet|takibe al)\b/i.test(r0) && !/hat[ıi]rlat/i.test(r0)) {
      var cc = window.TuranComm.parse(r0);
      if (cc) { runComm(cc, r0); return; }
    }
    runBasic(raw);
  }
  function runBasic(raw) {
    var c = parseCommand(raw), it;
    if (c.type === 'none') { $('voiceInput').focus(); return; }
    if (c.type === 'arz') { $('voiceModal').classList.add('hidden'); openArz(false); speak(); return; }
    if (c.type === 'weekly') { $('voiceModal').classList.add('hidden'); openWeekly(true); return; }
    if (c.type === 'research') { $('voiceModal').classList.add('hidden'); show('research'); $('resInput').value = c.q; doResearch(c.q, true); return; }
    if (c.type === 'close') {
      refresh(); it = bestOpen(c.q);
      if (!it) { reply('Komutanım, “' + c.q + '” ile eşleşen açık iş bulamadım. Arz ederim.'); return; }
      var before = clone(it); it.status = 'done'; it.closed = new Date().toISOString(); save(); render();
      reply('Emredersiniz komutanım. “' + it.text + '” tamamlandı olarak kapatıldı.');
      toast('Kapatıldı: ' + it.text, function () { revert(before); });
      return;
    }
    if (c.type === 'remind-notime') { reply('Komutanım, ne zaman hatırlatayım? Örneğin “yarın saat 10’da ' + (c.text || 'konuyu') + ' hatırlat”. Arz ederim.'); $('voiceInput').value = ''; $('voiceInput').focus(); return; }
    if (c.type === 'remind') {
      it = addItem(c.text, { remindAt: new Date(c.at).toISOString(), remindFired: false, prio: c.acil ? 'acil' : '' });
      reply('Emredersiniz komutanım. “' + it.text + '” için ' + fmtDT(it.remindAt) + ' saatinde hatırlatacağım.');
      toast('Hatırlatma kuruldu.', undoAdd(it));
      return;
    }
    it = addItem(c.text, c.acil ? { prio: 'acil' } : null);
    reply('Emredersiniz komutanım. ' + (c.acil ? 'Acil olarak ' : '') + 'not alındı: ' + it.text);
    toast('Emredersiniz komutanım.', undoAdd(it));
  }

  // ---------- Araştır ----------
  var fetchSeq = 0, fetchWait = {};
  window.__turanFetch = function (id, r) { var f = fetchWait[id]; delete fetchWait[id]; if (f) f(r); };
  window.__turanChat = function (id, r) { var f = fetchWait[id]; delete fetchWait[id]; if (f) f(r); };
  function getJSON(url) {
    if (N) return new Promise(function (res, rej) {
      var id = 'f' + (++fetchSeq); fetchWait[id] = function (r) { if (!r.ok) return rej(new Error(r.error || ('HTTP ' + r.status))); try { res(JSON.parse(r.body)); } catch (e) { rej(e); } };
      N.fetch(id, url);
    });
    return fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
  }
  function aiAnswer(q, ctx) {
    return new Promise(function (res, rej) { var id = 'c' + (++fetchSeq); fetchWait[id] = function (r) { r.ok ? res(r.answer) : rej(new Error(r.error)); }; N.aiChat(id, q, ctx); });
  }
  function cleanQ(q) { return q.replace(/\b(nedir|kimdir|ne demek(tir)?|nerededir|nerede|hakkında|bilgi ver|nasıl)\b/gi, ' ').replace(/[?]/g, ' ').replace(/\s+/g, ' ').trim() || q; }
  var lastRes = null;
  function resCard(r, i) {
    return '<div class="card res" data-i="' + i + '"><div class="src">' + esc(r.src) + '</div>' + (r.title ? '<b>' + esc(r.title) + '</b>' : '') +
      '<p>' + esc(r.text) + '</p><div class="b">' + (r.url ? '<a class="btnlink" href="' + esc(r.url) + '" target="_blank" rel="noopener">↗ Kaynağı aç</a>' : '') +
      '<button data-r="speak">🔊 Oku</button><button data-r="note">📌 Not al</button></div></div>';
  }
  function doResearch(q, voice) {
    q = String(q || '').trim(); if (!q) { $('resInput').focus(); return; }
    addHist(q);
    var out = $('resOut'), cq = cleanQ(q), results = [], enc = encodeURIComponent;
    out.innerHTML = '<p class="muted">Araştırılıyor komutanım…</p>';
    var webLinks = '<div class="card"><b>Web\'de devam et</b><div class="b"><a class="btnlink" target="_blank" href="https://duckduckgo.com/?kl=tr-tr&q=' + enc(q) + '">DuckDuckGo</a>' +
      '<a class="btnlink" target="_blank" href="https://www.google.com/search?hl=tr&q=' + enc(q) + '">Google</a>' +
      '<a class="btnlink" target="_blank" href="https://tr.wikipedia.org/w/index.php?search=' + enc(cq) + '">Vikipedi</a></div></div>';
    var ddg = getJSON('https://api.duckduckgo.com/?q=' + enc(q) + '&format=json&no_html=1&skip_disambig=1&no_redirect=1').then(function (d) {
      if (d.Answer) results.push({ src: 'DuckDuckGo · Anlık cevap', text: String(d.Answer), url: d.AbstractURL || '' });
      if (d.AbstractText) results.push({ src: 'DuckDuckGo · ' + (d.AbstractSource || 'Özet') + ' (çoğunlukla İngilizce)', title: d.Heading || '', text: d.AbstractText, url: d.AbstractURL });
      if (d.Definition) results.push({ src: 'Tanım · ' + (d.DefinitionSource || ''), text: d.Definition, url: d.DefinitionURL });
      (d.RelatedTopics || []).filter(function (x) { return x.Text && x.Text.length > 30; }).slice(0, results.length ? 2 : 4).forEach(function (x) { results.push({ src: 'DuckDuckGo · İlgili', text: x.Text, url: x.FirstURL }); });
    }).catch(function () {});
    var wiki = getJSON('https://tr.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=3&origin=*&srsearch=' + enc(cq)).then(function (d) {
      var hits = (d.query && d.query.search) || [];
      return Promise.all(hits.slice(0, 2).map(function (h, k) {
        return getJSON('https://tr.wikipedia.org/api/rest_v1/page/summary/' + enc(h.title.replace(/ /g, '_'))).then(function (s) {
          if (s.extract) results.push({ src: 'Vikipedi', title: s.title, text: s.extract, url: (s.content_urls && s.content_urls.mobile && s.content_urls.mobile.page) || ('https://tr.wikipedia.org/wiki/' + enc(h.title)), wiki: k });
        }).catch(function () {});
      }));
    }).catch(function () {});
    Promise.all([ddg, wiki]).then(function () {
      results.sort(function (a, b) { return (a.wiki === 0 ? -1 : 0) - (b.wiki === 0 ? -1 : 0); });
      lastRes = { q: q, results: results };
      var hasKey = N && (function () { try { return aiCfg().hasKey; } catch (e) { return false; } })();
      if (!results.length) {
        out.innerHTML = '<div class="card"><p>Komutanım, “' + esc(q) + '” için hızlı kaynaklarda özet bulamadım.' + (hasKey ? '' : ' Aşağıdaki bağlantılarla web\'de arayabilirsiniz.') + ' Arz ederim.</p></div>' + (hasKey ? '<div id="aiBox" class="card"><p class="muted">Yapay zekâ cevabı hazırlanıyor…</p></div>' : '') + webLinks;
        if (voice) say('Komutanım, hızlı kaynaklarda özet bulamadım. Arz ederim.');
      } else {
        out.innerHTML = (hasKey ? '<div id="aiBox" class="card"><p class="muted">Yapay zekâ cevabı hazırlanıyor…</p></div>' : '') + results.map(resCard).join('') + webLinks;
        if (voice && !hasKey) say(firstSentences(results[0].text, 2));
      }
      if (hasKey) {
        var ctx = results.map(function (r) { return '- ' + r.src + (r.title ? ' (' + r.title + ')' : '') + ': ' + r.text.slice(0, 700); }).join('\n');
        aiAnswer(q, ctx).then(function (a) {
          lastRes.ai = a;
          $('aiBox').innerHTML = '<div class="src">Turan Asistan · yapay zekâ cevabı</div><p class="pw">' + esc(a) + '</p><div class="b"><button data-r="speak-ai">🔊 Oku</button><button data-r="note-ai">📌 Not al</button></div>';
          if (voice) say(a);
        }).catch(function (e) { $('aiBox').innerHTML = '<p class="muted small">Yapay zekâ cevabı alınamadı: ' + esc(e.message) + '</p>'; if (voice && results.length) say(firstSentences(results[0].text, 2)); });
      }
    });
  }
  function firstSentences(t, n) { var s = String(t).match(/[^.!?]+[.!?]+/g) || [t]; return s.slice(0, n).join(' ').trim(); }
  $('resForm').onsubmit = function (e) { e.preventDefault(); doResearch($('resInput').value); };
  $('resMic').onclick = function () { openVoice(true); };
  $('resOut').onclick = function (e) {
    var b = e.target.closest('button[data-r]'); if (!b || !lastRes) return;
    var act = b.dataset.r, card = b.closest('.res'), r = card ? lastRes.results[+card.dataset.i] : null;
    var text = act.indexOf('-ai') > 0 ? lastRes.ai : r.text;
    if (act.indexOf('speak') === 0) { say(text); return; }
    var it = addItem('Araştırma – ' + lastRes.q + ': ' + firstSentences(text, 2).slice(0, 300) + (r && r.url ? ' (' + r.url + ')' : ''));
    toast('Emredersiniz komutanım, not alındı.', undoAdd(it));
  };
  function getHist() { try { return JSON.parse(localStorage.getItem('turan-reshist') || '[]'); } catch (e) { return []; } }
  function addHist(q) { var h = getHist().filter(function (x) { return x !== q; }); h.unshift(q); try { localStorage.setItem('turan-reshist', JSON.stringify(h.slice(0, 8))); } catch (e) {} renderHist(); }
  function renderHist() {
    var h = getHist(); $('resHistWrap').classList.toggle('hidden', !h.length);
    $('resHist').innerHTML = h.map(function (q) { return '<button data-q="' + esc(q) + '">' + esc(q) + '</button>'; }).join('');
  }
  $('resHist').onclick = function (e) { var b = e.target.closest('button[data-q]'); if (b) { $('resInput').value = b.dataset.q; doResearch(b.dataset.q); } };

  // ---------- Haftalık özet ----------
  var weekPlain = '';
  function openWeekly(voice) {
    refresh();
    var now = Date.now(), since = now - 7 * 86400000, today = Sched.ymd(Sched.istParts()), in7 = Sched.ymd(Sched.istParts(new Date(now + 7 * 86400000)));
    var added = state.items.filter(function (i) { return Date.parse(i.created) >= since; });
    var done = state.items.filter(function (i) { return i.status === 'done' && i.closed && Date.parse(i.closed) >= since; });
    var dropped = state.items.filter(function (i) { return i.status === 'dropped' && i.closed && Date.parse(i.closed) >= since; });
    var open = state.items.filter(function (i) { return i.status === 'open'; });
    var acil = open.filter(function (i) { return i.prio === 'acil'; });
    var over = open.filter(function (i) { return i.due && i.due < today; });
    var soon = open.filter(function (i) { return i.due && i.due >= today && i.due <= in7; }).sort(function (a, b) { return a.due < b.due ? -1 : 1; });
    var oldest = open.slice().sort(function (a, b) { return a.created < b.created ? -1 : 1; })[0];
    var range = Sched.fmtDate(new Date(since).toISOString()) + ' – ' + Sched.fmtDate(new Date(now).toISOString());
    var L = [], H = [];
    function sec(title, arr) {
      if (!arr.length) return;
      L.push(title + ':'); arr.forEach(function (i) { L.push('• ' + Sched.itemLine(i)); });
      H.push('<p><b>' + esc(title) + ':</b></p><ul>' + arr.map(function (i) { return '<li>' + esc(Sched.itemLine(i)) + '</li>'; }).join('') + '</ul>');
    }
    var head = 'Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle son 7 günün (' + range + ') özetini arz ediyorum:';
    var stats = 'Eklenen iş: ' + added.length + ' · Tamamlanan: ' + done.length + ' · Takipten çıkarılan: ' + dropped.length +
      ' · Açık: ' + open.length + (acil.length ? ' (acil ' + acil.length + ')' : '') + (over.length ? ' · Süresi geçen: ' + over.length : '');
    L.push(head, stats); H.push('<p>' + esc(head) + '</p><p class="stats">' + esc(stats) + '</p>');
    sec('Tamamlananlar', done); sec('Süresi geçenler', over); sec('Önümüzdeki 7 gün son tarihli', soon); sec('Acil açık işler', acil.filter(function (i) { return over.indexOf(i) < 0; }));
    if (oldest) { var d = Math.floor((now - Date.parse(oldest.created)) / 86400000); var o = 'En eski açık iş: ' + oldest.text + ' (' + (d ? d + ' gündür takipte' : 'bugün eklendi') + ')'; L.push(o); H.push('<p>' + esc(o) + '</p>'); }
    L.push(Sched.ARZ_TAIL); H.push('<p>' + Sched.ARZ_TAIL + '</p>');
    weekPlain = L.join('\n');
    $('weekText').innerHTML = H.join('');
    $('weekModal').classList.remove('hidden');
    if (voice) say(weekPlain.replace(/•/g, ''));
  }
  $('btnWeekly').onclick = function () { openWeekly(false); };
  $('weekSpeak').onclick = function () { say(weekPlain.replace(/•/g, '')); };
  $('weekShare').onclick = function () {
    if (N) { N.share(weekPlain, '[]'); return; }
    if (navigator.share) navigator.share({ title: 'Haftalık özet', text: weekPlain }).catch(function () {});
    else if (navigator.clipboard) navigator.clipboard.writeText(weekPlain).then(function () { toast('Panoya kopyalandı.'); });
  };
  $('weekOk').onclick = function () { $('weekModal').classList.add('hidden'); if (N) N.stopSpeak(); else if (window.speechSynthesis) speechSynthesis.cancel(); };

  // ---------- Tema ----------
  var mq = window.matchMedia ? matchMedia('(prefers-color-scheme: dark)') : null;
  function applyTheme(night) {
    var t = state.settings.theme || 'auto';
    if (night === undefined) night = N ? (function () { try { return N.isNight(); } catch (e) { return false; } })() : !!(mq && mq.matches);
    var dark = t === 'dark' || (t === 'auto' && night);
    document.documentElement.classList.toggle('dark', dark);
    var meta = document.querySelector('meta[name=theme-color]'); if (meta) meta.content = dark ? '#151a10' : '#2e3a23';
    $('theme').value = t;
  }
  window.__turanTheme = function (n) { applyTheme(!!n); };
  if (mq && mq.addEventListener) mq.addEventListener('change', function () { if (!N) applyTheme(); });
  $('theme').onchange = function () { refresh(); state.settings.theme = this.value; save(); applyTheme(); };

  // ---------- Sürüm ve güncelleme ----------
  function fmtTime(ms) { return ms ? new Intl.DateTimeFormat('tr-TR', { timeZone: Sched.TZ, day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(ms)) : 'henüz yapılmadı'; }
  function fillApp() {
    if (!N || !N.appInfo) return;
    var i = {}; try { i = JSON.parse(N.appInfo()); } catch (e) {}
    $('appVer').textContent = (i.versionName || '?') + ' (' + (i.versionCode || '?') + ')';
    $('updLast').textContent = fmtTime(i.lastCheck) + (i.lastResult ? ' · ' + i.lastResult : '');
  }
  window.__turanUpdate = function (r) {
    if (r.state === 'checking') $('updStatus').textContent = 'Kontrol ediliyor…';
    else if (r.state === 'none') $('updStatus').textContent = 'Komutanım, uygulama güncel.';
    else if (r.state === 'available') $('updStatus').textContent = 'Yeni sürüm var: ' + r.versionName;
    else if (r.state === 'error') $('updStatus').textContent = 'Kontrol edilemedi: ' + (r.error || '');
    fillApp();
  };
  if (N && N.checkUpdate) $('btnUpdate').onclick = function () { N.checkUpdate(); };
  fillApp();

  // ---------- Hızlı erişim ayarları ----------
  if (N) {
    $('persistOn').onchange = function () { N.setPersistent(this.checked); toast(this.checked ? 'Emredersiniz komutanım, kalıcı kısayol açıldı.' : 'Kalıcı kısayol kapatıldı.'); };
    $('btnWidget').onclick = function () { N.pinWidget(); };
    $('btnTile').onclick = function () { N.addTile(); };
    $('btnAssist').onclick = function () { N.openAssistantSettings(); };
  }

  // =====================================================================
  // ---------- v1.4: Arşiv tarama ----------
  var TC = window.TuranComm;
  var arch = { data: { colls: [], docs: [] }, cur: 0, seq: 0, wait: {}, last: null, timer: null };
  function archLoad() { if (!N) return; try { arch.data = JSON.parse(N.archiveList()); } catch (e) { arch.data = { colls: [], docs: [] }; } }
  function collOf(id) { return arch.data.colls.filter(function (c) { return c.id === id; })[0]; }
  function docsOf(id) { return arch.data.docs.filter(function (d) { return d.coll === id; }); }
  function fmtSize(b) { return b > 1048576 ? (b / 1048576).toFixed(1).replace('.', ',') + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB'; }
  function docLine(d) {
    var p = [fmtSize(d.size)];
    if (d.pages) p.push(d.pages + ' sayfa');
    if (d.ocr) p.push(d.ocr + ' sayfa OCR');
    return p.join(' · ');
  }
  function renderArsiv() {
    if (!N) return;
    archLoad();
    if (arch.cur && !collOf(arch.cur)) arch.cur = 0;
    $('arsivHome').classList.toggle('hidden', !!arch.cur);
    $('arsivBot').classList.toggle('hidden', !arch.cur);
    $('botCount').textContent = arch.data.colls.length;
    $('botEmpty').classList.toggle('hidden', arch.data.colls.length > 0);
    $('botList').innerHTML = arch.data.colls.map(function (c) {
      var busy = c.busy ? ' · <b>işleniyor</b>' : '';
      return '<li class="item" data-bot="' + c.id + '"><div class="t">📚 ' + esc(c.name) + '</div><div class="d">' + c.docs + ' belge · ' + c.pages + ' sayfa' + busy + '</div>' +
        (c.busy && c.pages ? '<div class="prog"><i style="width:' + Math.round(100 * c.done / Math.max(1, c.pages)) + '%"></i></div>' : '') + '</li>';
    }).join('');
    var any = arch.data.docs.some(function (d) { return d.status === 'queued' || d.status === 'running'; });
    if (arch.cur) {
      var c = collOf(arch.cur), ds = docsOf(arch.cur);
      $('botTitle').textContent = c.name;
      $('docCount').textContent = ds.length;
      $('docEmpty').classList.toggle('hidden', ds.length > 0);
      var ready = ds.filter(function (d) { return d.status === 'ok' || d.done > 0; }).length;
      $('askHint').textContent = !ds.length ? 'Önce PDF ekleyin komutanım.' : (ready < ds.length ? 'Belgeler okunuyor; işlenen sayfalar şimdiden aranabilir.' : (aiCfg().hasKey ? 'Cevaplar yapay zekâ ile, kaynak sayfa numaralarıyla hazırlanır.' : 'Yapay zekâ anahtarı yok: cevap, belgelerden kaynaklı alıntılarla verilir.'));
      $('docList').innerHTML = ds.map(function (d) {
        var st = '', pr = '';
        if (d.status === 'queued') st = '<div class="d">⏳ Sırada bekliyor</div>';
        if (d.status === 'running') { st = '<div class="d">⚙️ Okunuyor: ' + d.done + '/' + (d.pages || '?') + ' sayfa</div>'; pr = '<div class="prog"><i style="width:' + Math.round(100 * d.done / Math.max(1, d.pages)) + '%"></i></div>'; }
        if (d.status === 'error') st = '<div class="err">⚠ Okunamadı: ' + esc(d.error || '') + '</div>';
        return '<li class="item" data-doc="' + d.id + '"><div class="t">📕 ' + esc(d.name) + '</div><div class="d">' + docLine(d) + '</div>' + st + pr +
          '<div class="b"><button data-a="open">👁 Aç</button><button data-a="sum">📊 Analiz</button><button data-a="more" class="edit">⋯</button></div></li>';
      }).join('');
    }
    clearTimeout(arch.timer);
    if (any) arch.timer = setTimeout(function () { if ($('view-arsiv').classList.contains('active')) renderArsiv(); }, 2000);
  }
  var archThrottle = 0;
  window.__turanArchiveChanged = function () {
    var now = Date.now(); if (now - archThrottle < 800) return; archThrottle = now;
    if ($('view-arsiv').classList.contains('active')) renderArsiv();
  };
  window.__turanArchiveAdded = function (ok, err) {
    renderArsiv();
    if (ok) toast('Emredersiniz komutanım, ' + ok + ' PDF sıraya alındı; arka planda okunuyor.');
    if (err) alert('Bazı dosyalar eklenemedi:\n' + err);
  };
  function openBot(id) { arch.cur = id; try { localStorage.setItem('turan-lastbot', String(id)); } catch (e) {} if (arch.shown !== id) { $('askOut').innerHTML = ''; $('askInput').value = ''; arch.shown = id; } show('arsiv'); renderArsiv(); }
  function inputSheet(title, value, label, fn) {
    sheet(title, [{ html: '<input type="text" id="sheetInput" value="' + esc(value || '') + '" autocomplete="off">' },
      { label: label || 'Kaydet', primary: true, fn: function () { var v = $('sheetInput').value.trim(); if (v) fn(v); } }]);
    setTimeout(function () { var i = $('sheetInput'); if (i) { i.focus(); i.select(); } }, 80);
  }
  if (N) {
    $('botForm').onsubmit = function (e) {
      e.preventDefault(); var n = $('botName').value.trim(); if (!n) { $('botName').focus(); return; }
      var id = N.archiveCreate(n); $('botName').value = ''; openBot(id);
      toast('Emredersiniz komutanım, “' + n + '” botu kuruldu. Şimdi PDF ekleyin.');
    };
    $('botList').onclick = function (e) { var li = e.target.closest('[data-bot]'); if (li) openBot(+li.dataset.bot); };
    $('botBack').onclick = function () { arch.cur = 0; renderArsiv(); };
    $('docAdd').onclick = function () { N.archivePick(arch.cur); };
    $('botSum').onclick = function () { archSummary(arch.cur, 0); };
    $('botMore').onclick = function () {
      var c = collOf(arch.cur); if (!c) return;
      sheet(c.name, [
        { label: '✎ Yeniden adlandır', fn: function () { inputSheet('Bot adı', c.name, 'Kaydet', function (v) { N.archiveRename(c.id, v); renderArsiv(); }); } },
        { label: '🗑 Botu ve tüm belgelerini sil', fn: function () { setTimeout(function () { if (confirm(c.docs ? '“' + c.name + '” botu ve içindeki ' + c.docs + ' belge silinsin mi komutanım?' : '“' + c.name + '” botu silinsin mi komutanım?')) { N.archiveDelete(c.id); arch.cur = 0; setTimeout(renderArsiv, 300); toast('Bot silindi.'); } }, 80); } }]);
    };
    $('docList').onclick = function (e) {
      var b = e.target.closest('button[data-a]'), li = e.target.closest('[data-doc]'); if (!b || !li) return;
      var d = arch.data.docs.filter(function (x) { return x.id === +li.dataset.doc; })[0]; if (!d) return;
      if (b.dataset.a === 'open') N.archiveOpen(d.id);
      else if (b.dataset.a === 'sum') archSummary(d.coll, d.id);
      else sheet(d.name, [
        { label: '✎ Yeniden adlandır', fn: function () { inputSheet('Belge adı', d.name, 'Kaydet', function (v) { N.docRename(d.id, v); renderArsiv(); }); } },
        { label: '⟳ Yeniden oku', fn: function () { N.archiveRetry(d.id); renderArsiv(); } },
        { label: '🗑 Belgeyi sil', fn: function () { setTimeout(function () { if (confirm('“' + d.name + '” arşivden silinsin mi komutanım?')) { N.docDelete(d.id); setTimeout(renderArsiv, 300); } }, 80); } }]);
    };
    $('askForm').onsubmit = function (e) { e.preventDefault(); $('askInput').blur(); archAsk(arch.cur, $('askInput').value, false); };
    $('askMic').onclick = function () { N.listenTo('arsiv'); };
    document.querySelector('.tabs button[data-view=arsiv]').addEventListener('click', renderArsiv);
  }
  window.__turanHeard = function (tag, t) {
    if (tag === 'arsiv') { if (t) { $('askInput').value = t; archAsk(arch.cur, t, true); } return; }
    if (tag === 'comm') { var ta = $('commText'); if (ta && t) { ta.value = TC.convertBody(t); } }
  };
  window.__turanArchive = function (req, r) { var f = arch.wait[req]; delete arch.wait[req]; if (f) f(r); };
  function archCall(fn) { return new Promise(function (res) { var id = 'q' + (++arch.seq); arch.wait[id] = res; fn(id); }); }
  function archAsk(coll, q, voice) {
    q = String(q || '').trim(); if (!q) { $('askInput').focus(); return; }
    var out = $('askOut');
    out.innerHTML = '<p class="muted">Arşiv taranıyor komutanım…</p>';
    archCall(function (id) { N.archiveAsk(id, coll, q); }).then(function (r) {
      if (!r.ok) { out.innerHTML = '<p class="err">Hata: ' + esc(r.error || '') + '</p>'; return; }
      arch.last = { q: q, r: r };
      var label = r.mode === 'ai' ? 'Turan Asistan · yapay zekâ cevabı (kaynaklı)' : r.mode === 'extract' ? 'Arşivden kaynaklı alıntılar' : 'Sonuç';
      var srcs = (r.sources || []).map(function (s) { return '<li><b>📕 ' + esc(s.name) + ' · s. ' + s.page + '</b><span class="sn">' + esc(s.snippet) + '</span></li>'; }).join('');
      out.innerHTML = '<div class="ans"><div class="src">' + label + '</div><p class="pw">' + esc(r.answer) + '</p>' +
        (r.aiError ? '<p class="muted small">Yapay zekâya ulaşılamadı (' + esc(r.aiError) + '); alıntılar gösterildi.</p>' : '') +
        '<div class="b"><button id="ansSpeak">🔊 Oku</button><button id="ansNote">📌 Not al</button></div>' +
        (srcs ? '<details' + (r.mode === 'ai' ? '' : ' open') + '><summary class="small">Kaynak bölümler (' + r.sources.length + ')</summary><ul class="srcs">' + srcs + '</ul></details>' : '') + '</div>';
      $('ansSpeak').onclick = function () { say(speakable(r.answer)); };
      $('ansNote').onclick = function () { var it = addItem('Arşiv – ' + q + ': ' + r.answer.replace(/\s+/g, ' ').slice(0, 400)); toast('Emredersiniz komutanım, not alındı.', undoAdd(it)); };
      if (voice) say(speakable(r.answer));
    });
  }
  function speakable(t) { return String(t).replace(/\[[^\]]{1,80}\]/g, '').replace(/—\s*[^\n]*?, s\. \d+/g, '').replace(/[•“”]/g, '').slice(0, 900); }
  var sumPlain = '';
  function archSummary(coll, doc) {
    $('sumTitle').textContent = 'Analiz hazırlanıyor…'; $('sumText').textContent = 'Komutanım, belgeler inceleniyor…'; $('sumModal').classList.remove('hidden');
    archCall(function (id) { N.archiveSummary(id, coll, doc); }).then(function (r) {
      if (!r.ok) { $('sumTitle').textContent = 'Analiz'; $('sumText').textContent = 'Komutanım, analiz yapılamadı: ' + (r.error || ''); return; }
      $('sumTitle').textContent = (doc ? 'Belge analizi: ' : 'Bot analizi: ') + r.title;
      sumPlain = r.text + (r.mode === 'ai' ? '\n\n' + r.stats : '');
      $('sumText').textContent = sumPlain;
    });
  }
  $('sumOk').onclick = function () { $('sumModal').classList.add('hidden'); if (N) N.stopSpeak(); };
  $('sumSpeak').onclick = function () { say(speakable(sumPlain)); };
  $('sumNote').onclick = function () { if (!sumPlain) return; var it = addItem($('sumTitle').textContent + ' – ' + sumPlain.replace(/\s+/g, ' ').slice(0, 500)); toast('Emredersiniz komutanım, not alındı.', undoAdd(it)); };
  function findBot(name) {
    archLoad();
    var cs = arch.data.colls; if (!cs.length) return null;
    if (name) {
      var f = TC.fold(name), best = null, bs = 0;
      cs.forEach(function (c) { var s = TC.scoreName(f, c.name) || (TC.fold(c.name).indexOf(f) >= 0 ? 50 : 0); if (s > bs) { bs = s; best = c; } });
      if (best) return best;
    }
    var last = +(localStorage.getItem('turan-lastbot') || 0);
    return collOf(arch.cur) || collOf(last) || (cs.length === 1 ? cs[0] : cs[0]);
  }
  // Paylaş → Arşive ekle
  function shareToArchive(files) {
    if (!N) return;
    archLoad();
    var pdfs = files.filter(function (m) { return /pdf/i.test(m.mime) || /\.pdf$/i.test(m.name); });
    if (!pdfs.length) { toast('Arşive yalnızca PDF eklenebilir komutanım.'); return; }
    var opts = arch.data.colls.map(function (c) { return { label: '📚 ' + c.name + ' (' + c.docs + ' belge)', fn: function () { N.archiveAddAtts(c.id, JSON.stringify(pdfs)); openBot(c.id); } }; });
    opts.push({ label: '＋ Yeni araştırma botu', primary: true, fn: function () { inputSheet('Yeni bot adı', '', 'Oluştur ve ekle', function (v) { var id = N.archiveCreate(v); N.archiveAddAtts(id, JSON.stringify(pdfs)); openBot(id); }); } });
    sheet(pdfs.length + ' PDF hangi araştırma botuna eklensin?', opts);
  }

  // ---------- v1.4: Arama / SMS / WhatsApp ----------
  function commSet() { state.settings.comm = Object.assign({ confirmCalls: true, channel: 'sms', waAuto: false }, state.settings.comm || {}); return state.settings.comm; }
  function permState() { try { return JSON.parse(N.permState()); } catch (e) { return {}; } }
  var permWait = {};
  window.__turanPerm = function (name, ok) { var f = permWait[name]; delete permWait[name]; if (f) f(!!ok); try { fillComm(); } catch (e) {} };
  window.__turanPermRefresh = function () { fillComm(); fillX(); };
  function needPerm(name) {
    return new Promise(function (res) { if (permState()[name]) return res(true); permWait[name] = res; N.requestPerm(name); });
  }
  var contactsCache = null, contactsAt = 0, ctWait = {}, ctSeq = 0;
  window.__turanContacts = function (id, list) { var f = ctWait[id]; delete ctWait[id]; if (f) f(list || []); };
  function loadContacts() {
    if (contactsCache && Date.now() - contactsAt < 120000) return Promise.resolve(contactsCache);
    return new Promise(function (res) { var id = 'k' + (++ctSeq); ctWait[id] = function (l) { contactsCache = l; contactsAt = Date.now(); res(l); }; N.contacts(id); });
  }
  var TYPE_TR = { mobile: 'Cep', home: 'Ev', work: 'İş', other: 'Diğer' };
  function fmtNum(n) { return String(n); }
  function closeVoice() { $('voiceModal').classList.add('hidden'); }
  function runComm(c, raw) {
    try { document.activeElement && document.activeElement.blur(); } catch (e) {}
    if (c.type === 'x') {
      if (!N) { reply('Komutanım, X özeti ' + NA + '.'); return; }
      closeVoice(); N.openX(!!c.auto);
      say(c.auto ? 'Emredersiniz komutanım, zaman tünelini tarayıp özetliyorum.' : 'Emredersiniz komutanım.');
      return;
    }
    if (c.type === 'archive') {
      if (!N) { reply('Komutanım, arşiv tarama ' + NA + '.'); return; }
      var b = findBot(c.bot);
      if (!b) { reply('Komutanım, henüz araştırma botu yok. Arşiv bölümünden bir bot oluşturup PDF ekleyin. Arz ederim.'); return; }
      closeVoice(); openBot(b.id); $('askInput').value = c.q; archAsk(b.id, c.q, true);
      return;
    }
    if (!N) { reply('Komutanım, arama ve mesaj komutları ' + NA + '.'); return; }
    reply('Rehberde aranıyor komutanım…', true);
    needPerm('contacts').then(function (ok) {
      if (!ok) {
        if (c.type === 'msg' && !c.strong) return runBasic(raw);
        reply('Komutanım, rehber izni olmadan kişiyi bulamıyorum. Ayarlar → Arama, mesaj ve WhatsApp bölümünden izin verebilirsiniz. Arz ederim.');
        return;
      }
      loadContacts().then(function (list) {
        var best = null, opt = null;
        (c.options || [{ who: c.who, body: c.body }]).forEach(function (o) {
          var m = TC.matchContacts(list, o.who);
          if (m.length && (!best || m[0].score > best[0].score)) { best = m; opt = o; }
        });
        if (!best) {
          if (c.type === 'msg' && !c.strong) return runBasic(raw);
          var nm = TC.nameCandidates(c.who)[0] || c.who;
          reply('Komutanım, rehberde “' + cap(nm) + '” adında bir kişi bulamadım. Arz ederim.');
          return;
        }
        var job = { type: c.type, body: opt.body || '', channel: c.type === 'msg' ? (c.channel || commSet().channel) : null };
        if (best.length === 1) return chooseNumber(best[0].c, job);
        say('Komutanım, birden fazla kişi buldum. Hangisi?');
        sheet('Hangi kişi komutanım?', best.map(function (r) {
          var n = TC.pickNumbers(r.c, job.type)[0];
          return { label: '👤 ' + r.c.name + (n ? '  ·  ' + fmtNum(n.n) : ''), fn: function () { chooseNumber(r.c, job); } };
        }));
      });
    });
  }
  function chooseNumber(ct, job) {
    var nums = TC.pickNumbers(ct, job.type);
    if (!nums.length) { reply('Komutanım, ' + ct.name + ' için kayıtlı numara yok. Arz ederim.'); return; }
    job.name = ct.name;
    if (nums.length === 1) { job.num = nums[0].n; return confirmJob(job); }
    say('Komutanım, ' + ct.name + ' için birden fazla numara var. Hangisi?');
    sheet(ct.name + ' – hangi numara?', nums.map(function (n) { return { label: (TYPE_TR[n.t] || '') + ': ' + fmtNum(n.n), fn: function () { job.num = n.n; confirmJob(job); } }; }));
  }
  function chanName(ch) { return ch === 'wa' ? 'WhatsApp' : 'SMS'; }
  function confirmJob(job) {
    if (job.type === 'call') {
      if (!commSet().confirmCalls) return doCall(job);
      var q = job.name + ' aransın mı komutanım?';
      reply(q, true); say(q);
      sheet('Arama onayı', [{ html: '<p class="who">📞 <b>' + esc(job.name) + '</b><small>' + esc(job.num) + '</small></p><p>' + esc(job.name) + ' aransın mı?</p>' },
        { label: '📞 Evet, ara', primary: true, fn: function () { doCall(job); } },
        { label: 'Vazgeç', fn: function () { reply('Emredersiniz komutanım, arama iptal edildi.', true); } }]);
      return;
    }
    var body = job.body;
    var q = body ? TC.dative(job.name) + ' “' + body + '” mesajı ' + (job.channel === 'wa' ? 'WhatsApp\'tan ' : '') + 'gönderilsin mi?' : TC.dative(job.name) + ' ne yazayım komutanım?';
    reply(q, true); say(q);
    sheet(chanName(job.channel) + ' mesajı – onay', [
      { html: '<p class="who">' + (job.channel === 'wa' ? '🟢' : '✉️') + ' <b>' + esc(job.name) + '</b><small>' + esc(job.num) + ' · ' + chanName(job.channel) + '</small></p>' +
        '<p>' + esc(body ? TC.dative(job.name) + ' bu mesaj gönderilsin mi?' : TC.dative(job.name) + ' ne yazayım komutanım?') + '</p><textarea id="commText" placeholder="Mesaj metni…">' + esc(body) + '</textarea><div class="row2"><button type="button" id="commMic">🎤 Sesle yaz</button></div>' },
      { label: (job.channel === 'wa' ? '🟢 WhatsApp\'ta gönder' : '✉️ SMS gönder'), primary: true, fn: function () { job.body = $('commText').value.trim(); if (!job.body) { toast('Mesaj boş komutanım.'); return; } doSend(job); } },
      { label: '⇄ ' + (job.channel === 'wa' ? 'SMS' : 'WhatsApp') + ' ile gönder', keep: true, fn: function () { job.body = $('commText').value.trim(); job.channel = job.channel === 'wa' ? 'sms' : 'wa'; confirmJob(job); } },
      { label: 'Vazgeç', fn: function () { reply('Emredersiniz komutanım, mesaj gönderilmedi.', true); } }]);
    $('commMic').onclick = function () { N.listenTo('comm'); };
    if (!body) setTimeout(function () { var t = $('commText'); if (t) t.focus(); }, 100);
  }
  function doCall(job) {
    closeVoice();
    needPerm('call').then(function () {
      N.call(job.num);
      say('Emredersiniz komutanım, ' + job.name + ' aranıyor.');
    });
  }
  var smsWait = {}, smsSeq = 0;
  window.__turanSmsResult = function (id, r) { var f = smsWait[id]; delete smsWait[id]; if (f) f(r); };
  function doSend(job) {
    if (job.channel === 'wa') {
      var mode = N.whatsapp(job.num, job.body, !!commSet().waAuto);
      var msg = mode === 'auto' ? 'Emredersiniz komutanım, WhatsApp\'ta ' + job.name + ' sohbetini açtım; mesaj otomatik gönderilecek.'
        : mode === 'app' ? 'Emredersiniz komutanım, mesaj WhatsApp\'ta hazır; Gönder\'e basmanız yeterli.'
        : 'Komutanım, telefonda WhatsApp bulunamadı; WhatsApp bağlantısını tarayıcıda açtım.';
      reply(msg, true); toast(msg); closeVoice();
      return;
    }
    needPerm('sms').then(function () {
      var id = 's' + (++smsSeq);
      smsWait[id] = function (r) {
        var m = r === 'ok' ? 'Emredersiniz komutanım, mesaj ' + TC.dative(job.name) + ' gönderildi.'
          : r === 'compose' ? 'Komutanım, mesajı mesaj uygulamasında hazırladım; göndermek için Gönder\'e basın.'
          : 'Komutanım, mesaj gönderilemedi. Şebeke veya SIM durumunu kontrol edin. Arz ederim.';
        reply(m, true); toast(m); say(m);
      };
      N.sms(id, job.num, job.body);
      closeVoice();
    });
  }
  function permTxt(ok) { return ok ? 'verildi ✔' : 'verilmedi ✖'; }
  function fillComm() {
    if (!N) return;
    var p = permState(), cs = commSet();
    $('pContacts').textContent = permTxt(p.contacts); $('pCall').textContent = permTxt(p.call); $('pSms').textContent = permTxt(p.sms);
    $('pWa').textContent = p.wa ? 'yüklü ✔' : 'yüklü değil';
    $('pWaSvc').textContent = p.waService ? 'açık ✔' : 'kapalı';
    $('btnPContacts').classList.toggle('hidden', !!p.contacts); $('btnPCall').classList.toggle('hidden', !!p.call); $('btnPSms').classList.toggle('hidden', !!p.sms || p.telephony === false);
    $('commChannel').value = cs.channel; $('commNoConfirm').checked = !cs.confirmCalls; $('commWaAuto').checked = !!cs.waAuto;
  }
  if (N) {
    $('btnPContacts').onclick = function () { N.requestPerm('contacts'); };
    $('btnPCall').onclick = function () { N.requestPerm('call'); };
    $('btnPSms').onclick = function () { N.requestPerm('sms'); };
    $('btnWaSvc').onclick = function () { N.openAccessibility(); };
    $('commChannel').onchange = function () { refresh(); commSet().channel = this.value; save(); };
    $('commNoConfirm').onchange = function () { refresh(); commSet().confirmCalls = !this.checked; save(); };
    $('commWaAuto').onchange = function () {
      refresh(); commSet().waAuto = this.checked; save();
      if (this.checked && !permState().waService) {
        sheet('Turan WhatsApp gönderici', [{ html: '<p>Komutanım, otomatik gönderme için erişilebilirlik hizmetini açmanız gerekiyor:</p><ol class="cmds small"><li>Açılacak ekranda <b>Yüklü uygulamalar</b> → <b>Turan WhatsApp gönderici</b> → Aç.</li><li>Seçenek gri / “Kısıtlanmış ayar” ise: Ayarlar → Uygulamalar → Turan Asistan → ⋮ → <b>Kısıtlanmış ayarlara izin ver</b>, sonra tekrar deneyin.</li></ol><p class="muted small">Hizmet yalnızca onayladığınız mesajda Gönder\'e basar; başka hiçbir şeyi okumaz.</p>' },
          { label: 'Erişilebilirlik ayarlarını aç', primary: true, fn: function () { N.openAccessibility(); } },
          { label: 'Uygulama bilgisi (kısıtlanmış ayarlar)', fn: function () { N.openAppDetails(); } }]);
      }
    };
  }

  // ---------- v1.4: X zaman tüneli ----------
  function fillX() {
    if (!N) return;
    var x = {}; try { x = JSON.parse(N.xInfo()); } catch (e) {}
    $('xState').textContent = x.loggedIn ? 'giriş yapıldı ✔' : 'giriş yapılmadı';
    $('xLogout').classList.toggle('hidden', !x.loggedIn);
    var sel = $('xScreens'), v = String(x.screens || 15);
    if (![].some.call(sel.options, function (o) { return o.value === v; })) { var o = document.createElement('option'); o.textContent = v; sel.appendChild(o); }
    sel.value = v;
    var last = x.last || {};
    $('xLast').classList.toggle('hidden', !last.text);
    if (last.text) { $('xLastAt').textContent = 'Son özet · ' + fmtTime(last.at); $('xLastText').textContent = last.text; }
  }
  if (N) {
    $('gizliOpen').onclick = function () { N.openGizli(); };
    $('xOpen').onclick = function () { N.openX(false); };
    $('xSum').onclick = function () { N.openX(true); };
    $('xScreens').onchange = function () { N.setXScreens(+this.value); };
    $('xLogout').onclick = function () { if (confirm('X oturumu bu uygulamadan kapatılsın mı komutanım?')) { N.xLogout(); setTimeout(fillX, 500); } };
    $('xSpeak').onclick = function () { say(speakable($('xLastText').textContent)); };
    $('xNote').onclick = function () { var it = addItem('X özeti: ' + $('xLastText').textContent.replace(/\s+/g, ' ').slice(0, 500)); toast('Emredersiniz komutanım, not alındı.', undoAdd(it)); };
    document.querySelector('.tabs button[data-view=research]').addEventListener('click', fillX);
    document.querySelector('.tabs button[data-view=settings]').addEventListener('click', fillComm);
  }
  window.__turanComm = TC; // test için

  // ---------- Başlat ----------
  function route(url) {
    var v = new URLSearchParams((url || location.search).split('?')[1] || '').get('view');
    if (v === 'arz') { state.lastShown.arz = Sched.ymd(Sched.istParts()); save(); openArz(true); }
    else if (v === 'not') { state.lastShown.not = Sched.ymd(Sched.istParts()); save(); openNotAlma(); }
    else if (v === 'voice') { show('open'); openVoice(true); }
    else if (v === 'weekly') { show('open'); openWeekly(); }
    else if (v === 'arsiv') { show('arsiv'); renderArsiv(); }
    else if (v) show(v);
    if (v && !url) history.replaceState(null, '', location.pathname);
    return !!v;
  }
  window.__turanRoute = function (v) { refresh(); render(); route('?view=' + v); };
  window.__turanReload = function () { refresh(); render(); notifStatus(); checkDue(true); if (N) { fillX(); if ($('view-arsiv').classList.contains('active')) renderArsiv(); } };
  load(); if (N) { cleanupOrphans(); save(); } render(); fillSched(); notifStatus(); renderGLinks(); fillAi(); applyTheme(); renderHist();
  if (N) { fillComm(); fillX(); try { N.archiveKick(); } catch (e) {} }
  if (!route()) checkDue(true);
  setInterval(function () { checkDue(false); renderNext(); }, 20000);
  document.addEventListener('visibilitychange', function () { if (document.visibilityState === 'visible') { checkDue(true); renderNext(); } });

  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('sw.js').then(function () { mirror(); registerPeriodic(); }).catch(function () {});
    navigator.serviceWorker.addEventListener('message', function (e) { if (e.data && e.data.open) route(e.data.open); });
  }
})();
