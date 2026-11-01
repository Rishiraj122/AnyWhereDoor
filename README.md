# AnyWhereDoor

Move big files between your office laptop, your phone and any other device — over your phone's hotspot, with no mobile data and nothing installed on the laptop.

The phone running the hotspot is the **door**: AnyWhereDoor runs a small file server on it. Every other device just opens the address shown in the app in a browser. That works even on a locked-down laptop, because the laptop only makes outgoing connections to the phone; it never has to accept one.

```
 Office laptop ──browser──►┐
                           │   Phone (hotspot + AnyWhereDoor)
 Zebra / another phone ───►┤   Download/AnyWhereDoor
 any device with a browser►┘
```

## Use it

1. Install the APK on the phone that will run the hotspot (see below) and open **AnyWhereDoor**. The door opens by itself and a notification keeps it open while the phone is locked.
2. Turn on the phone's hotspot (the **Hotspot** button jumps to the setting). Mobile data can be off.
3. Connect the laptop (and any other device) to that hotspot.
4. In Chrome or Edge on the laptop, type the address the app shows, e.g. `192.168.43.1:8080`.

- **Laptop → phone:** drag files or whole folders onto the page, or click **Choose files** / **Choose folder**. They upload one after another with progress, speed and time left, and are saved in `Download/AnyWhereDoor` on the phone, folder structure included.
- **Phone → laptop:** in the app, tap **Share files** or **Share a folder** (or use Share → AnyWhereDoor from the gallery or file manager). They appear on the page with a **Download** button, folders as **Download ZIP**. Nothing is copied, so multi-GB files are instant to add.
- **Phone → laptop in ZIP batches:** tap **Send images to the laptop in ZIPs**, pick **A whole folder** (any number of files — use this for thousands) or **Pick images** (Android caps a hand-picked selection at a few thousand), or select several photos in the gallery → Share → AnyWhereDoor → *Send to the laptop in ZIPs*) and choose how many files go in each ZIP. With the page open on the laptop, it saves `…_part01ofNN.zip`, then part 02, and so on into Downloads, oldest files first, each with its original time. A part that fails is retried on its own; nothing already saved is sent again.
- **Parallel transfers:** set in the app's **⚙ Settings** (or the slider on the page), 1–8, default 3: how many files go up at once and how many ZIPs the laptop pulls at once. The phone keeps the setting, so every device follows it, even mid-transfer.
- **Resume:** if the hotspot drops, the page waits for the phone and carries on from the exact byte each file stopped at. If the page was closed, pick the same folder again: files already on the phone are skipped and half-sent ones resume. ZIP batches carry on with the ZIPs not yet saved.
- **Stop all transfers & clear:** in the app or on the page. Every upload and download on every device stops, queues are cleared and half-finished files are thrown away; files that already arrived stay.
- **Clean cache on all devices:** the button at the bottom of the page (or in the app). The phone deletes half-finished uploads left by dropped connections, fully saved ZIP batches and its own cache folder; every device with the page open clears its finished-upload list and stored page data. Received files are never deleted.
- **Laptop → another device:** the other device joins the same hotspot, opens the same address and downloads what the laptop sent. The phone relays.

### Time order is kept

Every file keeps its original modified time end to end:

- Uploads go **oldest first** (same time → natural name order, so `img2` before `img10`), and each file's modified time is sent with it and set on the phone. The phone also keeps its own record (`.awd-meta`) in case the storage ignores the time.
- On the page, **Show files in order** lists a folder's files oldest first with their exact times.
- Folders download as a **ZIP** whose entries are in that same order and stamped with each file's original time. Windows "Extract all", 7-Zip, macOS and `unzip` all restore those times.
- A single file downloaded on its own gets the download date from the browser (browsers don't let a page set it), so use the folder ZIP — or **Download all as ZIP** — when the times matter.

Downloads support resume (HTTP Range), so a dropped download can be resumed from the browser's download list.

Tap **✕** on a received file to delete it from the phone; on a phone file it's only taken off the shelf, the original stays.

## Install / update

1. Open this repo's **Releases** page on the phone and download the newest `AnyWhereDoor-v1.0.N.apk`.
2. Open it and allow "Install unknown apps" for your browser if asked.

Every build is signed with the same key (`app/anywheredoor.keystore`), so new versions install over the old one.

## Tips

- If the address doesn't load, try the other address shown under it, and check that the laptop is really on the phone's hotspot and not on office Wi-Fi.
- Turn off mobile data on the phone while transferring if you want to be sure the laptop doesn't use it for updates in the background — the transfer itself never touches it.
- Keep both devices close; hotspot speed drops fast through walls.

## How it works

- `Http.java` — a small HTTP/1.1 server that streams files straight to and from storage (1 MB buffers, nothing held in memory), with Range support for downloads.
- `Shelf.java` — received files in `Download/AnyWhereDoor`, plus files shared from the phone by reference.
- `HubService.java` — foreground service that keeps the server, a wake lock and a Wi-Fi lock alive, and finds the hotspot's address.
- `assets/index.html` — the page the laptop sees.

API (port 8080, or the next free one):

| Method | Path | |
|---|---|---|
| GET | `/` | the web page |
| GET | `/api/state` | phone name, free space, files and folders, live transfers |
| GET | `/api/folder?id=` | files in a folder, oldest first |
| PUT | `/api/upload?path=&mtime=&batch=&from=` | raw file body; `path` may include folders, `mtime` in ms (`curl -T a.jpg "http://PHONE:8080/api/upload?path=Shots/a.jpg&mtime=1760000000000"`) |
| GET | `/api/file?id=&dl=1` | one file (Range supported) |
| GET | `/api/zip?id=` | a folder, or `id=all`, as a ZIP with original times, oldest first |
| GET | `/api/zip?id=b:N&part=k` | part k of a batch picked on the phone |
| POST | `/api/claim?id=b:N`, `/api/sent?id=b:N&part=k` | the page picking up a batch / confirming a saved part |
| GET | `/api/resume?key=` | how much of an upload already arrived; upload with `&key=&offset=` to continue |
| POST | `/api/settings?par=N` | parallel transfers, 1–8 |
| POST | `/api/stop` | stop everything on every device |
| POST | `/api/clean` | clean the phone's cache and signal every open page to clean its own |
| POST | `/api/delete?id=` | delete a received file/folder, or unshare a phone one |

## Build

Every push to `main` builds the APK on GitHub Actions and publishes it as a Release. Local build: `gradle :app:assembleRelease` with the Android SDK installed. No libraries are used.
