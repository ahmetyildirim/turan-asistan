/* Gizli Hat arayüzü — yerel köprü: window.GizliNative */
(function () {
  'use strict';
  var G = window.GizliNative;
  var $ = function (id) { return document.getElementById(id); };
  var S = { st: null, cur: 0, contact: null, circ: null, prevHops: null, pin: '', pinMode: 'unlock', pin1: '', view: '', helpFrom: 'vHome', progress: {}, rtt: {}, timers: {} };

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function J(s, d) { try { return JSON.parse(s); } catch (e) { return d; } }
  function toast(t, ms) { var e = $('toast'); e.textContent = t; e.classList.remove('hidden'); clearTimeout(S.timers.toast); S.timers.toast = setTimeout(function () { e.classList.add('hidden'); }, ms || 3200); }
  function show(v) { ['vLock', 'vSetup', 'vHome', 'vChat', 'vSettings', 'vHelp'].forEach(function (x) { $(x).classList.toggle('hidden', x !== v); }); S.view = v; }
  function hhmm(t) { var d = new Date(t); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  function ago(t) { var s = Math.round((Date.now() - t) / 1000); if (s < 60) return s + ' sn önce'; if (s < 3600) return Math.round(s / 60) + ' dk önce'; return Math.round(s / 3600) + ' saat önce'; }
  function size(n) { return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'; }
  function flag(cc) { if (!cc || cc.length !== 2 || cc === '??') return '🏳️'; return String.fromCodePoint(0x1F1E6 + cc.charCodeAt(0) - 65, 0x1F1E6 + cc.charCodeAt(1) - 65); }
  function ttlText(s) { if (!s) return 'kapalı'; if (s < 60) return s + ' sn'; if (s < 3600) return (s / 60) + ' dk'; if (s < 86400) return (s / 3600) + ' saat'; if (s < 604800) return (s / 86400) + ' gün'; return (s / 604800) + ' hafta'; }

  if (!G) { document.body.innerHTML = '<p style="padding:24px">Gizli Hat yalnızca Turan Asistan Android uygulamasında çalışır.</p>'; return; }

  // ---------- genel alt sayfa ----------
  function sheet(title, items) {
    $('sheetTitle').textContent = title; var b = $('sheetBody'); b.innerHTML = '';
    items.forEach(function (it) {
      if (it.html) { var d = document.createElement('div'); d.innerHTML = it.html; b.appendChild(d); return; }
      var btn = document.createElement('button'); btn.textContent = it.label; if (it.cls) btn.className = it.cls;
      btn.onclick = function () { if (!it.keep) closeSheet(); it.fn && it.fn(); }; b.appendChild(btn);
    });
    $('sheet').classList.remove('hidden');
  }
  function closeSheet() { $('sheet').classList.add('hidden'); }
  $('sheet').onclick = function (e) { if (e.target === $('sheet')) closeSheet(); };
  function prompt2(title, ph, val, okText, cb, multiline, okCls) {
    sheet(title, [{ html: (multiline ? '<textarea id="pIn" rows="4" placeholder="' + esc(ph) + '"></textarea>' : '<input type="text" id="pIn" placeholder="' + esc(ph) + '">') }, { label: okText, cls: okCls || 'primary', fn: function () { cb($('pIn').value); } }, { label: 'Vazgeç' }]);
    $('pIn').value = val || ''; setTimeout(function () { $('pIn').focus(); }, 50);
  }

  // ---------- durum ----------
  function refresh() {
    S.st = J(G.state(), {});
    if (!S.st.hasIdentity) { show('vSetup'); return; }
    if (!S.st.pinSet) { startPin('set'); return; }
    if (S.st.locked) { startPin('unlock'); return; }
    if (S.view === 'vLock' || S.view === 'vSetup' || !S.view) show('vHome');
    renderHome(); renderTor(S.st.tor, S.st.online);
    if (S.view === 'vChat' && S.cur) { loadChat(true); }
    if (S.st.call && S.st.call.state !== 'idle') renderCall(S.st.call);
  }

  // ---------- PIN ----------
  function startPin(mode) {
    S.pinMode = mode; S.pin = ''; show('vLock');
    $('lockMsg').textContent = mode === 'set' ? 'Gizli Hat için 4–10 haneli bir PIN belirleyin' : mode === 'set2' ? 'PIN\'i tekrar girin' : mode === 'old' ? 'Mevcut PIN\'i girin' : 'PIN kodunuzu girin';
    $('bioBtn').classList.toggle('hidden', !(mode === 'unlock' && S.st && S.st.bio && S.st.settings && S.st.settings.bio));
    dots();
    if (mode === 'unlock' && S.st && S.st.settings && S.st.settings.bio) setTimeout(function () { G.bioUnlock(); }, 300);
  }
  function dots() { var d = $('pinDots'); d.innerHTML = ''; var n = Math.max(4, S.pin.length); for (var i = 0; i < n; i++) { var x = document.createElement('i'); if (i < S.pin.length) x.className = 'on'; d.appendChild(x); } }
  (function buildPad() {
    var p = $('pad'); ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✓'].forEach(function (k) {
      var b = document.createElement('button'); b.textContent = k; b.onclick = function () { padKey(k); }; p.appendChild(b);
    });
  })();
  function padKey(k) {
    if (k === '⌫') { S.pin = S.pin.slice(0, -1); dots(); return; }
    if (k !== '✓') { if (S.pin.length < 10) S.pin += k; dots(); return; }
    if (S.pin.length < 4) { toast('PIN en az 4 haneli olmalı.'); return; }
    var pin = S.pin; S.pin = '';
    if (S.pinMode === 'set') { S.pin1 = pin; startPin('set2'); return; }
    if (S.pinMode === 'set2') {
      if (pin !== S.pin1) { toast('PIN\'ler uyuşmadı, tekrar deneyin.'); startPin('set'); return; }
      if (G.setPin(pin)) { toast('PIN belirlendi. Unutmayın: PIN unutulursa veriler kurtarılamaz.', 4500); S.view = ''; refresh(); } else toast('PIN kaydedilemedi.');
      return;
    }
    var r = J(G.unlock(pin), {});
    if (r.ok) { if (S.pinMode === 'old') { startPin('set'); return; } S.view = ''; refresh(); return; }
    if (r.wiped) return;
    $('lockMsg').textContent = 'Hatalı PIN (' + (r.fails || 1) + '. deneme)'; dots();
  }
  $('bioBtn').onclick = function () { G.bioUnlock(); };
  window.__gzUnlocked = function () { S.view = ''; refresh(); };

  // ---------- kurulum ----------
  $('createBtn').onclick = function () { S.st = J(G.createIdentity(), {}); toast('Kimliğiniz bu telefonda oluşturuldu.'); startPin('set'); };

  // ---------- Tor durumu ----------
  function renderTor(t, online) {
    t = t || {}; var on = !!online;
    var ph = on ? (t.phase || 'Tor başlatılıyor') : 'Çevrimdışı';
    $('torPhase').textContent = (on && t.published ? '🟢 ' : on ? '🟡 ' : '⚪ ') + ph;
    $('torSub').textContent = !on ? 'Tor kapalı. Mesaj alıp göndermek için çevrimiçi olun.' :
      t.published ? 'Onion hizmetiniz Tor ağında yayında; kişileriniz size ulaşabilir. Trafik yalnızca Tor üzerinden.' :
      t.ready ? 'Tor bağlı. Onion tanımınız yayınlanıyor (ilk seferde ~1 dk sürebilir).' : 'Tor ağına bağlanılıyor… (açık internete geri dönüş yok)';
    if (t.error) $('torSub').textContent = '⚠️ ' + t.error;
    $('onlineBtn').textContent = on ? 'Çevrimdışı ol' : 'Çevrimiçi ol';
    $('onlineBtn').className = on ? '' : 'primary';
    var bar = $('torBar'); bar.classList.toggle('hidden', !on || t.published);
    bar.firstChild.style.width = (t.ready ? 95 : Math.min(90, t.progress || 3)) + '%';
    if (S.view === 'vChat') chatBanner();
  }
  $('onlineBtn').onclick = function () {
    var on = !(S.st && S.st.online);
    G.online(on); S.st.online = on; renderTor(on ? { phase: 'Tor başlatılıyor', progress: 1 } : {}, on);
    if (on) toast('Tor başlatılıyor. Gizli Hat çevrimiçiyken bildirim çubuğunda bir simge görünür.');
  };

  // ---------- ana ekran ----------
  function renderHome() {
    if (!S.st || !S.st.id) return;
    $('myId').textContent = S.st.id; $('myFp').textContent = 'Parmak izi: ' + S.st.fp;
    var list = J(G.contacts(), []); var box = $('contactList'); box.innerHTML = '';
    $('noContacts').classList.toggle('hidden', list.length > 0);
    list.sort(function (a, b) { return (b.state === 'in') - (a.state === 'in') || b.last - a.last; });
    list.forEach(function (c) {
      var d = document.createElement('div'); d.className = 'ct';
      var tag = c.state === 'in' ? '<span class="tag">Bağlantı isteği</span>' : c.state === 'out' ? '<span class="tag">Onay bekleniyor</span>' : (c.verified ? '<span class="tag" style="border-color:#7bd389;color:#7bd389">✔ doğrulandı</span>' : '');
      d.innerHTML = '<div class="av' + (c.online ? ' on' : '') + '">' + esc(c.alias ? c.alias.slice(0, 2).toUpperCase() : String(c.short || c.name || '').replace(/^0x/i, '').slice(0, 2).toUpperCase()) + '</div>' +
        '<div class="mid"><div class="ell"><b>' + esc(c.name) + '</b>' + tag + '</div><div class="small muted ell">' + (c.ttl ? '⏱ ' : '') + esc(c.prev || c.short) + '</div></div>' +
        (c.unread ? '<span class="badge">' + c.unread + '</span>' : '<span class="small muted">' + (c.last ? hhmm(c.last) : '') + '</span>');
      d.onclick = function () { openChat(c.cid); };
      box.appendChild(d);
    });
  }

  function addContact(prefill) {
    sheet('Kişi ekle', [
      { html: '<p class="small muted">Karşı tarafın <b>0x</b> ile başlayan kimliğini yapıştırın (70 karakter) ya da QR kodunu tarayın.</p><textarea id="addId" rows="3" class="mono" placeholder="0x…"></textarea><input type="text" id="addAlias" placeholder="Takma ad (yalnız sizde görünür, örn. Mehmet)" style="margin-top:8px">' },
      { label: '📋 Panodan yapıştır', keep: true, fn: function () { $('addId').value = G.clipboard(); } },
      { label: '📷 QR tara (kamera)', keep: true, fn: function () { G.scanQr(true); } },
      { label: '🖼 QR görselini seç', keep: true, fn: function () { G.scanQr(false); } },
      { label: 'Ekle ve bağlan', cls: 'primary', keep: true, fn: function () {
        var r = J(G.addContact($('addId').value, $('addAlias').value), {});
        if (!r.ok) { toast(r.error || 'Eklenemedi', 5000); return; }
        closeSheet(); if (r.note) toast(r.note); renderHome(); openChat(r.cid);
      } },
      { label: 'Vazgeç' }
    ]);
    if (prefill) $('addId').value = prefill;
  }
  window.__gzScanned = function (t) { if ($('addId')) $('addId').value = t; else addContact(t); toast('QR okundu.'); };

  // ---------- sohbet ----------
  function openChat(cid) {
    S.cur = cid; S.prevHops = null; S.circ = null; show('vChat'); $('msgList').innerHTML = '';
    G.openChat(cid, true); loadChat(true); pollCircuit();
    clearInterval(S.timers.chat); S.timers.chat = setInterval(function () { if (S.view === 'vChat') { loadHeader(); tickTtl(); } }, 1000);
    clearInterval(S.timers.circ); S.timers.circ = setInterval(pollCircuit, 5000);
  }
  function leaveChat() { if (S.cur) G.openChat(S.cur, false); S.cur = 0; clearInterval(S.timers.chat); clearInterval(S.timers.circ); show('vHome'); renderHome(); }

  function loadHeader() {
    var c = J(G.contact(S.cur), null); if (!c || !c.cid) { leaveChat(); return; } S.contact = c;
    $('chatName').textContent = (c.verified ? '✔ ' : '') + c.name;
    var sub = c.online ? '🟢 bağlı · uçtan uca şifreli' : c.connecting ? '🟡 Tor üzerinden bağlanıyor…' : '⚪ bağlı değil';
    if (S.rtt[S.cur] && c.online) sub += ' · gecikme ~' + (S.rtt[S.cur] / 1000).toFixed(1) + ' sn';
    if (c.ttl) sub += ' · ⏱ ' + ttlText(c.ttl);
    $('chatSub').textContent = sub;
    chatBanner();
  }
  function chatBanner() {
    var c = S.contact, b = $('chatBanner'); if (!c) return; b.className = 'banner'; var h = '';
    if (!(S.st && S.st.online)) h = '⚪ Gizli Hat çevrimdışı. Yazdıklarınız kuyrukta bekler. <button id="bOn" class="primary">Çevrimiçi ol</button>';
    else if (c.state === 'in') { b.className = 'banner req'; h = '🔔 Bu kişi sizinle bağlantı kurmak istiyor.<br><span class="mono small">' + esc(c.id) + '</span><br>Kabul etmeden önce kimliği güvendiğiniz bir kanaldan doğrulayın.<br><button id="bAcc" class="primary">Kabul et</button><button id="bRej">Reddet ve engelle</button>'; }
    else if (c.state === 'out') h = '⏳ Onay bekleniyor. Karşı taraf sizi kabul edince (veya o da sizi ekleyince) bağlantı kurulur. İkiniz de çevrimiçi olmalısınız.' + (c.error ? '<br>⚠️ ' + esc(c.error) : '');
    else if (!c.online && c.error) h = '⚠️ ' + esc(c.error) + '<br>Mesajlar telefonunuzda şifreli bekliyor; ikiniz de çevrimiçi olunca iletilecek.' + (c.retry ? ' Sonraki deneme: ' + Math.ceil(c.retry / 1000) + ' sn.' : '');
    if (!h) { b.classList.add('hidden'); return; }
    b.innerHTML = h;
    if ($('bOn')) $('bOn').onclick = function () { $('onlineBtn').onclick(); };
    if ($('bAcc')) $('bAcc').onclick = function () { G.contactAction(S.cur, 'accept', ''); toast('Kabul edildi.'); loadChat(true); };
    if ($('bRej')) $('bRej').onclick = function () { G.contactAction(S.cur, 'block', ''); toast('Reddedildi ve engellendi.'); leaveChat(); };
  }

  function loadChat(scroll) {
    loadHeader();
    var list = J(G.messages(S.cur, 0), []); var box = $('msgList');
    var atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    box.innerHTML = '';
    list.forEach(function (m) {
      var d = document.createElement('div');
      if (m.kind === 'sys') { d.className = 'm sys'; d.textContent = m.text; box.appendChild(d); return; }
      d.className = 'm' + (m.dir === 1 ? ' me' : ''); d.dataset.id = m.id; if (m.exp) d.dataset.exp = m.exp;
      var body = '';
      if (m.kind === 'f') {
        body = (m.thumb ? '<img src="' + m.thumb + '" alt="">' : '') + '<div>📎 ' + esc(m.name) + ' · ' + size(m.size) + '</div>';
        var p = S.progress[m.id + ''] ; if (p && m.dir === 1 && m.st < 1) body += '<div class="prog">gönderiliyor %' + p + '</div>';
      } else body = esc(m.text);
      var stx = '';
      if (m.dir === 1) stx = m.st === 0 ? ' <span title="Kuyrukta">🕓</span>' : m.st === 1 ? ' ✓' : m.st === 2 ? ' ✓✓' : ' <span class="st3">✓✓</span>';
      d.innerHTML = body + '<div class="meta">' + (m.ttl ? '<span class="ttl">⏱</span> ' : '') + hhmm(m.ts) + stx + '</div>';
      d.onclick = function () { if (m.kind === 'f') openFile(m); };
      d.oncontextmenu = function (e) { e.preventDefault(); msgMenu(m); };
      var lp; d.addEventListener('touchstart', function () { lp = setTimeout(function () { msgMenu(m); }, 600); }, { passive: true });
      d.addEventListener('touchend', function () { clearTimeout(lp); }); d.addEventListener('touchmove', function () { clearTimeout(lp); }, { passive: true });
      box.appendChild(d);
    });
    if (scroll || atBottom) box.scrollTop = box.scrollHeight;
    tickTtl();
  }
  function tickTtl() {
    var now = Date.now();
    document.querySelectorAll('.m[data-exp]').forEach(function (d) {
      var left = Math.max(0, Math.round((+d.dataset.exp - now) / 1000)); var t = d.querySelector('.ttl');
      if (t) t.textContent = '⏱ ' + (left >= 3600 ? Math.floor(left / 3600) + 'sa' : Math.floor(left / 60) + ':' + ('0' + left % 60).slice(-2));
    });
  }
  function msgMenu(m) {
    var items = [];
    if (m.kind !== 'f') items.push({ label: '📋 Metni kopyala (60 sn sonra panodan silinir)', fn: function () { G.copyText(m.text); toast('Kopyalandı.'); } });
    items.push({ label: '🗑 Bu telefondan sil', fn: function () { G.contactAction(S.cur, 'delmsg', String(m.id)); } });
    items.push({ label: 'Vazgeç' });
    sheet(m.dir === 1 ? 'Gönderdiğiniz mesaj' : 'Gelen mesaj', items);
  }
  function openFile(m) {
    var r = J(G.openFile(m.id), {});
    if (!r.ok) { toast(r.error || 'Açılamadı'); return; }
    if (r.img) { $('imgView').src = r.img; $('imgCap').textContent = m.name + ' · ' + size(m.size); $('imgOv').classList.remove('hidden'); }
    else toast('Dosya başka bir uygulamada açılıyor. O uygulama ekran görüntüsünü engellemeyebilir; geçici kopya 10 dk sonra silinir.', 5000);
  }
  function send() {
    var t = $('msgInput').value; if (!t.trim()) return;
    if (G.send(S.cur, t) < 0) { toast('Gönderilemedi.'); return; }
    $('msgInput').value = ''; autosize(); loadChat(true);
  }
  $('sendBtn').onclick = send;
  function autosize() { var t = $('msgInput'); t.style.height = 'auto'; t.style.height = Math.min(120, t.scrollHeight) + 'px'; }
  $('msgInput').addEventListener('input', autosize);
  window.__gzFileQueued = function (note) { toast(note, 4500); loadChat(true); };

  // ---------- devre ----------
  function pollCircuit() {
    if (!S.cur) return;
    var c = J(G.circuit(S.cur), {}); S.circ = c;
    var chip = $('circChip');
    if (!c.ok) chip.textContent = '🧅 devre yok';
    else chip.textContent = '🧅 ' + c.hops.map(function (h) { return h.ip; }).join(' · ');
    if (!$('circOv').classList.contains('hidden')) renderCirc();
  }
  function renderCirc() {
    var c = S.circ || {}, b = $('circBody'), h = '';
    if (!c.ok) {
      h = '<p class="note">Bu kişiyle şu an kurulu bir Tor devresi yok' + (S.contact && S.contact.online ? '' : ' (bağlantı yok)') + '. Bağlantı kurulunca röleler burada görünür.</p>';
    } else {
      h = '<p class="note">' + (c.kind === 'client' ? 'Bu, <b>sizin kurduğunuz</b> buluşma devresidir.' : 'Bağlantıyı karşı taraf başlattı; bu, <b>sizin onion hizmetinizin</b> buluşma devresidir.') +
        ' Onion↔onion trafikte çıkış düğümü ve tek bir "çıkış IP\'si" yoktur. Karşı tarafın kendi 3 rölesi de vardır; onları göremezsiniz.</p>';
      var prev = S.prevHops || [];
      c.hops.forEach(function (x, i) {
        var isNew = prev.length && prev.indexOf(x.fp) < 0;
        h += '<div class="hop' + (isNew ? ' new' : '') + '"><div class="r1"><b>' + (i + 1) + '. ' + esc(x.role) + '</b>' + (isNew ? '<span class="newtag">YENİ</span>' : '') + '</div>' +
          '<div>' + flag(x.cc) + ' ' + esc(x.country) + ' · <span class="mono">' + esc(x.ip) + '</span></div>' +
          '<div class="small muted">Takma ad: ' + esc(x.nick) + ' · <span class="mono">' + esc((x.fp || '').slice(0, 16)) + '…</span></div></div>';
      });
    }
    b.innerHTML = h;
    var rm = c.rotateMin || 0, lr = c.lastRotate || 0;
    $('circTimes').textContent = 'Son değişim: ' + (lr ? ago(lr) : '—') + (rm ? ' · Otomatik: her ' + rm + ' dk (sonraki ~' + Math.max(0, Math.ceil((lr + rm * 60000 - Date.now()) / 60000)) + ' dk)' : ' · Otomatik değişim kapalı');
  }
  $('rotateBtn').onclick = function () {
    if (S.st && S.st.call && S.st.call.state === 'active') { if (!confirm('Görüşme sürüyor. Devre değişirse görüşme birkaç saniye kesilebilir. Devam edilsin mi?')) return; }
    S.prevHops = S.circ && S.circ.ok ? S.circ.hops.map(function (h) { return h.fp; }) : null;
    G.rotate(); toast('Devreler kapatılıyor, yeni rölelerle yeniden bağlanılıyor…', 4000);
    $('circBody').innerHTML = '<p class="note">⟳ Yeni devre kuruluyor… (Tor üzerinden 5–30 sn)</p>';
  };

  // ---------- arama ----------
  function renderCall(c) {
    S.st = S.st || {}; S.st.call = c; var ov = $('callOv');
    if (!c || c.state === 'idle') {
      if (!ov.classList.contains('hidden')) { ov.classList.add('hidden'); if (c && c.reason) toast(c.reason, 4000); }
      return;
    }
    ov.classList.remove('hidden');
    $('callName').textContent = c.name || '';
    var st = { calling: c.rtt >= 0 ? 'Çalıyor…' : 'Tor üzerinden bağlanılıyor…', ringing: '📞 Gelen arama', active: 'Görüşme ' + Math.floor((c.dur || 0) / 60) + ':' + ('0' + (c.dur || 0) % 60).slice(-2) }[c.state] || '';
    if (c.peerDown) st += ' · bağlantı koptu, yeniden kuruluyor…';
    $('callState').textContent = st;
    $('callQual').textContent = c.state === 'active' ? ('Gecikme ~' + (c.rtt >= 0 ? (c.rtt / 1000).toFixed(1) + ' sn' : '?') + ' · Kalite: ' + c.quality + (c.loss ? ' · kayıp %' + c.loss : '') + ' · ' + (c.codec === 'opus' ? 'Opus 16 kbit/sn' : 'G.711 64 kbit/sn')) : '';
    var b = $('callBtns'); b.innerHTML = '';
    function btn(t, cls, fn) { var x = document.createElement('button'); x.textContent = t; if (cls) x.className = cls; x.onclick = fn; b.appendChild(x); }
    if (c.state === 'ringing') { btn('✅ Cevapla', 'primary', function () { var r = J(G.answer(), {}); if (r.error) toast(r.error); }); btn('❌ Reddet', 'dangerBtn', function () { G.reject(); }); }
    else {
      if (c.state === 'active') { btn(c.muted ? '🎙 Sesi aç' : '🔇 Sessiz', '', function () { G.mute(!c.muted); }); btn(c.speaker ? '📱 Ahize' : '🔊 Hoparlör', '', function () { G.speaker(!c.speaker); }); }
      btn('📕 Kapat', 'dangerBtn', function () { G.hangup(); });
    }
  }

  // ---------- menüler ----------
  function chatMenu() {
    var c = S.contact; if (!c) return;
    sheet(c.name, [
      { label: '📞 Sesli ara (Tor)', fn: doCall },
      { label: '⏱ Kaybolan mesajlar: ' + ttlText(c.ttl), fn: ttlMenu },
      { label: '🔐 Güvenlik numarası / doğrula', fn: safety },
      { label: '✎ Takma adı değiştir', fn: function () { prompt2('Takma ad', 'Örn. Mehmet', c.alias, 'Kaydet', function (v) { G.contactAction(c.cid, 'rename', v); loadHeader(); }); } },
      { label: '📋 Kişinin kimliğini kopyala', fn: function () { G.copyText(c.id); toast('Kopyalandı (60 sn sonra panodan silinir).'); } },
      { label: '🧹 Sohbeti bu telefondan temizle', fn: function () { if (confirm('Bu sohbetteki tüm mesajlar ve dosyalar bu telefondan silinsin mi?')) { G.contactAction(c.cid, 'clear', ''); loadChat(true); } } },
      { label: '⛔ Engelle', fn: function () { if (confirm('Bu kişi engellensin mi? Size bağlanamaz, sohbet silinir.')) { G.contactAction(c.cid, 'block', ''); leaveChat(); } } },
      { label: '🗑 Kişiyi sil', fn: function () { if (confirm('Kişi ve tüm sohbet silinsin mi?')) { G.contactAction(c.cid, 'remove', ''); leaveChat(); } } },
      { label: 'Kapat' }
    ]);
  }
  function ttlMenu() {
    var opts = [[0, 'Kapalı'], [30, '30 saniye'], [300, '5 dakika'], [3600, '1 saat'], [86400, '1 gün'], [604800, '1 hafta']];
    sheet('Kaybolan mesajlar', [{ html: '<p class="small muted">Yeni mesajlar, okunduktan (gelen) / iletildikten (giden) sonra bu süre dolunca iki telefondan da silinir. Karşı tarafa da bildirilir.</p>' }].concat(opts.map(function (o) {
      return { label: (S.contact.ttl === o[0] ? '● ' : '○ ') + o[1], fn: function () { G.contactAction(S.cur, 'ttl', String(o[0])); setTimeout(function () { loadChat(true); }, 200); } };
    })).concat([{ label: 'Vazgeç' }]));
  }
  function safety() {
    var c = S.contact;
    sheet('Güvenlik numarası', [
      { html: '<p class="small">Bu numara sizin ve ' + esc(c.name) + ' kimliklerinden hesaplanır; iki telefonda da <b>aynı</b> görünmelidir. Yüz yüze ya da güvendiğiniz başka bir kanaldan karşılaştırın.</p><div class="safety mono">' + esc(c.safety) + '</div><p class="small muted">Kişinin kimliği:<br><span class="mono" style="word-break:break-all">' + esc(c.id) + '</span></p>' },
      { label: c.verified ? '✖ Doğrulama işaretini kaldır' : '✔ Karşılaştırdım, aynı — doğrulandı olarak işaretle', cls: c.verified ? '' : 'primary', fn: function () { G.contactAction(c.cid, 'verify', c.verified ? '0' : '1'); loadChat(true); } },
      { label: 'Kapat' }
    ]);
  }
  function doCall() {
    if (!S.contact || S.contact.state !== 'ok') { toast('Arama için bağlantının iki tarafça onaylanmış olması gerekir.'); return; }
    var r = J(G.call(S.cur), {}); if (r.error) toast(r.error, 4000);
  }

  // ---------- ayarlar ----------
  function renderSettings() {
    var s = J(G.settings(), {});
    $('setRotate').value = String(s.rotateMin); $('setLock').value = String(s.lockSec);
    $('setRead').checked = !!s.readReceipts; $('setBoot').checked = !!s.bootOnline; $('setWipeFail').checked = !!s.wipeOnFail; $('setBio').checked = !!s.bio;
    $('setBio').disabled = !(S.st && S.st.bio);
    $('battState').textContent = s.battery ? '(muaf ✔)' : '(açık)';
    $('verInfo').textContent = 'Turan Asistan ' + G.version() + ' · Tor ' + ((S.st && S.st.tor && S.st.tor.version) || '') + ' · Ses: ' + (S.st && S.st.opus ? 'Opus' : 'G.711');
  }
  $('setRotate').onchange = function () { G.setSetting('rotateMin', this.value); toast(this.value === '0' ? 'Otomatik devre değişimi kapatıldı.' : 'Devre her ' + this.value + ' dakikada bir değişecek.'); };
  $('setLock').onchange = function () { G.setSetting('lockSec', this.value); };
  $('setRead').onchange = function () { G.setSetting('readReceipts', this.checked ? '1' : '0'); };
  $('setBoot').onchange = function () { G.setSetting('bootOnline', this.checked ? '1' : '0'); };
  $('setWipeFail').onchange = function () { G.setSetting('wipeOnFail', this.checked ? '1' : '0'); if (this.checked) toast('Dikkat: 10 hatalı PIN denemesinde tüm Gizli Hat verileri silinir.', 4500); };
  $('setBio').onchange = function () { G.setSetting('bio', this.checked ? '1' : '0'); };

  // ---------- eylemler ----------
  var ACT = {
    close: function () { G.close(); },
    help: function () { S.helpFrom = S.view; show('vHelp'); $('vHelp').querySelector('.scroll').scrollTop = 0; },
    helpBack: function () { show(S.helpFrom || 'vHome'); },
    settings: function () { renderSettings(); show('vSettings'); },
    home: function () { show('vHome'); renderHome(); },
    back: leaveChat,
    copyId: function () { G.copyId(); toast('Kimlik kopyalandı. Güvenlik için 60 sn sonra panodan silinecek.'); },
    shareId: function () { G.shareId(); },
    showQr: function () { $('imgView').src = G.qr(); $('imgCap').textContent = 'Karşı taraf bu QR kodu Gizli Hat › Kişi ekle › QR tara ile okutabilir.'; $('imgOv').classList.remove('hidden'); },
    closeImg: function () { $('imgOv').classList.add('hidden'); $('imgView').src = ''; },
    add: function () { addContact(''); },
    attach: function () { G.pickFile(S.cur); },
    call: doCall,
    chatMenu: chatMenu,
    safety: function () { if (S.contact) safety(); },
    circuit: function () { pollCircuit(); renderCirc(); $('circOv').classList.remove('hidden'); },
    closeCirc: function () { $('circOv').classList.add('hidden'); },
    battery: function () { G.battery(); },
    changePin: function () { startPin('old'); },
    panic: function () {
      prompt2('Panik silme', 'Onay için SİL yazın', '', '🚨 Kalıcı olarak sil', function (v) {
        if (v.trim().toLocaleUpperCase('tr-TR') !== 'SİL') { toast('Onay metni hatalı; hiçbir şey silinmedi.'); return; }
        G.panic('SİL');
      }, false, 'dangerBtn');
    }
  };
  document.addEventListener('click', function (e) {
    var t = e.target.closest('[data-act]'); if (!t) return;
    if (t.id === 'imgOv' && e.target !== t && e.target.id !== 'imgView') return;
    var f = ACT[t.dataset.act]; if (f) { e.preventDefault(); f(); }
  });
  $('circOv').onclick = function (e) { if (e.target === $('circOv')) $('circOv').classList.add('hidden'); };

  // ---------- yerel olaylar ----------
  window.__gz = function (type, d) {
    d = d || {};
    switch (type) {
      case 'tor': if (S.st) { renderTor(d, S.st.online); } break;
      case 'contacts': if (S.view === 'vHome') renderHome(); else if (S.view === 'vChat') loadHeader(); break;
      case 'msgs': if (S.view === 'vChat' && (d.cid === S.cur || d.cid === -1)) loadChat(false); else if (S.view === 'vHome') renderHome(); break;
      case 'presence': if (S.view === 'vChat' && d.cid === S.cur) { loadHeader(); if (d.online) setTimeout(pollCircuit, 800); } else if (S.view === 'vHome') renderHome(); break;
      case 'rtt': S.rtt[d.cid] = d.rtt; break;
      case 'rotated': S.st && (S.st.lastRotate = d.at); setTimeout(pollCircuit, 3000); setTimeout(pollCircuit, 8000); setTimeout(pollCircuit, 15000); if (d.manual) toast('Tor devresi değiştirildi (' + d.closed + ' devre kapatıldı). Yeni röleler kuruluyor.', 4000); break;
      case 'call': renderCall(d); break;
      case 'progress': S.progress[d.mid] = Math.round(100 * d.done / Math.max(1, d.size)); break;
      case 'error': if (!d.cid || d.cid === S.cur) toast(d.text, 4500); break;
    }
  };
  window.__gzResume = function () { refresh(); };
  window.__gzPause = function () {};
  window.__gzWiped = function () { S.cur = 0; S.view = ''; ['sheet', 'circOv', 'callOv', 'imgOv'].forEach(function (x) { $(x).classList.add('hidden'); }); refresh(); };
  window.__gzBack = function () {
    var ovs = ['sheet', 'circOv', 'imgOv']; for (var i = 0; i < ovs.length; i++) if (!$(ovs[i]).classList.contains('hidden')) { $(ovs[i]).classList.add('hidden'); return true; }
    if (S.view === 'vChat') { leaveChat(); return true; }
    if (S.view === 'vSettings' || S.view === 'vHelp') { show(S.view === 'vHelp' ? (S.helpFrom || 'vHome') : 'vHome'); return true; }
    return false;
  };
  setInterval(function () { if (S.st && S.st.call && S.st.call.state !== 'idle') renderCall(J(G.callStatus(), {})); }, 1000);
  refresh();
})();
