package fr.palmero.interventions;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Widget d'écran d'accueil : Nouvelle intervention, Rapport en cours, Aujourd'hui. */
public class Widget extends AppWidgetProvider {
    static PendingIntent go(Context c, String k, int code) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_GO).putExtra("go", k).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setOnClickPendingIntent(R.id.w_new, go(c, "new", 501));
            v.setOnClickPendingIntent(R.id.w_cur, go(c, "cur", 502));
            v.setOnClickPendingIntent(R.id.w_today, go(c, "today", 503));
            m.updateAppWidget(id, v);
        }
    }
}
