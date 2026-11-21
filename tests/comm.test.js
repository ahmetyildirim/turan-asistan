// Türkçe sesli komut çözümleme birim testleri: node tests/comm.test.js
const C = require('/workspace/emir-eri-turan/comm.js');
let fail = 0, n = 0;
function eq(name, got, exp) {
  n++; const g = JSON.stringify(got), e = JSON.stringify(exp);
  if (g !== e) { fail++; console.log('✗', name, '\n   got', g, '\n   exp', e); } else console.log('✓', name);
}
function P(s) { const r = C.parse(s); return r && { type: r.type, who: r.who, body: r.body, channel: r.channel === undefined ? undefined : r.channel }; }
// Arama
eq('Ahmet\'i ara', P("Ahmet'i ara"), { type: 'call', who: "Ahmet'i" });
eq('Ahmeti ara', P('Ahmeti ara'), { type: 'call', who: 'Ahmeti' });
eq('Mehmet Yılmaz\'ı ara', P("Mehmet Yılmaz'ı ara."), { type: 'call', who: "Mehmet Yılmaz'ı" });
eq('annemi arar mısın', P('annemi arar mısın'), { type: 'call', who: 'annemi' });
eq('ara Ayşe', P('Ara Ayşe'), { type: 'call', who: 'Ayşe' });
eq('Ayşe\'ye telefon et', P("Ayşe'ye telefon et"), { type: 'call', who: "Ayşe'ye" });
eq('internette ara değil', C.parse('ofis koltuğu fiyatlarını internette ara'), null);
eq('araştır değil', C.parse('Mehmet araştır'), null);
// SMS
eq('Mehmet\'e evde olduğumu mesaj at', P("Mehmet'e evde olduğumu mesaj at"), { type: 'msg', who: "Mehmet'e", body: 'Evdeyim', channel: null });
eq('Mehmete evde olduğumu mesaj at (kesmesiz)', P('Mehmete evde olduğumu mesaj at'), { type: 'msg', who: 'Mehmete', body: 'Evdeyim', channel: null });
eq('ASCII: Mehmete evde oldugumu mesaj gonder', P('Mehmete evde oldugumu mesaj gonder'), { type: 'msg', who: 'Mehmete', body: 'Evdeyim', channel: null });
eq('yolda olduğumu', P("Ayşe'ye yolda olduğumu yaz"), { type: 'msg', who: "Ayşe'ye", body: 'Yoldayım', channel: null });
eq('geç kalacağımı', P("Ali'ye toplantıya geç kalacağımı SMS at"), { type: 'msg', who: "Ali'ye", body: 'Toplantıya geç kalacağım', channel: 'sms' });
eq('çıktığımı', P("Annem'e yola çıktığımı söyle"), { type: 'msg', who: "Annem'e", body: 'Yola çıktım', channel: null });
eq('aramasını', P("Mehmet'e beni aramasını yaz"), { type: 'msg', who: "Mehmet'e", body: 'Beni arasın', channel: null });
eq('evde olmadığımı', P("Mehmet'e evde olmadığımı mesaj at"), { type: 'msg', who: "Mehmet'e", body: 'Evde değilim', channel: null });
eq('iki kelimelik ad', P("Mehmet Yılmaz'a 5 dakikaya geliyorum diye yaz"), { type: 'msg', who: "Mehmet Yılmaz'a", body: '5 dakikaya geliyorum', channel: null });
eq('iki nokta biçimi', P("Mehmet'e mesaj at: Akşam yemeğe geliyorum"), { type: 'msg', who: "Mehmet'e", body: 'Akşam yemeğe geliyorum', channel: null });
eq('tırnaklı', P('Mehmet\'e "evdeyim" yaz'), { type: 'msg', who: "Mehmet'e", body: 'Evdeyim', channel: null });
eq('boş metin', P("Mehmet'e mesaj at"), { type: 'msg', who: "Mehmet'e", body: '', channel: null });
// WhatsApp
eq('WhatsApp\'tan yaz', P("Mehmet'e WhatsApp'tan geliyorum yaz"), { type: 'msg', who: "Mehmet'e", body: 'Geliyorum', channel: 'wa' });
eq('whatsapp ile mesaj gönder', P("Mehmet'e whatsapp ile evde olduğumu mesaj gönder"), { type: 'msg', who: "Mehmet'e", body: 'Evdeyim', channel: 'wa' });
eq('WhatsApp başta', P("WhatsApp'tan Ayşe'ye toplantı bitti yaz"), { type: 'msg', who: "Ayşe'ye", body: 'Toplantı bitti', channel: 'wa' });
eq('whatsapptan ASCII', P('Mehmete whatsapptan geliyorum yaz'), { type: 'msg', who: 'Mehmete', body: 'Geliyorum', channel: 'wa' });
// Diğer komutlar karışmamalı
eq('bana söyle değil', C.parse('bana hava durumunu söyle'), null);
eq('not al değil', C.parse('Fatura ödemesi kontrolü tamamlandı'), null);
// X ve arşiv
eq('Twitter\'ı özetle', P("Twitter'ı özetle"), { type: 'x', who: undefined, body: undefined, channel: undefined });
eq('zaman tünelini özetle', C.parse('zaman tünelini özetle').type, 'x');
eq('arşive sor', C.parse('Arşive sor: sözleşme ne zaman bitiyor'), { type: 'archive', bot: '', q: 'sözleşme ne zaman bitiyor' });
eq('bot adıyla', C.parse('Hukuk botuna sor: ihtar süresi kaç gün'), { type: 'archive', bot: 'Hukuk', q: 'ihtar süresi kaç gün' });
// Ad adayları ve eşleştirme
eq('aday Mehmete', C.nameCandidates('Mehmete').slice(0, 2), ['mehmete', 'mehmet']);
eq('aday annemi', C.nameCandidates('annemi').includes('annem'), true);
eq('aday Burağı (yumuşama)', C.nameCandidates('Burağı').includes('burak'), true);
eq('aday Mehmet Yılmaz\'a', C.nameCandidates("Mehmet Yılmaz'a"), ['mehmet yilmaz']);
const book = [
  { name: 'Mehmet Yılmaz', nums: [{ n: '05321112233', t: 'mobile' }, { n: '02125556677', t: 'work' }] },
  { name: 'Mehmet Kaya', nums: [{ n: '05334445566', t: 'mobile' }] },
  { name: 'Ahmet Demir', nums: [{ n: '+905551234567', t: 'mobile' }] },
  { name: 'Annem', nums: [{ n: '05301234567', t: 'mobile' }] },
  { name: 'Ayşe Öztürk', nums: [{ n: '05441234567', t: 'mobile' }] },
  { name: 'İsmail Işık', nums: [{ n: '05001112233', t: 'mobile' }] }];
