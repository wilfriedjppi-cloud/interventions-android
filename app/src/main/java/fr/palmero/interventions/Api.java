package fr.palmero.interventions;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Le même service que le serveur QPython (/api/…), mais dans l'appli : fichiers dans Documents/Gestion interventions. */
class Api {
    static final String VERSION = "appli-2.7";
    static final String[] SUBS = {"", "Gestion interventions", "Gestion interventions/config/global", "Gestion interventions/config/utilisateur",
            "Gestion interventions/données/OI", "Gestion interventions/données/rapports", "Gestion interventions/données/backup",
            "Gestion interventions/données/gestion", "Gestion interventions/appli"};
    static final String SHIM = "<script>(function(){var A=window.AndroidApi;if(!A)return;"
            + "var of=window.fetch.bind(window),CB={n:0};window.__apiCb=function(i,r){var f=CB[i];delete CB[i];if(f)f(r);};"
            + "window.fetch=function(u,o){var s=typeof u==='string'?u:(u&&u.url)||'';"
            + "if(o&&String(o.method||'').toUpperCase()==='POST'&&s.indexOf('/api/')>=0){return new Promise(function(res){var p=new URL(s,location.href).pathname;"
            + "var bd=typeof o.body==='string'?o.body:'{}',done=function(r){res(new Response(r,{status:200,headers:{'Content-Type':'application/json'}}));};"
            + "if(A.postAsync){var id=++CB.n;CB[id]=done;A.postAsync(id,p,bd);}else done(A.post(p,bd));});}"
            + "return of(u,o);};"
            + "function b64(buf){var u=new Uint8Array(buf),s='',i;for(i=0;i<u.length;i+=32768)s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));return btoa(s);}"
            + "function sh(d){d=d||{};return Promise.all((d.files||[]).map(function(f){return f.arrayBuffer().then(function(b){return {name:f.name,type:f.type,b64:b64(b)};});}))"
            + ".then(function(fs){var r=A.share(JSON.stringify({title:d.title||'',text:d.text||'',url:d.url||'',to:d.to||'',gmail:!!d.gmail,files:fs}));if(r!=='ok')throw new Error(r);});}"
            + "try{Object.defineProperty(navigator,'share',{value:sh,configurable:true});Object.defineProperty(navigator,'canShare',{value:function(){return true;},configurable:true});}catch(e){navigator.share=sh;navigator.canShare=function(){return true;};}"
            + "window.__appBack=function(){var l=Array.prototype.filter.call(document.querySelectorAll('.over,[id$=\"-ov\"],#crop,#ann,#cfgb'),function(e){return !e.hidden&&e.getClientRects().length;});"
            + "if(l.length){l[l.length-1].hidden=true;return true;}return false;};"
            + "window.AndroidApp={version:A.version()};})();</script>";

    final Context ctx;
    final SharedPreferences sp;

    Api(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("interventions", Context.MODE_PRIVATE);
    }

    String root() {
        return sp.getString("root", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath());
    }

    String oiDir() {
        return sp.getString("oi_dir", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
    }

    boolean canWrite() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return writeTest().isEmpty();
    }

    static boolean subOk(String sub) {
        if (sub == null || sub.isEmpty()) return true;
        if (sub.startsWith("/") || sub.contains("\\") || sub.contains("\u0000")) return false;
        String[] parts = sub.split("/", -1);
        if (parts.length > 6) return false;
        for (String x : parts) if (x.isEmpty() || x.equals(".") || x.equals("..")) return false;
        return true;
    }

    static String safeName(String n) {
        n = String.valueOf(n == null ? "" : n).replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        for (char ch : "<>:\"|?*".toCharArray()) n = n.replace(ch, ' ');
        n = n.trim();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "fichier" : n;
    }

    File dirOf(String sub) {
        return sub == null || sub.isEmpty() ? new File(root()) : new File(root(), sub);
    }

