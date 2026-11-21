/* Ortak zamanlama ve metin yardımcıları (sayfa + service worker) */
(function (g) {
  var TZ = 'Europe/Istanbul';
  var TZ_OFFSET_MIN = 180; // Türkiye 2016'dan beri sabit UTC+3
  var DEFAULT_SCHEDULE = {
    wd: { arz: '08:30', not: '17:00', on: true },
    sa: { arz: '09:30', not: '12:00', on: true },
    su: { arz: '09:30', not: '12:00', on: false }
  };
  var ARZ_HEAD = 'Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle şu tarihli konuları arz ediyorum:';
  var ARZ_TAIL = 'Arz ederim.';
  var NOT_TEXT = 'Emir eri Turan, tekmil veriyorum. Komutanım, müsaadenizle gün sonu notlarını almak istiyorum. Arz ederim.';
  var WD = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

  function istParts(date) {
    var f = new Intl.DateTimeFormat('en-US', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short', hourCycle: 'h23' });
    var o = {};
    f.formatToParts(date || new Date()).forEach(function (p) { o[p.type] = p.value; });
    return { y: +o.year, m: +o.month, d: +o.day, h: +o.hour % 24, min: +o.minute, wd: WD[o.weekday] };
  }
  function dayKeyOf(wd) { return wd === 0 ? 'su' : wd === 6 ? 'sa' : 'wd'; }
  function ymd(p) { return p.y + '-' + String(p.m).padStart(2, '0') + '-' + String(p.d).padStart(2, '0'); }
  function slotEpoch(y, m, d, hhmm) {
    var hm = hhmm.split(':');
    return Date.UTC(y, m - 1, d, +hm[0], +hm[1]) - TZ_OFFSET_MIN * 60000;
  }
  // Bugünün (İstanbul) slotları: [{type:'arz'|'not', at:epoch, day:'YYYY-MM-DD'}]
  function slotsForDay(schedule, p) {
    var s = (schedule || DEFAULT_SCHEDULE)[dayKeyOf(p.wd)];
    if (!s || !s.on) return [];
    var out = [];
    ['arz', 'not'].forEach(function (t) { if (s[t]) out.push({ type: t, at: slotEpoch(p.y, p.m, p.d, s[t]), day: ymd(p) }); });
    return out;
  }
  // Önümüzdeki n gün içindeki slotlar
  function upcoming(schedule, days, from) {
    var now = from || Date.now(), out = [];
    for (var i = 0; i <= days; i++) {
      var p = istParts(new Date(now + i * 86400000));
      slotsForDay(schedule, p).forEach(function (s) { if (s.at > now) out.push(s); });
    }
    return out.sort(function (a, b) { return a.at - b.at; });
  }
  function fmtDate(iso) {
    return new Intl.DateTimeFormat('tr-TR', { timeZone: TZ, day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(iso));
  }
  // Son tarih bilgisi: {text, over}
  function dueInfo(it) {
    if (!it.due || !/^\d{4}-\d{2}-\d{2}$/.test(it.due)) return null;
    var today = ymd(istParts());
    return { text: fmtDate(it.due + 'T12:00:00+03:00'), over: it.due < today, today: it.due === today };
  }
  // Arz satırı: [ACİL] metin (son tarih: …)
  function itemLine(it) {
    var s = (it.prio === 'acil' ? '[ACİL] ' : '') + it.text, d = dueInfo(it);
    if (d) s += ' (son tarih: ' + d.text + (d.over ? ', süresi geçti' : '') + ')';
    return s;
  }
  // Açık işleri tarihe göre grupla (her grupta acil olanlar önce)
  function arzGroups(items) {
    var open = items.filter(function (i) { return i.status === 'open'; })
      .sort(function (a, b) { return a.created < b.created ? -1 : 1; });
    var groups = [];
    open.forEach(function (it) {
      var d = fmtDate(it.created), last = groups[groups.length - 1];
      if (!last || last.date !== d) groups.push(last = { date: d, items: [], urgent: [] });
      (it.prio === 'acil' ? last.urgent : last.items).push(itemLine(it));
    });
    groups.forEach(function (g) { g.items = g.urgent.concat(g.items); delete g.urgent; });
    return groups;
  }
  function arzPlain(items) {
    var g2 = arzGroups(items);
    if (!g2.length) return '';
    var lines = [ARZ_HEAD];
    g2.forEach(function (g) { lines.push(g.date + ' tarihli:'); g.items.forEach(function (t) { lines.push('• ' + t); }); });
    lines.push(ARZ_TAIL);
    return lines.join('\n');
  }
  g.Sched = { TZ: TZ, DEFAULT_SCHEDULE: DEFAULT_SCHEDULE, ARZ_HEAD: ARZ_HEAD, ARZ_TAIL: ARZ_TAIL, NOT_TEXT: NOT_TEXT,
    istParts: istParts, ymd: ymd, slotsForDay: slotsForDay, upcoming: upcoming, fmtDate: fmtDate, arzGroups: arzGroups, arzPlain: arzPlain, itemLine: itemLine, dueInfo: dueInfo, slotEpoch: slotEpoch, dayKeyOf: dayKeyOf };
})(typeof self !== 'undefined' ? self : window);
