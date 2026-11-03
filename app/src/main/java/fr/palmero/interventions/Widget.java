package fr.palmero.interventions;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/** Widget d'écran d'accueil personnalisable : jusqu'à 8 boutons choisis dans l'appli (Réglages → Widget personnalisé). */
public class Widget extends AppWidgetProvider {
    static final int[] SLOT = { R.id.w_0, R.id.w_1, R.id.w_2, R.id.w_3, R.id.w_4, R.id.w_5, R.id.w_6, R.id.w_7 };

    static PendingIntent go(Context c, String k, int code) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_GO).putExtra("go", k).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (k.startsWith("pt-")) i.putExtra("fromWidget", true);          // pointage : retour à l'écran d'accueil ensuite
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids != null && ids.length > 0) new Widget().onUpdate(c, m, ids);
    }

    static JSONArray items(Context c) {
        try { JSONArray a = new JSONArray(c.getSharedPreferences("wst", Context.MODE_PRIVATE).getString("items", "[]")); if (a.length() > 0) return a; } catch (Exception ignored) { }
        JSONArray d = new JSONArray();
        try {
            d.put(new JSONObject().put("k", "new").put("lb", "＋ Nouvelle\nintervention"));
            d.put(new JSONObject().put("k", "cur").put("lb", "📝 Rapport\nen cours"));
            d.put(new JSONObject().put("k", "today").put("lb", "🏠 Aujour-\nd'hui"));
        } catch (Exception ignored) { }
        return d;
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        JSONArray a = items(c); int n = Math.min(8, a.length());
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            for (int i = 0; i < 8; i++) {
                if (i < n) {
                    JSONObject o = a.optJSONObject(i); String k = o == null ? "" : o.optString("k"), lb = o == null ? "" : o.optString("lb");
                    if (!k.matches("[a-z0-9\\-]{2,12}")) { v.setViewVisibility(SLOT[i], View.INVISIBLE); continue; }
                    v.setViewVisibility(SLOT[i], View.VISIBLE); v.setTextViewText(SLOT[i], lb);
                    v.setOnClickPendingIntent(SLOT[i], go(c, k, 501 + i));
                } else v.setViewVisibility(SLOT[i], i < 4 && n <= 4 && i >= n ? View.GONE : View.INVISIBLE);
            }
            v.setViewVisibility(R.id.w_r2, n > 4 ? View.VISIBLE : View.GONE);
            m.updateAppWidget(id, v);
        }
    }
}
