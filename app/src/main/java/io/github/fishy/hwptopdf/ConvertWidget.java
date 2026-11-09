package io.github.fishy.hwptopdf;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** A home-screen button: tap it to pick a file, tap the last line to reopen the latest PDF. */
public class ConvertWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = views(context);
        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ConvertWidget.class));
        if (ids.length > 0) {
            manager.updateAppWidget(ids, views(context));
        }
    }

    private static RemoteViews views(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setOnClickPendingIntent(R.id.widget_root, launch(context, MainActivity.EXTRA_PICK, 1));

        String last = MainActivity.lastName(context);
        if (last == null) {
            views.setTextViewText(R.id.widget_last, context.getString(R.string.widget_hint));
        } else {
            views.setTextViewText(R.id.widget_last, context.getString(R.string.widget_last, last));
            views.setOnClickPendingIntent(R.id.widget_last, launch(context, MainActivity.EXTRA_OPEN_LAST, 2));
        }
        return views;
    }

    private static PendingIntent launch(Context context, String extra, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(extra, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