    String writeTest() {
        try {
            for (String s : SUBS) {
                File d = dirOf(s);
                if (!d.isDirectory() && !d.mkdirs()) throw new Exception("AccesAppli : création de " + d.getAbsolutePath() + " refusée");
            }
            File f = new File(dirOf(SUBS[SUBS.length - 1]), ".test-ecriture.tmp");
            try (OutputStream o = new FileOutputStream(f)) { o.write(new byte[]{'O', 'K'}); }
            if (!f.delete()) throw new Exception("AccesAppli : suppression refusée");
            return "";
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("AccesAppli") ? m : "AccesAppli : " + e.getClass().getSimpleName() + " " + m;
        }
    }

    /* ---------- réponses ---------- */
    static WebResourceResponse resp(int code, String mime, byte[] data) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("Access-Control-Allow-Origin", "*");
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : "Error";
        return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") ? "utf-8" : null,
                code, reason, h, new ByteArrayInputStream(data));
    }

    static WebResourceResponse json(int code, JSONObject o) {
        return resp(code, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject err(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", m); } catch (Exception ignored) { }
        return o;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static String mimeOf(String name) {
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase() : "";
        if (ext.equals("xls")) return "application/vnd.ms-excel";
        if (ext.equals("bck") || ext.equals("ndf")) return "application/octet-stream";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    JSONArray listOf(File d, boolean files, boolean pdfOnly) throws Exception {
        List<File> l = new ArrayList<>();
        File[] all = d.listFiles();
        if (all != null) for (File f : all) {
            if (files ? !f.isFile() : !f.isDirectory()) continue;
            if (pdfOnly && !f.getName().toLowerCase().endsWith(".pdf")) continue;
            l.add(f);
        }
        if (pdfOnly) Collections.sort(l, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONArray a = new JSONArray();
        for (File f : l) {
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            if (files) o.put("size", f.length());
            o.put("mtime", f.lastModified());
            a.put(o);
        }
        return a;
    }

    /* ---------- page et /api/… en lecture ---------- */
    WebResourceResponse serve(WebResourceRequest r) {
        Uri u = r.getUrl();
        String p = u.getPath() == null ? "/" : u.getPath();
        try {
            if (p.equals("/") || p.equals("/index.html") || p.equals("/interventions.html")) return page();
            if (p.equals("/favicon.ico")) return resp(404, "text/plain", new byte[0]);
            if (p.startsWith("/lib/") && !p.contains("..")) {   // bibliothèques intégrées à l'APK : lecteur PDF, OCR (moteur + langues), tableur
                try {
                    InputStream in = ctx.getAssets().open(p.substring(1));
                    String m = p.endsWith(".wasm") ? "application/wasm" : p.endsWith(".js") ? "application/javascript" : "application/octet-stream";
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "max-age=31536000");
                    return new WebResourceResponse(m, m.contains("javascript") ? "utf-8" : null, 200, "OK", h, in);
                } catch (Exception e) { return json(404, err("absent de l'appli : " + p)); }
            }
            if (p.equals("/api/ping")) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("app", true);
                o.put("version", VERSION);
                o.put("root", root());
                o.put("oi_dir", oiDir());
                o.put("html", htmlSource());
                o.put("write_error", writeTest());
                return json(200, o);
            }
            if (p.equals("/api/list")) {
                String sub = u.getQueryParameter("sub");
                if (!subOk(sub)) return json(400, err("dossier non autorisé"));
                File d = dirOf(sub);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("dir", d.getAbsolutePath());
                o.put("exists", d.isDirectory());
                o.put("files", d.isDirectory() ? listOf(d, true, false) : new JSONArray());
                o.put("dirs", d.isDirectory() ? listOf(d, false, false) : new JSONArray());
                return json(200, o);
            }
            if (p.equals("/api/read")) {
                String sub = u.getQueryParameter("sub");
                if (!subOk(sub)) return json(400, err("dossier non autorisé"));
                File f = new File(dirOf(sub), safeName(u.getQueryParameter("name")));
                if (!f.isFile()) return json(404, err("introuvable"));
                return resp(200, mimeOf(f.getName()), readAll(new FileInputStream(f)));
            }
            if (p.equals("/api/pdfs")) {
                File d = new File(oiDir());
                JSONObject o = new JSONObject();
                o.put("dir", d.getAbsolutePath());
                if (!d.isDirectory() || d.listFiles() == null) {
                    o.put("ok", false);
                    o.put("files", new JSONArray());
                    o.put("error", d.isDirectory() ? "Pas d'accès à " + d.getAbsolutePath() + " : autorise « Gestion de tous les fichiers » pour Interventions (Paramètres Android > Applications > Interventions)." : "Dossier des OI introuvable : " + d.getAbsolutePath());
                    return json(200, o);
                }
                int n = 12;
                try { n = Integer.parseInt(String.valueOf(u.getQueryParameter("n"))); } catch (Exception ignored) { }
                JSONArray all = listOf(d, true, true), out = new JSONArray();
                for (int i = 0; i < all.length() && i < n; i++) out.put(all.get(i));
                o.put("ok", true);
                o.put("files", out);
                return json(200, o);
            }
            if (p.equals("/api/sizes")) {   // tailles par catégorie pour la sauvegarde .zip
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("cats", sizes());
                byte[] pb = pageBytes();
                o.put("html", pb.length);
                o.put("htmlVersion", pageVersion(pb));
                File apk = new File(ctx.getApplicationInfo().sourceDir);
                o.put("apk", apk.length());
                o.put("apkVersion", versionName());
                o.put("downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
                return json(200, o);
            }
            if (p.equals("/api/pdf") || p.equals("/api/inbox")) {
                File d = p.equals("/api/pdf") ? new File(oiDir()) : new File(ctx.getCacheDir(), "inbox");
                File f = new File(d, safeName(u.getQueryParameter("name")));
                if (!f.isFile()) return json(404, err("introuvable"));
                return resp(200, "application/pdf", readAll(new FileInputStream(f)));
            }
            return json(404, err("inconnu"));
        } catch (Exception e) {
            return json(500, err(String.valueOf(e.getMessage())));
        }
    }

    /* ---------- la page : l'APK n'en contient pas ; elle est cherchée selon le réglage ----------
       auto (par défaut) : la page HTML la plus récente de Documents/Gestion interventions/appli (n° de version, puis date)
       file : un fichier choisi n'importe où (Documents, Téléchargements, Drive…)
       url  : une adresse web (copie gardée pour le hors connexion) */
    String source = "";

    File appDir() { return new File(dirOf("Gestion interventions"), "appli"); }

    static final java.util.regex.Pattern PV = java.util.regex.Pattern.compile("interventions-version: (\\d+)|APPVER = \\{\"v\": \"(\\d+)|\\{v:\"(\\d+)\",d:\"\\d\\d/\\d\\d/\\d{4}");

    /** N° de version de la page : repère en tête, sinon dans le script (lisible ou minifié). */
    static int pageVersion(byte[] b) {
        String t = new String(b, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = PV.matcher(t);
        if (!m.find()) return -1;
        for (int g = 1; g <= 3; g++) if (m.group(g) != null) try { return Integer.parseInt(m.group(g)); } catch (Exception ignored) { }
        return -1;
    }

    /* ---------- signature de la page : seule une page signée par l'auteur est acceptée ---------- */
    static final String PAGE_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESsedENqWpdYR7mmu91pXA7k/jnNHdmm9+o9aDx76Mrmwx1KSYreCZWNywWffVPtV9HlL9b4OKITPUp8cgryacg==";
    static final byte[] SIG_A = "<!-- interventions-sig: ".getBytes(StandardCharsets.US_ASCII), SIG_B = " -->".getBytes(StandardCharsets.US_ASCII);
    static int indexOf(byte[] h, byte[] n, int from) {
        outer:
        for (int i = Math.max(0, from); i <= h.length - n.length; i++) { for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer; return i; }
        return -1;
    }
    static boolean signed(byte[] b) {
        try {
            if (b == null) return false;
            int a = indexOf(b, SIG_A, 0); if (a < 0 || a > 4096) return false;
            int e = indexOf(b, SIG_B, a + SIG_A.length); if (e < 0) return false;
            byte[] sig = android.util.Base64.decode(new String(b, a + SIG_A.length, e - a - SIG_A.length, StandardCharsets.US_ASCII), android.util.Base64.DEFAULT);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream(b.length);
            body.write(b, 0, a); body.write(b, e + SIG_B.length, b.length - e - SIG_B.length);
            java.security.PublicKey k = java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(PAGE_PUB, android.util.Base64.DEFAULT)));
            java.security.Signature v = java.security.Signature.getInstance("SHA256withECDSA");
            v.initVerify(k); v.update(body.toByteArray());
            return v.verify(sig);
        } catch (Exception e) { return false; }
    }
    String refused = "";

    static boolean isHtml(String n) { n = n.toLowerCase(); return n.endsWith(".html") || n.endsWith(".htm"); }

    /** La page la plus récente du dossier appli : plus grand n° de version, puis la plus récemment modifiée. */
    File newestPage() {
        File[] l = appDir().listFiles();
        if (l == null) return null;
        File best = null;
        int bv = -2;
        for (File f : l) {
            if (!f.isFile() || !isHtml(f.getName())) continue;
            int v;
            try { byte[] b = readAll(new FileInputStream(f)); if (!signed(b)) continue; v = pageVersion(b); } catch (Exception e) { continue; }
            if (best == null || v > bv || (v == bv && f.lastModified() > best.lastModified())) { best = f; bv = v; }
        }
        return best;
    }

    String mode() { return sp.getString("src", "auto"); }

    byte[] fetchUrl(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            byte[] b = readAll(c.getInputStream());
            return b.length > 100 ? b : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    byte[] pageBytes() {
        String m = mode(); refused = "";
        try {
            if (m.equals("url")) {
                String url = sp.getString("url", "");
                File cache = new File(ctx.getFilesDir(), "page-web.html");
                byte[] b = url.isEmpty() ? null : fetchUrl(url);
                if (b != null && !signed(b)) { refused = "page de l'adresse web refusée (non signée par l'auteur)"; b = null; }
                if (b != null) {
                    try (OutputStream o = new FileOutputStream(cache)) { o.write(b); }
                    source = "adresse web : " + url;
                    return b;
                }
                if (cache.isFile()) { byte[] cb = readAll(new FileInputStream(cache)); if (signed(cb)) { source = "adresse web (copie hors connexion) : " + url; return cb; } }
            } else if (m.equals("file")) {
                String u = sp.getString("uri", "");
                if (!u.isEmpty()) {
                    try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(u))) {
                        byte[] b = readAll(in);
                        if (signed(b)) { source = "fichier choisi : " + sp.getString("uriName", u); return b; }
                        refused = "page choisie refusée (non signée par l'auteur)";
                    } catch (Exception e) { /* fichier déplacé ou supprimé : recherche automatique */ }
                }
            }
            File f = newestPage();
            byte[] emb = null;
            try { emb = readAll(ctx.getAssets().open("page/interventions.html")); } catch (Exception ignored) { }
            int ev = emb == null ? -1 : pageVersion(emb);
            if (f != null) {
                byte[] b = readAll(new FileInputStream(f));
                int v = pageVersion(b);
                if (ev <= v) {
                    source = (m.equals("auto") ? "" : "secours, ") + "automatique : " + f.getAbsolutePath() + (v > 0 ? " (v" + v + ")" : "") + (refused.isEmpty() ? "" : " — " + refused);
                    return b;
                }
            }
            if (emb != null) {   // page intégrée à l'APK, plus récente (ou seule) : copiée dans le dossier appli
                try { if (appDir().isDirectory() || appDir().mkdirs()) { File c = new File(appDir(), "interventions-v" + ev + ".html"); if (!c.isFile()) try (OutputStream o = new FileOutputStream(c)) { o.write(emb); } } } catch (Exception ignored) { }
                source = "intégrée à l'appli" + (ev > 0 ? " (v" + ev + ")" : "") + (refused.isEmpty() ? "" : " — " + refused);
                return emb;
            }
        } catch (Exception ignored) { }
        source = "aucune page trouvée";
        try { return readAll(ctx.getAssets().open("accueil.html")); } catch (Exception e) { return "<p>Aucune page.</p>".getBytes(StandardCharsets.UTF_8); }
    }

    String htmlSource() { return source.isEmpty() ? mode() : source; }

    WebResourceResponse page() throws Exception {
        String t = new String(pageBytes(), StandardCharsets.UTF_8);
        int i = t.indexOf("<head>");
        t = i >= 0 ? t.substring(0, i + 6) + SHIM + t.substring(i + 6) : SHIM + t;
        return resp(200, "text/html", t.getBytes(StandardCharsets.UTF_8));
    }

    /** Copie une page HTML (choisie ou reçue par « Ouvrir avec ») dans le dossier appli. */
    String importPage(InputStream in, String name) throws Exception {
        byte[] b = readAll(in);
        if (!signed(b)) throw new Exception("page non signée par l'auteur (modifiée ou non officielle) : refusée");
        File d = appDir();
        if (!d.isDirectory() && !d.mkdirs()) throw new Exception("impossible de créer " + d.getAbsolutePath());
        if (!isHtml(name)) name = name + ".html";
        int v = pageVersion(b);
        if (v > 0) name = "interventions-v" + v + ".html";
        File f = new File(d, safeName(name));
        try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
        if (v > 0) {   // ménage : les copies plus anciennes de la page sont supprimées (la plus récente est gardée)
            File[] l = d.listFiles();
            if (l != null) for (File x : l) {
                if (x.equals(f) || !x.isFile() || !isHtml(x.getName())) continue;
                try { int xv = pageVersion(readAll(new FileInputStream(x))); if (xv > 0 && xv < v) x.delete(); } catch (Exception ignored) { }
            }
        }
        return f.getName();
    }

    /* ---------- sauvegarde complète en .zip (Téléchargements) ---------- */
    String versionName() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    File gi() { return dirOf("Gestion interventions"); }

    static long[] dirSize(File d) {
        long[] r = {0, 0};
        File[] l = d.listFiles();
        if (l != null) for (File f : l) {
            if (f.isDirectory()) { long[] x = dirSize(f); r[0] += x[0]; r[1] += x[1]; }
            else if (f.isFile() && !f.getName().endsWith(".tmp")) { r[0] += f.length(); r[1]++; }
        }
        return r;
    }

    /** Catégories : chaque dossier de Gestion interventions (et de « données »), plus les fichiers isolés. */
    JSONArray sizes() throws Exception {
        JSONArray a = new JSONArray();
        File g = gi();
        List<String> keys = new ArrayList<>();
        File[] l = g.listFiles();
        long looseS = 0, looseN = 0;
        if (l != null) for (File f : l) {
            if (f.isFile()) { looseS += f.length(); looseN++; continue; }
            if (!f.isDirectory()) continue;
            if (f.getName().equals("données")) {
                File[] l2 = f.listFiles();
                if (l2 != null) for (File x : l2) { if (x.isDirectory()) keys.add("données/" + x.getName()); else if (x.isFile()) { looseS += x.length(); looseN++; } }
            } else keys.add(f.getName());
        }
        Collections.sort(keys);
        for (String k : keys) {
            long[] sz = dirSize(new File(g, k));
            JSONObject o = new JSONObject();
            o.put("key", k); o.put("size", sz[0]); o.put("n", sz[1]);
            a.put(o);
        }
        if (looseN > 0) { JSONObject o = new JSONObject(); o.put("key", ""); o.put("size", looseS); o.put("n", looseN); a.put(o); }
        return a;
    }

    static void addFile(ZipOutputStream z, String name, File f) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) z.write(b, 0, n); }
        z.closeEntry();
    }

    static void addBytes(ZipOutputStream z, String name, byte[] b, long t) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(t);
        z.putNextEntry(e);
        z.write(b);
        z.closeEntry();
    }

    int addTree(ZipOutputStream z, File d, String prefix) throws Exception {
        int n = 0;
        File[] l = d.listFiles();
        if (l != null) for (File f : l) {
            if (f.isDirectory()) n += addTree(z, f, prefix + f.getName() + "/");
            else if (f.isFile() && !f.getName().endsWith(".tmp")) { addFile(z, prefix + f.getName(), f); n++; }
        }
        return n;
    }

    JSONObject makeZip(JSONObject j) throws Exception {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dl.isDirectory() && !dl.mkdirs()) return err("Téléchargements inaccessible");
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        int num = 1;
        File[] ex = dl.listFiles();
        if (ex != null) for (File f : ex) {
            String n = f.getName();
            if (n.startsWith("interventions_" + day + "_") && n.endsWith(".zip")) {
                try { num = Math.max(num, Integer.parseInt(n.substring(("interventions_" + day + "_").length(), n.length() - 4)) + 1); } catch (Exception ignored) { }
            }
        }
        String name = "interventions_" + day + "_" + num + ".zip";
        File out = new File(dl, name), tmp = new File(dl, name + ".tmp");
        JSONArray cats = j.optJSONArray("cats");
        int files = 0;
        long now = System.currentTimeMillis();
        byte[] page = pageBytes();
        int pv = pageVersion(page);
        File apk = new File(ctx.getApplicationInfo().sourceDir);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp))) {
            z.setLevel(6);
            File g = gi();
            if (cats != null) for (int i = 0; i < cats.length(); i++) {
                String k = cats.getString(i);
                if (k.isEmpty()) {   // fichiers isolés de Gestion interventions et de données
                    for (File base : new File[]{g, new File(g, "données")}) {
                        File[] l = base.listFiles();
                        String pre = base.equals(g) ? "Gestion interventions/" : "Gestion interventions/données/";
                        if (l != null) for (File f : l) if (f.isFile() && !f.getName().endsWith(".tmp")) { addFile(z, pre + f.getName(), f); files++; }
                    }
                    continue;
                }
                if (!subOk(k)) continue;
                File d = new File(g, k);
                if (d.isDirectory()) files += addTree(z, d, "Gestion interventions/" + k + "/");
            }
            String mem = j.optString("mem", "");
            if (!mem.isEmpty()) { addBytes(z, "memoire/appli.json", mem.getBytes(StandardCharsets.UTF_8), now); files++; }
            if (j.optBoolean("html", true)) { addBytes(z, "appli-utilisee/interventions-v" + (pv > 0 ? pv : 0) + ".html", page, now); files++; }
            if (j.optBoolean("apk", true) && apk.isFile()) { addFile(z, "appli-utilisee/Interventions-" + versionName() + ".apk", apk); files++; }
            JSONObject m = new JSONObject();
            m.put("kind", "interventions-backup");
            m.put("v", 1);
            m.put("created", now);
            m.put("date", new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(now)));
            m.put("num", num);
            m.put("page", pv);
            m.put("appli", VERSION);
            m.put("apk", versionName());
            m.put("cats", cats == null ? new JSONArray() : cats);
            m.put("files", files);
            m.put("tech", j.optString("tech", ""));
            addBytes(z, "manifest.json", m.toString(1).getBytes(StandardCharsets.UTF_8), now);
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) return err("écriture du zip refusée");
        JSONObject o = new JSONObject();
        o.put("ok", true); o.put("path", out.getAbsolutePath()); o.put("name", name); o.put("size", out.length()); o.put("files", files);
        return o;
    }

    static JSONObject zipManifest(File f) {
        try (ZipFile z = new ZipFile(f)) {
            ZipEntry e = z.getEntry("manifest.json");
            if (e == null) return null;
            JSONObject m = new JSONObject(new String(readAll(z.getInputStream(e)), StandardCharsets.UTF_8));
            if (!"interventions-backup".equals(m.optString("kind"))) return null;
            long tot = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) { ZipEntry x = en.nextElement(); if (x.getSize() > 0) tot += x.getSize(); }
            m.put("unzipped", tot);
            m.put("zipSize", f.length());
            return m;
        } catch (Exception e) { return null; }
    }

    /** Restaure : fichiers plus récents que ceux du téléphone, page si plus récente ; la mémoire et l'APK sont rendus à part. */
    JSONObject restoreZip(File f, File memOut, File apkOut) throws Exception {
        int put = 0, kept = 0;
        String apkName = "";
        boolean mem = false, html = false;
        File g = gi();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.contains("..") || n.startsWith("/")) continue;
                if (n.startsWith("Gestion interventions/")) {
                    File t = new File(g, n.substring("Gestion interventions/".length()));
                    if (t.isFile() && t.lastModified() >= e.getTime() - 2000) { kept++; continue; }
                    File par = t.getParentFile();
                    if (par != null && !par.isDirectory()) par.mkdirs();
                    File tmp = new File(t.getPath() + ".tmp");
                    try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(tmp)) { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); }
                    if (t.exists()) t.delete();
                    tmp.renameTo(t);
                    t.setLastModified(e.getTime());
                    put++;
                } else if (n.equals("memoire/appli.json")) {
                    try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(memOut)) { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); }
                    mem = true;
                } else if (n.startsWith("appli-utilisee/") && isHtml(n)) {
                    byte[] b = readAll(z.getInputStream(e));
                    int v = pageVersion(b), cur = pageVersion(pageBytes());
                    if (v > cur && signed(b)) { try { importPage(new ByteArrayInputStream(b), n.substring(n.lastIndexOf('/') + 1)); html = true; } catch (Exception ignored) { } }
                } else if (n.startsWith("appli-utilisee/") && n.endsWith(".apk")) {
                    apkName = n.substring(n.lastIndexOf('/') + 1);
                    try (InputStream in = z.getInputStream(e); OutputStream o = new FileOutputStream(apkOut)) { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); }
                }
            }
        }
        JSONObject o = new JSONObject();
        o.put("put", put); o.put("kept", kept); o.put("mem", mem); o.put("html", html); o.put("apk", apkName);
        return o;
    }

    /* ---------- /api/… en écriture (via le pont JavaScript) ---------- */
    String post(String path, String body) {
        try {
            JSONObject j = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            if (path.equals("/api/save")) {
                String sub = j.optString("sub", "");
                if (!subOk(sub)) return err("dossier non autorisé").toString();
                File d = dirOf(sub);
                if (!d.isDirectory() && !d.mkdirs()) return err("AccesAppli : impossible de créer " + d.getAbsolutePath()).toString();
                File f = new File(d, safeName(j.optString("name"))), tmp = new File(d, f.getName() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(android.util.Base64.decode(j.optString("b64", ""), android.util.Base64.DEFAULT));
                }
                if (f.exists() && !f.delete()) { tmp.delete(); return err("AccesAppli : remplacement de " + f.getName() + " refusé").toString(); }
                if (!tmp.renameTo(f)) return err("AccesAppli : écriture de " + f.getName() + " refusée").toString();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("path", f.getAbsolutePath());
                return o.toString();
            }
            if (path.equals("/api/delete")) {
                String sub = j.optString("sub", "");
                if (!subOk(sub)) return err("dossier non autorisé").toString();
                File f = new File(dirOf(sub), safeName(j.optString("name")));
                if (f.isFile()) f.delete();
                return new JSONObject().put("ok", true).toString();
            }
            if (path.equals("/api/zip")) return makeZip(j).toString();
            if (path.equals("/api/config")) {
                SharedPreferences.Editor e = sp.edit();
                String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
                for (String k : new String[]{"oi_dir", "root"}) {
                    String v = j.optString(k, "").trim();
                    if (v.isEmpty()) continue;
                    if (!v.startsWith("/")) v = sd + "/" + v;
                    File d = new File(v);
                    if (k.equals("root")) d.mkdirs();
                    else if (!d.isDirectory()) return err("dossier introuvable : " + v).toString();
                    e.putString(k, v);
                }
                e.apply();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("root", sp.getString("root", root()));
                o.put("oi_dir", sp.getString("oi_dir", oiDir()));
                return o.toString();
            }
            return err("inconnu").toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage())).toString();
        }
    }

    /* ---------- bibliothèques (lecteur PDF, OCR, tableur) gardées pour le hors-ligne ---------- */
    static final String[] CDN = {"cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "tessdata.projectnaptha.com"};

    WebResourceResponse cdn(WebResourceRequest r) {
        Uri u = r.getUrl();
        String host = u.getHost();
        boolean ok = false;
        for (String h : CDN) if (h.equals(host)) ok = true;
        if (!ok) return null;
        try {
            String url = u.toString();
            String LF = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/";   // carte : Leaflet intégré à l'APK (les fonds de carte restent en ligne)
            if (url.startsWith(LF)) {
                String rest = url.substring(LF.length()).split("[?#]")[0], as = rest.equals("leaflet.min.js") ? "lib/leaflet/leaflet.js" : rest.equals("leaflet.min.css") ? "lib/leaflet/leaflet.css" : rest.startsWith("images/") ? "lib/leaflet/" + rest : null;
                if (as != null && !as.contains("..")) {
                    try {
                        InputStream in = ctx.getAssets().open(as);
                        Map<String, String> hh = new HashMap<>();
                        hh.put("Access-Control-Allow-Origin", "*");
                        String mm = as.endsWith(".js") ? "application/javascript" : as.endsWith(".css") ? "text/css" : "image/png";
                        return new WebResourceResponse(mm, mm.startsWith("image") ? null : "utf-8", 200, "OK", hh, in);
                    } catch (Exception ignored) { }
                }
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(url.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
            File dir = new File(ctx.getFilesDir(), "cdn");
            dir.mkdirs();
            File data = new File(dir, sb + ".bin"), meta = new File(dir, sb + ".type");
            if (!data.isFile() || !meta.isFile()) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                String type = c.getContentType();
                byte[] b = readAll(c.getInputStream());
                c.disconnect();
                File tmp = new File(dir, sb + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                tmp.renameTo(data);
                try (OutputStream o = new FileOutputStream(meta)) { o.write(String.valueOf(type == null ? "" : type).getBytes(StandardCharsets.UTF_8)); }
            }
            String type = new String(readAll(new FileInputStream(meta)), StandardCharsets.UTF_8).trim();
            String mime = type, enc = null;
            int sc = type.indexOf(';');
            if (sc >= 0) {
                mime = type.substring(0, sc).trim();
                int cs = type.toLowerCase().indexOf("charset=");
                if (cs >= 0) enc = type.substring(cs + 8).trim();
            }
            if (mime.isEmpty()) mime = mimeOf(u.getLastPathSegment() == null ? "" : u.getLastPathSegment());
            if (url.endsWith(".wasm")) mime = "application/wasm";
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "max-age=31536000");
            return new WebResourceResponse(mime, enc, 200, "OK", h, new FileInputStream(data));
        } catch (Exception e) {
            return null;                    // hors ligne et pas encore en cache : la page affiche son message habituel
        }
    }
}
