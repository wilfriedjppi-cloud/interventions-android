package fr.palmero.interventions;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** WillRelais : la page willrelais-vN.html du dossier appli (schémas WinRelais .xrs), ouverte par son icône ou par « Ouvrir avec » sur un fichier .xrs. */
public class WrActivity extends MainActivity {

    @Override String startPath() { return "/willrelais.html"; }

    @Override void beforeLoad() {
        web.setBackgroundColor(Color.parseColor("#111827"));
        getWindow().setStatusBarColor(Color.parseColor("#1f2a37"));
        web.addJavascriptInterface(new WrBridge(), "WrApi");
        new Thread(this::ensureSync, "wr-sync-start").start();   // l'autre appareil peut parcourir celui-ci même si Interventions est fermée
    }

    @Override void askPermissions() { }   // demandées par Interventions ; sans accès aux fichiers, l'enregistrement passe par « Partager »

    /** Bibliothèques de symboles en ligne (typonrelais.com) : chargées par l'appli, la page les reçoit sans blocage CORS. */
    @Override WebResourceResponse intercept(WebResourceRequest r) {
        Uri u = r.getUrl();
        if (HOST.equals(u.getHost()) && "/wrfile".equals(u.getPath())) {   // fichier .xrs d'un dossier WillRelais, ouvert depuis le sélecteur de dossiers
            try {
                String rel = u.getQueryParameter("p"); File f = Api.extFile(rel);
                if (f == null || !f.isFile() || !Api.isWrFile(f.getName())) return new WebResourceResponse("text/plain", "utf-8", 404, "Absent", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
                return new WebResourceResponse("application/octet-stream", null, 200, "OK", new HashMap<>(), new java.io.FileInputStream(f));
            } catch (Exception e) { return null; }
        }
        if (!"GET".equalsIgnoreCase(r.getMethod()) || !"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().endsWith("typonrelais.com")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u.toString()).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            byte[] b = Api.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            String t = c.getContentType(); if (t == null) t = "application/octet-stream";
            return new WebResourceResponse(t.split(";")[0], null, code, code < 400 ? "OK" : "Erreur", h, new java.io.ByteArrayInputStream(b));
        } catch (Exception e) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    @Override void handleIntent(Intent i) {
        if (i == null) return;
        String pd = i.getStringExtra("photoDone");
        if (pd != null) { i.removeExtra("photoDone"); if (pd.matches("wrphoto-[A-Za-z0-9]+-ok\\.jpg")) { pendingJs = "window.__wrPhotoDone&&window.__wrPhotoDone('/api/inbox?name=" + Uri.encode(pd) + "'," + JSONObject.quote(pd) + ")"; deliverOi(); } return; }
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        try {
            String dn = displayName(u);
            if (dn.equals("OI.pdf")) dn = "schema.xrs";
            File dir = new File(getCacheDir(), "inbox"); dir.mkdirs();
            File f = new File(dir, Api.safeName(dn));
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            byte[] head = new byte[6];
            try (InputStream in = new java.io.FileInputStream(f)) { int n = in.read(head); if (n < 6) head = new byte[6]; }
            if (!new String(head, StandardCharsets.ISO_8859_1).equals("<< Sch")) {
                Toast.makeText(this, "« " + dn + " » n'est pas un schéma WinRelais (.xrs)", Toast.LENGTH_LONG).show();
                f.delete(); return;
            }
            pendingJs = "window.__wrOpen&&window.__wrOpen('/api/inbox?name=" + Uri.encode(f.getName()) + "'," + JSONObject.quote(dn) + ")";
            deliverOi();
        } catch (Exception e) {
            Toast.makeText(this, "Schéma illisible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Synchro avec l'autre appareil dès qu'un fichier ou un dossier WillRelais change (si la synchro est active dans Interventions). */
    /** WillRelais ouvert seul (Interventions fermée) : la synchro démarre avec le code de jumelage déjà réglé dans Interventions. */
    Sync ensureSync() {
        Sync sy = MainActivity.syncer;
        if (sy != null && !sy.token.isEmpty()) return sy;
        android.content.SharedPreferences bs = getSharedPreferences("bisync", MODE_PRIVATE);
        String code = bs.getString("code", "");
        if (code.trim().length() < 4) return null;
        synchronized (MainActivity.class) { if (MainActivity.syncer == null) MainActivity.syncer = new Sync(this, api); sy = MainActivity.syncer; }
        try { if (sy.token.isEmpty()) sy.start(code, bs.getString("dev", ""), bs.getString("name", "")); } catch (Exception ignored) { }
        return sy.token.isEmpty() ? null : sy;
    }

    static void syncSoon() {
        final Sync sy = MainActivity.syncer;
        if (sy == null || sy.token.isEmpty()) return;
        new Thread(() -> { try { Thread.sleep(1500); sy.syncAll(null); } catch (Exception ignored) { } }, "wr-sync").start();
    }

    class WrBridge {
        /** État : dossier d'enregistrement courant et dossiers WillRelais connus (chemins relatifs à la mémoire interne). */
        @JavascriptInterface
        public String state() {
            try {
                JSONObject o = new JSONObject();
                o.put("cur", api.wrCur()); o.put("def", api.wrDefaultDir()); o.put("dirs", new org.json.JSONArray(api.wrDirs()));
                o.put("root", Api.ext().getAbsolutePath()); o.put("write", api.canWrite());
                o.put("sync", MainActivity.syncer != null && !MainActivity.syncer.token.isEmpty());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Contenu d'un dossier : sous-dossiers et fichiers WillRelais. */
        @JavascriptInterface
        public String ls(String rel) {
            try {
                File d = Api.extFile(rel); JSONObject o = new JSONObject();
                o.put("path", d == null ? "" : Api.relOf(d)); o.put("exists", d != null && d.isDirectory());
                org.json.JSONArray ds = new org.json.JSONArray(), fs = new org.json.JSONArray();
                File[] l = d == null ? null : d.listFiles();
                if (l != null) {
                    java.util.Arrays.sort(l, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File f : l) {
                        String n = f.getName(); if (n.startsWith(".")) continue;
                        if (f.isDirectory()) { if (!(rel == null || rel.isEmpty()) || !n.equalsIgnoreCase("Android")) ds.put(n); }
                        else if (Api.isWrFile(n)) fs.put(new JSONObject().put("name", n).put("size", f.length()).put("mtime", f.lastModified()));
                    }
                }
                o.put("dirs", ds); o.put("files", fs); o.put("wr", d != null && api.inWrDirs(Api.relOf(d)));
                return o.toString();
            } catch (Exception e) { return "{\"dirs\":[],\"files\":[]}"; }
        }

        @JavascriptInterface
        public String mkdir(String rel, String name) {
            String n = Api.safeName(name).replace("/", " ").trim();
            File d = Api.extFile(rel == null || rel.isEmpty() ? n : rel + "/" + n);
            if (d == null || n.isEmpty() || n.startsWith(".")) return "";
            return d.isDirectory() || d.mkdirs() ? Api.relOf(d) : "";
        }

        /** Dossier choisi : devient le dossier d'enregistrement et un dossier WillRelais (créé et recopié sur l'autre appareil). */
        @JavascriptInterface
        public boolean setDir(String rel) {
            File d = Api.extFile(rel); if (d == null) return false;
            if (!d.isDirectory() && !d.mkdirs()) return false;
            api.wrSetCur(Api.relOf(d)); syncSoon(); return true;
        }

        /** Appels longs (autre appareil, recherche) : exécutés à part, résultat renvoyé à window.__wrCb(id, ok, texte). */
        @JavascriptInterface
        public void call(final int id, final String m, final String a, final String b) {
            new Thread(() -> {
                String res; boolean ok = true;
                try {
                    Sync sy = m.equals("lfind") ? null : ensureSync();
                    if (!m.equals("lfind") && sy == null) throw new Exception("synchro entre appareils non réglée : choisis un code de jumelage dans Interventions (Réglages → Synchro)");
                    switch (m) {
                        case "lfind": res = new Sync(WrActivity.this, api).xfind(a, b).toString(); break;
                        case "rls": res = sy.remote("/xls?p=" + java.net.URLEncoder.encode(a == null ? "" : a, "UTF-8")); break;
                        case "rfind": res = sy.remote("/xfind?q=" + java.net.URLEncoder.encode(a == null ? "" : a, "UTF-8") + "&p=" + java.net.URLEncoder.encode(b == null ? "" : b, "UTF-8")); break;
                        case "rget": {   // ouvrir un schéma de l'autre appareil (copie temporaire)
                            String n = Api.safeName(a.substring(a.lastIndexOf('/') + 1));
                            File dir = new File(getCacheDir(), "inbox"); dir.mkdirs();
                            sy.remoteGet(a, new File(dir, n));
                            res = new JSONObject().put("inbox", n).put("name", n).toString(); break;
                        }
                        case "rcopy": {  // recopier un fichier de l'autre appareil dans un dossier de celui-ci
                            String n = Api.safeName(a.substring(a.lastIndexOf('/') + 1));
                            File d = Api.extFile(b); if (d == null) throw new Exception("dossier refusé");
                            File to = new File(d, n); sy.remoteGet(a, to);
                            res = new JSONObject().put("rel", Api.relOf(to)).toString(); break;
                        }
                        default: throw new Exception("inconnu");
                    }
                } catch (Exception e) { ok = false; res = String.valueOf(e.getMessage()); }
                final String js = "window.__wrCb&&window.__wrCb(" + id + "," + ok + "," + JSONObject.quote(res) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }, "wr-call").start();
        }

        /** Enregistre un fichier produit par la page (.xrs, nomenclature .csv) dans le dossier choisi. */
        @JavascriptInterface
        public String saveTo(String rel, String name, String b64) {
            byte[] b = android.util.Base64.decode(b64 == null ? "" : b64, android.util.Base64.DEFAULT);
            String n = Api.safeName(name == null || name.isEmpty() ? "schema.xrs" : name);
            File d = Api.extFile(rel == null ? api.wrCur() : rel);
            try {
                if (d == null) throw new Exception("dossier refusé");
                if (!d.isDirectory() && !d.mkdirs()) throw new Exception("dossier impossible à créer");
                File f = new File(d, n), tmp = new File(d, "." + n + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                if (f.exists()) f.delete();
                if (!tmp.renameTo(f)) { tmp.delete(); throw new Exception("écriture"); }
                api.wrSetCur(Api.relOf(d)); syncSoon();
                runOnUiThread(() -> Toast.makeText(WrActivity.this, "Enregistré : " + f.getAbsolutePath(), Toast.LENGTH_LONG).show());
                return Api.relOf(f);
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(WrActivity.this, "Enregistrement impossible : " + e.getMessage() + (api.canWrite() ? "" : " (autorise « Gestion de tous les fichiers » pour Interventions)"), Toast.LENGTH_LONG).show()); return ""; }
        }

        /** Partage d'un fichier produit (mail, Drive…) sans l'enregistrer dans un dossier. */
        @JavascriptInterface
        public String share(String name, String b64) {
            try {
                byte[] b = android.util.Base64.decode(b64 == null ? "" : b64, android.util.Base64.DEFAULT);
                String n = Api.safeName(name == null || name.isEmpty() ? "schema.xrs" : name);
                File d = new File(getCacheDir(), "share"); d.mkdirs();
                File f = new File(d, n);
                try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
                Uri su = FileProvider.getUriForFile(WrActivity.this, getPackageName() + ".files", f);
                Intent s = new Intent(Intent.ACTION_SEND).setType(n.toLowerCase().endsWith(".csv") ? "text/csv" : "application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, su).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(s, "Envoyer " + n)));
                return "ok";
            } catch (Exception e) { return ""; }
        }

        /** Texte d'un fichier WillRelais (photos d'un schéma .photos.json) ; "" si absent. */
        @JavascriptInterface
        public String readText(String rel) {
            try { File f = Api.extFile(rel); if (f == null || !f.isFile() || !Api.isWrFile(f.getName()) || f.length() > 80L * 1024 * 1024) return "";
                return new String(Api.readAll(new java.io.FileInputStream(f)), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
        }

        /** Photos d'un schéma : fichier compagnon nom.photos.json, à côté du .xrs (recopié sur l'autre appareil avec lui). */
        @JavascriptInterface
        public String saveText(String rel, String name, String text) {
            try {
                String n = Api.safeName(name); if (!n.toLowerCase().endsWith(".photos.json")) return "";
                File d = Api.extFile(rel); if (d == null) return ""; if (!d.isDirectory() && !d.mkdirs()) return "";
                File f = new File(d, n), tmp = new File(d, "." + n + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if (f.exists()) f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return ""; }
                syncSoon(); return Api.relOf(f);
            } catch (Exception e) { return ""; }
        }

        File libFile() { File d = Api.extFile(api.wrDefaultDir()); return d == null ? null : new File(d, Api.WR_LIB); }

        /** Bibliothèque de symboles importée : gardée dans le dossier WillRelais (partagé avec l'autre appareil). */
        @JavascriptInterface
        public String libLoad() {
            try { File f = libFile(); if (f == null || !f.isFile()) return ""; return new String(Api.readAll(new java.io.FileInputStream(f)), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean libSave(String text) {
            try {
                File f = libFile(); if (f == null) return false; File d = f.getParentFile(); if (!d.isDirectory() && !d.mkdirs()) return false;
                File tmp = new File(d, ".biblio.part");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write((text == null ? "[]" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if (f.exists()) f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return false; }
                syncSoon(); return true;
            } catch (Exception e) { return false; }
        }

        /** Modifier une photo du schéma dans l'éditeur de photo d'Interventions (recadrer, redresser, améliorer, dessiner). */
        @JavascriptInterface
        public void editPhoto(String name, String b64) {
            try {
                String n = Api.safeName(name); if (!n.startsWith("wrphoto-")) return;
                File dir = new File(getCacheDir(), "inbox"); dir.mkdirs();
                try (OutputStream o = new FileOutputStream(new File(dir, n))) { o.write(android.util.Base64.decode(b64 == null ? "" : b64, android.util.Base64.DEFAULT)); }
                runOnUiThread(() -> startActivity(new Intent(WrActivity.this, MainActivity.class).setAction(ACT_GO).putExtra("editPhoto", n)));
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(WrActivity.this, "Photo : " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }

        /** « PDF / Imprimer » : impression Android (choix « Enregistrer au format PDF » possible). */
        @JavascriptInterface
        public void print(final String title) {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    String t = title == null || title.isEmpty() ? "Schéma" : title;
                    pm.print(t, web.createPrintDocumentAdapter(t), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A3.asLandscape()).build());
                } catch (Exception e) { Toast.makeText(WrActivity.this, "Impression impossible : " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            });
        }
    }
}
