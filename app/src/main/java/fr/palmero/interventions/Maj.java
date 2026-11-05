package fr.palmero.interventions;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Mises à jour préchargées entre appareils, même en veille : toutes les 15 min environ (Android regroupe les réveils),
 * l'appli interroge l'autre appareil déjà connu (adresse retenue par la synchro) et récupère la page Interventions,
 * la page WillRelais et l'appli si elles sont plus récentes (ou lui envoie les siennes). L'installation est proposée
 * dès le retour dans l'appli.
 */
public class Maj extends BroadcastReceiver {
    static final String A = "fr.palmero.interventions.MAJ";
    static final long EVERY = 15 * 60000L;

    static void schedule(Context c, long delay) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, Maj.class).setAction(A), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        final Context ctx = c.getApplicationContext();
        schedule(ctx, EVERY);
        final PendingResult r = goAsync();
        new Thread(() -> { try { run(ctx); } catch (Throwable ignored) { } finally { r.finish(); } }, "maj").start();
    }

    static void run(Context c) {
        Sync s = MainActivity.syncer;
        if (s == null || s.token.isEmpty()) {
            SharedPreferences bs = c.getSharedPreferences("bisync", Context.MODE_PRIVATE);
            String code = bs.getString("code", "");
            if (code.trim().length() < 4) return;                 // synchro jamais réglée
            s = new Sync(c, new Api(c));
            s.prepare(code, bs.getString("dev", ""), bs.getString("name", ""));
        }
        String res = s.pullUpdates();
        c.getSharedPreferences("bisync", Context.MODE_PRIVATE).edit().putLong("majT", System.currentTimeMillis()).putString("majR", res).apply();
    }
}
