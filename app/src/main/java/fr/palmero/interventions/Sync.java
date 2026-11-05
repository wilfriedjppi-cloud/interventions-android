package fr.palmero.interventions;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronisation directe entre appareils (téléphone ↔ tablette) sur le même Wi-Fi ou le point d'accès d'un des deux,
 * sans logiciel tiers ni Internet : chaque appareil ouvert sert le dossier « Gestion interventions » sur le réseau local
 * (protégé par le code de jumelage) et recopie dans les deux sens les fichiers modifiés depuis la dernière synchro.
 */
class Sync {
    static final String TYPE = "_bisync._tcp.", ROOT = "Gestion interventions";
    static final int PORT0 = 47900;
    final Context ctx;
    final Api api;
    final SharedPreferences sp;
    final AtomicBoolean busy = new AtomicBoolean(false);
    final ExecutorService pool = Executors.newCachedThreadPool();   // chaque demande a son fil : un transfert long ne bloque pas la saisie en direct
    final Map<String, String> found = new LinkedHashMap<>();   // appareils vus sur le réseau : nom -> hôte:port
    ServerSocket server;
    int port = 0;
    String token = "", dev = "", name = "";
    NsdManager nsd;
    NsdManager.RegistrationListener reg;
    NsdManager.DiscoveryListener disc;
    WifiManager.MulticastLock mlock;
    Runnable onRemote;
    java.util.function.Consumer<String> onLive;                // saisie de l'autre appareil reçue en direct
    final Set<String> liveHosts = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    final ExecutorService liveEx = Executors.newSingleThreadExecutor();   // envoi en direct : fil d'exécution à part, l'écran n'attend jamais
    java.util.function.Consumer<Integer> onPage;               // page mise à jour par l'autre appareil
    java.util.function.Consumer<File> onApk;                   // appli reçue de l'autre appareil                                         // l'autre appareil vient de finir une synchro avec celui-ci

    Sync(Context c, Api a) {
        ctx = c.getApplicationContext();
        api = a;
        sp = c.getSharedPreferences("bisync", Context.MODE_PRIVATE);
    }

