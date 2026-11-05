# DocNest (Android app)

A free, ad-free document scanner for Android. Photograph your pages and DocNest puts them together
into a PDF.

- **Fast camera.** Tap **Scan**, then the shutter (or a volume button) again and again: there is no
  ✓ / ✗ screen between shots. Tap **Done** when finished.
- **Add photos** from your gallery. They are sorted by the time they were taken, with a note if any
  were taken before today.
- **Check before you make the PDF.** Hold a page and drag it to reorder. Rotate or delete it (with
  Undo). Tap a page to see it large.
- **PDF settings** (the sliders button at the top). Quality, colour or black & white, A4 or
  photo-size pages, file name, and an optional footer with the date and page numbers.
- **Share anywhere.** After **Make PDF**, the file is saved in **Downloads** and the share screen
  opens, so you can send it on WhatsApp, Gmail or any other app.
- **Updates itself.** DocNest checks GitHub for a new version twice a day and offers to install it.
  You can also tap **Check for updates** in the settings sheet.
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
4. Open the **Actions** tab. A "Build APK" run starts by itself and takes about 5–10 minutes. A green
   tick means it worked. A red cross means it failed: open it to read the error.
5. Click the finished run, scroll to **Artifacts** and download **DocNest.apk**. You can do this
   straight from your phone's browser (signed in to GitHub) and then tap the file to install it.

## Releases and updates

Every build from `main` is published as a GitHub Release named `DocNest 1.<build number>`, with the
APK attached. The app reads the latest release to offer updates, so the repository must be public.

Every release is signed with the same key, so a new version installs over the old one and keeps your
pages. Set this up once, before the first release:

1. Open the repository's **Settings → Secrets and variables → Actions** and click **New repository
   secret**.
2. **Name:** `SIGNING_PASSWORD`. **Secret:** a long password you make up, at least 25 letters and
   numbers. Keep a copy in a safe place, such as a password manager. Click **Add secret**.

The next build creates the signing key, locks it with that password, and saves it as
`app/release.keystore`. Only GitHub's build can unlock it. Never delete the secret: without it, new
versions can't be signed to install over the old ones.

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
4. When you are finished, tap **Clear** to start a new batch.

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
