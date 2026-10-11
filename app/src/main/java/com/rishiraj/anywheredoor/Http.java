package com.rishiraj.anywheredoor;

import android.content.Context;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP/1.1 server made for one job: moving big files in and out of the phone
 * without holding them in memory. Every response closes the connection.
 *
 *   GET  /                      the web page (assets/index.html)
 *   GET  /api/state             phone name, free space, shelf, transfers (JSON)
 *   PUT  /api/upload?name=&from= raw file body, Content-Length required
 *   GET  /api/file?id=[&dl=1]   download (supports Range, so browsers can resume)
 *   POST /api/delete?id=        delete a received file / unshare a phone file
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
        public final long start = System.nanoTime();
        public volatile long done;

        Active(String name, long size, boolean upload, String who) {
            this.name = name; this.size = size; this.upload = upload; this.who = who;
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

    public Http(Context ctx, Shelf shelf, String phoneName, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.shelf = shelf;
        this.phoneName = phoneName;
        this.events = events;
    }

    public int port() { return port; }
    public boolean running() { return running; }

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
                if (m.equals("GET")) { sendJson(out, 200, state()); return; }
                break;
            case "/api/upload":
                if (m.equals("PUT") || m.equals("POST")) { upload(r, out); return; }
                break;
            case "/api/file":
                if (m.equals("GET")) { download(r, out, head); return; }
                break;
            case "/api/delete":
                if (m.equals("POST")) {
                    boolean ok = shelf.remove(r.query.get("id"));
                    sendJson(out, ok ? 200 : 404, "{\"ok\":" + ok + "}");
                    return;
                }
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
        String name = r.query.get("name");
        String from = r.query.get("from");
        if (from == null || from.trim().isEmpty()) from = r.client;
        long len;
        try { len = Long.parseLong(r.headers.get("content-length")); } catch (Exception e) { len = -1; }
        if (name == null || name.isEmpty() || len < 0) {
            sendJson(out, 400, "{\"error\":\"Need name and Content-Length\"}");
            return;
        }
        if (len > shelf.freeSpace() - 50L * 1024 * 1024) {
            sendJson(out, 507, "{\"error\":\"Not enough space on the phone\"}");
            return;
        }
        if ("100-continue".equalsIgnoreCase(r.headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        File target = shelf.reserve(name);
        File part = new File(shelf.dir(), "." + target.getName() + ".part");
        Active a = new Active(target.getName(), len, true, from);
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
        File finalFile = target;
        if (ok) {
            if (finalFile.exists()) finalFile = shelf.reserve(name);
            ok = part.renameTo(finalFile);
            if (!ok) err = "Couldn't save the file";
        }
        if (!ok) part.delete();
        active.remove(a);
        events.onTransferEnd(a, ok, err);
        if (ok) {
            shelf.received(finalFile, from);
            sendJson(out, 200, "{\"ok\":true,\"name\":" + js(finalFile.getName()) + "}");
        } else {
            try { sendJson(out, 500, "{\"error\":" + js(err) + "}"); } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- download (out of the phone)

    private void download(Req r, OutputStream out, boolean head) throws IOException {
        Shelf.Item it = shelf.get(r.query.get("id"));
        if (it == null) { sendJson(out, 404, "{\"error\":\"That file is no longer here\"}"); return; }

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
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        String disp = "1".equals(r.query.get("dl")) ? "attachment" : "inline";
        h.append("Content-Disposition: ").append(disp).append("; filename=\"").append(ascii(it.name))
                .append("\"; filename*=UTF-8''").append(enc(it.name)).append("\r\n");
        h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (head || len == 0) return;

        Active a = new Active(it.name, len, false, r.client);
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

    // ---------------------------------------------------------------- state JSON

    private String state() {
        StringBuilder b = new StringBuilder(2048);
        b.append("{\"phone\":").append(js(phoneName));
        b.append(",\"free\":").append(shelf.freeSpace());
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
                    .append('}');
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
                    .append('}');
        }
        b.append("]}");
        return b.toString();
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
