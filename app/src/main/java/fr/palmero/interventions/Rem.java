package fr.palmero.interventions;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Relances : résumé quotidien et rappels avant les rendez-vous, préparés par la page (setReminders). */
public class Rem extends BroadcastReceiver {
    static final String CH = "relances", A_DAY = "fr.palmero.interventions.REM_DAY", A_EV = "fr.palmero.interventions.REM_EV";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("rem", Context.MODE_PRIVATE); }

    static PendingIntent pi(Context c, String act, int code, String title, String text) {
        Intent i = new Intent(c, Rem.class).setAction(act);
        if (title != null) { i.putExtra("t", title); i.putExtra("x", text); }
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = sp(c);
        int old = p.getInt("n", 0);
        am.cancel(pi(c, A_DAY, 1, null, null));
        for (int k = 0; k < old; k++) am.cancel(pi(c, A_EV, 100 + k, null, null));
        int n = 0;
        try {
            JSONObject o = new JSONObject(p.getString("json", "{}"));
            if (o.optBoolean("on", false)) {
                String[] hm = o.optString("hour", "07:30").split(":");
                Calendar t = Calendar.getInstance();
                t.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0])); t.set(Calendar.MINUTE, hm.length > 1 ? Integer.parseInt(hm[1]) : 0); t.set(Calendar.SECOND, 0);
                if (t.getTimeInMillis() <= System.currentTimeMillis() + 30000) t.add(Calendar.DAY_OF_MONTH, 1);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi(c, A_DAY, 1, null, null));
                JSONArray ev = o.optJSONArray("events");
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.FRANCE);
                if (ev != null) for (int k = 0; k < ev.length() && n < 40; k++) {
                    JSONObject e = ev.getJSONObject(k);
                    Date at = f.parse(e.optString("at"));
                    if (at == null) continue;
                    long when = at.getTime() - e.optInt("before", 30) * 60000L;
                    if (when <= System.currentTimeMillis() || when > System.currentTimeMillis() + 31L * 86400000L) continue;
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c, A_EV, 100 + n, e.optString("title"), e.optString("text")));
                    n++;
                }
            }
        } catch (Exception ignored) { }
        p.edit().putInt("n", n).apply();
    }

    static void notify(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || title == null || title.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Relances et rendez-vous", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_GO).putExtra("go", "today").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent ci = PendingIntent.getActivity(c, 900 + id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text == null ? "" : text.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text)).setAutoCancel(true).setContentIntent(ci);
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (A_DAY.equals(a)) {
            try { JSONObject o = new JSONObject(sp(c).getString("json", "{}")); notify(c, 1, o.optString("title"), o.optString("text")); } catch (Exception ignored) { }
            schedule(c);
        } else if (A_EV.equals(a)) {
            notify(c, 2 + (int) (System.currentTimeMillis() % 1000), i.getStringExtra("t"), i.getStringExtra("x"));
        } else schedule(c);   // redémarrage du téléphone, mise à jour de l'appli
    }
}
