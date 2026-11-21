package com.ahmet.emirerituran;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tor üzerinden sesli arama. Ses, oturumun uçtan uca şifreli akışında taşınır (Opus 16 kbit/sn; Opus yoksa G.711 μ-law).
 * Tor'da gecikme 0,5–3 sn olabilir; oynatma tamponu gecikmeyi ~1 sn ile sınırlar (gerekirse eski sesi atar).
 * Görüntülü arama eklenmedi: Tor'un bant genişliği ve gecikmesi buna uygun değil.
 */
final class GizliCall {
    private final GizliNet net;
    private final Context ctx;
    volatile String state = "idle"; // idle | calling | ringing | active
    volatile long cid = -1; volatile String callId, codec, endReason; volatile long startedAt, offeredAt;
    volatile double rtt = -1; volatile boolean muted = false, speaker = false, peerDown = false;
    private volatile long peerDownAt = 0;
    private JSONArray offered;
    private volatile boolean audioOn = false;
    private Thread capT, playT, decT;
    private final LinkedBlockingQueue<byte[]> rxPackets = new LinkedBlockingQueue<>(400);
    private final LinkedBlockingQueue<short[]> pcm = new LinkedBlockingQueue<>(400);
    private volatile int rxSeq = -1, rxLost = 0, rxGot = 0, txPkts = 0; private volatile long txBytes = 0;
    private Ringtone ringtone; private ToneGenerator tone; private PowerManager.WakeLock wl;

    GizliCall(GizliNet n) { net = n; ctx = n.ctx; }

