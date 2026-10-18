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

/** Le même service que le serveur QPython (/api/…), mais dans l'appli : fichiers dans Documents/Gestion interventions. */
class Api {
    static final String VERSION = "appli-1.0";
    static final String[] SUBS = {"", "Gestion interventions", "Gestion interventions/config/global", "Gestion interventions/config/utilisateur",
            "Gestion interventions/données/OI", "Gestion interventions/données/rapports", "Gestion interventions/données/backup",
            "Gestion interventions/données/gestion"};
    static final String SHIM = "<script>(function(){var A=window.AndroidApi;if(!A)return;"
            + "var of=window.fetch.bind(window);"
            + "window.fetch=function(u,o){var s=typeof u==='string'?u:(u&&u.url)||'';"
            + "if(o&&String(o.method||'').toUpperCase()==='POST'&&s.indexOf('/api/')>=0){return new Promise(function(res){var p=new URL(s,location.href).pathname;"
            + "var r=A.post(p,typeof o.body==='string'?o.body:'{}');res(new Response(r,{status:200,headers:{'Content-Type':'application/json'}}));});}"
            + "return of(u,o);};"
            + "function b64(buf){var u=new Uint8Array(buf),s='',i;for(i=0;i<u.length;i+=32768)s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));return btoa(s);}"
            + "function sh(d){d=d||{};return Promise.all((d.files||[]).map(function(f){return f.arrayBuffer().then(function(b){return {name:f.name,type:f.type,b64:b64(b)};});}))"
            + ".then(function(fs){var r=A.share(JSON.stringify({title:d.title||'',text:d.text||'',url:d.url||'',to:d.to||'',files:fs}));if(r!=='ok')throw new Error(r);});}"
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

    /** La page : interventions.html déposé dans Documents/Gestion interventions/appli (mise à jour sans réinstaller), sinon celle de l'appli. */
    String htmlSource() {
        File f = new File(dirOf("Gestion interventions"), "appli/interventions.html");
        return f.isFile() ? f.getAbsolutePath() : "intégrée à l'application";
    }

    WebResourceResponse page() throws Exception {
        File f = new File(dirOf("Gestion interventions"), "appli/interventions.html");
        byte[] b = f.isFile() ? readAll(new FileInputStream(f)) : readAll(ctx.getAssets().open("index.html"));
        String t = new String(b, StandardCharsets.UTF_8);
        int i = t.indexOf("<head>");
        t = i >= 0 ? t.substring(0, i + 6) + SHIM + t.substring(i + 6) : SHIM + t;
        return resp(200, "text/html", t.getBytes(StandardCharsets.UTF_8));
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
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(url.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
            File dir = new File(ctx.getFilesDir(), "cdn");
            dir.mkdirs();
            File data = new File(dir, sb + ".bin"), meta = new File(dir, sb + ".type");
            if (!data.isFile() || !meta.isFile()) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
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
