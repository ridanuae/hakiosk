package io.github.ridanuae.hakiosk;

import android.content.Context;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Asks the outdoor door station whether a call is up.
 *
 * Why this exists: the panel cannot tell. Read off a live panel with a
 * call over and nothing playing -- `players=1 rec=0 musicActive=true mode=0`.
 * The audio mode never leaves MODE_NORMAL, no playback configuration is ever
 * registered, and isMusicActive() sticks true for good once anything has played
 * (which the camera popup guarantees). Five versions tried to infer the call
 * from the panel's own state and every one was wrong. The door station, by
 * contrast, simply knows: ISAPI reports idle / ring / onCall.
 *
 * HTTP digest is done by hand here. Android's HttpURLConnection does Basic and
 * nothing else -- java.net.Authenticator will not negotiate Digest on this
 * platform -- and Hikvision ISAPI is digest-only. It is about sixty lines and
 * needs no dependency, which matters for a build with no Gradle and no jars.
 *
 * Never called on the main thread: PanelWatchdog runs it inside goAsync().
 */
final class DoorStation {

    /** Short: this runs inside a BroadcastReceiver's ~10s of grace. */
    private static final int TIMEOUT_MS = 4000;

    private static final String PATH = "/ISAPI/VideoIntercom/callStatus?format=json";

    /** What the door station says, or null when it could not be asked. */
    static final String IDLE = "idle";

    private DoorStation() {
    }

    static boolean configured(Context context) {
        return Prefs.doorHost(context).length() > 0
                && Prefs.doorPassword(context).length() > 0;
    }

    /**
     * Returns "idle", "ring", "onCall", or null if the station could not be
     * reached or refused us. Null is the important one: the caller must treat
     * "don't know" as "fall back to the timer", never as "no call".
     */
    static String status(Context context) {
        String host = Prefs.doorHost(context);
        String user = Prefs.doorUser(context);
        String password = Prefs.doorPassword(context);
        if (host.length() == 0 || password.length() == 0) {
            return null;
        }
        try {
            String url = "http://" + host + PATH;
            // First pass: unauthenticated, purely to collect the challenge.
            HttpURLConnection probe = open(url);
            int code = probe.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                return parse(read(probe));
            }
            if (code != HttpURLConnection.HTTP_UNAUTHORIZED) {
                return null;
            }
            String challenge = probe.getHeaderField("WWW-Authenticate");
            probe.disconnect();
            if (challenge == null) {
                return null;
            }

            HttpURLConnection auth = open(url);
            auth.setRequestProperty("Authorization",
                    authorization(challenge, user, password, PATH));
            if (auth.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            return parse(read(auth));
        } catch (Exception ignored) {
            // Unreachable, timed out, bad host, DNS -- all the same answer here:
            // we do not know, so the caller uses its timer.
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        InputStream in = connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > 8192) {
                break;
            }
        }
        in.close();
        connection.disconnect();
        return out.toString("UTF-8");
    }

    /** {"CallStatus":{"status":"idle"}} -- small enough not to want a parser. */
    private static String parse(String body) {
        int key = body.indexOf("\"status\"");
        if (key < 0) {
            return null;
        }
        int open = body.indexOf('"', body.indexOf(':', key) + 1);
        int close = open < 0 ? -1 : body.indexOf('"', open + 1);
        return close < 0 ? null : body.substring(open + 1, close);
    }

    private static String authorization(String challenge, String user,
                                        String password, String uri)
            throws Exception {
        String realm = field(challenge, "realm");
        String nonce = field(challenge, "nonce");
        String qop = field(challenge, "qop");
        String opaque = field(challenge, "opaque");
        if (realm == null || nonce == null) {
            return null;
        }

        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5("GET:" + uri);
        String cnonce = Long.toHexString(System.nanoTime());
        String nc = "00000001";

        String response;
        StringBuilder header = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append("\"");
        if (qop != null && qop.contains("auth")) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            header.append(", qop=auth, nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append("\"");
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        header.append(", response=\"").append(response).append("\"");
        if (opaque != null && opaque.length() > 0) {
            header.append(", opaque=\"").append(opaque).append("\"");
        }
        return header.toString();
    }

    /** Pulls name="value" out of the WWW-Authenticate line. */
    private static String field(String challenge, String name) {
        int at = challenge.indexOf(name + "=");
        if (at < 0) {
            return null;
        }
        int from = at + name.length() + 1;
        if (from < challenge.length() && challenge.charAt(from) == '"') {
            int end = challenge.indexOf('"', from + 1);
            return end < 0 ? null : challenge.substring(from + 1, end);
        }
        int end = challenge.indexOf(',', from);
        return challenge.substring(from, end < 0 ? challenge.length() : end).trim();
    }

    private static String md5(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(text.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            hex.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return hex.toString();
    }
}
