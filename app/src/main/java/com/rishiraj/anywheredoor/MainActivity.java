package com.rishiraj.anywheredoor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 10;
    private static final int REQ_PERMS = 11;
    private static final int REQ_TREE = 12;
    private static final int REQ_BATCH = 13;
    private static final int REQ_BATCH_TREE = 14;

    private TextView status, address, otherAddresses, hint, free, folder, log, shelfEmpty, myName;
    private View dot, activeBox, sendsBox;
    private LinearLayout activeList, shelfList, sendsList;
    private Button btnDoor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Uri> pendingShare = new ArrayList<>();
    private String shelfSignature = "";
    private boolean selecting;
    private final java.util.Set<String> selected = new java.util.HashSet<>();
    private List<Shelf.Item> lastItems = new ArrayList<>();
    private TextView btnSelect;
    private Button btnDeleteSel, btnSelectAll;
    private View selectBar;
    private boolean wantOpen = true;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        address = findViewById(R.id.address);
        otherAddresses = findViewById(R.id.otherAddresses);
        hint = findViewById(R.id.hint);
        free = findViewById(R.id.free);
        folder = findViewById(R.id.folder);
        log = findViewById(R.id.log);
        shelfEmpty = findViewById(R.id.shelfEmpty);
        myName = findViewById(R.id.myName);
        dot = findViewById(R.id.dot);
        activeBox = findViewById(R.id.activeBox);
        activeList = findViewById(R.id.active);
        shelfList = findViewById(R.id.shelf);
        btnDoor = findViewById(R.id.btnDoor);
        sendsBox = findViewById(R.id.sendsBox);
        sendsList = findViewById(R.id.sends);

        btnDoor.setOnClickListener(v -> {
            if (HubService.get() != null) { wantOpen = false; HubService.stop(this); }
            else { wantOpen = true; HubService.start(this); }
            refresh();
        });
        findViewById(R.id.btnHotspot).setOnClickListener(v -> openHotspotSettings());
        findViewById(R.id.btnShare).setOnClickListener(v -> pickFiles());
        findViewById(R.id.btnShareFolder).setOnClickListener(v -> pickFolder());
        findViewById(R.id.btnBatch).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Send to the laptop in ZIPs")
                .setItems(new String[]{"A whole folder (best for thousands of images)", "Pick images (up to ~2,000)"},
                        (d, which) -> { if (which == 0) pickTreeForBatch(); else pickForBatch(); })
                .show());
        address.setOnClickListener(v -> copy(address.getText().toString()));
        findViewById(R.id.btnClean).setOnClickListener(v -> cleanAll());
        btnSelect = findViewById(R.id.btnSelect);
        selectBar = findViewById(R.id.selectBar);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnDeleteSel = findViewById(R.id.btnDeleteSel);
        btnSelect.setOnClickListener(v -> setSelecting(!selecting));
        btnSelectAll.setOnClickListener(v -> {
            if (selected.size() == lastItems.size()) selected.clear();
            else for (Shelf.Item it : lastItems) selected.add(it.id);
            redrawShelf();
        });
        btnDeleteSel.setOnClickListener(v -> deleteSelected());

        askPermissions();
        HubService.start(this);
        takeShared(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeShared(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    // ---------------------------------------------------------------- permissions

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(perms[i])
                    && results[i] == PackageManager.PERMISSION_GRANTED && HubService.get() != null) {
                // re-open so received files go to Download/AnyWhereDoor
                HubService.stop(this);
                handler.postDelayed(() -> HubService.start(this), 400);
            }
        }
    }

    // ---------------------------------------------------------------- sharing from this phone

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    private void pickForBatch() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_BATCH);
            Toast.makeText(this, "Long-press to select several", Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    private void pickTreeForBatch() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_BATCH_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No folder picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    /** Ask how many files go in each ZIP, then hand the batch to the door. */
    private void askBatch(List<Uri> uris) {
        askBatch(uris.size() + (uris.size() == 1 ? " file" : " files"), n -> startBatch(uris, null, n));
    }

    private interface PerChosen { void go(int per); }

    private void askBatch(String what, PerChosen then) {
        android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        EditText per = new EditText(this);
        per.setInputType(InputType.TYPE_CLASS_NUMBER);
        per.setText(String.valueOf(prefs.getInt("per", 20)));
        per.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        box.addView(per);
        new AlertDialog.Builder(this)
                .setTitle("Send " + what + " in ZIPs")
                .setMessage("How many files in each ZIP? Oldest go first. Keep AnyWhereDoor open on the laptop — "
                        + "it saves the ZIPs one after another.")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    int n;
                    try { n = Integer.parseInt(per.getText().toString().trim()); } catch (Exception e) { n = 20; }
                    n = Math.max(1, Math.min(n, 10000));
                    prefs.edit().putInt("per", n).apply();
                    then.go(n);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startBatch(List<Uri> uris, Uri tree, int per) {
        HubService s = HubService.get();
        if (s == null) {
            if (!wantOpen) { wantOpen = true; HubService.start(this); }
            handler.postDelayed(() -> startBatch(uris, tree, per), 300);
            return;
        }
        if (tree != null) Toast.makeText(this, "Reading the folder…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Shelf.Batch b = tree != null ? s.shelf.makeBatch(tree, per) : s.shelf.makeBatch(uris, per);
            runOnUiThread(() -> {
                Toast.makeText(this, b == null ? "Couldn't read those files"
                        : b.entries.size() + " files → " + b.parts + (b.parts == 1 ? " ZIP" : " ZIPs")
                        + ". Open the address on the laptop to receive them.", Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private static List<Uri> urisOf(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData c = data.getClipData();
            for (int i = 0; i < c.getItemCount(); i++) uris.add(c.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No folder picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            HubService s = HubService.get();
            if (s == null) { Toast.makeText(this, "Open the door first", Toast.LENGTH_SHORT).show(); return; }
            Uri tree = data.getData();
            Toast.makeText(this, "Reading the folder…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                Shelf.Item it = s.shelf.shareTree(tree);
                runOnUiThread(() -> {
                    Toast.makeText(this, it == null ? "Couldn't read that folder"
                            : it.name + " is on the shelf · " + it.count + " files", Toast.LENGTH_LONG).show();
                    shelfSignature = "";
                    refresh();
                });
            }).start();
            return;
        }
        if (req == REQ_BATCH_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            askBatch("this folder", n -> startBatch(null, tree, n));
            return;
        }
        if (req == REQ_BATCH && res == RESULT_OK && data != null) {
            List<Uri> uris = urisOf(data);
            if (uris.size() <= 200) for (Uri u : uris) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
            }
            if (!uris.isEmpty()) askBatch(uris);
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        addToShelf(urisOf(data), true);
    }

    /** Files shared into the app from the gallery, file manager, etc. */
    private void takeShared(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        intent.setAction(null);
        if (uris.isEmpty()) return;
        if (uris.size() == 1) {
            pendingShare.addAll(uris);
            flushPending();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(uris.size() + " files")
                .setItems(new String[]{"Send to the laptop in ZIPs", "Put on the shelf"}, (d, which) -> {
                    if (which == 0) askBatch(uris);
                    else { pendingShare.addAll(uris); flushPending(); }
                })
                .show();
    }

    private void flushPending() {
        if (pendingShare.isEmpty()) return;
        HubService s = HubService.get();
        if (s == null) { handler.postDelayed(this::flushPending, 300); return; }
        List<Uri> copy = new ArrayList<>(pendingShare);
        pendingShare.clear();
        addToShelf(copy, false);
    }

    private void addToShelf(List<Uri> uris, boolean persistable) {
        HubService s = HubService.get();
        if (s == null) {
            pendingShare.addAll(uris);
            if (!wantOpen) { wantOpen = true; HubService.start(this); }
            flushPending();
            return;
        }
        new Thread(() -> {
            int n = s.shelf.share(uris, persistable);
            runOnUiThread(() -> {
                Toast.makeText(this, n == 0 ? "Those files are already on the shelf"
                        : n + (n == 1 ? " file is" : " files are") + " on the shelf now", Toast.LENGTH_SHORT).show();
                shelfSignature = "";
                refresh();
            });
        }).start();
    }

    // ---------------------------------------------------------------- screen

    private void refresh() {
        HubService s = HubService.get();
        boolean open = s != null && s.http.running();
        btnDoor.setText(open ? "Close the door" : "Open the door");
        btnDoor.setBackgroundResource(open ? R.drawable.btn_ghost : R.drawable.btn_primary);
        btnDoor.setTextColor(getColor(open ? R.color.text : android.R.color.white));

        if (s == null) {
            setDot(R.color.muted);
            status.setText("Door is closed");
            address.setVisibility(View.GONE);
            otherAddresses.setVisibility(View.GONE);
            hint.setText("Open the door, then connect your laptop or other device to this phone's hotspot.");
            activeBox.setVisibility(View.GONE);
            sendsBox.setVisibility(View.GONE);
            renderShelf(new ArrayList<>(), null);
            log.setText("—");
            return;
        }

        myName.setText("This phone: " + s.phoneName);
        if (s.error != null) {
            setDot(R.color.bad);
            status.setText("Couldn't open the door");
            hint.setText(s.error);
            address.setVisibility(View.GONE);
            otherAddresses.setVisibility(View.GONE);
        } else {
            List<String> urls = HubService.addresses(s.http.port());
            if (urls.isEmpty()) {
                setDot(R.color.warn);
                status.setText("Door is open — waiting for a network");
                address.setVisibility(View.GONE);
                otherAddresses.setVisibility(View.GONE);
                hint.setText("Turn on this phone's hotspot (tap Hotspot). Mobile data can stay off — "
                        + "files move over the hotspot's Wi-Fi only.");
            } else {
                setDot(R.color.ok);
                status.setText("Door is open");
                address.setText(urls.get(0).replace("http://", ""));
                address.setVisibility(View.VISIBLE);
                if (urls.size() > 1) {
                    StringBuilder sb = new StringBuilder("If that doesn't load, try ");
                    for (int i = 1; i < urls.size(); i++) {
                        if (i > 1) sb.append("  or  ");
                        sb.append(urls.get(i).replace("http://", ""));
                    }
                    otherAddresses.setText(sb);
                    otherAddresses.setVisibility(View.VISIBLE);
                } else {
                    otherAddresses.setVisibility(View.GONE);
                }
                hint.setText("On the laptop or other device: join this phone's hotspot, then type the address above "
                        + "into Chrome or Edge. Tap the address to copy it.");
            }
        }

        renderActive(s.http.active);
        renderSends(s);
        renderShelf(s.shelf.list(), s);
        free.setText(size(s.shelf.freeSpace()) + " free");
        folder.setText("Received files are saved in " + s.shelf.dirLabel());
        synchronized (s.log) {
            log.setText(s.log.isEmpty() ? "—" : TextUtils.join("\n", s.log.subList(0, Math.min(8, s.log.size()))));
        }
        s.updateNotification(false);
    }

    private void setDot(int color) {
        dot.setBackgroundColor(getColor(color));
    }

    private void renderActive(List<Http.Active> list) {
        activeBox.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        activeList.removeAllViews();
        boolean first = true;
        for (Http.Active a : list) {
            if (!first) activeList.addView(divider());
            first = false;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView name = text((a.upload ? "↓  " : "↑  ") + a.name, 15, R.color.text, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setProgressDrawable(getDrawable(R.drawable.progress));
            pb.setMax(1000);
            pb.setProgress(a.size > 0 ? (int) (a.done * 1000 / a.size) : 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(6);
            row.addView(pb, lp);
            String meta = size(a.done) + " of " + size(a.size) + " · " + size((long) a.speed()) + "/s"
                    + (a.upload ? " · from " + a.who : " · to " + a.who);
            row.addView(text(meta, 12, R.color.muted, false));
            activeList.addView(row);
        }
    }

    private void renderSends(HubService s) {
        List<Shelf.Batch> list = s.shelf.batches();
        sendsBox.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        sendsList.removeAllViews();
        boolean first = true;
        for (Shelf.Batch b : list) {
            if (!first) sendsList.addView(divider());
            first = false;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(4), dp(12));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(b.entries.size() + " files · " + size(b.size), 15, R.color.text, true));
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setProgressDrawable(getDrawable(R.drawable.progress));
            pb.setMax(b.parts);
            pb.setProgress(b.delivered);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(6);
            col.addView(pb, lp);
            String meta;
            if (b.delivered >= b.parts) meta = "All " + b.parts + " ZIPs saved on " + b.claimedBy + " ✓";
            else if (b.claimedBy == null) meta = b.parts + " ZIPs of " + b.per + " · waiting for the laptop to open the page";
            else meta = b.delivered + " of " + b.parts + " ZIPs saved on " + b.claimedBy;
            col.addView(text(meta, 12, R.color.muted, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView x = text("✕", 18, R.color.muted, false);
            x.setPadding(dp(14), dp(8), dp(14), dp(8));
            x.setOnClickListener(v -> { s.shelf.dropBatch(b.id); refresh(); });
            row.addView(x);
            sendsList.addView(row);
        }
    }

    private void renderShelf(List<Shelf.Item> items, HubService s) {
        StringBuilder sig = new StringBuilder();
        for (Shelf.Item it : items) sig.append(it.id).append(it.size).append(it.count).append(it.uri != null).append('|');
        sig.append(selecting).append(selected);
        if (sig.toString().equals(shelfSignature)) return;
        shelfSignature = sig.toString();
        lastItems = items;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Shelf.Item it : items) ids.add(it.id);
        selected.retainAll(ids);                       // drop anything that left the shelf
        btnSelect.setVisibility(items.isEmpty() && !selecting ? View.GONE : View.VISIBLE);
        btnSelect.setText(selecting ? "Done" : "Select");
        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnSelectAll.setText(!items.isEmpty() && selected.size() == items.size() ? "Select none" : "Select all");
        btnDeleteSel.setText(selected.isEmpty() ? "Delete" : "Delete " + selected.size());
        btnDeleteSel.setEnabled(!selected.isEmpty());
        btnDeleteSel.setAlpha(selected.isEmpty() ? 0.4f : 1f);

        shelfList.removeAllViews();
        shelfEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        shelfList.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        SimpleDateFormat tf = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        boolean first = true;
        for (Shelf.Item it : items) {
            if (!first) shelfList.addView(divider());
            first = false;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(4), dp(10));
            if (selecting) {
                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                cb.setChecked(selected.contains(it.id));
                cb.setClickable(false);                 // the whole row toggles
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.door)));
                row.addView(cb);
            }

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = text((it.folder ? "📁  " : "") + it.name, 15, R.color.text, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            col.addView(name);
            String origin = it.fromPhone ? "from this phone" : "received" + (it.from != null ? " from " + it.from : "");
            String meta;
            if (it.folder) {
                meta = it.count + (it.count == 1 ? " file · " : " files · ") + size(it.size) + " · " + origin;
                if (it.count > 0) meta += "\n" + tf.format(new Date(it.first)) + "  →  " + tf.format(new Date(it.last));
            } else {
                meta = size(it.size) + " · " + origin + " · " + tf.format(new Date(it.time));
            }
            col.addView(text(meta, 12, R.color.muted, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            if (!selecting) {
                TextView x = text("✕", 18, R.color.muted, false);
                x.setPadding(dp(14), dp(8), dp(14), dp(8));
                x.setOnClickListener(v -> confirmRemove(it));
                row.addView(x);
            }

            row.setOnClickListener(v -> {
                if (!selecting) { openItem(it); return; }
                if (!selected.remove(it.id)) selected.add(it.id);
                redrawShelf();
            });
            row.setOnLongClickListener(v -> {              // long-press starts selecting
                if (!selecting) { selecting = true; selected.add(it.id); redrawShelf(); }
                return true;
            });
            shelfList.addView(row);
        }
    }

    private void setSelecting(boolean on) {
        selecting = on;
        selected.clear();
        redrawShelf();
    }

    private void redrawShelf() {
        shelfSignature = "";
        refresh();
    }

    private void deleteSelected() {
        List<Shelf.Item> pick = new ArrayList<>();
        int received = 0;
        for (Shelf.Item it : lastItems) if (selected.contains(it.id)) { pick.add(it); if (!it.fromPhone) received++; }
        if (pick.isEmpty()) return;
        String msg = received == 0
                ? "They're only taken off the shelf. The originals stay on the phone."
                : received + (received == 1 ? " received item is" : " received items are") + " deleted from the phone."
                  + (received < pick.size() ? " Items shared from this phone are only taken off the shelf." : "");
        new AlertDialog.Builder(this)
                .setTitle("Delete " + pick.size() + (pick.size() == 1 ? " item?" : " items?"))
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    HubService h = HubService.get();
                    if (h == null) return;
                    new Thread(() -> {
                        for (Shelf.Item it : pick) h.shelf.remove(it.id);
                        runOnUiThread(() -> setSelecting(false));
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(Shelf.Item it) {
        HubService s = HubService.get();
        if (s == null) return;
        if (it.fromPhone) {
            s.shelf.remove(it.id); // the original stays where it is
            shelfSignature = "";
            refresh();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + it.name + "?")
                .setMessage(it.folder ? "This removes the received folder and all " + it.count + " files in it from the phone."
                        : "This removes the received file from the phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    HubService h = HubService.get();
                    if (h != null) h.shelf.remove(it.id);
                    shelfSignature = "";
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openItem(Shelf.Item it) {
        if (it.folder) {
            Toast.makeText(this, it.fromPhone ? "Shared from this phone · download it as a ZIP from the other device"
                    : "Find it in Files → Download → AnyWhereDoor → " + it.name, Toast.LENGTH_LONG).show();
            return;
        }
        if (it.uri == null) {
            Toast.makeText(this, "Find it in Files → Download → AnyWhereDoor", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent v = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(it.uri, Http.mime(it.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(v);
        } catch (Exception e) {
            Toast.makeText(this, "No app on this phone can open " + it.name, Toast.LENGTH_SHORT).show();
        }
    }

    /** Cleans the phone's cache; every page open on the hotspot clears its own on its next check. */
    private void cleanAll() {
        HubService s = HubService.get();
        if (s == null) { Toast.makeText(this, "Open the door first", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            long freed = s.http.cleanAll();
            runOnUiThread(() -> {
                Toast.makeText(this, "Cache cleaned · " + (freed >> 20) + " MB freed on the phone. Open pages clean themselves.",
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void openHotspotSettings() {
        Intent[] tries = {
                new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent i : tries) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
    }

    private void copy(String s) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("AnyWhereDoor address", "http://" + s));
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- small helpers

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.line));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.ROOT, m < 10 ? "%.1f MB" : "%.0f MB", m);
        return String.format(Locale.ROOT, "%.2f GB", m / 1024.0);
    }
}
