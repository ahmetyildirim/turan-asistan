// Turan Asistan – Linux masaüstü sürümü (Electron). Web arayüzünü (../web → app-web/) app://turan/ altında sunar.
'use strict';
const { app, BrowserWindow, protocol, net, shell, session, Menu } = require('electron');
const path = require('path');
const { pathToFileURL } = require('url');

const WEB = path.join(__dirname, 'app-web');
const ORIGIN = 'app://turan';

// Kalıcı localStorage / service worker / fetch için güvenli, standart bir şema
protocol.registerSchemesAsPrivileged([{
  scheme: 'app',
  privileges: { standard: true, secure: true, supportFetchAPI: true, allowServiceWorkers: true, corsEnabled: true, stream: true }
}]);

app.setName('Turan Asistan');
if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1100, height: 820, minWidth: 360, minHeight: 500,
    title: 'Turan Asistan',
    icon: path.join(WEB, 'icons', 'icon-512.png'),
    backgroundColor: '#0f172a',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false
    }
  });
  // Dış bağlantılar (Vikipedi, Google Takvim, kaynaklar) sistem tarayıcısında açılır
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:|^mailto:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(ORIGIN + '/')) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); }
  });
  win.webContents.on('console-message', (e, ...a) => {
    // Electron 33+: tek parametreli olay nesnesi; eskiler: (level, message, line, sourceId)
    const level = e.level !== undefined ? e.level : a[0];
    const msg = e.message !== undefined ? e.message : a[1];
    if (process.env.TURAN_DEBUG || level === 'error' || level === 3) console.log('[web:' + level + '] ' + msg);
  });
  win.loadURL(ORIGIN + '/index.html');
  // Test kancası: TURAN_SCREENSHOT=/yol.png → yüklendikten sonra ekran görüntüsü alır ve (TURAN_QUIT=1 ise) çıkar
  if (process.env.TURAN_SCREENSHOT) win.webContents.once('did-finish-load', () => setTimeout(async () => {
    const img = await win.webContents.capturePage();
    require('fs').writeFileSync(process.env.TURAN_SCREENSHOT, img.toPNG());
    console.log('[test] ekran görüntüsü: ' + process.env.TURAN_SCREENSHOT);
    if (process.env.TURAN_QUIT) app.quit();
  }, 4000));
  win.on('closed', () => { win = null; });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  protocol.handle('app', (req) => {
    const u = new URL(req.url);
    let rel = decodeURIComponent(u.pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.normalize(path.join(WEB, rel));
    if (!file.startsWith(WEB + path.sep)) return new Response('Yasak', { status: 403 });
    return net.fetch(pathToFileURL(file).toString());
  });
  // Yalnız bildirim izni verilir; kamera/mikrofon/konum vb. reddedilir
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(perm === 'notifications'));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => perm === 'notifications');
  createWindow();
});

app.on('window-all-closed', () => app.quit());
