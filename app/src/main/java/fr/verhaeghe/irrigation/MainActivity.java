package fr.verhaeghe.irrigation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.provider.Settings;
import java.io.OutputStream;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.util.Arrays;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE = 42;
    private static final int INSTALL_PERM = 43;
    private File pendingApk;

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private String[] sigs(PackageInfo pi) {
        if (pi == null) return null;
        Signature[] a;
        if (Build.VERSION.SDK_INT >= 28) { if (pi.signingInfo == null) return null; a = pi.signingInfo.getApkContentsSigners(); }
        else a = pi.signatures;
        if (a == null) return null;
        String[] r = new String[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i].toCharsString();
        Arrays.sort(r);
        return r;
    }

    /** true si l'APK téléchargé est signé avec la même clé que l'appli installée (sinon Android refuse la mise à jour). */
    private boolean sameSigner(File apk) {
        try {
            int f = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageManager pm = getPackageManager();
            PackageInfo n = pm.getPackageArchiveInfo(apk.getPath(), f);
            String[] a = sigs(pm.getPackageInfo(getPackageName(), f)), b = sigs(n);
            if (a == null || b == null) return true; // impossible de vérifier : on laisse Android décider
            return Arrays.equals(a, b);
        } catch (Exception e) { return true; }
    }

    private void installApk(File apk) {
        if (!sameSigner(apk)) { js("window.__upState&&window.__upState('sig')"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            js("window.__upState&&window.__upState('perm')");
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivityForResult(s, INSTALL_PERM);
            return;
        }
        Uri u = FileProvider.getUriForFile(this, "fr.verhaeghe.irrigation.files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        js("window.__upState&&window.__upState('install')");
        startActivity(i);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100); // mise en page stable même si la taille de police Android est agrandie
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme();
                if ("file".equals(sc)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Aucune application pour ouvrir ce lien", Toast.LENGTH_SHORT).show(); }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = p.createIntent();
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i, "Choisir"), PICK_FILE);
                } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == INSTALL_PERM) {
            if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) installApk(pendingApk);
            else js("window.__upState&&window.__upState('denied')");
            return;
        }
        if (req == PICK_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int k = 0; k < n; k++) out[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); web.saveState(b); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Verhaeghe Irrigation", text));
            });
        }

        /** Enregistre le fichier dans Téléchargements puis ouvre la feuille de partage. */
        @JavascriptInterface
        public String saveFile(String base64, String filename, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u != null) try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(bytes); }
                }
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                File f = new File(dir, filename);
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(bytes); }
                Uri share = FileProvider.getUriForFile(MainActivity.this, "fr.verhaeghe.irrigation.files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, share);
                send.putExtra(Intent.EXTRA_SUBJECT, filename);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Partager " + filename)));
                return "ok";
            } catch (Exception e) {
                return "erreur: " + e.getMessage();
            }
        }

        /** Télécharge le nouvel APK dans l'appli (progression envoyée à la page) puis lance l'installation. */
        @JavascriptInterface
        public void downloadUpdate(String url) {
            new Thread(() -> {
                try {
                    String cur = url;
                    HttpURLConnection c = null;
                    for (int k = 0; k < 6; k++) {
                        c = (HttpURLConnection) new URL(cur).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(30000);
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400) { cur = new URL(new URL(cur), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
                        if (code != 200) throw new Exception("HTTP " + code);
                        break;
                    }
                    long total = c.getContentLengthLong();
                    File dir = new File(getCacheDir(), "maj");
                    dir.mkdirs();
                    File out = new File(dir, "Verhaeghe-Irrigation.apk");
                    try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        long got = 0; int n, last = -1;
                        while ((n = in.read(buf)) > 0) {
                            fo.write(buf, 0, n); got += n;
                            int pct = total > 0 ? (int) (got * 100 / total) : -1;
                            if (pct != last) { last = pct; js("window.__upProgress&&window.__upProgress(" + pct + "," + got + ")"); }
                        }
                    }
                    js("window.__upState&&window.__upState('downloaded')");
                    runOnUiThread(() -> installApk(out));
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage()).replace("'", " ");
                    js("window.__upState&&window.__upState('error','" + m + "')");
                }
            }).start();
        }

        @JavascriptInterface
        public void shareText(String title, String text) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, title);
            send.putExtra(Intent.EXTRA_TEXT, text);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, title)));
        }
    }
}
