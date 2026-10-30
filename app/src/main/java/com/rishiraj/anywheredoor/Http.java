package com.rishiraj.anywheredoor;

import android.content.Context;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tiny HTTP/1.1 server made for one job: moving big files in and out of the phone
 * without holding them in memory. Every response closes the connection.
 *
 *   GET  /                                 the web page (assets/index.html)
 *   GET  /api/state                        phone name, free space, shelf, transfers (JSON)
 *   GET  /api/folder?id=                   files in a folder, oldest first (JSON)
 *   PUT  /api/upload?path=&mtime=&batch=&from=   raw file body, Content-Length required
 *   GET  /api/file?id=[&dl=1]              one file (supports Range, so browsers can resume)
 *   GET  /api/zip?id=                      a folder (or id=all) as a ZIP, oldest file first,
 *                                          every entry stamped with its original time
 *   POST /api/delete?id=                   delete a received file/folder, or unshare a phone one
 *   GET  /api/zip?id=b:N&part=k            part k of a batch picked on the phone
 *   POST /api/claim?id=b:N                 a browser offers to pick up a batch (first one wins)
 *   POST /api/sent?id=b:N&part=k           that browser saved part k
 *   POST /api/clean                        clean the cache on the phone and on every open page
 */
public class Http {

    public static final int DEFAULT_PORT = 8080;
    private static final int BUF = 1024 * 1024;

    /** A transfer in progress, shown on the phone and in the browser. */
    public static class Active {
        public final String name;
        public final long size;
        public final boolean upload;   // true = coming into the phone
        public final String who;
        public final String group;     // top-level folder for folder uploads, else null
        public final long start = System.nanoTime();
        public volatile long done;
        public volatile int files;     // for ZIPs: files written so far
        public volatile int fileCount;

        Active(String name, long size, boolean upload, String who, String group) {
            this.name = name; this.size = size; this.upload = upload; this.who = who; this.group = group;
        }

        public double speed() {
            double s = (System.nanoTime() - start) / 1e9;
            return s > 0.3 ? done / s : 0;
        }
    }

    public interface Events {
        void onTransferStart(Active a);
        void onTransferEnd(Active a, boolean ok, String error);
    }

    private final Context ctx;
    private final Shelf shelf;
    private final Events events;
    private final String phoneName;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    public final List<Active> active = new CopyOnWriteArrayList<>();

    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile int port = DEFAULT_PORT;
    private byte[] pageCache;
    /** Bumped by a clean; every open page sees the new value in /api/state and clears itself. */
    private volatile long cleanEpoch;

    public Http(Context ctx, Shelf shelf, String phoneName, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.shelf = shelf;
        this.phoneName = phoneName;
        this.events = events;
    }

    public int port() { return port; }
    public boolean running() { return running; }

    /** Cleans the phone's cache and tells every connected page to clean its own. Returns bytes freed on the phone. */
    public long cleanAll() {
        long freed = shelf.clean();
        pageCache = null;
        cleanEpoch = System.currentTimeMillis();
        return freed;
    }

