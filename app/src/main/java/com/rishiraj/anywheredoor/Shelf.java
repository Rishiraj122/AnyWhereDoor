package com.rishiraj.anywheredoor;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the door offers for download:
 *  - files and folders received from other devices, kept in Download/AnyWhereDoor
 *  - files and folders shared from this phone, served from where they are (never copied)
 *
 * Every file keeps its original modified time. Folder contents are always listed
 * oldest first, which is the order they are uploaded in and the order they go into ZIPs.
 */
public class Shelf {

    /** A top-level thing on the shelf: a file or a folder. */
    public static class Item {
        public String id;
        public String name;
        public long size;
        public long time;       // the file's own modified time (files) / when it arrived (folders)
        public long arrived;    // for sorting the shelf: newest arrivals first
        public boolean fromPhone;
        public String from;
        public File file;       // received file or folder
        public Uri uri;         // shared file, or media-scanned uri of a received file
        public boolean folder;
        public int count;       // files in the folder
        public long first, last;// oldest and newest file time in the folder
        Uri tree;               // shared folder
        String treeRoot;        // its root document id
    }

    /** A file inside a folder. */
    public static class Entry {
        public String id;
        public String path;     // relative, starts with the folder's own name: "Photos/sub/a.jpg"
        public long size;
        public long time;
        Uri uri;                // files picked on the phone (batches)
    }

    /** Files picked on the phone and sent to the laptop as a run of ZIPs, oldest first. */
    public static class Batch {
        public String id, name;
        public final List<Entry> entries = new ArrayList<>();
        public int per, parts;
        public long size;
        public volatile int delivered;      // ZIPs the laptop has saved
        public volatile String claimedBy;   // the browser picking them up
        public volatile long touched;

        public List<Entry> part(int k) {
            return entries.subList(k * per, Math.min(entries.size(), (k + 1) * per));
        }

        public long partSize(int k) {
            long n = 0;
            for (Entry e : part(k)) n += e.size;
            return n;
        }

        public String partName(int k) {
            return String.format(Locale.ROOT, "%s_part%02dof%02d.zip", name, k + 1, parts);
        }
    }

    /** Oldest first; same time falls back to natural name order (img2 before img10). */
    public static final Comparator<Entry> BY_TIME = (a, b) -> {
        int c = Long.compare(a.time, b.time);
        return c != 0 ? c : natural(a.path, b.path);
    };

    private static final String PREFS = "shelf";
    private static final String KEY_SHARED = "shared";
    private static final String KEY_TREES = "trees";
    private static final String META = ".awd-meta";

    private final Context app;
    private final ContentResolver cr;
    private final File dir;
    private final LinkedHashMap<String, Item> shared = new LinkedHashMap<>();
    private final Map<String, Long> times = new HashMap<>();      // rel path -> original modified time
    private final Map<String, String> senders = new HashMap<>();  // top-level name -> sender
    private final Map<String, Long> arrivals = new HashMap<>();   // top-level name -> arrival time
    private final Map<String, Uri> scanned = new HashMap<>();
    private final Map<String, String> batchRoots = new HashMap<>();
    private final Map<String, long[]> folderSums = new HashMap<>(); // name -> {count, size, first, last}
    private final LinkedHashMap<String, Batch> batches = new LinkedHashMap<>();
    private long nextShareId = 1;
    private volatile int rev;

    public Shelf(Context context) {
        app = context.getApplicationContext();
        cr = app.getContentResolver();
        dir = pickDir(app);
        loadMeta();
        Thread t = new Thread(this::loadShared, "awd-shelf");
        t.setDaemon(true);
        t.start();
    }

    public File dir() { return dir; }
    public int rev() { return rev; }

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

    // ================================================================ listing

