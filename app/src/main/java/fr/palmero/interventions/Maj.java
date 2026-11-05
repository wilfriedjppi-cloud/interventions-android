package fr.palmero.interventions;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Synchro en veille : toutes les 15 min environ (Android regroupe les réveils), même écran éteint et appli fermée,
 * l'appli démarre son serveur de synchro (l'autre appareil peut la joindre) et synchronise avec l'autre appareil
 * déjà connu : fichiers de « Gestion interventions » (interventions, dossiers, photos, rapports), dossiers WillRelais,
 * puis mises à jour (page, WillRelais, appli). Au retour, la page relit les données reçues et l'installation est proposée.
 * Redémarrage du téléphone ou mise à jour de l'appli : la tâche est reprogrammée.
 */
public class Maj extends BroadcastReceiver {
    static final int JOB = 4711;
    static final long EVERY = 15 * 60000L;

    static void schedule(Context c, long ignored) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB) return;     // déjà programmée
            js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, Job.class))
                    .setPeriodic(EVERY).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        } catch (Exception ignored2) { }
    }

    @Override
    public void onReceive(Context c, Intent i) { schedule(c.getApplicationContext(), 0); }   // redémarrage, mise à jour de l'appli

    /** Tâche de fond : jusqu'à 10 min de travail possible (fichiers et appli). */
    public static class Job extends JobService {
        @Override public boolean onStartJob(final JobParameters p) {
            new Thread(() -> { try { run(getApplicationContext()); } catch (Throwable ignored) { } finally { jobFinished(p, false); } }, "maj").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters p) { return false; }
    }

    static void run(Context c) {
        SharedPreferences bs = c.getSharedPreferences("bisync", Context.MODE_PRIVATE);
        Sync s;
        synchronized (MainActivity.class) {
            s = MainActivity.syncer;
            if (s == null || s.token.isEmpty()) {
                String code = bs.getString("code", "");
                if (code.trim().length() < 4) return;             // synchro jamais réglée
                if (s == null) { s = new Sync(c, new Api(c)); MainActivity.syncer = s; }
                s.start(code, bs.getString("dev", ""), bs.getString("name", ""));   // serveur ouvert : l'autre appareil peut aussi joindre celui-ci
            }
        }
        try { Thread.sleep(2500); } catch (Exception ignored) { }  // découverte Wi-Fi des appareils
        String res = s.syncAll(null);
        bs.edit().putLong("majT", System.currentTimeMillis()).putString("majR", res).apply();
        try {                                                      // données reçues : la page ouverte (même en arrière-plan) les relit
            JSONArray ps = new JSONObject(res).optJSONArray("peers"); boolean got = false;
            for (int i = 0; ps != null && i < ps.length(); i++) { JSONObject pr = ps.getJSONObject(i); if (pr.optInt("down") > 0 || pr.optInt("del") > 0) got = true; }
            if (got && s.onRemote != null) s.onRemote.run();
        } catch (Exception ignored) { }
    }
}
