package fr.verhaeghe.irrigation;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

/** Widget d'écran d'accueil : heures du jour et accès direct à chaque étape de la saisie. */
public class HeuresWidget extends AppWidgetProvider {
    static final String[] KEYS = {"prep", "trajet", "inter", "pause", "retour"};
    static final String[] LBL = {"Prépa", "Aller", "Sur place", "Pause", "Retour"};
    static final int[] IDS = {R.id.w_prep, R.id.w_trajet, R.id.w_inter, R.id.w_pause, R.id.w_retour};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    /** Ouvre l'appli directement sur une étape de la saisie « étape par étape ». */
    static PendingIntent open(Context c, String step, int rq) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("fr.verhaeghe.irrigation.ETAPE_" + step);
        i.putExtra("wstep", step);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, rq, i, f);
    }

    static RemoteViews build(Context c) {
        SharedPreferences p = c.getSharedPreferences("widget", Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_heures);
        v.setTextViewText(R.id.w_total, p.getString("total", "0 h 00"));
        v.setTextViewText(R.id.w_ot, p.getString("ot", "Touchez une étape pour noter vos heures"));
        String done = "," + p.getString("done", "") + ",";
        String hidden = "," + p.getString("hidden", "") + ",";
        for (int k = 0; k < KEYS.length; k++) {
            boolean d = done.contains("," + KEYS[k] + ",");
            v.setTextViewText(IDS[k], (d ? "✓ " : "") + LBL[k]);
            v.setViewVisibility(IDS[k], hidden.contains("," + KEYS[k] + ",") ? android.view.View.GONE : android.view.View.VISIBLE);
            v.setOnClickPendingIntent(IDS[k], open(c, KEYS[k], 10 + k));
        }
        v.setOnClickPendingIntent(R.id.w_valider, open(c, "fin", 20));
        v.setOnClickPendingIntent(R.id.w_head, open(c, "id", 21));
        v.setOnClickPendingIntent(R.id.w_ot, open(c, "id", 22));
        return v;
    }

    /** Appelé par l'appli quand les heures ou la saisie en cours changent. */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, HeuresWidget.class));
        for (int id : ids) m.updateAppWidget(id, build(c));
    }
}