    static String sha(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    File base() { return new File(api.root(), ROOT); }

    /* ---------- démarrage : serveur local + annonce sur le réseau ---------- */
    synchronized String start(String code, String devId, String devName) {
        token = code == null || code.trim().length() < 4 ? "" : sha("bi-sync|" + code.trim());
        sp.edit().putString("code", code == null ? "" : code).putString("dev", devId == null ? "" : devId).putString("name", devName == null ? "" : devName).apply();   // repris par WillRelais ouvert seul
        dev = devId == null ? "" : devId;
        name = devName == null ? "" : devName;
        if (token.isEmpty()) { stop(); return "code"; }
        if (server == null) {
            for (int p = PORT0; p < PORT0 + 6 && server == null; p++) {
                try { ServerSocket s = new ServerSocket(); s.setReuseAddress(true); s.bind(new InetSocketAddress(p)); server = s; port = p; } catch (Exception ignored) { }
            }
            if (server == null) return "port";
            final ServerSocket s = server;
            new Thread(() -> { while (!s.isClosed()) { try { final Socket c = s.accept(); pool.execute(() -> serve(c)); } catch (Exception e) { if (s.isClosed()) break; } } }, "bisync-srv").start();
        }
        announce();
        return "ok";
    }

    synchronized void stop() {
        try { if (nsd != null && reg != null) nsd.unregisterService(reg); } catch (Exception ignored) { }
        try { if (nsd != null && disc != null) nsd.stopServiceDiscovery(disc); } catch (Exception ignored) { }
        reg = null; disc = null;
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null; port = 0;
    }

    void announce() {
        try {
            if (nsd == null) nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && mlock == null) { mlock = wm.createMulticastLock("bisync"); mlock.setReferenceCounted(false); }
            if (mlock != null && !mlock.isHeld()) mlock.acquire();
            if (reg == null) {
                NsdServiceInfo si = new NsdServiceInfo();
                si.setServiceName("Interventions-" + (dev.length() > 8 ? dev.substring(dev.length() - 8) : dev));
                si.setServiceType(TYPE);
                si.setPort(port);
                reg = new NsdManager.RegistrationListener() {
                    public void onRegistrationFailed(NsdServiceInfo i, int e) { reg = null; }
                    public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                    public void onServiceRegistered(NsdServiceInfo i) { }
                    public void onServiceUnregistered(NsdServiceInfo i) { }
                };
                nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, reg);
            }
            if (disc == null) {
                disc = new NsdManager.DiscoveryListener() {
                    public void onStartDiscoveryFailed(String t, int e) { disc = null; }
                    public void onStopDiscoveryFailed(String t, int e) { }
                    public void onDiscoveryStarted(String t) { }
                    public void onDiscoveryStopped(String t) { }
                    public void onServiceLost(NsdServiceInfo i) { synchronized (found) { found.remove(i.getServiceName()); } }
                    public void onServiceFound(NsdServiceInfo i) {
                        if (i.getServiceName() == null || i.getServiceName().endsWith(dev.length() > 8 ? dev.substring(dev.length() - 8) : dev)) return;
                        try {
                            nsd.resolveService(i, new NsdManager.ResolveListener() {
                                public void onResolveFailed(NsdServiceInfo x, int e) { }
                                public void onServiceResolved(NsdServiceInfo x) {
                                    InetAddress h = x.getHost(); if (h == null) return;
                                    synchronized (found) { found.put(x.getServiceName(), h.getHostAddress() + ":" + x.getPort()); }
                                }
                            });
                        } catch (Exception ignored) { }
                    }
                };
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc);
            }
        } catch (Exception ignored) { }
    }

    /** Adresse de la box ou du téléphone qui fait point d'accès (appareil connecté à son Wi-Fi). */
    String gateway() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            int g = wm == null ? 0 : wm.getDhcpInfo().gateway;
            if (g == 0) return "";
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Exception e) { return ""; }
    }

    /* ---------- serveur : petit HTTP sur le réseau local ---------- */
    void serve(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] rq = line.split(" ");
            if (rq.length < 2) return;
            Map<String, String> h = new HashMap<>();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) { int i = l.indexOf(':'); if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim()); }
            String path = rq[1], q = "";
            int qi = path.indexOf('?'); if (qi >= 0) { q = path.substring(qi + 1); path = path.substring(0, qi); }
            Map<String, String> qp = new HashMap<>();
            for (String kv : q.split("&")) { int e = kv.indexOf('='); if (e > 0) qp.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); }
            long len = 0; try { len = Long.parseLong(h.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (token.isEmpty() || !token.equals(h.get("x-pair"))) { reply(out, 403, "{\"error\":\"code de jumelage différent\"}"); return; }
            if (path.equals("/ping")) {
                JSONObject o = new JSONObject(); o.put("ok", true); o.put("dev", dev); o.put("name", name); o.put("busy", busy.get());
                o.put("pv", Api.pageVersion(api.pageBytes())); o.put("av", appCode()); o.put("wv", Math.max(0, api.wrLocalVersion())); o.put("wd", new JSONArray(api.wrDirs()));
                try { String wd = qp.get("wd"); if (wd != null) { JSONArray wa = new JSONArray(wd); java.util.List<String> wl = new java.util.ArrayList<>(); for (int i = 0; i < wa.length(); i++) wl.add(wa.optString(i)); api.wrAddDirs(wl); } } catch (Exception ignored) { }   // dossiers WillRelais de l'autre appareil
                reply(out, 200, o.toString()); return;
            }
            if (path.equals("/live")) {                       // saisie en direct de l'autre appareil (petit message) : passe même pendant une synchro
                if (len <= 0 || len > 60L * 1024 * 1024) { reply(out, 400, "{}"); return; }
                ByteArrayOutputStream b = new ByteArrayOutputStream(); copy(in, b, len);
                try { String xp = h.get("x-port"); if (xp != null && xp.matches("\\d{2,5}")) liveHosts.add(s.getInetAddress().getHostAddress() + ":" + xp); } catch (Exception ignored) { }
                if (onLive != null) try { onLive.accept(b.toString("UTF-8")); } catch (Exception ignored) { }   // d'abord l'affichage, puis la réponse
                try { reply(out, 200, "{\"ok\":true}"); } catch (Exception ignored) { }
                return;
            }
            if (path.equals("/xls")) { reply(out, 200, xls(qp.get("p")).toString()); return; }          // parcourir les dossiers de cet appareil depuis l'autre
            if (path.equals("/xfind")) { reply(out, 200, xfind(qp.get("q"), qp.get("p")).toString()); return; }   // chercher un fichier par son nom
            if (path.equals("/xget")) {                                  // recopier un fichier de cet appareil vers l'autre
                File f = Api.extFile(qp.get("p"));
                if (f == null || !f.isFile() || f.length() > 300L * 1024 * 1024) { reply(out, 404, "{}"); return; }
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length() + "\r\nX-Mtime: " + f.lastModified() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream fi = new FileInputStream(f)) { copy(fi, out, f.length()); }
                out.flush(); return;
            }
            if (busy.get() && !path.equals("/ping")) { reply(out, 423, "{\"error\":\"occupé\"}"); return; }
            if (path.equals("/list")) { reply(out, 200, listJson().toString()); return; }
            if (path.equals("/page")) { sendBytes(out, api.pageBytes()); return; }
            if (path.equals("/wr")) { byte[] wb = api.wrBytes(); if (wb == null) reply(out, 404, "{}"); else sendBytes(out, wb); return; }
            if (path.equals("/wrin")) {                      // page WillRelais plus récente envoyée par l'autre appareil
                if (len <= 0 || len > 60L * 1024 * 1024) { reply(out, 400, "{}"); return; }
                ByteArrayOutputStream b = new ByteArrayOutputStream(); copy(in, b, len); byte[] wb = b.toByteArray();
                boolean ok = Api.signed(wb) && Api.wrVersion(wb) > api.wrLocalVersion();
                if (ok) try { api.importWr(wb); } catch (Exception e) { ok = false; }
                reply(out, 200, "{\"ok\":" + ok + "}"); return;
            }
            if (path.equals("/apk")) { File f = new File(ctx.getApplicationInfo().sourceDir); out.write(("HTTP/1.0 200 OK\r\nContent-Length: " + f.length() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); try (InputStream fi = new FileInputStream(f)) { copy(fi, out, f.length()); } out.flush(); return; }
            if (path.equals("/pagein")) {                    // page plus récente envoyée par l'autre appareil
                ByteArrayOutputStream b = new ByteArrayOutputStream(); copy(in, b, len); byte[] pb = b.toByteArray();
                if (!Api.signed(pb) || Api.pageVersion(pb) <= Api.pageVersion(api.pageBytes())) { reply(out, 200, "{\"ok\":false}"); return; }
                api.importPage(new java.io.ByteArrayInputStream(pb), "interventions.html");
                try { reply(out, 200, "{\"ok\":true}"); } catch (Exception ignored) { }
                if (onPage != null) try { onPage.accept(Api.pageVersion(pb)); } catch (Exception ignored) { }
                return;
            }
            if (path.equals("/apkin")) {                     // appli plus récente envoyée par l'autre appareil : proposée à l'installation
                if (len <= 0 || len > 300L * 1024 * 1024) { reply(out, 400, "{}"); return; }
                File d = new File(ctx.getCacheDir(), "maj"); d.mkdirs(); File f = new File(d, "Interventions-recue.apk");
                try (OutputStream fo = new FileOutputStream(f)) { copy(in, fo, len); }
                try { reply(out, 200, "{\"ok\":" + (f.length() == len) + "}"); } catch (Exception ignored) { }
                if (f.length() == len && onApk != null) try { onApk.accept(f); } catch (Exception ignored) { }
                return;
            }
            if (path.equals("/man")) {                       // état commun noté par l'autre appareil : gardé ici aussi (vu de ce côté)
                ByteArrayOutputStream b = new ByteArrayOutputStream(); copy(in, b, len);
                String d = qp.getOrDefault("d", "");
                if (!d.isEmpty() && d.length() < 80) sp.edit().putString("m_" + d, b.toString("UTF-8")).putLong("t_" + d, System.currentTimeMillis()).apply();
                try { reply(out, 200, "{\"ok\":true}"); } catch (Exception ignored) { }
                try { if (onRemote != null) onRemote.run(); } catch (Exception ignored) { }
                return;
            }
            String rel = qp.getOrDefault("p", "");
            File f = fileOf(rel);
            if (f == null) { reply(out, 400, "{\"error\":\"chemin\"}"); return; }
            if (path.equals("/get")) {
                if (!f.isFile()) { reply(out, 404, "{}"); return; }
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length() + "\r\nX-Mtime: " + f.lastModified() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream fi = new FileInputStream(f)) { copy(fi, out, f.length()); }
                out.flush(); return;
            }
            if (rel.startsWith("~ext/") && (path.equals("/put") || path.equals("/del")) && !Api.isWrFile(f.getName())) { reply(out, 400, "{\"error\":\"fichier non WillRelais\"}"); return; }
            if (path.equals("/put")) {
                File dir = f.getParentFile(); if (dir != null && !dir.isDirectory()) dir.mkdirs();
                File tmp = new File(dir, "." + f.getName() + ".part");
                try (OutputStream fo = new FileOutputStream(tmp)) { copy(in, fo, len); }
                if (tmp.length() != len) { tmp.delete(); reply(out, 500, "{\"error\":\"transfert incomplet\"}"); return; }
                if (f.exists()) f.delete();
                if (!tmp.renameTo(f)) { tmp.delete(); reply(out, 500, "{\"error\":\"écriture\"}"); return; }
                try { f.setLastModified(Long.parseLong(qp.getOrDefault("m", "0"))); } catch (Exception ignored) { }
                JSONObject o = new JSONObject(); o.put("ok", true); o.put("m", f.lastModified());
                reply(out, 200, o.toString()); return;
            }
            if (path.equals("/mkdir")) { boolean ok = f.isDirectory() || f.mkdirs(); reply(out, 200, "{\"ok\":" + ok + "}"); return; }
            if (path.equals("/del")) { boolean ok = !f.exists() || f.delete(); reply(out, 200, "{\"ok\":" + ok + "}"); return; }
            reply(out, 404, "{}");
        } catch (Exception ignored) { }
    }

    static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); if (b.size() > 16384) break; }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static void copy(InputStream in, OutputStream out, long n) throws java.io.IOException {
        byte[] buf = new byte[65536]; long left = n; int r;
        while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) != -1) { out.write(buf, 0, r); left -= r; }
    }

    static void reply(OutputStream out, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.0 " + code + " X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b); out.flush();
    }

    /** Fichier sous « Gestion interventions », chemin relatif sûr (pas de .., pas de fichiers cachés ni du dossier appli). */
    File fileOf(String rel) {
        if (rel != null && rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\u0000")) return null;
        String[] parts = rel.split("/", -1);
        if (parts.length > (rel.startsWith("~ext/") ? 14 : 6)) return null;
        for (String x : parts) if (x.isEmpty() || x.equals(".") || x.equals("..") || x.startsWith(".")) return null;
        if (parts[0].equals("appli")) return null;
        if (parts[0].equals("~ext")) {                                   // dossiers WillRelais hors de « Gestion interventions » : même chemin sur les deux appareils
            String r = rel.substring(5);
            File f = Api.extFile(r);
            return f != null && api.inWrDirs(r) ? f : null;
        }
        return new File(base(), rel);
    }

    JSONObject listJson() throws Exception {
        JSONObject o = new JSONObject();
        walk(base(), "", o, 0);
        String br = Api.relOf(base());
        for (String d : api.wrDirs()) {                                  // dossiers WillRelais ailleurs : seulement les fichiers WillRelais (.xrs, .xsy, nomenclatures)
            if (br != null && (d.equals(br) || d.startsWith(br + "/"))) continue;   // déjà dans « Gestion interventions »
            File f = Api.extFile(d); if (f == null) continue;
            o.put("~ext/" + d + "/", new JSONArray().put(0L).put(-1L));
            if (f.isDirectory()) walkExt(f, "~ext/" + d, o, 0, br);
        }
        return o;
    }

    boolean walkExt(File d, String pre, JSONObject o, int depth, String skip) throws Exception {
        File[] l = d.listFiles(); if (l == null || depth > 4) return false;
        boolean any = false;
        for (File f : l) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".part")) continue;
            String rel = pre + "/" + n;
            if (f.isDirectory()) {
                String r = Api.relOf(f);
                if (r == null || (skip != null && r.equals(skip)) || r.equalsIgnoreCase("Android")) continue;
                if (walkExt(f, rel, o, depth + 1, skip)) { o.put(rel + "/", new JSONArray().put(0L).put(-1L)); any = true; }
            } else if (Api.isWrFile(n)) { o.put(rel, new JSONArray().put(f.lastModified()).put(f.length())); any = true; }
        }
        return any;
    }

    void walk(File d, String pre, JSONObject o, int depth) throws Exception {
        File[] l = d.listFiles(); if (l == null || depth > 5) return;
        for (File f : l) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".tmp") || n.endsWith(".part")) continue;
            if (depth == 0 && n.equals("appli")) continue;            // page et appli propres à chaque appareil
            String rel = pre.isEmpty() ? n : pre + "/" + n;
            if (f.isDirectory()) { o.put(rel + "/", new JSONArray().put(0L).put(-1L)); walk(f, rel, o, depth + 1); }   // dossiers : même arborescence des deux côtés
            else o.put(rel, new JSONArray().put(f.lastModified()).put(f.length()));
        }
    }

    /* ---------- fichiers d'un appareil à l'autre (parcourir, chercher, recopier) : mémoire interne, sauf Android/ et fichiers cachés ---------- */
    JSONObject xls(String rel) {
        JSONObject o = new JSONObject();
        try {
            File d = Api.extFile(rel); o.put("path", d == null ? "" : Api.relOf(d)); o.put("name", name);
            JSONArray ds = new JSONArray(), fs = new JSONArray(), dm = new JSONArray();
            File[] l = d == null ? null : d.listFiles();
            if (l != null) {
                java.util.Arrays.sort(l, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : l) {
                    String n = f.getName(); if (n.startsWith(".")) continue;
                    if (f.isDirectory()) { if (!Api.relOf(f).equalsIgnoreCase("Android")) { ds.put(n); dm.put(f.lastModified()); } }
                    else fs.put(new JSONObject().put("name", n).put("size", f.length()).put("mtime", f.lastModified()));
                }
            }
            o.put("dirs", ds); o.put("dirsM", dm); o.put("files", fs);   // dirsM : date des dossiers (tri par date)
        } catch (Exception ignored) { }
        return o;
    }

    static String fold(String s) { return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(); }

    JSONObject xfind(String q, String rel) {
        JSONObject o = new JSONObject(); JSONArray hits = new JSONArray();
        try {
            String[] words = fold(q).trim().split("\\s+");
            File d = Api.extFile(rel == null ? "" : rel);
            long end = System.currentTimeMillis() + 5000;
            if (d != null && words.length > 0 && !words[0].isEmpty()) find(d, words, hits, 0, end);
            o.put("hits", hits); o.put("name", name); o.put("more", hits.length() >= 300 || System.currentTimeMillis() > end);
        } catch (Exception ignored) { }
        return o;
    }

    void find(File d, String[] words, JSONArray hits, int depth, long end) throws Exception {
        File[] l = d.listFiles(); if (l == null || depth > 12) return;
        for (File f : l) {
            if (hits.length() >= 300 || System.currentTimeMillis() > end) return;
            String n = f.getName(); if (n.startsWith(".")) continue;
            String r = Api.relOf(f); if (r == null || r.equalsIgnoreCase("Android")) continue;
            String fn = fold(n); boolean ok = true;
            for (String w : words) if (!fn.contains(w)) { ok = false; break; }
            if (ok) hits.put(new JSONObject().put("path", r).put("dir", f.isDirectory()).put("size", f.isFile() ? f.length() : 0).put("mtime", f.lastModified()));
            if (f.isDirectory()) find(f, words, hits, depth + 1, end);
        }
    }

    void remember(String hp) {
        try { JSONArray k = new JSONArray(sp.getString("known", "[]")), n = new JSONArray(); n.put(hp); for (int i = 0; i < k.length() && n.length() < 6; i++) if (!k.optString(i).equals(hp)) n.put(k.optString(i)); sp.edit().putString("known", n.toString()).apply(); } catch (Exception ignored) { }
    }
    String tried = "";

    /** Code de jumelage sans démarrer le serveur (mises à jour en arrière-plan). */
    void prepare(String code, String devId, String devName) {
        token = code == null || code.trim().length() < 4 ? "" : sha("bi-sync|" + code.trim());
        dev = devId == null ? "" : devId; name = devName == null ? "" : devName;
    }

    /** Mises à jour seulement (page, page WillRelais, appli) avec les appareils joignables : récupère ou envoie la plus récente. */
    String pullUpdates() {
        JSONArray out = new JSONArray();
        if (token.isEmpty() || !busy.compareAndSet(false, true)) return "[]";
        int ct = connTo; connTo = 1200;
        try {
            Set<String> seen = new HashSet<>();
            for (String hp : candidates(null)) {
                try {
                    Res r = http(hp, "GET", "/ping", null); if (r.code != 200) continue;
                    JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8)); String pd = o.optString("dev");
                    if (pd.isEmpty() || pd.equals(dev) || seen.contains(pd)) continue;
                    seen.add(pd); remember(hp); liveHosts.add(hp);
                    JSONObject pr = new JSONObject(); pr.put("host", hp); pr.put("dev", pd);
                    connTo = 2500; updates(hp, o, pr); connTo = 1200; out.put(pr);
                } catch (Exception ignored) { }
            }
        } finally { connTo = ct; busy.set(false); }
        return out.toString();
    }
    int connTo = 2500;

    /** L'autre appareil joignable (déjà vu par la synchro, sinon recherché) : hôte:port ou null. */
    String peer() {
        for (int w = 0; w < 8; w++) { boolean any; synchronized (found) { any = !found.isEmpty(); } if (any || !liveHosts.isEmpty()) break; try { Thread.sleep(500); } catch (Exception ignored) { } }   // découverte Wi-Fi en cours (WillRelais ouvert seul)
        java.util.List<String> l = new java.util.ArrayList<>(liveHosts);
        for (String c : candidates(null)) if (!l.contains(c)) l.add(c);
        tried = String.join(", ", l);
        for (String hp : l) {
            try { Res r = http(hp, "GET", "/ping", null); if (r.code != 200) continue; JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8)); if (!o.optString("dev").equals(dev) && !o.optString("dev").isEmpty()) { liveHosts.add(hp); remember(hp); return hp; } } catch (Exception ignored) { }
        }
        return null;
    }

    String remote(String path) throws Exception {
        String hp = peer(); if (hp == null) throw new Exception("autre appareil injoignable" + (tried.isEmpty() ? " : aucun appareil trouvé sur le Wi-Fi" : " (essayé : " + tried + ")") + ". Vérifie : même Wi-Fi ou point d'accès, appli Interventions ou WillRelais ouverte sur l'autre appareil, même code de jumelage, appli 3.7 ou plus sur les deux.");
        Res r = http(hp, "GET", path, null); if (r.code != 200) throw new Exception("refusé (" + r.code + ")");
        return new String(r.body, StandardCharsets.UTF_8);
    }

    /** Recopie un fichier de l'autre appareil dans « to » ; garde sa date. */
    void remoteGet(String rel, File to) throws Exception {
        String hp = peer(); if (hp == null) throw new Exception("autre appareil injoignable");
        int c = hp.lastIndexOf(':');
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 2500); s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /xget?p=" + URLEncoder.encode(rel, "UTF-8") + " HTTP/1.0\r\nX-Pair: " + token + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            InputStream in = s.getInputStream(); String st = readLine(in); long n = -1, mt = 0; String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) { String ll = l.toLowerCase(); if (ll.startsWith("content-length:")) n = Long.parseLong(l.substring(15).trim()); if (ll.startsWith("x-mtime:")) mt = Long.parseLong(l.substring(8).trim()); }
            if (st == null || !st.contains(" 200 ") || n < 0) throw new Exception("fichier introuvable sur l'autre appareil");
            File dir = to.getParentFile(); if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(dir, "." + to.getName() + ".part");
            try (OutputStream fo = new FileOutputStream(tmp)) { copy(in, fo, n); }
            if (tmp.length() != n) { tmp.delete(); throw new Exception("transfert incomplet"); }
            if (to.exists()) to.delete();
            if (!tmp.renameTo(to)) { tmp.delete(); throw new Exception("écriture impossible"); }
            if (mt > 0) to.setLastModified(mt);
        }
    }

    long appCode() {
        try { android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0); return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode; } catch (Exception e) { return 0; }
    }

    static void sendBytes(OutputStream out, byte[] b) throws java.io.IOException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(b); out.flush();
    }

    /** Mise à jour de la page et de l'appli entre les deux appareils : la plus récente passe sur l'autre. */
    void updates(String hp, JSONObject ping, JSONObject pr) {
        try {
            int rpv = ping.optInt("pv", 0), lpv = Api.pageVersion(api.pageBytes());
            if (rpv > lpv) {
                Res r = http(hp, "GET", "/page", null);
                if (r.code == 200 && Api.signed(r.body) && Api.pageVersion(r.body) > lpv) { api.importPage(new java.io.ByteArrayInputStream(r.body), "interventions.html"); pr.put("pageNew", Api.pageVersion(r.body)); }
            } else if (lpv > rpv && rpv > 0) {
                File tmp = File.createTempFile("bipage", ".html", ctx.getCacheDir());
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(api.pageBytes()); }
                Res w = http(hp, "POST", "/pagein", tmp); tmp.delete();
                if (w.code == 200 && new String(w.body, StandardCharsets.UTF_8).contains("true")) pr.put("pageSent", lpv);
            }
            if (ping.has("wv")) {                           // page WillRelais : la plus récente passe aussi sur l'autre appareil
                int rwv = ping.optInt("wv", 0), lwv = Math.max(0, api.wrLocalVersion());
                if (rwv > lwv) {
                    Res r = http(hp, "GET", "/wr", null);
                    if (r.code == 200 && Api.signed(r.body) && Api.wrVersion(r.body) > lwv) { api.importWr(r.body); pr.put("wrNew", Api.wrVersion(r.body)); }
                } else if (lwv > rwv) {
                    File tmp = File.createTempFile("biwr", ".html", ctx.getCacheDir());
                    try (OutputStream o = new FileOutputStream(tmp)) { o.write(api.wrBytes()); }
                    Res w = http(hp, "POST", "/wrin", tmp); tmp.delete();
                    if (w.code == 200 && new String(w.body, StandardCharsets.UTF_8).contains("true")) pr.put("wrSent", lwv);
                }
            }
            long rav = ping.optLong("av", 0), lav = appCode();
            if (rav > lav && sp.getLong("apkGot", 0) == rav && new File(new File(ctx.getCacheDir(), "maj"), "Interventions-recue.apk").length() == sp.getLong("apkLen", -1)) {
                pr.put("apkNew", new File(new File(ctx.getCacheDir(), "maj"), "Interventions-recue.apk").getAbsolutePath());   // déjà reçue : pas de nouveau transfert
            } else if (rav > lav) {
                File d = new File(ctx.getCacheDir(), "maj"); d.mkdirs(); File f = new File(d, "Interventions-recue.apk");
                int c = hp.lastIndexOf(':');
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 2500); s.setSoTimeout(60000);
                    OutputStream out = s.getOutputStream(); out.write(("GET /apk HTTP/1.0\r\nX-Pair: " + token + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                    InputStream in = s.getInputStream(); String st = readLine(in); long n = -1; String l;
                    while ((l = readLine(in)) != null && !l.isEmpty()) if (l.toLowerCase().startsWith("content-length:")) n = Long.parseLong(l.substring(15).trim());
                    if (st != null && st.contains(" 200 ") && n > 0) { try (OutputStream fo = new FileOutputStream(f)) { copy(in, fo, n); } if (f.length() == n) { pr.put("apkNew", f.getAbsolutePath()); sp.edit().putLong("apkGot", rav).putLong("apkLen", n).apply(); } }
                }
            } else if (lav > rav && rav > 0 && System.currentTimeMillis() - sp.getLong("apkSentT_" + ping.optString("dev") + "_" + rav + "_" + lav, 0) > 15 * 60000L) {   // renvoyée au plus toutes les 15 min tant que l'autre appareil n'est pas à jour
                Res w = http(hp, "POST", "/apkin", new File(ctx.getApplicationInfo().sourceDir));
                if (w.code == 200) { pr.put("apkSent", lav); sp.edit().putLong("apkSentT_" + ping.optString("dev") + "_" + rav + "_" + lav, System.currentTimeMillis()).apply(); }
            }
        } catch (Exception e) { try { pr.put("updErr", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
    }

    /** Envoi en direct (saisie) à l'autre appareil : file d'attente à part ; seul le dernier état non parti compte. */
    final java.util.concurrent.ConcurrentLinkedQueue<String> liveQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    void live(String json) {
        if (token.isEmpty() || json == null) return;
        liveQ.add(json);
        liveEx.execute(() -> {
            String m = liveQ.poll(); if (m == null) return;
            byte[] body = m.getBytes(StandardCharsets.UTF_8);
            String[] hs; synchronized (liveHosts) { hs = liveHosts.toArray(new String[0]); }
            for (String hp : hs) {
                try {
                    int c = hp.lastIndexOf(':');
                    try (Socket so = new Socket()) {
                        so.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 1500); so.setSoTimeout(5000);
                        OutputStream out = so.getOutputStream();
                        out.write(("POST /live HTTP/1.0\r\nX-Pair: " + token + "\r\nX-Port: " + port + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(body); out.flush();
                        String st = readLine(so.getInputStream());
                        if (st == null || !st.contains(" 200 ")) liveHosts.remove(hp);
                    }
                } catch (Exception e) { liveHosts.remove(hp); }   // plus joignable : retrouvé à la prochaine synchro
            }
        });
    }

    /* ---------- client : synchro avec un autre appareil ---------- */
    static class Res { int code; byte[] body; long mt; }

    Res http(String hp, String method, String path, File upload) throws Exception {
        int c = hp.lastIndexOf(':');
        String host = hp.substring(0, c); int p = Integer.parseInt(hp.substring(c + 1));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, p), connTo);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            long len = upload == null ? 0 : upload.length();
            out.write((method + " " + path + " HTTP/1.0\r\nX-Pair: " + token + "\r\nContent-Length: " + len + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            if (upload != null) try (InputStream fi = new FileInputStream(upload)) { copy(fi, out, len); }
            out.flush();
            InputStream in = s.getInputStream();
            Res r = new Res();
            String st = readLine(in); if (st == null) throw new Exception("pas de réponse");
            String[] sp0 = st.split(" "); r.code = sp0.length > 1 ? Integer.parseInt(sp0[1]) : 0;
            long n = -1; String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                String ll = l.toLowerCase();
                if (ll.startsWith("content-length:")) n = Long.parseLong(l.substring(15).trim());
                if (ll.startsWith("x-mtime:")) r.mt = Long.parseLong(l.substring(8).trim());
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (n >= 0) copy(in, b, n); else { byte[] buf = new byte[65536]; int k; while ((k = in.read(buf)) != -1) b.write(buf, 0, k); }
            r.body = b.toByteArray();
            return r;
        }
    }

    Res download(String hp, String rel, File to) throws Exception {
        int c = hp.lastIndexOf(':');
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 2500);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /get?p=" + URLEncoder.encode(rel, "UTF-8") + " HTTP/1.0\r\nX-Pair: " + token + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            InputStream in = s.getInputStream();
            Res r = new Res(); String st = readLine(in); if (st == null) throw new Exception("pas de réponse");
            String[] a = st.split(" "); r.code = a.length > 1 ? Integer.parseInt(a[1]) : 0;
            long n = -1; String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) { String ll = l.toLowerCase(); if (ll.startsWith("content-length:")) n = Long.parseLong(l.substring(15).trim()); if (ll.startsWith("x-mtime:")) r.mt = Long.parseLong(l.substring(8).trim()); }
            if (r.code != 200 || n < 0) return r;
            File dir = to.getParentFile(); if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(dir, "." + to.getName() + ".part");
            try (OutputStream fo = new FileOutputStream(tmp)) { copy(in, fo, n); }
            if (tmp.length() != n) { tmp.delete(); r.code = 500; return r; }
            if (to.exists()) to.delete();
            if (!tmp.renameTo(to)) { tmp.delete(); r.code = 500; return r; }
            if (r.mt > 0) to.setLastModified(r.mt);
            return r;
        }
    }

    /** Appareils à essayer : vus sur le réseau, point d'accès / box, adresse saisie à la main. */
    Set<String> candidates(String manual) {
        Set<String> s = new java.util.LinkedHashSet<>();
        synchronized (found) { s.addAll(found.values()); }
        if (manual == null || manual.trim().isEmpty()) manual = sp.getString("manual", "");
        if (manual != null && !manual.trim().isEmpty()) { String m = manual.trim(); s.add(m.contains(":") ? m : m + ":" + PORT0); }
        try { JSONArray k = new JSONArray(sp.getString("known", "[]")); for (int i = 0; i < k.length(); i++) s.add(k.optString(i)); } catch (Exception ignored) { }
        String g = gateway(); if (!g.isEmpty()) for (int p = PORT0; p < PORT0 + 3; p++) s.add(g + ":" + p);
        return s;
    }

    /** Synchronise avec chaque appareil joignable ; renvoie un compte rendu JSON. */
    String syncAll(String manual) {
        JSONObject rep = new JSONObject();
        JSONArray peers = new JSONArray();
        if (token.isEmpty()) return "{\"error\":\"code de jumelage absent\"}";
        if (!busy.compareAndSet(false, true)) return "{\"error\":\"synchro déjà en cours\"}";
        if (manual != null && !manual.trim().isEmpty()) sp.edit().putString("manual", manual.trim()).apply();
        try {
            Set<String> seen = new HashSet<>();
            for (String hp : candidates(manual)) {
                JSONObject pr = new JSONObject(); pr.put("host", hp);
                try {
                    Res r = http(hp, "GET", "/ping?wd=" + URLEncoder.encode(new JSONArray(api.wrDirs()).toString(), "UTF-8"), null);
                    if (r.code == 403) { pr.put("error", "code de jumelage différent"); peers.put(pr); continue; }
                    if (r.code != 200) continue;
                    JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                    String pd = o.optString("dev");
                    if (pd.isEmpty() || pd.equals(dev) || seen.contains(pd)) continue;
                    seen.add(pd);
                    pr.put("dev", pd); pr.put("name", o.optString("name"));
                    liveHosts.add(hp); remember(hp);
                    if (o.optBoolean("busy")) { pr.put("error", "l'autre appareil synchronise déjà"); peers.put(pr); continue; }
                    try { JSONArray a = o.optJSONArray("wd"); if (a != null) { java.util.List<String> l = new java.util.ArrayList<>(); for (int i = 0; i < a.length(); i++) l.add(a.optString(i)); api.wrAddDirs(l); } } catch (Exception ignored) { }   // mêmes dossiers WillRelais des deux côtés
                    syncWith(hp, pd, pr);
                    updates(hp, o, pr);
                } catch (Exception e) { if (pr.has("dev")) pr.put("error", String.valueOf(e.getMessage())); else continue; }
                peers.put(pr);
            }
            rep.put("peers", peers);
            rep.put("ok", true);
        } catch (Exception e) { try { rep.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
        finally { busy.set(false); }
        return rep.toString();
    }

    void syncWith(String hp, String pd, JSONObject pr) throws Exception {
        Res lr = http(hp, "GET", "/list", null);
        if (lr.code != 200) throw new Exception("liste refusée (" + lr.code + ")");
        JSONObject R = new JSONObject(new String(lr.body, StandardCharsets.UTF_8)), L = listJson();
        JSONObject M; try { M = new JSONObject(sp.getString("m_" + pd, "{}")); } catch (Exception e) { M = new JSONObject(); }
        JSONObject NM = new JSONObject();
        int nR = 0, nL = 0;
        for (java.util.Iterator<String> it = R.keys(); it.hasNext(); ) if (!it.next().endsWith("/")) nR++;
        for (java.util.Iterator<String> it = L.keys(); it.hasNext(); ) if (!it.next().endsWith("/")) nL++;
        if (M.length() > 0 && nR == 0) throw new Exception("dossier de l'autre appareil vide ou inaccessible : rien n'est supprimé");
        if (M.length() > 0 && nL == 0) throw new Exception("dossier de cet appareil vide ou inaccessible : rien n'est supprimé");
        int gone = 0;
        for (java.util.Iterator<String> it = M.keys(); it.hasNext(); ) { String k = it.next(); if (!k.endsWith("/") && (!L.has(k) || !R.has(k))) gone++; }
        boolean allowDel = gone <= Math.max(20, M.length() / 3);   // trop de disparitions d'un coup : prudence, aucune suppression
        Set<String> all = new java.util.TreeSet<>();
        for (java.util.Iterator<String> it = R.keys(); it.hasNext(); ) all.add(it.next());
        for (java.util.Iterator<String> it = L.keys(); it.hasNext(); ) all.add(it.next());
        int up = 0, down = 0, del = 0, err = 0; long bytes = 0;
        int dirs = 0;
        for (String p : all) {
            if (!p.endsWith("/")) continue;
            File dd = fileOf(p); if (dd == null) continue;
            if (R.has(p) && !L.has(p)) { if (dd.mkdirs()) dirs++; }
            else if (L.has(p) && !R.has(p)) { try { if (http(hp, "POST", "/mkdir?p=" + URLEncoder.encode(p, "UTF-8"), null).code == 200) dirs++; } catch (Exception ignored) { } }
        }
        for (String p : all) {
            if (p.endsWith("/")) continue;
            JSONArray l = L.optJSONArray(p), r = R.optJSONArray(p), m = M.optJSONArray(p);
            long lm = l == null ? 0 : l.getLong(0), rm = r == null ? 0 : r.getLong(0);
            boolean lch = l != null && (m == null || Math.abs(lm - m.getLong(0)) > 2000 || l.getLong(1) != m.optLong(2, -1));
            boolean rch = r != null && (m == null || Math.abs(rm - m.getLong(1)) > 2000 || r.getLong(1) != m.optLong(2, -1));
            File f = fileOf(p);
            if (f == null) continue;
            try {
                if (l != null && r != null) {
                    if (!lch && !rch) { NM.put(p, m); continue; }
                    if (l.getLong(1) == r.getLong(1) && Math.abs(lm - rm) <= 2000) { NM.put(p, new JSONArray().put(lm).put(rm).put(l.getLong(1))); continue; }
                    boolean push = lch && (!rch || lm >= rm);
                    if (push) { Res w = http(hp, "POST", "/put?p=" + URLEncoder.encode(p, "UTF-8") + "&m=" + lm, f); if (w.code != 200) { err++; continue; } long nm = new JSONObject(new String(w.body, StandardCharsets.UTF_8)).optLong("m", lm); NM.put(p, new JSONArray().put(lm).put(nm).put(l.getLong(1))); up++; bytes += l.getLong(1); }
                    else { Res d = download(hp, p, f); if (d.code != 200) { err++; continue; } NM.put(p, new JSONArray().put(f.lastModified()).put(rm).put(f.length())); down++; bytes += f.length(); }
                } else if (l != null) {                                 // seulement ici
                    if (m != null && !lch) { if (allowDel && f.delete()) del++; continue; }          // supprimé sur l'autre appareil
                    Res w = http(hp, "POST", "/put?p=" + URLEncoder.encode(p, "UTF-8") + "&m=" + lm, f); if (w.code != 200) { err++; continue; }
                    long nm = new JSONObject(new String(w.body, StandardCharsets.UTF_8)).optLong("m", lm); NM.put(p, new JSONArray().put(lm).put(nm).put(l.getLong(1))); up++; bytes += l.getLong(1);
                } else {                                                 // seulement là-bas
                    if (m != null && !rch) { if (allowDel) { Res d = http(hp, "POST", "/del?p=" + URLEncoder.encode(p, "UTF-8"), null); if (d.code == 200) del++; } continue; }   // supprimé ici
                    Res d = download(hp, p, f); if (d.code != 200) { err++; continue; }
                    NM.put(p, new JSONArray().put(f.lastModified()).put(rm).put(f.length())); down++; bytes += f.length();
                }
            } catch (Exception e) { err++; }
        }
        sp.edit().putString("m_" + pd, NM.toString()).putLong("t_" + pd, System.currentTimeMillis()).apply();
        try {                                                     // même état commun envoyé à l'autre appareil (local et distant inversés)
            JSONObject SW = new JSONObject();
            for (java.util.Iterator<String> it = NM.keys(); it.hasNext(); ) { String k = it.next(); JSONArray x = NM.optJSONArray(k); if (x != null) SW.put(k, new JSONArray().put(x.getLong(1)).put(x.getLong(0)).put(x.optLong(2, -1))); }
            File tmp = File.createTempFile("bisync", ".json", ctx.getCacheDir());
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(SW.toString().getBytes(StandardCharsets.UTF_8)); }
            http(hp, "POST", "/man?d=" + URLEncoder.encode(dev, "UTF-8"), tmp); tmp.delete();
        } catch (Exception ignored) { }
        pr.put("held", !allowDel); pr.put("dirs", dirs); pr.put("up", up); pr.put("down", down); pr.put("del", del); pr.put("err", err); pr.put("bytes", bytes);
    }

    String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("on", server != null); o.put("port", port); o.put("gateway", gateway()); o.put("busy", busy.get());
            JSONArray ips = new JSONArray();
            for (java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements(); ) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.util.Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) { InetAddress x = a.nextElement(); if (x instanceof java.net.Inet4Address) ips.put(x.getHostAddress()); }
            }
            o.put("ips", ips);
            JSONObject f = new JSONObject(); synchronized (found) { for (Map.Entry<String, String> en : found.entrySet()) f.put(en.getKey(), en.getValue()); }
            o.put("found", f);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
