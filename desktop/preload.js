// Turan Asistan – masaüstü preload. Web arayüzüne yalnızca salt-okunur sürüm bilgisi verilir.
// Android köprüsü (window.TuranNative) bilinçli olarak YOKTUR; web arayüzü bu sayede Android'e özgü
// özellikleri gizler ve "Linux sürümünde desteklenmiyor" notunu gösterir.
const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('TuranDesktop', Object.freeze({ platform: process.platform, version: '1.5.0' }));
