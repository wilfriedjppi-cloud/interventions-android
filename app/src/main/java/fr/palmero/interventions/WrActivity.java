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

/** Schémas WinRelais : la page winrelais-vN.html du dossier appli, ouverte par son icône ou par « Ouvrir avec » sur un fichier .xrs. */
public class WrActivity extends MainActivity {

    @Override String startPath() { return "/winrelais.html"; }

    @Override void beforeLoad() {
        web.setBackgroundColor(Color.parseColor("#111827"));
        getWindow().setStatusBarColor(Color.parseColor("#1f2a37"));
        web.addJavascriptInterface(new WrBridge(), "WrApi");
    }

    @Override void askPermissions() { }   // demandées par Interventions ; sans accès aux fichiers, l'enregistrement passe par « Partager »

    /** Bibliothèques de symboles en ligne (typonrelais.com) : chargées par l'appli, la page les reçoit sans blocage CORS. */
    @Override WebResourceResponse intercept(WebResourceRequest r) {
        Uri u = r.getUrl();
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

    class WrBridge {
        /** Fichier produit par la page (.xrs enregistré, nomenclature .csv) : rangé dans « Gestion interventions/Schémas WinRelais » (recopié sur l'autre appareil par la synchro). */
        @JavascriptInterface
        public String save(String name, String b64) {
            byte[] b = android.util.Base64.decode(b64 == null ? "" : b64, android.util.Base64.DEFAULT);
            String n = Api.safeName(name == null || name.isEmpty() ? "schema.xrs" : name);
            try {
                File d = new File(api.gi(), "Schémas WinRelais");
                if (!d.isDirectory() && !d.mkdirs()) throw new Exception("dossier");
                File f = new File(d, n);
                try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
                runOnUiThread(() -> Toast.makeText(WrActivity.this, "Enregistré : " + f.getAbsolutePath(), Toast.LENGTH_LONG).show());
                return f.getAbsolutePath();
            } catch (Exception e) {   // pas d'accès aux dossiers : partage du fichier
                try {
                    File d = new File(getCacheDir(), "share"); d.mkdirs();
                    File f = new File(d, n);
                    try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
                    Uri su = FileProvider.getUriForFile(WrActivity.this, getPackageName() + ".files", f);
                    Intent s = new Intent(Intent.ACTION_SEND).setType(n.toLowerCase().endsWith(".csv") ? "text/csv" : "application/octet-stream")
                            .putExtra(Intent.EXTRA_STREAM, su).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> startActivity(Intent.createChooser(s, "Enregistrer " + n)));
                    return "partage";
                } catch (Exception e2) { return ""; }
            }
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