const names = (w) => C.matchContacts(book, w).map((r) => r.c.name);
eq('Mehmet → iki aday (seçim sorulur)', names("Mehmet'e"), ['Mehmet Kaya', 'Mehmet Yılmaz']);
eq('Mehmet Yılmaz → tek', names("Mehmet Yılmaz'a"), ['Mehmet Yılmaz']);
eq('Ahmeti → Ahmet Demir', names('Ahmeti'), ['Ahmet Demir']);
eq('annemi → Annem', names('annemi'), ['Annem']);
eq('Ayşeye (ASCII ayseye) → Ayşe', names('ayseye'), ['Ayşe Öztürk']);
eq('İsmail (I/İ) → İsmail Işık', names('ismaile'), ['İsmail Işık']);
eq('yazım hatası Mehmt → Mehmet', names('Mehmt').length, 2);
eq('bilinmeyen kişi', names('Zeynep'), []);
eq('mesaj için cep numarası seçilir', C.pickNumbers(book[0], 'sms').map((x) => x.n), ['05321112233']);
eq('arama için tüm numaralar', C.pickNumbers(book[0], 'call').length, 2);
// Biçim ve WhatsApp adresi
eq('yönelme eki', [C.dative('Mehmet Yılmaz'), C.dative('Ayşe'), C.dative('Annem'), C.dative('Ali')], ["Mehmet Yılmaz'a", "Ayşe'ye", "Annem'e", "Ali'ye"]);
eq('intl 0532', C.intl('0532 111 22 33'), '905321112233');
eq('intl +90', C.intl('+90 555 123 45 67'), '905551234567');
eq('intl 532', C.intl('5321112233'), '905321112233');
eq('waUrl', C.waUrl('0532 111 22 33', 'Evdeyim, 5 dk & geliyorum'), 'https://api.whatsapp.com/send?phone=905321112233&text=Evdeyim%2C%205%20dk%20%26%20geliyorum');
eq('güçlü/zayıf ipucu', [C.parse("Mehmet'e geliyorum yaz").strong, C.parse('rapor dosyasını müdüre yaz').strong, C.parse('Mehmete evde olduğumu mesaj at').strong], [true, false, true]);
eq('kesmeli ad önceki zayıf adayları ezer', C.parse("Ayşe Yılmaz'a geliyorum yaz").options.map((o) => o.who), ["Ayşe Yılmaz'a"]);
eq('kesmesiz çok adaylı', C.parse('Mehmete evde olduğumu yaz').options.map((o) => o.who), ['Mehmete', 'Mehmete evde']);
eq('ASCII çıktığımı', C.convertBody('yola ciktigimi'), 'Yola ciktim');
eq('ASCII geleceğimi', C.convertBody('aksam gec gelecegimi'), 'Aksam gec gelecegim');
eq('geleceğimi', C.convertBody('akşam geç geleceğimi'), 'Akşam geç geleceğim');
console.log(`\n${n - fail}/${n} geçti`);
process.exit(fail ? 1 : 0);
