/* Turan Asistan – service worker */
importScripts('sched.js');
var VERSION = 'eet-v6';
var SHELL = ['./', './index.html', './style.css', './app.js', './sched.js', './comm.js', './manifest.json',
  './icons/icon-192.png', './icons/icon-512.png', './icons/icon-maskable-512.png', './icons/apple-touch-icon.png'];
var DATA_CACHE = 'eet-data';

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(VERSION).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== VERSION && k !== DATA_CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  if (e.request.method !== 'GET') return;
  var url = new URL(e.request.url);
  if (url.origin !== location.origin) return;
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then(function (hit) {
    var net = fetch(e.request).then(function (res) {
      if (res && res.ok) { var copy = res.clone(); caches.open(VERSION).then(function (c) { c.put(e.request, copy); }); }
      return res;
    }).catch(function () { return hit; });
    return hit || net;
  }));
});

function readJSON(key) {
  return caches.open(DATA_CACHE).then(function (c) { return c.match(key); })
    .then(function (r) { return r ? r.json() : null; }).catch(function () { return null; });
}
function writeJSON(key, obj) {
  return caches.open(DATA_CACHE).then(function (c) { return c.put(key, new Response(JSON.stringify(obj), { headers: { 'Content-Type': 'application/json' } })); });
}

// Arka planda (tarayıcı izin verdiğinde) çalışan kontrol
function backgroundCheck() {
  return Promise.all([readJSON('./__state.json'), readJSON('./__sw_shown.json')]).then(function (r) {
    var state = r[0], shown = r[1] || {};
    if (!state) return;
    var now = Date.now(), p = Sched.istParts(new Date(now));
    var jobs = [];
    Sched.slotsForDay(state.settings && state.settings.schedule, p).forEach(function (s) {
      var key = s.type + ':' + s.day;
      var appShown = state.lastShown && state.lastShown[s.type] === s.day;
      if (now >= s.at && now - s.at < 4 * 3600000 && !shown[key] && !appShown) {
        if (s.type === 'arz') {
          var txt = Sched.arzPlain(state.items || []);
          if (!txt) return;
          jobs.push(self.registration.showNotification('Turan Asistan – Arz', { body: txt, tag: 'arz', icon: 'icons/icon-192.png', badge: 'icons/icon-192.png', data: { url: './index.html?view=arz' } }));
        } else {
          jobs.push(self.registration.showNotification('Turan Asistan – Not alma', { body: Sched.NOT_TEXT, tag: 'not', icon: 'icons/icon-192.png', badge: 'icons/icon-192.png', data: { url: './index.html?view=not' } }));
        }
        shown[key] = true;
      }
    });
    return Promise.all(jobs).then(function () { return writeJSON('./__sw_shown.json', shown); });
  });
}
self.addEventListener('periodicsync', function (e) { if (e.tag === 'turan-check') e.waitUntil(backgroundCheck()); });
self.addEventListener('message', function (e) { if (e.data === 'check') backgroundCheck(); });

self.addEventListener('notificationclick', function (e) {
  e.notification.close();
  var target = new URL((e.notification.data && e.notification.data.url) || './index.html', self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      if ('focus' in list[i]) { list[i].postMessage({ open: target }); return list[i].focus(); }
    }
    return self.clients.openWindow(target);
  }));
});
