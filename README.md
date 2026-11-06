# DocNest (Android app)

A free, ad-free document scanner for Android. Photograph your pages and DocNest puts them together
into a PDF.

- **Fast camera.** Tap **Scan**, then the shutter (or a volume button) again and again: there is no
  ✓ / ✗ screen between shots. Tap **Done** when finished.
- **Add photos** from your gallery. They are sorted by the time they were taken, with a note if any
  were taken before today.
- **Check before you make the PDF.** Hold a page and drag it to reorder. Rotate or delete it (with
  Undo). Tap a page to see it large.
- **PDF settings** (the sliders button at the top). Quality, A4 or photo-size pages, an optional
  footer with the date and page numbers, and how pages look:
  - **Colour:** the photo as taken.
  - **Document:** like a scanner. Lighting is evened out, shadows and yellowed paper turn white,
    and text is sharpened.
  - **B&W:** pure black text on white, judged spot by spot so faint print and shaded areas stay
    readable. Pages are stored at 1 bit per pixel, so the PDF is much smaller. Best for pages of
    text; photos in the article turn into rough black-and-white patterns.
- **File names** are always `Scan_<date>.pdf`; a second PDF on the same day gets "(1)" added.
- **Share anywhere.** After **Make PDF**, the file is saved in **Downloads** and the share screen
  opens, so you can send it on WhatsApp, Gmail or any other app.
- **My documents** (the folder button at the top). Every PDF DocNest has made, newest first, with a
  preview of page 1. Tap one to open it, tap the star to keep it under **Favourites**, or use **⋮**
  to share, rename or delete it. Deleting has an Undo. These are the same files as in Downloads, so a
  rename or delete here changes the file there too.
- **Updates itself.** When a new version is out, a card appears at the top: **What's new**, **Later**,
  **Update**, or ✕ to skip that version. Every download is checked against the release's SHA-256
  checksum before it can be installed. You can also tap **Check for updates** in the settings sheet.
- **Private.** No account, no ads, no tracking. Pages stay on the phone until you tap **Clear**. The
  only thing DocNest does online is check GitHub for updates.

For now the whole photo always goes into the PDF, uncropped. Automatic edge detection, which you will
be able to switch on or off, is planned.

---

## Build the app online with GitHub (nothing to install)

GitHub builds the APK for free on its own computers. You only need a GitHub account and a browser.

1. Sign in at <https://github.com> and click **New** (top left) to make a repository. Name it
   `docnest`, choose **Private**, and click **Create repository**.
2. On the new repository page click **uploading an existing file**. Open `D:\Article Scan app` in
   File Explorer, select everything inside it (Ctrl+A) and drag it onto the page. Wait for all the
   files to finish, then click **Commit changes**.
3. Check that the upload included the `.github` folder: it holds the build instructions. If it is
   missing, click **Add file → Create new file**, type `.github/workflows/build-apk.yml` as the name,
   paste in the contents of that file from this folder, and click **Commit changes**.
4. Open the **Actions** tab. A "Build check" run starts by itself and takes a few minutes. A green
   tick means the app builds. A red cross means it failed: open it to read the error.
5. To get an APK you can install, make a release (see below).

## Releases and updates

Pushes to `main` are only checked. A release is made when you choose, in either of two ways:

- **On GitHub:** **Actions → Release → Run workflow**. It picks the next number itself.
- **From a computer:** push a tag with the next number, for example `git tag v1.7` then
  `git push origin v1.7`.

Each release is named `DocNest 1.<number>` and carries:

- `DocNest-1.<number>.apk`, the app;
- `DocNest-latest.apk`, the same file under a name that never changes, so this link always gets the
  newest version: <https://github.com/rahulranjan-dev-py/docnest/releases/latest/download/DocNest-latest.apk>;
- `SHA256SUMS.txt`, the checksums the app uses to check its download;
- release notes: one line per change since the previous release, shown in the app under
  **What's new**.

Before publishing, the build checks that the key opens with your secrets, and that the finished APK
carries that key's certificate. The app reads the latest release to offer updates, so the repository
must be public.

Every release is signed with the same key, so a new version installs over the old one and keeps your
pages. The key lives only in the repository's secrets (**Settings → Secrets and variables → Actions**),
never in the code:

| Secret | What it holds |
|---|---|
| `KEYSTORE_BASE64` | The keystore file, converted to text (base64) |
| `KEYSTORE_PASSWORD` | The password that opens the keystore |
| `KEY_ALIAS` | The name of the key inside the keystore (`docnest`) |
| `KEY_PASSWORD` | The key's own password (the same as `KEYSTORE_PASSWORD` for this keystore) |

If any of them is missing, the build stops instead of publishing an APK signed with another key. Keep
your own copy of the keystore file and its password somewhere safe, such as a password manager:
GitHub never shows a secret again once it is saved, and without that key, new versions can't install
over the old ones.

## Or build the app in Android Studio (on this computer)

1. **Install Android Studio.** Download it free from <https://developer.android.com/studio> and run
   the installer. When it first opens, choose **Standard** setup and accept the licences. It downloads
   about 1–2 GB.
2. **Open this project.** In Android Studio choose **Open**, pick the folder `D:\Article Scan app`,
   and click **Trust Project** if asked.
3. **Wait for the sync.** The first time, Android Studio downloads its build tools. Watch the bar at
   the bottom; this can take 10–20 minutes. It is done when the bottom bar is quiet and there is no
   red error.
4. **Build the APK.** Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. When it finishes,
   a message appears at the bottom right: click **locate**. The file is
   `D:\Article Scan app\app\build\outputs\apk\debug\app-debug.apk`.

## Install it on the phone

1. Copy `app-debug.apk` to the phone: by USB cable, Google Drive, or send it to yourself on WhatsApp
   or email.
2. On the phone, tap the file. Android asks to allow installing apps from that source (Files, Drive,
   WhatsApp…): turn it on, go back, and tap **Install**.
3. If Play Protect says the app is from an unknown developer, tap **More details → Install anyway**.
   This is normal for an app you built yourself.
4. Open **DocNest** from the app drawer. The first time you tap **Camera**, allow the camera.

## How to use

1. Tap **Scan** and shoot your pages, one after another. Fit each page inside the frame; what you
   see on screen is exactly what goes into the PDF. Tap the screen to focus. The lightning button
   turns on the torch for dim rooms.
2. Tap **Done**. Check the pages: hold and drag to reorder, rotate or delete. Tap a page to see it
   large.
3. Tap **Make PDF**. It is saved in Downloads and the share screen opens: choose WhatsApp, Gmail or
   any other app. Tap **Share PDF** to send it again.
4. When you are finished, tap **Clear** to start a new batch. Your PDF stays in **My documents**.

---

## If something goes wrong while building

- **"Unsupported class file major version" or a Gradle JDK error:** go to **File → Settings → Build,
  Execution, Deployment → Build Tools → Gradle**, set **Gradle JDK** to a version 17 or 21 (choose
  **Download JDK…** if none is listed), then click **Sync Project with Gradle Files** (the elephant
  icon at the top right).
- **"SDK location not found" or a missing Android platform:** click the blue link in the error
  (for example *Install missing platform*), let it download, and sync again.
- **Android Studio offers to upgrade the Android Gradle plugin:** you can accept. The Upgrade
  Assistant updates the version numbers for you.

## Updating the app later

DocNest offers each new release by itself: tap **Update** when it appears. The first time, Android asks
you to allow DocNest to install apps; turn on **Allow from this source** and go back.

Builds made in Android Studio are signed with your computer's debug key, not the release key, so they
won't install over a release. Uninstall DocNest first if you want to test one.
