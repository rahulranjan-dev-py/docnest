# DocNest (Android app)

A free, ad-free document scanner for Android. Photograph your pages and DocNest puts them together
into a PDF.

- **Fast camera.** Tap the button (or a volume button) again and again: there is no ✓ / ✗ screen
  between shots. Tap **Done** when finished.
- **Add from gallery.** Photos are sorted by the time they were taken, with a warning for photos from
  an earlier day.
- **Check before you make the PDF.** Rotate, move or remove any photo (with Undo). Tap a photo to see
  it large.
- **PDF settings.** File size, colour or black & white, A4 or photo-size pages, file name, and an
  optional footer with the date and page numbers.
- **Share anywhere.** After **Make PDF**, the file is saved in **Downloads** and the share screen
  opens, so you can send it on WhatsApp, Gmail or any other app.
- **Private and offline.** No account, no internet, no ads. Photos stay on the phone until you tap
  **New batch**.

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
4. Open the **Actions** tab. A "Build APK" run starts by itself and takes about 5–10 minutes. A green
   tick means it worked. A red cross means it failed: open it to read the error.
5. Click the finished run, scroll to **Artifacts** and download **DocNest-apk**. It is a zip
   file: open it to get `app-debug.apk`, then install it as described below.

Each online build is signed with a new key, so installing a newer build over the old one fails with
"App not installed". Send the day's PDF first, then uninstall the old app and install the new one.

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

1. Tap **Camera** and shoot your pages, one after another. Fit each page inside the frame; what you
   see on screen is exactly what goes into the PDF. Tap the screen to focus. **Light** turns on the
   torch for dim rooms.
2. Tap **Done**. Check the photos: rotate, move or remove any of them. Tap a photo to see it large.
3. Tap **Make PDF**. It is saved in Downloads and the share screen opens: choose WhatsApp, Gmail or
   any other app. Tap **Share PDF** to send it again.
4. When you are finished, tap **New batch** to clear the photos.

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

Build again and install the new APK over the old one; today's photos are kept. If you ever build on a
different computer, uninstall the old app from the phone first, because the two builds are signed
differently.