    public synchronized List<Item> list() {
        List<Item> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                Item it = f.isDirectory() ? folderItem(f) : fileItem(f, f.getName());
                if (it != null) out.add(it);
            }
        }
        out.addAll(shared.values());
        Collections.sort(out, (a, b) -> Long.compare(b.arrived, a.arrived));
        return out;
    }

    private Item fileItem(File f, String rel) {
        if (!f.isFile()) return null;
        Item it = new Item();
        it.id = "f:" + rel;
        it.name = f.getName();
        it.size = f.length();
        it.time = timeOf(f, rel);
        Long a = arrivals.get(rel);
        it.arrived = a != null ? a : it.time;
        it.file = f;
        it.from = senders.get(rel);
        it.uri = scanned.get(rel);
        return it;
    }

    private Item folderItem(File d) {
        String name = d.getName();
        long[] s = folderSums.get(name);
        if (s == null) {
            List<Entry> es = new ArrayList<>();
            walk(d, name + "/", es);
            s = new long[]{0, 0, Long.MAX_VALUE, 0};
            for (Entry e : es) sum(s, e.size, e.time);
            folderSums.put(name, s);
        }
        Item it = new Item();
        it.id = "d:" + name;
        it.name = name;
        it.folder = true;
        it.file = d;
        it.count = (int) s[0];
        it.size = s[1];
        it.first = s[0] > 0 ? s[2] : 0;
        it.last = s[3];
        Long a = arrivals.get(name);
        it.arrived = a != null ? a : d.lastModified();
        it.time = it.arrived;
        it.from = senders.get(name);
        return it;
    }

    private static void sum(long[] s, long size, long time) {
        s[0]++;
        s[1] += size;
        s[2] = Math.min(s[2], time);
        s[3] = Math.max(s[3], time);
    }

    private long timeOf(File f, String rel) {
        Long t = times.get(rel);
        return t != null ? t : f.lastModified();
    }

    private void walk(File d, String prefix, List<Entry> out) {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            String rel = prefix + k.getName();
            if (k.isDirectory()) { walk(k, rel + "/", out); continue; }
            Entry e = new Entry();
            e.id = "f:" + rel;
            e.path = rel;
            e.size = k.length();
            e.time = timeOf(k, rel);
            out.add(e);
        }
    }

    /** Files inside a folder (or everything on the shelf for "all"), oldest first. */
    public List<Entry> entries(String id) {
        List<Entry> out = new ArrayList<>();
        if ("all".equals(id)) {
            for (Item it : list()) {
                if (it.folder) out.addAll(entries(it.id));
                else {
                    Entry e = new Entry();
                    e.id = it.id;
                    e.path = it.name;
                    e.size = it.size;
                    e.time = it.time;
                    out.add(e);
                }
            }
        } else if (id != null && id.startsWith("d:")) {
            File d = resolve(id.substring(2));
            if (d != null && d.isDirectory()) synchronized (this) { walk(d, d.getName() + "/", out); }
        } else if (id != null && id.startsWith("t:")) {
            Item it;
            synchronized (this) { it = shared.get(id); }
            if (it != null && it.folder) walkTree(it, out);
        }
        Collections.sort(out, BY_TIME);
        return out;
    }

    // ================================================================ lookup / open / remove

    public Item get(String id) {
        if (id == null) return null;
        if (id.startsWith("u:")) synchronized (this) { return shared.get(id); }
        if (id.startsWith("t:")) {
            String[] p = id.split(":", 3);
            Item t;
            synchronized (this) { t = shared.get("t:" + p[1]); }
            if (t == null) return null;
            if (p.length < 3) return t;
            return treeFile(t, p[2]);
        }
        if (id.startsWith("d:")) {
            File d = resolve(id.substring(2));
            if (d == null || !d.isDirectory() || d.getParentFile() == null || !d.getParentFile().equals(dir)) return null;
            synchronized (this) { return folderItem(d); }
        }
        if (id.startsWith("f:")) {
            String rel = id.substring(2);
            File f = resolve(rel);
            if (f == null) return null;
            synchronized (this) { return fileItem(f, rel); }
        }
        return null;
    }

    /** Relative path -> file inside the shelf folder, refusing anything that could escape it. */
    private File resolve(String rel) {
        if (rel == null || rel.isEmpty()) return null;
        File f = dir;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || !seg.equals(Http.cleanName(seg))) return null;
            f = new File(f, seg);
        }
        return f;
    }

    public InputStream open(Item it) throws IOException {
        if (it.file != null) return new FileInputStream(it.file);
        InputStream in = cr.openInputStream(it.uri);
        if (in == null) throw new IOException("Can't open " + it.name);
        return in;
    }

    public InputStream open(Entry e) throws IOException {
        if (e.uri != null) {
            InputStream in = cr.openInputStream(e.uri);
            if (in == null) throw new IOException("Can't open " + e.path);
            return in;
        }
        Item it = get(e.id);
        if (it == null) throw new IOException(e.path + " is gone");
        return open(it);
    }

    /** Received files and folders are deleted; things shared from the phone are only taken off the shelf. */
    public boolean remove(String id) {
        if (id == null) return false;
        if (id.startsWith("u:") || (id.startsWith("t:") && id.split(":").length == 2)) {
            Item it;
            synchronized (this) { it = shared.remove(id); saveShared(); rev++; }
            if (it == null) return false;
            try {
                cr.releasePersistableUriPermission(it.folder ? it.tree : it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            return true;
        }
        Item it = get(id);
        if (it == null || it.file == null) return false;
        boolean ok;
        synchronized (this) {
            ok = deleteTree(it.file);
            String top = it.folder ? it.name : id.substring(2).split("/")[0];
            folderSums.remove(top);
            if (!it.folder && id.substring(2).equals(it.name)) {
                senders.remove(it.name);
                arrivals.remove(it.name);
                Uri u = scanned.remove(it.name);
                if (u != null) { try { cr.delete(u, null, null); } catch (Exception ignored) { } }
            }
            rev++;
        }
        return ok;
    }

    private static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete();
    }

    // ================================================================ receiving

    /**
     * Where an uploaded file goes. "a.zip" lands in the shelf folder with a unique name;
     * "Photos/sub/a.jpg" lands in a folder named Photos — or "Photos (1)" if a different
     * upload already used that name. All files of one upload (same batch) share one folder.
     * The returned file's ".name.part" companion already exists, so names can't collide.
     */
    public synchronized File target(String path, String batch) throws IOException {
        List<String> parts = new ArrayList<>();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue;
            parts.add(Http.cleanName(seg));
        }
        if (parts.isEmpty()) throw new IOException("No file name");
        File f;
        if (parts.size() == 1) {
            f = unique(dir, parts.get(0));
        } else {
            String key = batch + "\n" + parts.get(0);
            String root = batchRoots.get(key);
            if (root == null) {
                File d = unique(dir, parts.get(0));
                if (!d.mkdirs()) throw new IOException("Couldn't make folder " + d.getName());
                root = d.getName();
                batchRoots.put(key, root);
                arrivals.put(root, System.currentTimeMillis());
                appendMeta("a", root, String.valueOf(System.currentTimeMillis()));
            }
            f = new File(dir, root);
            for (int i = 1; i < parts.size(); i++) f = new File(f, parts.get(i));
            File parent = f.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Couldn't make folder " + parent.getName());
        }
        File part = partOf(f);
        part.delete();
        if (!part.createNewFile()) throw new IOException("Couldn't create " + f.getName());
        return f;
    }

    static File partOf(File f) {
        return new File(f.getParentFile(), "." + f.getName() + ".part");
    }

    private File unique(File parent, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        File f = new File(parent, name);
        for (int i = 1; f.exists() || partOf(f).exists(); i++) f = new File(parent, base + " (" + i + ")" + ext);
        return f;
    }

    public String rel(File f) {
        String d = dir.getAbsolutePath() + "/";
        String p = f.getAbsolutePath();
        return p.startsWith(d) ? p.substring(d.length()) : f.getName();
    }

    /** An upload finished and was renamed into place. */
    public synchronized void received(File f, long mtime, String from, boolean replaced) {
        String rel = rel(f);
        String top = rel.split("/")[0];
        if (mtime > 0) {
            f.setLastModified(mtime);                 // best effort; some storage ignores it
            times.put(rel, mtime);                    // so we keep our own record too
            appendMeta("t", rel, String.valueOf(mtime));
        }
        if (from != null && !from.isEmpty() && !from.equals(senders.get(top))) {
            senders.put(top, from);
            appendMeta("f", top, from);
        }
        if (top.equals(rel)) {
            long now = System.currentTimeMillis();
            arrivals.put(rel, now);
            appendMeta("a", rel, String.valueOf(now));
            MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, null, (path, uri) -> {
                if (uri != null) synchronized (Shelf.this) { scanned.put(rel, uri); }
            });
        } else {
            long[] s = folderSums.get(top);
            if (s != null) {
                if (replaced) folderSums.remove(top);
                else sum(s, f.length(), mtime > 0 ? mtime : f.lastModified());
            }
        }
        rev++;
    }

    public long freeSpace() {
        return dir.getUsableSpace();
    }

    // ================================================================ metadata log

    /** Append-only "kind \t path \t value" lines; rewritten compactly at start-up. */
    private void appendMeta(String kind, String path, String value) {
        try (Writer w = new FileWriter(new File(dir, META), true)) {
            w.write(kind + "\t" + path.replace('\t', ' ') + "\t" + value.replace('\t', ' ').replace('\n', ' ') + "\n");
        } catch (IOException ignored) { }
    }

    private void loadMeta() {
        File m = new File(dir, META);
        if (!m.isFile()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(m))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                try {
                    switch (p[0]) {
                        case "t": times.put(p[1], Long.parseLong(p[2])); break;
                        case "a": arrivals.put(p[1], Long.parseLong(p[2])); break;
                        case "f": senders.put(p[1], p[2]); break;
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        // drop records of files that are gone, then rewrite
        times.keySet().removeIf(k -> !new File(dir, k).exists());
        arrivals.keySet().removeIf(k -> !new File(dir, k).exists());
        senders.keySet().removeIf(k -> !new File(dir, k).exists());
        try (Writer w = new FileWriter(m, false)) {
            for (Map.Entry<String, Long> e : times.entrySet()) w.write("t\t" + e.getKey() + "\t" + e.getValue() + "\n");
            for (Map.Entry<String, Long> e : arrivals.entrySet()) w.write("a\t" + e.getKey() + "\t" + e.getValue() + "\n");
            for (Map.Entry<String, String> e : senders.entrySet()) w.write("f\t" + e.getKey() + "\t" + e.getValue() + "\n");
        } catch (IOException ignored) { }
    }

    // ================================================================ sharing from the phone

    /** Share single files. Runs off the main thread. Returns how many were added. */
    public int share(List<Uri> uris, boolean persistable) {
        int added = 0;
        for (Uri u : uris) {
            synchronized (this) {
                boolean dup = false;
                for (Item it : shared.values()) if (!it.folder && u.equals(it.uri)) dup = true;
                if (dup) continue;
            }
            if (persistable) {
                try { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
            }
            Item it = describe(u);
            if (it == null) continue;
            synchronized (this) {
                it.id = "u:" + (nextShareId++);
                shared.put(it.id, it);
                rev++;
            }
            added++;
        }
        synchronized (this) { saveShared(); }
        return added;
    }

    /** Share a whole folder picked with the system folder picker. Runs off the main thread. */
    public Item shareTree(Uri tree) {
        synchronized (this) {
            for (Item it : shared.values()) if (it.folder && tree.equals(it.tree)) return it;
        }
        try { cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        Item it = describeTree(tree);
        if (it == null) return null;
        synchronized (this) {
            it.id = "t:" + (nextShareId++);
            shared.put(it.id, it);
            saveShared();
            rev++;
        }
        return it;
    }

    private Item describe(Uri u) {
        String name = null;
        long size = -1, time = 0;
        try (Cursor c = cr.query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                int ti = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int di = c.getColumnIndex("date_modified");   // gallery uris: seconds
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                if (ti >= 0 && !c.isNull(ti)) time = c.getLong(ti);
                else if (di >= 0 && !c.isNull(di)) time = c.getLong(di) * 1000;
            }
        } catch (Exception e) {
            return null;
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "file";
        if (size < 0) return null; // we need the size to serve it
        Item it = new Item();
        it.name = Http.cleanName(name);
        it.size = size;
        it.arrived = System.currentTimeMillis();
        it.time = time > 0 ? time : it.arrived;
        it.fromPhone = true;
        it.uri = u;
        return it;
    }

    private Item describeTree(Uri tree) {
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            String name = "Folder";
            try (Cursor c = cr.query(rootDoc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            }
            Item it = new Item();
            it.name = Http.cleanName(name);
            it.folder = true;
            it.fromPhone = true;
            it.tree = tree;
            it.treeRoot = rootId;
            it.arrived = System.currentTimeMillis();
            it.time = it.arrived;
            List<Entry> es = new ArrayList<>();
            walkTree(it, es);
            long[] s = {0, 0, Long.MAX_VALUE, 0};
            for (Entry e : es) sum(s, e.size, e.time);
            it.count = (int) s[0];
            it.size = s[1];
            it.first = s[0] > 0 ? s[2] : 0;
            it.last = s[3];
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    private void walkTree(Item t, List<Entry> out) {
        walkTree(t, t.treeRoot, t.name + "/", out);
    }

    private void walkTree(Item t, String docId, String prefix, List<Entry> out) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t.tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = cr.query(kids, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.isNull(1) ? "file" : Http.cleanName(c.getString(1));
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, prefix + name + "/"});
                    continue;
                }
                Entry e = new Entry();
                e.id = t.id + ":" + id;
                e.path = prefix + name;
                e.size = c.isNull(3) ? 0 : c.getLong(3);
                e.time = c.isNull(4) ? 0 : c.getLong(4);
                out.add(e);
            }
        } catch (Exception ignored) { }
        for (String[] d : dirs) walkTree(t, d[0], d[1], out);
    }

    private Item treeFile(Item t, String docId) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(t.tree, docId);
        Item it = describe(u);
        if (it == null) return null;
        it.id = t.id + ":" + docId;
        return it;
    }

    private void loadShared() {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> files = p.getStringSet(KEY_SHARED, Collections.emptySet());
        Set<String> trees = p.getStringSet(KEY_TREES, Collections.emptySet());
        for (String s : new ArrayList<>(files)) {
            Item it = describe(Uri.parse(s));   // drops files that were deleted or lost permission
            if (it == null) continue;
            synchronized (this) { it.id = "u:" + (nextShareId++); shared.put(it.id, it); rev++; }
        }
        for (String s : new ArrayList<>(trees)) {
            Item it = describeTree(Uri.parse(s));
            if (it == null) continue;
            synchronized (this) { it.id = "t:" + (nextShareId++); shared.put(it.id, it); rev++; }
        }
        synchronized (this) { saveShared(); }
    }

    private void saveShared() {
        Set<String> f = new HashSet<>(), t = new HashSet<>();
        for (Item it : shared.values()) {
            if (it.folder) t.add(it.tree.toString());
            else f.add(it.uri.toString());
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_SHARED, f).putStringSet(KEY_TREES, t).apply();
    }

    // ================================================================ batches (phone -> laptop in ZIPs)

    /** Runs off the main thread: reads names, sizes and times of the picked files. */
    public Batch makeBatch(List<Uri> uris, int per) {
        Batch b = new Batch();
        for (Uri u : uris) {
            Item it = describe(u);
            if (it == null) continue;
            Entry e = new Entry();
            e.uri = u;
            e.path = it.name;
            e.size = it.size;
            e.time = it.time;
            b.entries.add(e);
            b.size += e.size;
        }
        if (b.entries.isEmpty()) return null;
        Collections.sort(b.entries, BY_TIME);
        // same name twice in one batch (e.g. from two albums) -> "a (1).jpg"
        Set<String> seen = new HashSet<>();
        for (Entry e : b.entries) {
            String n = e.path, base = n, ext = "";
            int dot = n.lastIndexOf('.');
            if (dot > 0) { base = n.substring(0, dot); ext = n.substring(dot); }
            for (int i = 1; !seen.add(n.toLowerCase(Locale.ROOT)); i++) n = base + " (" + i + ")" + ext;
            e.path = n;
        }
        b.per = Math.max(1, per);
        b.parts = (b.entries.size() + b.per - 1) / b.per;
        b.name = "AnyWhereDoor_" + new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(new java.util.Date());
        synchronized (this) {
            b.id = "b:" + (nextShareId++);
            batches.put(b.id, b);
            rev++;
        }
        return b;
    }

    public synchronized List<Batch> batches() { return new ArrayList<>(batches.values()); }
    public synchronized Batch batch(String id) { return id == null ? null : batches.get(id); }
    public synchronized void dropBatch(String id) { batches.remove(id); rev++; }
    public synchronized void touch() { rev++; }

    // ================================================================ natural sort

    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
