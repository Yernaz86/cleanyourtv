package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Continue watching: other apps' Watch Next rows from the TV provider (docs/watch-next.md), shown as one row of
 * tiles under the dock (config.xml, continue_watching). Needs READ_TV_LISTINGS, granted with pm grant; without
 * ACCESS_ALL_EPG_DATA the provider returns only rows an app marked searchable and refuses any selection, so the
 * whole table is read and filtered here. Posters are https links, fetched once and kept in the cache directory.
 */
final class WatchNext {
    private static final String TAG = "WatchNext";
    /** Component class prefixes of the row's tiles and of the empty slots that keep the row to one line. */
    static final String TILE = "watch-next/", PAD = "watch-next-pad/";
    /** The tile image: 16:9, about twice a tile's width so the focus zoom stays sharp. */
    private static final int POSTER_W = 480, POSTER_H = 270;
    /** The backdrop behind the shelf while a tile has focus: 16:9 at two thirds of a 1080p screen. */
    private static final int BACKDROP_W = 1280, BACKDROP_H = 720;
    /** A poster larger than this is not an image an app meant for a TV row; it is not downloaded. */
    private static final int MAX_POSTER_BYTES = 2 * 1024 * 1024;

    static final class Item {
        final String pkg;
        final String title;
        final String poster;
        final String intent;
        final long lastEngaged;

        Item(String pkg, String title, String poster, String intent, long lastEngaged) {
            this.pkg = pkg;
            this.title = title;
            this.poster = poster;
            this.intent = intent;
            this.lastEngaged = lastEngaged;
        }
    }

    private WatchNext() {
    }

