package com.rishiraj.anywheredoor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the door offers for download:
 *  - files received from other devices, kept in Download/AnyWhereDoor
 *  - files shared from this phone, served straight from their original place (never copied)
 */
public class Shelf {

    public static class Item {
        public String id;
        public String name;
        public long size;
        public long time;
        public boolean fromPhone;   // shared from this phone vs received
        public String from;         // who sent it (received files)
        public File file;           // received files
        public Uri uri;             // shared files, or the media-scanned uri of a received file
    }

    private static final String PREFS = "shelf";
    private static final String KEY_SHARED = "shared";

    private final Context app;
    private final File dir;
    private final LinkedHashMap<String, Item> shared = new LinkedHashMap<>();
    private final Map<String, String> senders = new HashMap<>();   // file name -> sender label
    private final Map<String, Uri> scanned = new HashMap<>();      // file name -> content uri
    private long nextShareId = 1;

    public Shelf(Context context) {
        app = context.getApplicationContext();
        dir = pickDir(app);
        loadShared();
    }

    public File dir() { return dir; }

    public String dirLabel() {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String p = dir.getAbsolutePath();
        return p.startsWith(root) ? "Internal storage" + p.substring(root.length()) : p;
    }

    private static File pickDir(Context c) {
        File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AnyWhereDoor");
        if (canWrite(pub)) return pub;
        File own = new File(c.getExternalFilesDir(null), "received");
        own.mkdirs();
        return own;
    }

    private static boolean canWrite(File d) {
        try {
            if (!d.isDirectory() && !d.mkdirs()) return false;
            File probe = new File(d, ".awd-probe");
            try (FileOutputStream o = new FileOutputStream(probe)) { o.write(1); }
            return probe.delete();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- listing

    public synchronized List<Item> list() {
        List<Item> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                Item it = new Item();
                it.id = "f:" + f.getName();
                it.name = f.getName();
                it.size = f.length();
                it.time = f.lastModified();
                it.file = f;
                it.from = senders.get(f.getName());
                it.uri = scanned.get(f.getName());
                out.add(it);
            }
        }
        out.addAll(shared.values());
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    public synchronized Item get(String id) {
        if (id == null) return null;
        if (id.startsWith("u:")) return shared.get(id);
        if (id.startsWith("f:")) {
            String name = id.substring(2);
            if (!name.equals(Http.cleanName(name))) return null; // no path tricks
            File f = new File(dir, name);
            if (!f.isFile()) return null;
            Item it = new Item();
            it.id = id;
            it.name = name;
            it.size = f.length();
            it.time = f.lastModified();
            it.file = f;
            it.from = senders.get(name);
            it.uri = scanned.get(name);
            return it;
        }
        return null;
    }

    public InputStream open(Item it) throws IOException {
        if (it.file != null) return new FileInputStream(it.file);
        InputStream in = app.getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Can't open " + it.name);
        return in;
    }

    /** Received files are deleted; shared files are only taken off the shelf. */
    public synchronized boolean remove(String id) {
        if (id.startsWith("u:")) {
            Item it = shared.remove(id);
            if (it == null) return false;
            try {
                app.getContentResolver().releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            saveShared();
            return true;
        }
        Item it = get(id);
        if (it == null) return false;
        boolean ok = it.file.delete();
        if (ok) {
            senders.remove(it.name);
            Uri u = scanned.remove(it.name);
            if (u != null) {
                try { app.getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------- receiving

    /** A unique final name in the folder: "a.zip", "a (1).zip", ... */
    public synchronized File reserve(String wanted) {
        String name = Http.cleanName(wanted);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        File f = new File(dir, name);
        for (int i = 1; f.exists() || new File(dir, "." + f.getName() + ".part").exists(); i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
        }
        return f;
    }

    public synchronized void received(File f, String from) {
        if (from != null && !from.isEmpty()) senders.put(f.getName(), from);
        final String name = f.getName();
        MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, null, (path, uri) -> {
            if (uri != null) synchronized (Shelf.this) { scanned.put(name, uri); }
        });
    }

    public long freeSpace() {
        return dir.getUsableSpace();
    }

    // ---------------------------------------------------------------- sharing from the phone

    public synchronized int share(List<Uri> uris, boolean persistable) {
        int added = 0;
        outer:
        for (Uri u : uris) {
            for (Item it : shared.values()) if (it.uri.equals(u)) continue outer;
            if (persistable) {
                try {
                    app.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { persistable = false; }
            }
            Item it = describe(u);
            if (it == null) continue;
            shared.put(it.id, it);
            added++;
        }
        saveShared();
        return added;
    }

    private Item describe(Uri u) {
        String name = null;
        long size = -1;
        try (Cursor c = app.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception e) {
            return null;
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "file";
        if (size < 0) return null; // we need the size to serve it
        Item it = new Item();
        it.id = "u:" + (nextShareId++);
        it.name = Http.cleanName(name);
        it.size = size;
        it.time = System.currentTimeMillis();
        it.fromPhone = true;
        it.uri = u;
        return it;
    }

    private void loadShared() {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = p.getStringSet(KEY_SHARED, Collections.emptySet());
        List<Uri> uris = new ArrayList<>();
        for (String s : saved) uris.add(Uri.parse(s));
        for (Uri u : uris) {
            Item it = describe(u);   // drops files that were deleted or lost permission
            if (it != null) shared.put(it.id, it);
        }
        saveShared();
    }

    private void saveShared() {
        Set<String> s = new HashSet<>();
        for (Item it : shared.values()) s.add(it.uri.toString());
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_SHARED, s).apply();
    }
}
