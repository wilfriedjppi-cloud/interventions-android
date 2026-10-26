package fr.palmero.interventions;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Caméra thermique (Doogee V31GT / InfiRay…) : lancement de l'appli constructeur et récupération de la dernière image. */
class Th {
    static final String RX = "(?i).*(therm|infiray|infisense|ir ?cam|flir|topdon|hikmicro|uni-?t|seek|guide|ircamera|imag).*";

    static String apps(Context c) {
        JSONArray a = new JSONArray();
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
            for (ResolveInfo r : l) {
                String pkg = r.activityInfo.packageName, lab = String.valueOf(r.loadLabel(pm));
                if (pkg.equals(c.getPackageName())) continue;
                boolean m = (lab + " " + pkg).matches(RX) && !(lab + " " + pkg).matches("(?i).*(gallery|galerie|imagin|image editor).*");
                if (m) { JSONObject o = new JSONObject(); o.put("pkg", pkg); o.put("label", lab); a.put(o); }
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    static boolean launch(Context c, String pkg) {
        try { Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg); if (i == null) return false; i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); return true; }
        catch (Exception e) { return false; }
    }

    static void copy(InputStream in, File out) throws Exception {
        try (InputStream i = in; OutputStream o = new FileOutputStream(out)) { byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); }
    }

    static File newest(File d, long since, int depth, File best) {
        File[] l = d.listFiles(); if (l == null) return best;
        for (File f : l) {
            if (f.isDirectory()) { if (depth > 0 && !f.getName().startsWith(".") && !f.getName().equals("Android")) best = newest(f, since, depth - 1, best); continue; }
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue;
            if (f.lastModified() > since && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    /** Dernière image enregistrée depuis « since » (ms) : copiée dans le cache « inbox » ; renvoie son nom ou "". */
    static String latest(Context c, long since) {
        File dir = new File(c.getCacheDir(), "inbox"); dir.mkdirs();
        try {   // 1) médiathèque Android
            ContentResolver cr = c.getContentResolver();
            try (Cursor q = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED},
                    MediaStore.Images.Media.DATE_ADDED + ">=?", new String[]{String.valueOf(since / 1000 - 2)}, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (q != null && q.moveToFirst()) {
                    Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, q.getLong(0));
                    String name = "thermo-" + System.currentTimeMillis() + "-" + Api.safeName(q.getString(1) == null ? "image.jpg" : q.getString(1));
                    copy(cr.openInputStream(u), new File(dir, name)); return name;
                }
            }
        } catch (Exception ignored) { }
        try {   // 2) dossiers usuels (accès à tous les fichiers)
            File root = Environment.getExternalStorageDirectory(), best = null;
            for (String s : new String[]{Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DOWNLOADS, "InfiRay", "Thermal", "IRCamera"}) {
                File d = new File(root, s); if (d.isDirectory()) best = newest(d, since - 2000, 4, best);
            }
            if (best != null) { String name = "thermo-" + System.currentTimeMillis() + "-" + Api.safeName(best.getName()); copy(new FileInputStream(best), new File(dir, name)); return name; }
        } catch (Exception ignored) { }
        return "";
    }
}