    /** Browsable rows, most recently watched first; empty when the permission is missing or nothing is visible. */
    static List<Item> read(Context ctx) {
        List<Item> items = new ArrayList<>();
        String[] projection = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        };
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection,
                null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue;
                items.add(new Item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)));
            }
        } catch (RuntimeException e) {
            // SecurityException without READ_TV_LISTINGS, or a provider that is missing on this firmware.
            Log.w(TAG, "cannot read Watch Next: " + e);
        }
        items.sort((a, b) -> Long.compare(b.lastEngaged, a.lastEngaged));
        return items;
    }

    /**
     * The row's tiles: up to {@code max} programmes from installed apps, newest first, each with its poster, or
     * the app's icon and the title when the poster cannot be fetched. Runs off the main thread (network).
     */
    static List<AppAdapter.Entry> entries(Context ctx, int max) {
        PackageManager pm = ctx.getPackageManager();
        List<AppAdapter.Entry> row = new ArrayList<>();
        Set<String> components = new HashSet<>(), posters = new HashSet<>();
        for (Item item : read(ctx)) {
            if (row.size() >= max) break;
            if (item.pkg == null || item.title == null || item.intent == null) continue;
            ApplicationInfo app;
            try {
                app = pm.getApplicationInfo(item.pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            ComponentName component = new ComponentName(item.pkg, TILE + Integer.toHexString(item.intent.hashCode()));
            if (!components.add(component.flattenToShortString())) continue;   // two rows for the same programme
            if (item.poster != null) posters.add(cacheName(item.poster));
            Bitmap poster;
            try {
                poster = poster(ctx, item.poster);
            } catch (RuntimeException e) {
                // A poster that cannot be drawn costs that tile its artwork, never Home.
                Log.w(TAG, "poster not drawn: " + e);
                poster = null;
            }
            Drawable banner = poster != null ? new BitmapDrawable(ctx.getResources(), poster) : null;
            Drawable icon = banner == null ? pm.getApplicationIcon(app) : null;
            String appName = Cards.cleanLabel(String.valueOf(pm.getApplicationLabel(app)), item.pkg);
            row.add(new AppAdapter.Entry(AppAdapter.Entry.WATCH, Cards.cleanLabel(item.title, appName, 120), component,
                    banner, icon, appName, 0, item.intent, poster != null ? item.poster : null));
        }
        prune(ctx, posters);
        return row;
    }

    /** What the row shows, to skip redrawing Home when nothing changed since the last read. */
    static String signature(List<AppAdapter.Entry> row) {
        StringBuilder sb = new StringBuilder();
        for (AppAdapter.Entry e : row) {
            sb.append(e.component.flattenToShortString()).append(e.banner != null ? '+' : '-').append(e.label).append('\n');
        }
        return sb.toString();
    }

    /** Empty slots after {@code shown} tiles so the apps start on the next line; none for an empty or full row. */
    static int padding(int shown, int columns) {
        return shown <= 0 || shown >= columns ? 0 : columns - shown;
    }

    /** A 16:9 tile image for the poster at this https URL, or null when it cannot be fetched or decoded. */
    private static Bitmap poster(Context ctx, String url) {
        if (url == null || !url.startsWith("https://")) return null;
        File file = new File(cacheDir(ctx), cacheName(url));
        if (!file.isFile() && !download(url, file)) return null;
        return compose(file, POSTER_W, POSTER_H, false);
    }

    /**
     * The shelf's backdrop for a tile's poster, from the copy the row already cached (no network, so it can run
     * on every focus change); null when it is not cached or cannot be decoded. Off the main thread.
     */
    static Bitmap backdrop(Context ctx, String url) {
        if (url == null) return null;
        File file = new File(cacheDir(ctx), cacheName(url));
        return file.isFile() ? compose(file, BACKDROP_W, BACKDROP_H, true) : null;
    }

    /**
     * Draws the image in {@code file} into a {@code w}×{@code h} bitmap: a landscape image fills it, a portrait
     * poster (Kinopoisk) stands at full height over a dimmed blur of its own colours, a tiny copy stretched across,
     * which costs nothing next to a real blur. {@code atEnd} puts the poster towards the right, leaving the left
     * side to the shelf's title.
     */
    private static Bitmap compose(File file, int w, int h, boolean atEnd) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            file.delete();
            return null;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;   // the field starts at 0
        while (o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect whole = new Rect(0, 0, w, h);
        float aspect = (float) src.getWidth() / src.getHeight();
        if (AppAdapter.isBannerShaped(src.getWidth(), src.getHeight())) {
            canvas.drawBitmap(src, centerCrop(src.getWidth(), src.getHeight(), w, h), whole, paint);
        } else {
            Bitmap tiny = Bitmap.createScaledBitmap(src, 8, Math.max(1, Math.round(8 / aspect)), true);
            paint.setColorFilter(new LightingColorFilter(0xFF5A5A5A, 0));
            canvas.drawBitmap(tiny, centerCrop(tiny.getWidth(), tiny.getHeight(), w, h), whole, paint);
            paint.setColorFilter(null);
            tiny.recycle();
            int pw = Math.round(h * aspect), left = atEnd ? w - pw - w / 10 : (w - pw) / 2;
            canvas.drawBitmap(src, null, new Rect(left, 0, left + pw, h), paint);
        }
        src.recycle();
        return out;
    }

    /** The part of a {@code w}×{@code h} image that fills a {@code dw}×{@code dh} frame, centred. */
    private static Rect centerCrop(int w, int h, int dw, int dh) {
        float scale = Math.max((float) dw / w, (float) dh / h);
        int cw = Math.round(dw / scale), ch = Math.round(dh / scale);
        int x = (w - cw) / 2, y = (h - ch) / 2;
        return new Rect(x, y, x + cw, y + ch);
    }

    private static boolean download(String url, File file) {
        File part = new File(file.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16 * 1024];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_POSTER_BYTES) return false;
                    out.write(buf, 0, n);
                }
            }
            return part.renameTo(file);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "poster not fetched: " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            part.delete();
        }
    }

    /** Drops cached posters no row uses any more. */
    private static void prune(Context ctx, Set<String> keep) {
        File[] files = cacheDir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "watch-next");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static String cacheName(String url) {
        return Integer.toHexString(url.hashCode());
    }
}
