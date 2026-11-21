/* Turan Asistan – sesli arama / mesaj / WhatsApp / X komut çözümleyici (DOM'suz, test edilebilir) */
(function (root) {
  'use strict';
  function lower(s) { return String(s || '').replace(/İ/g, 'i').replace(/I/g, 'ı').toLocaleLowerCase('tr-TR'); }
  function fold(s) {
    return lower(s).replace(/ı/g, 'i').replace(/ş/g, 's').replace(/ğ/g, 'g').replace(/ü/g, 'u').replace(/ö/g, 'o').replace(/ç/g, 'c')
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
  }
  var BACK = 'aıou', FRONT = 'eiöü', VOW = 'aeıioöuü';
  function lastVowel(w) { w = lower(w); for (var i = w.length - 1; i >= 0; i--) if (VOW.indexOf(w[i]) >= 0) return w[i]; return 'e'; }
  function endsVowel(w) { w = lower(w); return VOW.indexOf(w[w.length - 1]) >= 0; }
  /** Ad + yönelme eki: Mehmet Yılmaz'a, Ayşe'ye */
  function dative(name) {
    name = String(name).trim(); var v = lastVowel(name), e = BACK.indexOf(v) >= 0 ? 'a' : 'e';
    return name + "'" + (endsVowel(name) ? 'y' : '') + e;
  }
  /** 1. tekil şahıs ek-fiili: evde → evdeyim, yolda → yoldayım */
  function copula(w) {
    var v = lastVowel(w), x = /[aı]/.test(v) ? 'ı' : /[ei]/.test(v) ? 'i' : /[ou]/.test(v) ? 'u' : 'ü';
    return w + (endsVowel(w) ? 'y' : '') + x + 'm';
  }
  function cap(t) { t = String(t || '').trim(); return t.charAt(0).toLocaleUpperCase('tr-TR') + t.slice(1); }

  // Klavyesiz yazımlar
  var FIX = [[/\bgonder/gi, 'gönder'], [/\boldugumu\b/gi, 'olduğumu'], [/\bolmadigimi\b/gi, 'olmadığımı'], [/\bsoyle\b/gi, 'söyle'], [/\bcaldir\b/gi, 'çaldır'],
    [/\bzaman tunel/gi, 'zaman tünel'], [/\boldugumu\b/gi, 'olduğumu'], [/\bolacagimi\b/gi, 'olacağımı'], [/\bozetle/gi, 'özetle'], [/\barsiv/gi, 'arşiv'], [/\buzerinden\b/gi, 'üzerinden'], [/\bmisin\b/gi, 'mısın']];

  var WA_RE = /\s*\b(?:whats\s?app|whatsup|vatsap|vatsapp|watsap|wp|wapp)(?:['’]?(?:tan|dan|ten|den|la|le|ile|ın|ten))?(?:\s+(?:ile|üzerinden|üstünden|mesajı|mesaj))?\b/i;
  var SMS_RE = /\s*\b(?:sms|kısa mesaj|kisa mesaj)(?:['’]?(?:le|la|ile|den|dan))?(?:\s+(?:ile|olarak))?(?=\s)/i;
  var TRIG = '(?:(?:bir\\s+)?(?:mesaj(?:ı)?|sms(?:\'?i)?|ileti|not)\\s+)?(?:at|atar mısın|atın|atar mısınız|gönder|gönderir mısın|gönderin|yolla|yollar mısın|yaz|yazar mısın|yazın|ilet|söyle|haber ver|bildir)';
  var COLON_RE = new RegExp('^(.+?)\\s+' + TRIG + '\\s*[:,]\\s*(.+)$', 'i');
  var END_RE = new RegExp('^(.+?)\\s+' + TRIG + '$', 'i');
  var CALL_END = /^(.+?)\s+(?:ara|arayın|arar mısın|arar mısınız|arayalım|bir ara|telefonla ara|telefon et|telefon aç|çaldır|ile görüş(?:türt)?|la görüş|le görüş)$/i;
  var CALL_START = /^(?:ara|arama yap|telefon et|çaldır)\s*[:,]?\s+(.+)$/i;
  var DAT_APOS = /^(.+)['’](?:y?[ae]|n[ae])$/i;
  var DAT_BARE = /^(.+?)(?:ye|ya|ne|na|e|a)$/i;

  function parse(raw) {
    var t = String(raw || '').trim().replace(/[.!?]+$/, '').replace(/\s+/g, ' ');
    if (!t) return null;
    FIX.forEach(function (f) { t = t.replace(f[0], f[1]); });
    var l = lower(t), m;
    // X / Twitter
    if (/(twitter|tivitır|tweeter|x zaman|zaman tünel|x akış|^x['’]?(i|ı|te|deki)\s)/.test(l) && /(özetle|özet|neler var|gündem|oku)/.test(l)) return { type: 'x', auto: true };
    if (/^(twitter|x)['’]?(ı|i)? aç$|^(twitter|x zaman tüneli)'?(ni|ı)? aç$/.test(l)) return { type: 'x', auto: false };
    // Arşiv
    if ((m = t.match(/^(?:arşive|arşivden|arşivde|arşiv botuna)\s+(?:sor|ara|bak)\s*[:,]?\s+(.+)$/i))) return { type: 'archive', bot: '', q: m[1] };
    if ((m = t.match(/^(.+?)\s+(?:botuna|botundan|arşivine|arşivinden)\s+(?:sor|ara|bak)\s*[:,]?\s+(.+)$/i))) return { type: 'archive', bot: m[1], q: m[2] };
    // Mesaj
    var channel = null, s = t;
    if (WA_RE.test(s) && /(yaz|mesaj|gönder|at|yolla|ilet|söyle|bildir|haber)/i.test(s)) { channel = 'wa'; s = s.replace(WA_RE, ' ').replace(/\s+/g, ' ').trim(); }
    else if (SMS_RE.test(' ' + s + ' ')) { channel = 'sms'; s = (' ' + s + ' ').replace(SMS_RE, ' mesaj').replace(/\bmesaj\s+mesaj/i, 'mesaj').replace(/\s+/g, ' ').trim(); }
    var head = null, body = null;
    if ((m = s.match(COLON_RE))) { head = m[1]; body = m[2]; }
    else if ((m = s.match(END_RE))) head = m[1];
    if (head) {
      var toks = head.split(' '), opts = [];
      for (var i = 0; i < Math.min(3, toks.length); i++) {
        var tk = toks[i];
        if (DAT_APOS.test(tk)) { opts = [{ i: i, strong: true }]; break; } // kesme işaretli yönelme eki en güçlü ipucu
        if (DAT_BARE.test(tk) && tk.length > 2) opts.push({ i: i, strong: false });
      }
      if (!opts.length) opts.push({ i: 0, strong: false });
      var who = [], seen = {};
      opts.forEach(function (o) {
        var w = toks.slice(0, o.i + 1).join(' '), b = body != null ? body : toks.slice(o.i + 1).join(' ');
        if (seen[w]) return; seen[w] = 1;
        who.push({ who: w, body: convertBody(b) });
      });
      var w0 = fold(who[0].who);
      if (/^(bana|bize|kendime|onu|bunu|not|hatirlat)$/.test(w0)) return null;
      var strong = opts[0].strong || body != null || channel != null || /\b(mesaj|sms|ileti)/.test(lower(s));
      return { type: 'msg', who: who[0].who, body: who[0].body, options: who, channel: channel, strong: strong };
    }
    // Arama
    if ((m = t.match(CALL_END)) || (m = t.match(CALL_START))) {
      var w1 = m[1].trim();
      if (/internet|web|google|vikipedi|wikipedia/i.test(w1) || w1.split(' ').length > 4) return null;
      return { type: 'call', who: w1, strong: true };
    }
    return null;
  }

  /** Dolaylı anlatımı doğrudan mesaja çevir: "evde olduğumu" → "Evdeyim" */
  function convertBody(b) {
    b = String(b || '').trim().replace(/^["“”'‘’«]+|["“”'‘’»]+$/g, '').replace(/\s+diye$/i, '').replace(/^["“”'‘’«]+|["“”'‘’»]+$/g, '').trim();
    if (!b) return '';
    var m;
    if ((m = b.match(/^(.*?)(\S+)\s+olmadığımı$/i))) return cap(m[1] + m[2] + ' değilim');
    if ((m = b.match(/^(.*?)(\S+)\s+olduğumu$/i))) return cap(m[1] + copula(m[2]));
    if ((m = b.match(/^(.*?)(\S+)\s+olacağımı$/i))) return cap(m[1] + m[2] + ' olacağım');
    var words = b.split(' '), last = words[words.length - 1], nl = null;
    if ((m = last.match(/^(.+)(a[cç]a[ğg]|e[cç]e[ğg])([ıi]m[ıi])$/i))) nl = m[1] + m[2] + m[3].slice(0, -1);   // geleceğimi → geleceğim
    else if ((m = last.match(/^(.+[dtkg])([ıiuü][ğg])([ıiuü]m[ıiuü])$/i))) nl = m[1].replace(/[kg]$/, 't') + m[2][0] + 'm'; // çıktığımı → çıktım
    else if ((m = last.match(/^(.+)m(a|e)s(ı|i)n(ı|i)$/i))) nl = m[1] + 's' + (m[2] === 'a' ? 'ın' : 'in'); // aramasını → arasın
    else if ((m = last.match(/^(.+)(mekte|makta)\s*$/i))) nl = null;
    if (nl) { words[words.length - 1] = nl; b = words.join(' '); }
    return cap(b);
  }

  /** Rehberde aranacak ad adayları (ekleri soyulmuş, sadeleştirilmiş). */
  function nameCandidates(who) {
    var toks = String(who || '').trim().split(/\s+/), lastT = toks.pop() || '', base = toks.join(' '), out = [];
    function add(x) { x = fold((base ? base + ' ' : '') + x); if (x && out.indexOf(x) < 0) out.push(x); }
    var ap = lastT.match(/^(.+?)['’]/);
    if (ap) { add(ap[1]); return out; }
    add(lastT);
    var f = fold(lastT), suf = ['yla', 'yle', 'ye', 'ya', 'yi', 'yı', 'yu', 'yü', 'ne', 'na', 'ni', 'nı', 'nu', 'nü', 'la', 'le', 'e', 'a', 'i', 'ı', 'u', 'ü'];
    suf.forEach(function (sx) {
      var sf = fold(sx);
      if (f.length - sf.length >= 2 && f.slice(-sf.length) === sf) {
        var st = f.slice(0, -sf.length); add(st);
        var soft = { g: 'k', d: 't', b: 'p' }[st.slice(-1)]; if (soft) add(st.slice(0, -1) + soft);
      }
    });
    return out;
  }

  function lev(a, b) {
    if (Math.abs(a.length - b.length) > 2) return 9;
    var d = [], i, j; for (i = 0; i <= a.length; i++) d[i] = [i];
    for (j = 0; j <= b.length; j++) d[0][j] = j;
    for (i = 1; i <= a.length; i++) for (j = 1; j <= b.length; j++)
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    return d[a.length][b.length];
  }
  function scoreName(q, name) {
    var n = fold(name); if (!n || !q) return 0;
    if (n === q) return 100;
    var nt = n.split(' '), qt = q.split(' ');
    if (qt.length === 1) {
      if (nt[0] === q) return 85;
      if (nt.indexOf(q) >= 0) return 75;
      if (q.length >= 3 && nt.some(function (x) { return x.indexOf(q) === 0; })) return 55;
      if (q.length >= 4 && nt.some(function (x) { return lev(q, x) <= 1; })) return 45;
      if (q.length >= 7 && nt.some(function (x) { return lev(q, x) <= 2; })) return 35;
      return 0;
    }
    var all = qt.every(function (x) { return nt.some(function (y) { return y === x; }); });
    if (all) return 92;
    var pre = qt.every(function (x) { return nt.some(function (y) { return y.indexOf(x) === 0 || (x.length >= 4 && lev(x, y) <= 1); }); });
    if (pre) return 70;
    if (n.indexOf(q) === 0) return 60;
    return 0;
  }
  /** contacts: [{name, nums}] → en iyi eşleşmeler [{c, score}] (belirsizse birden fazla) */
  function matchContacts(contacts, who) {
    var cands = nameCandidates(who), res = [];
    (contacts || []).forEach(function (c) {
      var best = 0;
      cands.forEach(function (q, k) { var s = scoreName(q, c.name) - (k === 0 && cands.length > 1 && !/['’]/.test(who) ? 1 : 0); if (s > best) best = s; });
      if (best >= 35) res.push({ c: c, score: best });
    });
    res.sort(function (a, b) { return b.score - a.score || a.c.name.localeCompare(b.c.name, 'tr'); });
    if (!res.length) return [];
    var top = res[0].score;
    return res.filter(function (r) { return r.score >= top - 10; }).slice(0, 8);
  }
  /** Mesaj/arama için uygun numaralar */
  function pickNumbers(c, kind) {
    var nums = (c.nums || []).slice();
    var prim = nums.filter(function (n) { return n.p; });
    if (prim.length === 1) return prim;
    if (kind !== 'call') { var mob = nums.filter(function (n) { return n.t === 'mobile'; }); if (mob.length) nums = mob; }
    return nums;
  }
  function intl(num) {
    var s = String(num).trim(), plus = s.charAt(0) === '+', d = s.replace(/\D/g, '');
    if (plus) return d; if (d.indexOf('00') === 0) return d.slice(2);
    if (d.charAt(0) === '0' && d.length === 11) return '90' + d.slice(1);
    if (d.length === 10 && d.charAt(0) === '5') return '90' + d;
    return d;
  }
  function waUrl(num, text) { return 'https://api.whatsapp.com/send?phone=' + intl(num) + '&text=' + encodeURIComponent(text || ''); }

  var api = { parse: parse, convertBody: convertBody, nameCandidates: nameCandidates, matchContacts: matchContacts, scoreName: scoreName,
    pickNumbers: pickNumbers, dative: dative, copula: copula, fold: fold, intl: intl, waUrl: waUrl };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.TuranComm = api;
})(this);
