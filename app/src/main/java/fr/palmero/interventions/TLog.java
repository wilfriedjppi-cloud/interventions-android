package fr.palmero.interventions;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Journal des transferts entre appareils (mémoire de l'appli, 2 fichiers de 1 Mo au plus) : synchro, fichiers envoyés/reçus, erreurs, mises à jour, voyant, fusions. */
final class TLog {
    static File dir;
    static final Object L = new Object();
    static final long MAX = 1024 * 1024;

    static void init(Context c) { if (dir == null && c != null) { dir = new File(c.getApplicationContext().getFilesDir(), "logs"); dir.mkdirs(); } }

    static void w(String cat, String msg) {
        if (dir == null) return;
        long t = System.currentTimeMillis();
        String line = String.format(Locale.FRANCE, "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %2$-6s %3$s%n", t, cat, msg == null ? "" : msg.replace('\n', ' '));
        synchronized (L) {
            try {
                File f = new File(dir, "transferts.log");
                if (f.length() > MAX) { File o = new File(dir, "transferts.1.log"); o.delete(); f.renameTo(o); f = new File(dir, "transferts.log"); }
                try (OutputStream o = new FileOutputStream(f, true)) { o.write(line.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { }
        }
    }

    /** Fin du journal (les max derniers octets, ancien fichier compris). */
    static String tail(long max) {
        if (dir == null) return "";
        synchronized (L) {
            StringBuilder sb = new StringBuilder();
            for (String n : new String[]{"transferts.1.log", "transferts.log"}) {
                File f = new File(dir, n); if (!f.isFile()) continue;
                try (RandomAccessFile r = new RandomAccessFile(f, "r")) { byte[] b = new byte[(int) Math.min(r.length(), MAX)]; r.seek(r.length() - b.length); r.readFully(b); sb.append(new String(b, StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            }
            String s = sb.toString(); if (s.length() > max) { s = s.substring((int) (s.length() - max)); int i = s.indexOf('\n'); if (i >= 0) s = s.substring(i + 1); }
            return s;
        }
    }

    static void clear() { synchronized (L) { if (dir != null) { new File(dir, "transferts.log").delete(); new File(dir, "transferts.1.log").delete(); } } }
}
