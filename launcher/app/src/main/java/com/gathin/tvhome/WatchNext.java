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
import android.text.TextUtils;
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
    /** The backdrop behind the shelf while a tile has focus: a full 1080p screen, so it is not stretched. */
    private static final int BACKDROP_W = 1920, BACKDROP_H = 1080;
    /** A poster larger than this is not an image an app meant for a TV row; it is not downloaded. */
    private static final int MAX_POSTER_BYTES = 2 * 1024 * 1024;

    static final class Item {
        final String pkg;
        final String title;
        final String poster;
        final String intent;
        final long lastEngaged;
        /** Where playback stopped and how long the programme is, in ms; 0 when the app does not say. */
        long position, duration;
        /** Season and episode as the app numbers them ("2", "5"), and the episode's own title; null when absent. */
        String season, episode, episodeTitle;

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
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
        };
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection,
                null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue;
                Item item = new Item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4));
                item.position = c.getLong(6);
                item.duration = c.getLong(7);
                item.season = emptyToNull(c.getString(8));
                item.episode = emptyToNull(c.getString(9));
                item.episodeTitle = emptyToNull(c.getString(10));
                items.add(item);
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
            if (item.poster != null) {
                posters.add(cacheName(item.poster));
                // The shelf's larger copy, fetched now so focusing the tile shows it without a wait.
                String large = largeArt(item.poster);
                if (!large.equals(item.poster)) {
                    posters.add(cacheName(large));
                    File file = new File(cacheDir(ctx), cacheName(large));
                    if (!file.isFile()) download(large, file);
                }
            }
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
            AppAdapter.Entry entry = new AppAdapter.Entry(AppAdapter.Entry.WATCH, Cards.cleanLabel(item.title, appName, 120),
                    component, banner, icon, appName, 0, item.intent, poster != null ? item.poster : null);
            entry.progress = progress(item.position, item.duration);
            entry.detail = detail(ctx, item);
            row.add(entry);
        }
        prune(ctx, posters);
        return row;
    }

    /** What the row shows, to skip redrawing Home when nothing changed since the last read. */
    static String signature(List<AppAdapter.Entry> row) {
        StringBuilder sb = new StringBuilder();
        for (AppAdapter.Entry e : row) {
            sb.append(e.component.flattenToShortString()).append(e.banner != null ? '+' : '-').append(e.label)
                    .append('|').append(Math.round(e.progress * 100)).append('|').append(e.detail).append('\n');
        }
        return sb.toString();
    }

    /**
     * How far along the bar is, 0 to 1, or -1 for no bar: the app gave no duration, or nothing was watched yet (a
     * next episode). A programme just started still shows a sliver, so the bar never looks empty.
     */
    static float progress(long position, long duration) {
        if (duration <= 0 || position <= 0) return -1f;
        return Math.max(0.03f, Math.min(1f, (float) position / duration));
    }

    /** Whole minutes left, rounded up, or -1 when the app gave no position or duration. */
    static long minutesLeft(long position, long duration) {
        if (duration <= 0 || position <= 0 || position >= duration) return -1;
        return (duration - position + 59_999) / 60_000;
    }

    /**
     * The shelf's line under the title: "Season 2, episode 5 · Pilot · 23 min left", from whatever of that the app
     * gave; null when it gave none. An episode title that only repeats the programme's title is left out.
     */
    private static String detail(Context ctx, Item i) {
        List<String> parts = new ArrayList<>();
        if (i.season != null && i.episode != null) {
            parts.add(ctx.getString(R.string.watch_season_episode, i.season, i.episode));
        } else if (i.episode != null) {
            parts.add(ctx.getString(R.string.watch_episode, i.episode));
        }
        if (i.episodeTitle != null && !i.episodeTitle.equals(i.title)) {
            String episodeTitle = Cards.cleanLabel(i.episodeTitle, "", 60);
            if (!episodeTitle.isEmpty()) parts.add(episodeTitle);
        }
        long left = minutesLeft(i.position, i.duration);
        if (left >= 60) parts.add(ctx.getString(R.string.watch_left_hours, left / 60, left % 60));
        else if (left > 0) parts.add(ctx.getString(R.string.watch_left_minutes, left));
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
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
        File large = new File(cacheDir(ctx), cacheName(largeArt(url)));
        if (large.isFile()) {
            Bitmap art = compose(large, BACKDROP_W, BACKDROP_H, true);
            if (art != null) return art;
        }
        File file = new File(cacheDir(ctx), cacheName(url));
        return file.isFile() ? compose(file, BACKDROP_W, BACKDROP_H, true) : null;
    }

    /**
     * The same artwork in a size fit for the whole screen, where its CDN serves one: Kinopoisk's posters come from
     * Yandex's get-ott store, whose last path part is the size, and its row asks for 460x690, which arrives as a
     * 460x259 still. Any other URL is returned as it is (YouTube's hq720 is already its largest).
     */
    static String largeArt(String url) {
        int slash = url.lastIndexOf('/');
        if (!url.startsWith("https://") || !url.contains(".yandex.net/get-ott/") || slash < 0
                || !url.substring(slash + 1).matches("\\d+x\\d+")) {
            return url;
        }
        return url.substring(0, slash + 1) + "1920x1080";
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
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // photos, no alpha: half the memory of a 1080p decode
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
