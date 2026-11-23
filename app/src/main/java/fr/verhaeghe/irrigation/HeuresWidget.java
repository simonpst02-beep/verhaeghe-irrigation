package fr.verhaeghe.irrigation;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.HashMap;
import java.util.Map;

/**
 * Widget d'écran d'accueil : heures du jour et accès direct à chaque étape de la saisie.
 * Redimensionnable : mini 1×1 (total), petit (total + « Noter »), moyen (étapes + heures sup.),
 * grand (+ intervention en cours, dernière intervention, valider), très grand (+ semaine, mois, heures sup.).
 */
public class HeuresWidget extends AppWidgetProvider {
    static final String[] KEYS = {"prep", "trajet", "inter", "pause", "retour"};
    static final String[] LBL = {"Prépa", "Aller", "Sur place", "Pause", "Retour"};
    static final int[] IDS = {R.id.w_prep, R.id.w_trajet, R.id.w_inter, R.id.w_pause, R.id.w_retour};
    static final int MINI = -1, SMALL = 0, MEDIUM = 1, LARGE = 2, XL = 3;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle opts) {
        update(c, m, id);
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

    static RemoteViews build(Context c, int size) {
        SharedPreferences p = c.getSharedPreferences("widget", Context.MODE_PRIVATE);
        int layout = size == MINI ? R.layout.widget_heures_mini : size == SMALL ? R.layout.widget_heures_small : size == MEDIUM ? R.layout.widget_heures_medium : size == XL ? R.layout.widget_heures_xl : R.layout.widget_heures;
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setTextViewText(R.id.w_total, p.getString("total", "0 h 00"));
        v.setOnClickPendingIntent(R.id.w_head, open(c, "id", 21));
        if (size == MINI) return v;
        if (size == SMALL) {
            v.setOnClickPendingIntent(R.id.w_valider, open(c, "id", 23));
            return v;
        }
        String done = "," + p.getString("done", "") + ",";
        String hidden = "," + p.getString("hidden", "") + ",";
        for (int k = 0; k < KEYS.length; k++) {
            boolean d = done.contains("," + KEYS[k] + ",");
            v.setTextViewText(IDS[k], (d ? "✓ " : "") + LBL[k]);
            v.setViewVisibility(IDS[k], hidden.contains("," + KEYS[k] + ",") ? View.GONE : View.VISIBLE);
            v.setOnClickPendingIntent(IDS[k], open(c, KEYS[k], 10 + k));
        }
        v.setTextViewText(R.id.w_sup, "Heures sup. du mois : " + p.getString("sup", "\u2013"));
        if (size >= LARGE) {
            v.setTextViewText(R.id.w_last, "Derni\u00e8re intervention : " + p.getString("last", "\u2013"));
            v.setTextViewText(R.id.w_ot, p.getString("ot", "Touchez une étape pour noter vos heures"));
            v.setOnClickPendingIntent(R.id.w_ot, open(c, "id", 22));
            v.setOnClickPendingIntent(R.id.w_valider, open(c, "fin", 20));
        }
        if (size == XL) {
            v.setTextViewText(R.id.w_wk, p.getString("week", "\u2013"));
            v.setTextViewText(R.id.w_mo, p.getString("month", "\u2013"));
            v.setTextViewText(R.id.w_su, p.getString("sup", "\u2013"));
        }
        return v;
    }

    /** Taille choisie d'après l'espace donné au widget (en dp). */
    static int sizeFor(float w, float h) {
        if (w < 110) return MINI;
        if (w < 200 || h < 70) return SMALL;
        if (h < 140) return MEDIUM;
        if (h < 210) return LARGE;
        return XL;
    }

    static void update(Context c, AppWidgetManager m, int id) {
        RemoteViews v;
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12 et plus : le téléphone choisit la bonne présentation pendant le redimensionnement
            Map<SizeF, RemoteViews> map = new HashMap<>();
            map.put(new SizeF(40f, 40f), build(c, MINI));
            map.put(new SizeF(110f, 40f), build(c, SMALL));
            map.put(new SizeF(200f, 70f), build(c, MEDIUM));
            map.put(new SizeF(200f, 140f), build(c, LARGE));
            map.put(new SizeF(200f, 210f), build(c, XL));
            v = new RemoteViews(map);
        } else {
            Bundle o = m.getAppWidgetOptions(id);
            float w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            float h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
            v = build(c, sizeFor(w, h));
        }
        m.updateAppWidget(id, v);
    }

    /** Appelé par l'appli quand les heures ou la saisie en cours changent. */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        Class<?>[] all = {HeuresWidget.class, HeuresWidgetMini.class, HeuresWidgetPetit.class, HeuresWidgetMoyen.class, HeuresWidgetGrand.class, HeuresWidgetTresGrand.class};
        for (Class<?> k : all) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            for (int id : ids) update(c, m, id);
        }
    }
}
