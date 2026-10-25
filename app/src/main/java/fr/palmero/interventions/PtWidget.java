package fr.palmero.interventions;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import org.json.JSONObject;

/** Widget de pointage : début, repas, reprise, fin d'intervention + intervention en cours. */
public class PtWidget extends AppWidgetProvider {
    static PendingIntent go(Context c, String k, int code) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_GO).putExtra("go", k).putExtra("fromWidget", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, PtWidget.class));
        if (ids != null && ids.length > 0) new PtWidget().onUpdate(c, m, ids);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        String name = "Intervention en cours", line = "Appuie sur Début en arrivant";
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("wst", Context.MODE_PRIVATE).getString("json", "{}"));
            if (!o.optString("name").isEmpty()) name = o.optString("name");
            if (!o.optString("line").isEmpty()) line = o.optString("line");
        } catch (Exception ignored) { }
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_pt);
            v.setTextViewText(R.id.wp_name, name);
            v.setTextViewText(R.id.wp_line, line);
            v.setOnClickPendingIntent(R.id.wp_name, go(c, "cur", 610));
            v.setOnClickPendingIntent(R.id.wp_in, go(c, "pt-in", 611));
            v.setOnClickPendingIntent(R.id.wp_lunch, go(c, "pt-lunch", 612));
            v.setOnClickPendingIntent(R.id.wp_back, go(c, "pt-back", 613));
            v.setOnClickPendingIntent(R.id.wp_out, go(c, "pt-out", 614));
            m.updateAppWidget(id, v);
        }
    }
}
