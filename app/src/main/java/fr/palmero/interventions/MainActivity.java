package fr.palmero.interventions;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Interventions : la page interventions.html dans une WebView, avec accès direct aux dossiers du téléphone. */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    static final int REQ_FILE = 11, REQ_PERM = 12, REQ_PAGE = 13, REQ_IMPORT = 14;
    static final String ACT_SETTINGS = "fr.palmero.interventions.REGLAGES";

    WebView web;
    Api api;
    ValueCallback<Uri[]> fileCb;
    Uri cameraUri;
    File cameraFile;
    String pendingOi;
    boolean pageReady, hadAllFiles;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#eef0f3"));
        setContentView(web);
        getWindow().setStatusBarColor(Color.parseColor("#c86a0a"));

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setGeolocationEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setTextZoom(100);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "AndroidApi");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return api.serve(r);
                if ("GET".equalsIgnoreCase(r.getMethod())) return api.cdn(r);
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String s = r.getUrl().toString();
                if (s.startsWith(ORIGIN)) return false;
                openExternal(s);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                deliverOi();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(chooser(p), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    Toast.makeText(MainActivity.this, "Aucune application pour choisir un fichier", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() { public void run() { r.grant(r.getResources()); } });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }
        });

        hadAllFiles = api.canWrite();
        handleIntent(getIntent());
        askPermissions();
        web.loadUrl(ORIGIN + "/index.html");
    }

    /* ---------- autorisations ---------- */
    void askPermissions() {
        List<String> need = new ArrayList<>();
        String[] all = Build.VERSION.SDK_INT <= 32
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}
                : new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO};
        for (String p : all) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("Accès aux fichiers")
                    .setMessage("Pour enregistrer les rapports, notes de frais et sauvegardes dans Documents/Gestion interventions, et lire les OI téléchargés, autorise « Gestion de tous les fichiers » pour Interventions.")
                    .setPositiveButton("Autoriser", (d, w) -> openAllFilesSettings())
                    .setNegativeButton("Plus tard", null)
                    .show();
        }
    }

    void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean now = api.canWrite();
        if (now && !hadAllFiles && web != null) web.reload();      // accès accordé : la page refait son essai d'écriture
        hadAllFiles = now;
    }

    /* ---------- choix de fichiers et appareil photo ---------- */
    Intent chooser(WebChromeClient.FileChooserParams p) {
        Set<String> mimes = new LinkedHashSet<>();
        String[] acc = p.getAcceptTypes();
        if (acc != null) for (String a0 : acc) for (String a : a0.split(",")) {
            a = a.trim().toLowerCase();
            if (a.isEmpty()) continue;
            if (a.startsWith(".")) {
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1));
                if (m != null) mimes.add(m); else if (a.equals(".bck") || a.equals(".ndf")) mimes.add("application/octet-stream");
            } else mimes.add(a);
        }
        boolean image = mimes.isEmpty();
        for (String m : mimes) if (m.startsWith("image")) image = true;

        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes.size() == 1) pick.setType(mimes.iterator().next());
        else {
            pick.setType("*/*");
            if (!mimes.isEmpty()) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        cameraUri = null;
        cameraFile = null;
        Intent cam = null;
        if (image && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                File dir = new File(getCacheDir(), "camera");
                dir.mkdirs();
                cameraFile = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
                cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                cam = null;
                cameraUri = null;
            }
        }
        if (cam != null && p.isCaptureEnabled()) return cam;
        Intent ch = Intent.createChooser(pick, image ? "Photo ou fichier" : "Choisir un fichier");
        if (cam != null) ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        return ch;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PAGE || req == REQ_IMPORT) { pageChosen(req, res == RESULT_OK && data != null ? data.getData() : null); return; }
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            List<Uri> l = new ArrayList<>();
            if (data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) l.add(cd.getItemAt(i).getUri());
                else if (data.getData() != null) l.add(data.getData());
            }
            if (l.isEmpty() && cameraFile != null && cameraFile.length() > 0) l.add(cameraUri);
            if (!l.isEmpty()) out = l.toArray(new Uri[0]);
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    /* ---------- OI reçus (« Ouvrir avec » ou « Partager » depuis Gmail, Fichiers…) ---------- */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    void handleIntent(Intent i) {
        if (i == null) return;
        if (ACT_SETTINGS.equals(i.getAction())) { web.post(this::showSettings); return; }
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        String typ = String.valueOf(i.getType()), dn = displayName(u);
        if (typ.contains("html") || Api.isHtml(dn)) { pageChosen(REQ_IMPORT, u); return; }   // page HTML reçue : installée dans le dossier appli
        try {
            String name = dn;
            if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
            File dir = new File(getCacheDir(), "inbox");
            dir.mkdirs();
            File f = new File(dir, Api.safeName(name));
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            pendingOi = f.getName();
            deliverOi();
        } catch (Exception e) {
            Toast.makeText(this, "OI illisible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    String displayName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) { }
        if (n == null || n.isEmpty()) n = u.getLastPathSegment();
        return n == null || n.isEmpty() ? "OI.pdf" : n;
    }

    void deliverOi() {
        if (!pageReady || pendingOi == null || web == null) return;
        String n = pendingOi;
        pendingOi = null;
        String js = "window.biOpenOI&&window.biOpenOI('/api/inbox?name=" + Uri.encode(n) + "'," + JSONObject.quote(n) + ")";
        web.evaluateJavascript(js, null);
    }

    /* ---------- liens externes : mail, téléphone, Gmail, Google Maps, sites ---------- */
    void openExternal(String s) {
        try {
            Intent i;
            if (s.startsWith("intent:")) {
                i = Intent.parseUri(s, Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb)));
                }
                return;
            }
            if (s.startsWith("mailto:")) i = new Intent(Intent.ACTION_SENDTO, Uri.parse(s));
            else if (s.startsWith("tel:")) i = new Intent(Intent.ACTION_DIAL, Uri.parse(s));
            else i = new Intent(Intent.ACTION_VIEW, Uri.parse(s));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir : " + s, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__appBack?String(window.__appBack()):'false'", v -> {
            if (v == null || !v.contains("true")) moveTaskToBack(true);
        });
    }

    static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();   // écritures dans l'ordre, hors de l'écran

    /* ---------- réglage de la page : automatique, fichier choisi ou adresse web ---------- */
    void showSettings() {
        final String[] items = {"Automatique : la page la plus récente du dossier appli", "Un fichier HTML choisi…", "Une adresse web (URL)…"};
        String m = api.mode();
        int cur = m.equals("file") ? 1 : m.equals("url") ? 2 : 0;
        final int[] pick = {cur};
        new AlertDialog.Builder(this)
                .setTitle("Page de l'application\nActuelle : " + api.htmlSource())
                .setSingleChoiceItems(items, cur, (d, w) -> pick[0] = w)
                .setPositiveButton("Appliquer", (d, w) -> {
                    if (pick[0] == 0) { api.sp.edit().putString("src", "auto").apply(); reloadPage("Page : automatique (dossier " + api.appDir().getAbsolutePath() + ")"); }
                    else if (pick[0] == 1) openPicker(REQ_PAGE);
                    else askUrl();
                })
                .setNeutralButton("Importer une page…", (d, w) -> openPicker(REQ_IMPORT))
                .setNegativeButton("Fermer", null)
                .show();
    }

    void openPicker(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"});
        try { startActivityForResult(i, req); } catch (Exception e) { Toast.makeText(this, "Aucun sélecteur de fichiers", Toast.LENGTH_LONG).show(); }
    }

    void askUrl() {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setHint("ex : https://mon-site.fr/interventions.html");
        e.setText(api.sp.getString("url", ""));
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Adresse de la page").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    String u = e.getText().toString().trim();
                    if (!u.startsWith("https://") && !u.startsWith("http://")) { Toast.makeText(this, "Adresse invalide (https://…)", Toast.LENGTH_LONG).show(); return; }
                    api.sp.edit().putString("src", "url").putString("url", u).apply();
                    reloadPage("Page : " + u);
                })
                .setNegativeButton("Annuler", null).show();
    }

    void pageChosen(int req, Uri u) {
        if (u == null) return;
        String name = displayName(u);
        try {
            if (req == REQ_PAGE) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                api.sp.edit().putString("src", "file").putString("uri", u.toString()).putString("uriName", name).apply();
                reloadPage("Page : " + name);
            } else {
                String n;
                try (InputStream in = getContentResolver().openInputStream(u)) { n = api.importPage(in, name); }
                api.sp.edit().putString("src", "auto").apply();
                reloadPage("Page installée : " + n);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Page illisible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void reloadPage(String msg) {
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        pageReady = false;
        web.loadUrl(ORIGIN + "/index.html?t=" + System.currentTimeMillis());
    }

    /* ---------- pont JavaScript ---------- */
    class Bridge {
        @JavascriptInterface
        public String post(String path, String body) {
            return api.post(path, body);
        }

        /** Même chose sans bloquer l'écran : le fichier est écrit en tâche de fond, puis la page est rappelée. */
        @JavascriptInterface
        public void postAsync(final int id, final String path, final String body) {
            IO.execute(() -> {
                String r;
                try { r = api.post(path, body); } catch (Throwable e) { r = "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e)) + "}"; }
                final String js = "window.__apiCb&&window.__apiCb(" + id + "," + JSONObject.quote(r) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public void settings() { runOnUiThread(MainActivity.this::showSettings); }

        @JavascriptInterface
        public void importPage() { runOnUiThread(() -> openPicker(REQ_IMPORT)); }

        @JavascriptInterface
        public String pageInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("mode", api.mode());
                o.put("source", api.htmlSource());
                o.put("dir", api.appDir().getAbsolutePath());
                o.put("url", api.sp.getString("url", ""));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public String version() {
            return Api.VERSION;
        }

        @JavascriptInterface
        public String share(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray fs = o.optJSONArray("files");
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File x : old) if (System.currentTimeMillis() - x.lastModified() > 3600000L) x.delete();
                ArrayList<Uri> uris = new ArrayList<>();
                String type = null;
                if (fs != null) for (int k = 0; k < fs.length(); k++) {
                    JSONObject f = fs.getJSONObject(k);
                    File out = new File(dir, Api.safeName(f.optString("name", "fichier")));
                    try (OutputStream os = new FileOutputStream(out)) {
                        os.write(android.util.Base64.decode(f.optString("b64", ""), android.util.Base64.DEFAULT));
                    }
                    uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", out));
                    String t = f.optString("type", "");
                    if (t.isEmpty()) t = "application/octet-stream";
                    type = type == null || type.equals(t) ? t : "*/*";
                }
                final Intent s;
                if (uris.size() > 1) {
                    s = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    ClipData cd = ClipData.newRawUri("", uris.get(0));
                    for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                    s.setClipData(cd);
                } else {
                    s = new Intent(Intent.ACTION_SEND);
                    if (uris.size() == 1) {
                        s.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                        s.setClipData(ClipData.newRawUri("", uris.get(0)));
                    }
                }
                s.setType(type == null ? "text/plain" : type);
                String title = o.optString("title", ""), text = o.optString("text", ""), to = o.optString("to", "");
                if (!title.isEmpty()) s.putExtra(Intent.EXTRA_SUBJECT, title);
                if (!text.isEmpty()) s.putExtra(Intent.EXTRA_TEXT, text);
                if (!to.isEmpty()) s.putExtra(Intent.EXTRA_EMAIL, to.split("\\s*[,;]\\s*"));
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final boolean gm = o.optBoolean("gmail", false);
                runOnUiThread(() -> {
                    if (gm) {   // mail prêt directement dans Gmail : destinataire, objet, texte, pièces jointes
                        Intent g = new Intent(s);
                        g.setPackage("com.google.android.gm");
                        try { startActivity(g); return; } catch (ActivityNotFoundException e) { /* Gmail absent : choix de l'appli */ }
                    }
                    startActivity(Intent.createChooser(s, "Envoyer"));
                });
                return "ok";
            } catch (Exception e) {
                return "Partage impossible : " + e.getMessage();
            }
        }
    }
}