    static boolean hasOpus() {
        try {
            boolean e = false, d = false;
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())
                for (String t : i.getSupportedTypes()) if ("audio/opus".equalsIgnoreCase(t)) { if (i.isEncoder()) e = true; else d = true; }
            return e && d;
        } catch (Exception ex) { return false; }
    }

    boolean wants(long c) { return !"idle".equals(state) && cid == c && !"ringing".equals(state); }
    long activeCid() { return ("active".equals(state) || "calling".equals(state)) ? cid : -1; }

    JSONObject status() {
        JSONObject o = GizliNet.j("state", state, "cid", cid, "codec", codec == null ? "" : codec, "muted", muted, "speaker", speaker, "peerDown", peerDown);
        try {
            if ("active".equals(state)) o.put("dur", (System.currentTimeMillis() - startedAt) / 1000);
            o.put("rtt", rtt < 0 ? -1 : Math.round(rtt));
            int tot = rxGot + rxLost; o.put("loss", tot > 20 ? Math.round(100.0 * rxLost / tot) : 0);
            o.put("quality", quality());
            if (endReason != null) o.put("reason", endReason);
            GizliStore.Contact k = net.st().contact(cid); if (k != null) o.put("name", k.name());
        } catch (Exception ignored) {}
        return o;
    }
    private String quality() {
        if (rtt < 0) return "ölçülüyor";
        int tot = rxGot + rxLost; double loss = tot > 20 ? (double) rxLost / tot : 0;
        if (rtt < 1200 && loss < 0.05) return "iyi";
        if (rtt < 2500 && loss < 0.15) return "orta";
        return "zayıf";
    }
    private void emit() { net.emit("call", status()); }

    // ---------- arayan taraf ----------
    synchronized String dial(final long c) {
        if (!"idle".equals(state)) return "Zaten bir arama sürüyor.";
        GizliStore.Contact k = net.st().contact(c); if (k == null || !k.ok()) return "Bu kişiyle arama yapılamaz (bağlantı onaylanmamış).";
        state = "calling"; cid = c; callId = GizliCrypto.hex(GizliCrypto.rand(6)); codec = null; endReason = null; rtt = -1; offeredAt = 0;
        lock(true); emit();
        net.kick(c);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while ("calling".equals(state) && cid == c) {
                if (offeredAt == 0 && net.session(c) != null) {
                    try {
                        if (net.sendAll(c, GizliNet.T_CALL, GizliNet.j("op", "offer", "id", callId, "codecs", codecs())) > 0) { offeredAt = System.currentTimeMillis(); ringback(true); emit(); net.ping(c); }
                    } catch (Exception ignored) {}
                }
                long now = System.currentTimeMillis();
                if (offeredAt == 0 && now - t0 > 120000) { end("Karşı tarafa Tor üzerinden ulaşılamadı.", false); break; }
                if (offeredAt > 0 && now - offeredAt > 60000) { end("Cevap yok.", true); break; }
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            }
        }, "gz-dial").start();
        return null;
    }
    private JSONArray codecs() { JSONArray a = new JSONArray(); if (hasOpus()) a.put("opus"); a.put("ulaw"); return a; }

    // ---------- sinyaller ----------
    synchronized void onSignal(GizliStore.Contact k, JSONObject o) {
        String op = o.optString("op"), id = o.optString("id");
        switch (op) {
            case "offer":
                if (!k.ok()) { send(k.id, "reject", id, null); return; }
                if (!"idle".equals(state)) { if (!(cid == k.id && id.equals(callId))) send(k.id, "busy", id, null); return; }
                state = "ringing"; cid = k.id; callId = id; offered = o.optJSONArray("codecs"); endReason = null; rtt = -1;
                ring(true); net.notifyUser("Gizli Hat: gelen arama", true); emit();
                new Thread(() -> { try { Thread.sleep(60000); } catch (InterruptedException ignored) {} synchronized (GizliCall.this) { if ("ringing".equals(state) && id.equals(callId)) end("Cevapsız arama.", true); } }, "gz-ringto").start();
                break;
            case "accept":
                if ("calling".equals(state) && id.equals(callId) && cid == k.id) { codec = o.optString("codec", "ulaw"); ringback(false); startAudio(); }
                break;
            case "reject": if (id.equals(callId)) end("Arama reddedildi.", false); break;
            case "busy": if (id.equals(callId)) end("Karşı taraf başka bir görüşmede.", false); break;
            case "end": if (id.equals(callId)) end("Karşı taraf görüşmeyi bitirdi.", false); break;
            default: break;
        }
    }
    /** Sinyali canlı tüm oturumlardan gönderir; oturum yoksa bağlantıyı tetikleyip 30 sn'ye kadar yeniden dener. */
    private void send(final long c, String op, String id, String codec) {
        final JSONObject o;
        try { o = GizliNet.j("op", op, "id", id); if (codec != null) o.put("codec", codec); } catch (Exception e) { return; }
        if (net.sendAll(c, GizliNet.T_CALL, o) > 0) return;
        net.kick(c);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 30000) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                if (net.sendAll(c, GizliNet.T_CALL, o) > 0) return;
            }
        }, "gz-sig").start();
    }

    synchronized String answer() {
        if (!"ringing".equals(state)) return "Gelen arama yok.";
        String pick = "ulaw";
        if (offered != null) for (int i = 0; i < offered.length(); i++) { String c = offered.optString(i); if ("opus".equals(c) && hasOpus()) { pick = "opus"; break; } }
        codec = pick; ring(false); net.cancelCallNote(); lock(true);
        send(cid, "accept", callId, codec);
        startAudio();
        return null;
    }
    synchronized void reject() { if ("ringing".equals(state)) { send(cid, "reject", callId, null); end("Reddedildi.", false); } }
    synchronized void hangup(String why) { if (!"idle".equals(state)) { send(cid, "end", callId, null); end("offline".equals(why) ? "Gizli Hat çevrimdışı oldu." : "Görüşme bitti.", false); } }

    private synchronized void end(String reason, boolean missed) {
        if ("idle".equals(state)) return;
        GizliNet.dlog("call end prev=" + state + " reason=" + reason + " stats=" + stats());
        long c = cid; String prev = state; long dur = "active".equals(state) ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
        state = "idle"; endReason = reason; stopAudio(); ring(false); ringback(false); net.cancelCallNote(); lock(false);
        GizliStore.Contact k = net.st().contact(c);
        if (k != null) net.sysMsg(k, "📞 " + ("active".equals(prev) ? "Sesli görüşme · " + (dur / 60) + " dk " + (dur % 60) + " sn" : reason));
        GizliService.callMode(ctx, false);
        net.emit("call", status()); cid = -1;
    }

    void peerLost(long c) {
        if (c != cid || "idle".equals(state)) return;
        if ("ringing".equals(state)) { end("Arayan bağlantıyı kaybetti.", true); return; }
        peerDown = true; peerDownAt = System.currentTimeMillis(); emit();
        new Thread(() -> {
            while (peerDown && cid == c && !"idle".equals(state)) {
                if (net.session(c) != null) { peerDown = false; emit(); return; }
                if (System.currentTimeMillis() - peerDownAt > 40000) { end("Tor bağlantısı koptu.", false); return; }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "gz-reconn").start();
    }
    void onRtt(long c, long r) { if (c == cid) rtt = rtt < 0 ? r : rtt * 0.7 + r * 0.3; }

    // ---------- ses ----------
    private synchronized void startAudio() {
        if (audioOn) return;
        GizliNet.dlog("call active codec=" + codec);
        state = "active"; startedAt = System.currentTimeMillis(); audioOn = true; rxSeq = -1; rxLost = 0; rxGot = 0; txPkts = 0; txBytes = 0;
        rxPackets.clear(); pcm.clear(); peerDown = false;
        GizliService.callMode(ctx, true);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) { am.setMode(AudioManager.MODE_IN_COMMUNICATION); am.setSpeakerphoneOn(speaker); }
        final boolean opus = "opus".equals(codec);
        capT = new Thread(() -> capture(opus), "gz-cap"); capT.start();
        decT = new Thread(() -> decode(opus), "gz-dec"); decT.start();
        playT = new Thread(this::play, "gz-play"); playT.start();
        new Thread(() -> { while (audioOn) { net.ping(cid); emit(); try { Thread.sleep(2000); } catch (InterruptedException e) { return; } } }, "gz-callping").start();
        emit();
    }
    private void stopAudio() {
        audioOn = false;
        for (Thread t : new Thread[]{capT, decT, playT}) if (t != null) t.interrupt();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) { am.setSpeakerphoneOn(false); am.setMode(AudioManager.MODE_NORMAL); }
        muted = false;
    }
    void setMuted(boolean m) { muted = m; emit(); }
    void setSpeaker(boolean s) { speaker = s; AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE); if (am != null) am.setSpeakerphoneOn(s); emit(); }

    private void capture(boolean opus) {
        int rate = opus ? 16000 : 8000, frame = rate / 50; AudioRecord rec = null; MediaCodec enc = null;
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frame * 2 * 10));
            if (AcousticEchoCanceler.isAvailable()) try { AcousticEchoCanceler.create(rec.getAudioSessionId()).setEnabled(true); } catch (Exception ignored) {}
            if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(rec.getAudioSessionId()).setEnabled(true); } catch (Exception ignored) {}
            rec.startRecording();
            if (opus) {
                MediaFormat f = MediaFormat.createAudioFormat("audio/opus", rate, 1); f.setInteger(MediaFormat.KEY_BIT_RATE, 16000); f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame * 2);
                enc = MediaCodec.createEncoderByType("audio/opus"); enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start();
            }
            short[] buf = new short[frame]; byte[] bb = new byte[frame * 2]; List<byte[]> frames = new ArrayList<>(); int seq = 0; long pts = 0;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (audioOn) {
                int n = 0; while (n < frame && audioOn) { int r = rec.read(buf, n, frame - n); if (r <= 0) break; n += r; }
                if (!audioOn) break;
                if (muted) java.util.Arrays.fill(buf, (short) 0);
                if (opus) {
                    ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf);
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) { ByteBuffer ib = enc.getInputBuffer(ii); ib.clear(); ib.put(bb); enc.queueInputBuffer(ii, 0, bb.length, pts, 0); pts += 20000; }
                    int oi;
                    while ((oi = enc.dequeueOutputBuffer(bi, 0)) >= 0) {
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) { ByteBuffer ob = enc.getOutputBuffer(oi); byte[] fr = new byte[bi.size]; ob.position(bi.offset); ob.get(fr); frames.add(fr); }
                        enc.releaseOutputBuffer(oi, false);
                    }
                } else {
                    byte[] u = new byte[frame]; for (int i = 0; i < frame; i++) u[i] = ulaw(buf[i]); frames.add(u);
                }
                if (frames.size() >= 3) { sendPacket(seq++, frames); frames.clear(); }
            }
        } catch (Exception e) {
            net.emit("error", GizliNet.j("cid", cid, "text", "Mikrofon açılamadı: " + e.getMessage()));
        } finally {
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
        }
    }
    private void sendPacket(int seq, List<byte[]> frames) {
        GizliNet.Session s = net.session(cid); if (s == null) return;
        int len = 9; for (byte[] f : frames) len += 2 + f.length;
        ByteBuffer b = ByteBuffer.allocate(len); b.putInt(callId.hashCode()); b.putInt(seq); b.put((byte) frames.size());
        for (byte[] f : frames) { b.putShort((short) f.length); b.put(f); }
        try { s.send(GizliNet.T_AUDIO, b.array()); txPkts++; txBytes += len; } catch (Exception e) { s.close(); }
    }
    void onAudio(long c, byte[] p) {
        if (c != cid || !audioOn || p.length < 9) return;
        ByteBuffer b = ByteBuffer.wrap(p); if (b.getInt() != callId.hashCode()) return;
        int seq = b.getInt();
        if (rxSeq >= 0 && seq > rxSeq + 1) rxLost += Math.min(50, seq - rxSeq - 1);
        if (seq > rxSeq) rxSeq = seq; rxGot++;
        rxPackets.offer(p);
    }
    private void decode(boolean opus) {
        MediaCodec dec = null; int outRate = opus ? 48000 : 8000;
        try {
            if (opus) {
                MediaFormat f = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
                ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
                head.put("OpusHead".getBytes("US-ASCII")).put((byte) 1).put((byte) 1).putShort((short) 312).putInt(16000).putShort((short) 0).put((byte) 0); head.flip();
                f.setByteBuffer("csd-0", head);
                f.setByteBuffer("csd-1", le8(312L * 1000000000L / 48000));
                f.setByteBuffer("csd-2", le8(80000000L));
                dec = MediaCodec.createDecoderByType("audio/opus"); dec.configure(f, null, null, 0); dec.start();
                outRate = 48000; playRate = 48000;
            } else playRate = 8000;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(); long pts = 0;
            while (audioOn) {
                byte[] p = rxPackets.poll(200, TimeUnit.MILLISECONDS); if (p == null) continue;
                ByteBuffer b = ByteBuffer.wrap(p); b.position(8); int cnt = b.get() & 255;
                for (int i = 0; i < cnt; i++) {
                    int l = b.getShort() & 0xffff; byte[] fr = new byte[l]; b.get(fr);
                    if (!opus) { short[] s = new short[l]; for (int k = 0; k < l; k++) s[k] = unulaw(fr[k]); pcm.offer(s); continue; }
                    int ii = dec.dequeueInputBuffer(20000);
                    if (ii >= 0) { ByteBuffer ib = dec.getInputBuffer(ii); ib.clear(); ib.put(fr); dec.queueInputBuffer(ii, 0, l, pts, 0); pts += 20000; }
                    int oi;
                    while ((oi = dec.dequeueOutputBuffer(bi, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { playRate = dec.getOutputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE); continue; }
                        if (oi < 0) continue;
                        if (bi.size > 0) { ByteBuffer ob = dec.getOutputBuffer(oi); ob.position(bi.offset); ob.limit(bi.offset + bi.size); short[] s = new short[bi.size / 2]; ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); pcm.offer(s); }
                        dec.releaseOutputBuffer(oi, false);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) { net.emit("error", GizliNet.j("cid", cid, "text", "Ses çözücü hatası: " + e.getMessage()));
        } finally { try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {} }
    }
    private volatile int playRate = 16000;
    private static ByteBuffer le8(long v) { ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN); b.putLong(v); b.flip(); return b; }
    private void play() {
        AudioTrack t = null;
        try {
            Thread.sleep(150);
            int rate = playRate;
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(min * 2, rate / 5 * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            t.play();
            boolean primed = false;
            while (audioOn) {
                int buffered = 0; for (short[] s : pcm) buffered += s.length;
                int ms = buffered * 1000 / Math.max(1, playRate);
                if (!primed) { if (ms < 300) { Thread.sleep(20); continue; } primed = true; }
                if (ms > 1000) { while (ms > 350) { short[] d = pcm.poll(); if (d == null) break; ms -= d.length * 1000 / Math.max(1, playRate); } }
                short[] s = pcm.poll(200, TimeUnit.MILLISECONDS);
                if (s == null) { primed = false; continue; }
                t.write(s, 0, s.length);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) { net.emit("error", GizliNet.j("cid", cid, "text", "Hoparlör açılamadı: " + e.getMessage()));
        } finally { try { if (t != null) { t.stop(); t.release(); } } catch (Exception ignored) {} }
    }

    JSONObject stats() { return GizliNet.j("txPkts", txPkts, "txBytes", txBytes, "rxGot", rxGot, "rxLost", rxLost, "rtt", Math.round(rtt), "codec", codec == null ? "" : codec); }

    // G.711 μ-law
    private static byte ulaw(short s) {
        int sign = (s >> 8) & 0x80; int v = sign != 0 ? -s : s; if (v > 32635) v = 32635; v += 0x84;
        int exp = 7; for (int m = 0x4000; (v & m) == 0 && exp > 0; m >>= 1) exp--;
        int mant = (v >> (exp + 3)) & 0x0F; return (byte) ~(sign | (exp << 4) | mant);
    }
    private static short unulaw(byte b) {
        int u = ~b & 0xff; int sign = u & 0x80, exp = (u >> 4) & 7, mant = u & 0x0F;
        int v = ((mant << 3) + 0x84) << exp; v -= 0x84; return (short) (sign != 0 ? -v : v);
    }

    // ---------- zil / kilit ----------
    private void ring(boolean on) {
        try {
            if (on) { if (ringtone == null) ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)); if (ringtone != null) ringtone.play(); }
            else if (ringtone != null) { ringtone.stop(); ringtone = null; }
        } catch (Exception ignored) {}
    }
    private void ringback(boolean on) {
        try {
            if (on) { if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60); tone.startTone(ToneGenerator.TONE_SUP_RINGTONE); }
            else if (tone != null) { tone.stopTone(); tone.release(); tone = null; }
        } catch (Exception ignored) {}
    }
    private void lock(boolean on) {
        try {
            if (on) { if (wl == null) { PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE); wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "turan:gizli-arama"); wl.acquire(3 * 3600 * 1000L); } }
            else if (wl != null) { if (wl.isHeld()) wl.release(); wl = null; }
        } catch (Exception ignored) {}
    }
}