    public void start() throws IOException {
        IOException last = null;
        for (int p = DEFAULT_PORT; p < DEFAULT_PORT + 10; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.setReceiveBufferSize(2 * 1024 * 1024);
                ss.bind(new InetSocketAddress(p));
                server = ss;
                port = p;
                running = true;
                Thread t = new Thread(this::acceptLoop, "awd-http");
                t.setDaemon(true);
                t.start();
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    private void acceptLoop() {
        ServerSocket ss = server;
        while (running) {
            try {
                Socket s = ss.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ---------------------------------------------------------------- request handling

    private static class Req {
        String method, path;
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        InputStream body;
        String client;

        String q(String k) { return query.get(k); }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60_000);
            s.setSendBufferSize(2 * 1024 * 1024);
            InputStream in = new BufferedInputStream(s.getInputStream(), 256 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 256 * 1024);
            Req r = parse(in);
            if (r == null) return;
            r.client = s.getInetAddress().getHostAddress();
            r.body = in;
            route(r, out);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private Req parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k = e >= 0 ? kv.substring(0, e) : kv;
                String v = e >= 0 ? kv.substring(e + 1) : "";
                r.query.put(dec(k), dec(v));
            }
        }
        int total = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            total += line.length();
            if (total > 32 * 1024) return null;
            int c = line.indexOf(':');
            if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        return r;
    }

    private void route(Req r, OutputStream out) throws IOException {
        boolean head = r.method.equals("HEAD");
        String m = head ? "GET" : r.method;
        switch (r.path) {
            case "/":
            case "/index.html":
                if (m.equals("GET")) { sendBytes(out, 200, "text/html; charset=utf-8", page(), head); return; }
                break;
            case "/api/state":
                if (m.equals("GET")) { sendJson(out, 200, state(r.client)); return; }
                break;
            case "/api/folder":
                if (m.equals("GET")) { sendJson(out, 200, folder(r.q("id"))); return; }
                break;
            case "/api/upload":
                if (m.equals("PUT") || m.equals("POST")) { upload(r, out); return; }
                break;
            case "/api/file":
                if (m.equals("GET")) { download(r, out, head); return; }
                break;
            case "/api/zip":
                if (m.equals("GET")) { zip(r, out, head); return; }
                break;
            case "/api/claim":
                if (m.equals("POST")) { claim(r, out); return; }
                break;
            case "/api/sent":
                if (m.equals("POST")) {
                    Shelf.Batch b = shelf.batch(r.q("id"));
                    int k = -1;
                    try { k = Integer.parseInt(r.q("part")); } catch (Exception ignored) { }
                    if (b == null || k < 0 || k >= b.parts) { sendJson(out, 404, "{\"ok\":false}"); return; }
                    b.delivered = Math.max(b.delivered, k + 1);
                    b.touched = System.currentTimeMillis();
                    shelf.touch();
                    sendJson(out, 200, "{\"ok\":true}");
                    return;
                }
                break;
            case "/api/delete":
                if (m.equals("POST")) {
                    boolean ok = shelf.remove(r.q("id"));
                    sendJson(out, ok ? 200 : 404, "{\"ok\":" + ok + "}");
                    return;
                }
                break;
            case "/api/clean":
                if (m.equals("POST")) { sendJson(out, 200, "{\"ok\":true,\"freed\":" + cleanAll() + "}"); return; }
                break;
            case "/favicon.ico":
                sendBytes(out, 404, "text/plain", new byte[0], false);
                return;
            default:
                sendJson(out, 404, "{\"error\":\"Not found\"}");
                return;
        }
        sendJson(out, 405, "{\"error\":\"Method not allowed\"}");
    }

    // ---------------------------------------------------------------- upload (into the phone)

    private void upload(Req r, OutputStream out) throws IOException {
        String path = r.q("path");
        if (path == null || path.isEmpty()) path = r.q("name");
        String from = r.q("from");
        if (from == null || from.trim().isEmpty()) from = r.client;
        String batch = r.q("batch") == null ? "" : r.q("batch");
        long mtime = 0;
        try { mtime = Long.parseLong(r.q("mtime")); } catch (Exception ignored) { }
        long len;
        try { len = Long.parseLong(r.headers.get("content-length")); } catch (Exception e) { len = -1; }
        if (path == null || path.isEmpty() || len < 0) {
            sendJson(out, 400, "{\"error\":\"Need a file name and Content-Length\"}");
            return;
        }
        if (len > shelf.freeSpace() - 50L * 1024 * 1024) {
            sendJson(out, 507, "{\"error\":\"Not enough space on the phone\"}");
            return;
        }

        File target;
        try {
            target = shelf.target(path, batch);
        } catch (IOException e) {
            sendJson(out, 500, "{\"error\":" + js(e.getMessage()) + "}");
            return;
        }
        if ("100-continue".equalsIgnoreCase(r.headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        String rel = shelf.rel(target);
        int slash = rel.indexOf('/');
        File part = Shelf.partOf(target);
        Active a = new Active(rel, len, true, from, slash > 0 ? rel.substring(0, slash) : null);
        active.add(a);
        events.onTransferStart(a);
        boolean ok = false;
        String err = null;
        try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(part), BUF)) {
            byte[] buf = new byte[BUF];
            long left = len;
            while (left > 0) {
                int n = r.body.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("Upload stopped");
                fo.write(buf, 0, n);
                left -= n;
                a.done += n;
            }
            fo.flush();
            ok = true;
        } catch (SocketTimeoutException e) {
            err = "Upload stalled";
        } catch (IOException e) {
            err = e.getMessage() == null ? "Upload stopped" : e.getMessage();
        }
        boolean replaced = false;
        if (ok) {
            replaced = target.exists();      // a retried file inside a folder replaces the earlier try
            if (replaced) target.delete();
            ok = part.renameTo(target);
            if (!ok) err = "Couldn't save the file";
        }
        if (!ok) part.delete();
        active.remove(a);
        if (ok) shelf.received(target, mtime, from, replaced);
        events.onTransferEnd(a, ok, err);
        if (ok) {
            sendJson(out, 200, "{\"ok\":true,\"path\":" + js(rel) + "}");
        } else {
            try { sendJson(out, 500, "{\"error\":" + js(err) + "}"); } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- download one file

    private void download(Req r, OutputStream out, boolean head) throws IOException {
        Shelf.Item it = shelf.get(r.q("id"));
        if (it == null || it.folder) { sendJson(out, 404, "{\"error\":\"That file is no longer here\"}"); return; }

        long size = it.size, start = 0, end = size - 1;
        boolean partial = false;
        String range = r.headers.get("range");
        if (range != null && range.startsWith("bytes=") && size > 0) {
            try {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty()) { start = Math.max(0, size - Long.parseLong(b)); }
                else { start = Long.parseLong(a); if (!b.isEmpty()) end = Math.min(end, Long.parseLong(b)); }
                if (start > end || start >= size) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    return;
                }
                partial = true;
            } catch (Exception ignored) {
                start = 0; end = size - 1;
            }
        }
        long len = size == 0 ? 0 : end - start + 1;

        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mime(it.name)).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Last-Modified: ").append(httpDate(it.time)).append("\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        h.append("Content-Disposition: ").append("1".equals(r.q("dl")) ? "attachment" : "inline").append(disposition(it.name));
        h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (head || len == 0) return;

        Active a = new Active(it.name, len, false, r.client, null);
        active.add(a);
        events.onTransferStart(a);
        boolean ok = false;
        String err = null;
        try (InputStream src = shelf.open(it)) {
            long skip = start;
            while (skip > 0) {
                long n = src.skip(skip);
                if (n <= 0) { if (src.read() < 0) throw new IOException("File is shorter than expected"); n = 1; }
                skip -= n;
            }
            byte[] buf = new byte[BUF];
            long left = len;
            while (left > 0) {
                int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("File is shorter than expected");
                out.write(buf, 0, n);
                left -= n;
                a.done += n;
            }
            out.flush();
            ok = true;
        } catch (IOException e) {
            err = "Download stopped";
        } finally {
            active.remove(a);
            events.onTransferEnd(a, ok, err);
        }
    }

    // ---------------------------------------------------------------- download a folder as ZIP

    /**
     * Streams a ZIP without knowing its length up front. Entries go in oldest-first order
     * and carry each file's original modified time (DOS time + the precise UTC extra field),
     * so extracting on Windows/Mac/Linux restores both the files' times and their sequence.
     * Content is stored without compression (level 0) to keep the phone's CPU out of the way.
     */
    private void zip(Req r, OutputStream out, boolean head) throws IOException {
        String id = r.q("id");
        String zipName;
        List<Shelf.Entry> entries;
        if (id != null && id.startsWith("b:")) {
            Shelf.Batch b = shelf.batch(id);
            int k = -1;
            try { k = Integer.parseInt(r.q("part")); } catch (Exception ignored) { }
            if (b == null || k < 0 || k >= b.parts) { sendJson(out, 404, "{\"error\":\"That batch was cancelled on the phone\"}"); return; }
            b.touched = System.currentTimeMillis();
            zipName = b.partName(k);
            entries = b.part(k);
        } else if ("all".equals(id)) {
            zipName = "AnyWhereDoor-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(new Date()) + ".zip";
            entries = shelf.entries(id);
        } else {
            Shelf.Item it = shelf.get(id);
            if (it == null || !it.folder) { sendJson(out, 404, "{\"error\":\"That folder is no longer here\"}"); return; }
            zipName = it.name + ".zip";
            entries = shelf.entries(id);
        }
        long total = 0;
        for (Shelf.Entry e : entries) total += e.size;

        String h = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/zip\r\n"
                + "Content-Disposition: attachment" + disposition(zipName)
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        if (head) return;

        Active a = new Active(zipName, total, false, r.client, null);
        a.fileCount = entries.size();
        active.add(a);
        events.onTransferStart(a);
        boolean ok = false;
        String err = null;
        try {
            ZipOutputStream zos = new ZipOutputStream(new FilterOutputStream(out) {
                @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                @Override public void close() throws IOException { out.flush(); } // keep the socket for us
            });
            zos.setLevel(Deflater.NO_COMPRESSION);
            byte[] buf = new byte[BUF];
            for (Shelf.Entry e : entries) {
                ZipEntry ze = new ZipEntry(e.path);
                if (e.time > 0) ze.setLastModifiedTime(FileTime.fromMillis(e.time));
                zos.putNextEntry(ze);
                try (InputStream src = shelf.open(e)) {
                    int n;
                    while ((n = src.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                        a.done += n;
                    }
                }
                zos.closeEntry();
                a.files++;
            }
            zos.finish();
            zos.close();
            ok = true;
        } catch (IOException e) {
            err = "Download stopped";
        } finally {
            active.remove(a);
            events.onTransferEnd(a, ok, err);
        }
    }

    // ---------------------------------------------------------------- JSON

    /** First browser to ask gets the batch; another may take over if that one went quiet for 30 s. */
    private void claim(Req r, OutputStream out) throws IOException {
        Shelf.Batch b = shelf.batch(r.q("id"));
        boolean ok = false;
        if (b != null) {
            synchronized (b) {
                long now = System.currentTimeMillis();
                if (b.claimedBy == null || b.claimedBy.equals(r.client) || now - b.touched > 30_000) {
                    b.claimedBy = r.client;
                    b.touched = now;
                    ok = true;
                }
            }
            shelf.touch();
        }
        sendJson(out, ok ? 200 : 409, "{\"ok\":" + ok + "}");
    }

    private String state(String client) {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"phone\":").append(js(phoneName));
        b.append(",\"free\":").append(shelf.freeSpace());
        b.append(",\"rev\":").append(shelf.rev());
        b.append(",\"clean\":").append(cleanEpoch);
        b.append(",\"files\":[");
        boolean first = true;
        for (Shelf.Item it : shelf.list()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(js(it.id))
                    .append(",\"name\":").append(js(it.name))
                    .append(",\"size\":").append(it.size)
                    .append(",\"time\":").append(it.time)
                    .append(",\"phone\":").append(it.fromPhone)
                    .append(",\"from\":").append(it.from == null ? "null" : js(it.from))
                    .append(",\"folder\":").append(it.folder);
            if (it.folder) {
                b.append(",\"count\":").append(it.count)
                        .append(",\"first\":").append(it.first)
                        .append(",\"last\":").append(it.last);
            }
            b.append('}');
        }
        b.append("],\"sends\":[");
        first = true;
        for (Shelf.Batch sb : shelf.batches()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(js(sb.id))
                    .append(",\"name\":").append(js(sb.name))
                    .append(",\"files\":").append(sb.entries.size())
                    .append(",\"size\":").append(sb.size)
                    .append(",\"parts\":").append(sb.parts)
                    .append(",\"delivered\":").append(sb.delivered)
                    .append(",\"claimed\":").append(sb.claimedBy != null)
                    .append(",\"mine\":").append(client.equals(sb.claimedBy))
                    .append(",\"partSizes\":[");
            for (int k = 0; k < sb.parts; k++) b.append(k > 0 ? "," : "").append(sb.partSize(k));
            b.append("]}");
        }
        b.append("],\"active\":[");
        first = true;
        for (Active a : active) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"name\":").append(js(a.name))
                    .append(",\"size\":").append(a.size)
                    .append(",\"done\":").append(a.done)
                    .append(",\"upload\":").append(a.upload)
                    .append(",\"speed\":").append((long) a.speed())
                    .append(",\"files\":").append(a.files)
                    .append(",\"fileCount\":").append(a.fileCount)
                    .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    /** Files in a folder, oldest first, with paths shown relative to the folder. */
    private String folder(String id) {
        Shelf.Item it = shelf.get(id);
        if (it == null || !it.folder) return "{\"files\":[]}";
        List<Shelf.Entry> es = shelf.entries(id);
        StringBuilder b = new StringBuilder(64 + es.size() * 96);
        b.append("{\"files\":[");
        boolean first = true;
        for (Shelf.Entry e : es) {
            if (!first) b.append(',');
            first = false;
            String p = e.path.substring(e.path.indexOf('/') + 1);
            b.append("{\"id\":").append(js(e.id))
                    .append(",\"path\":").append(js(p))
                    .append(",\"size\":").append(e.size)
                    .append(",\"time\":").append(e.time)
                    .append('}');
        }
        return b.append("]}").toString();
    }

    // ---------------------------------------------------------------- helpers

    private byte[] page() throws IOException {
        if (pageCache == null) {
            try (InputStream in = ctx.getAssets().open("index.html")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                pageCache = bo.toByteArray();
            }
        }
        return pageCache;
    }

    private static void sendJson(OutputStream out, int code, String json) throws IOException {
        sendBytes(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body, boolean head) throws IOException {
        String h = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        if (!head) out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 507: return "Insufficient Storage";
            default: return code >= 500 ? "Server Error" : "Status";
        }
    }

    private static String disposition(String name) {
        return "; filename=\"" + ascii(name) + "\"; filename*=UTF-8''" + enc(name) + "\r\n";
    }

    private static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Line too long");
        }
        if (c < 0 && sb.length() == 0) return null;
        // header bytes are UTF-8 in practice; re-decode
        return new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    static String cleanName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "file";
        if (n.length() > 180) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 12 ? n.substring(dot) : "";
            n = n.substring(0, 180 - ext.length()) + ext;
        }
        return n;
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (t != null) return t;
        }
        return "application/octet-stream";
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return "file"; }
    }

    private static String ascii(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 32 && c < 127 && c != '"' && c != '\\' ? c : '_');
        return b.toString();
    }

    static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
