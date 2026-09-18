package com.spark.hakiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the APK Home Assistant is already serving and hands it to the
 * system installer.
 *
 * deploy.sh puts the APK in HA's /config/www, which is served without
 * authentication at /local/HAKiosk.apk -- the same address the panel used to
 * install this app in the first place. It is signed with the same key, so
 * Android treats it as an in-place upgrade rather than a different app.
 *
 * The APK is around 20 KB, so there is no version file on the server and no
 * HEAD request dance: download it and read its versionCode directly.
 */
final class Updater {

    static final String APK_NAME = "update.apk";
    static final String MIME = "application/vnd.android.package-archive";

    /** Where deploy.sh puts it, under whichever host the panel URL points at. */
    private static final String APK_PATH = "/local/HAKiosk.apk";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** Called back on the UI thread. newerApk is null when there is nothing to do. */
    interface Callback {
        void onResult(String message, File newerApk);
    }

    private Updater() {
    }

    static void check(Activity activity, Callback callback) {
        new Thread(new CheckTask(activity, callback, new Handler())).start();
    }

    /**
     * Where the download lands, which is a version question rather than a
     * tidiness one. Below API 24 the installer needs a file:// URI, and it
     * cannot read our private cache dir -- so the app's own external cache,
     * which since API 19 needs no storage permission. From 24 the private
     * cache is right, because ApkProvider hands the file over instead.
     */
    static File apkFile(Context context) {
        if (Build.VERSION.SDK_INT < 24) {
            File external = context.getExternalCacheDir();
            if (external != null && (external.isDirectory() || external.mkdirs())) {
                return new File(external, APK_NAME);
            }
        }
        return new File(context.getCacheDir(), APK_NAME);
    }

    static String apkUrl(Context context) {
        try {
            URL panel = new URL(Prefs.url(context));
            return new URL(panel.getProtocol(), panel.getHost(), panel.getPort(),
                    APK_PATH).toString();
        } catch (Exception e) {
            // Only reachable if the stored address will not parse as a URL.
            return Prefs.DEFAULT_URL.replaceAll("/+$", "") + APK_PATH;
        }
    }

    /**
     * Two version gates make this longer than it looks like it should be, and
     * both are unavoidable at targetSdk 28 on an Android version we still
     * cannot see from here. Fails with a readable line rather than a crash.
     */
    static String install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26) {
            PackageManager packages = activity.getPackageManager();
            if (!packages.canRequestPackageInstalls()) {
                try {
                    activity.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                    return "Allow this app to install apps, then check again.";
                } catch (Exception e) {
                    return "This Android will not let apps install apps.";
                }
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 24) {
            intent.setDataAndType(ApkProvider.uriFor(APK_NAME), MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apk), MIME);
        }

        try {
            activity.startActivity(intent);
            return "Installer opened.";
        } catch (Exception e) {
            return "No installer on this device.";
        }
    }

    /** Never on the main thread -- that is a NetworkOnMainThreadException. */
    private static final class CheckTask implements Runnable {
        private final Activity activity;
        private final Callback callback;
        private final Handler handler;

        CheckTask(Activity activity, Callback callback, Handler handler) {
            this.activity = activity;
            this.callback = callback;
            this.handler = handler;
        }

        public void run() {
            File out = apkFile(activity);
            String url = apkUrl(activity);
            HttpURLConnection connection = null;
            InputStream in = null;
            FileOutputStream fileOut = null;

            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    post("Server answered " + status + " for " + url, null);
                    return;
                }

                in = connection.getInputStream();
                fileOut = new FileOutputStream(out);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    fileOut.write(buffer, 0, read);
                }
                fileOut.flush();
                // The pre-24 installer is a different app opening this path.
                out.setReadable(true, false);
            } catch (Exception e) {
                post("Could not reach " + url, null);
                return;
            } finally {
                close(fileOut);
                close(in);
                if (connection != null) {
                    connection.disconnect();
                }
            }

            PackageManager packages = activity.getPackageManager();
            PackageInfo downloaded =
                    packages.getPackageArchiveInfo(out.getPath(), 0);
            if (downloaded == null) {
                out.delete();
                post("That file is not an app.", null);
                return;
            }

            int installed;
            try {
                installed = packages.getPackageInfo(
                        activity.getPackageName(), 0).versionCode;
            } catch (PackageManager.NameNotFoundException e) {
                installed = 0;
            }

            if (downloaded.versionCode <= installed) {
                out.delete();
                post(activity.getString(R.string.up_to_date), null);
            } else {
                post("Version " + downloaded.versionName + " is ready.", out);
            }
        }

        private void post(String message, File apk) {
            handler.post(new Post(callback, message, apk));
        }

        private static void close(java.io.Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Nothing useful to do about a failed close.
                }
            }
        }
    }

    private static final class Post implements Runnable {
        private final Callback callback;
        private final String message;
        private final File apk;

        Post(Callback callback, String message, File apk) {
            this.callback = callback;
            this.message = message;
            this.apk = apk;
        }

        public void run() {
            callback.onResult(message, apk);
        }
    }
}
