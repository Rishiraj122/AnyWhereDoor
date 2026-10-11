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

- **Laptop → phone:** drag files onto the page, or click **Choose files**. They upload one after another with progress, speed and time left, and are saved in `Download/AnyWhereDoor` on the phone.
- **Phone → laptop:** in the app, tap **Put files from this phone on the shelf** (or use Share → AnyWhereDoor from the gallery or file manager). The files appear on the page with a **Download** button. Nothing is copied, so multi-GB files are instant to add.
- **Laptop → another device:** the other device joins the same hotspot, opens the same address and downloads what the laptop sent. The phone relays.

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
| GET | `/api/state` | phone name, free space, files, live transfers |
| PUT | `/api/upload?name=&from=` | raw file body (`curl -T file.zip "http://PHONE:8080/api/upload?name=file.zip"`) |
| GET | `/api/file?id=&dl=1` | download (Range supported) |
| POST | `/api/delete?id=` | delete a received file / unshare a phone file |

## Build

Every push to `main` builds the APK on GitHub Actions and publishes it as a Release. Local build: `gradle :app:assembleRelease` with the Android SDK installed. No libraries are used.
