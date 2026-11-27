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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayOutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private File photoFile;
    private Uri photoUri;
    private boolean pageReady = false;
    private String pendingPhotoJs = null;
    private String pendingStepJs = null;
    private static final int PICK_FILE = 42;
    private static final int VOICE = 7301;
    private static final int INSTALL_PERM = 43;
    private File pendingApk;

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    /** Réduit la photo (1600 px, JPEG 82 %) et la renvoie en data URL, en tenant compte de l'orientation. */
    private String photoDataUrl(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
            if (b == null) return null;
            int rot = 0;
            try { int or = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0; } catch (Exception ignored) {}
            float k = Math.min(1f, 1600f / Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix(); m.postScale(k, k); if (rot != 0) m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            r.compress(Bitmap.CompressFormat.JPEG, 82, bo);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) { return null; }
    }

    /** Photo de l'appareil photo reçue alors que la page avait été rechargée : on la transmet à la page. */
    private void sendPhotoToPage(File f) {
        String d = photoDataUrl(f);
        if (d == null) return;
        String code = "window.__camPhoto&&window.__camPhoto('" + d + "')";
        if (pageReady) js(code); else pendingPhotoJs = code;
    }

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
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (pendingPhotoJs != null) { String c = pendingPhotoJs; pendingPhotoJs = null; v.evaluateJavascript(c, null); }
                if (pendingStepJs != null) { String c = pendingStepJs; pendingStepJs = null; v.postDelayed(() -> v.evaluateJavascript(c, null), 300); }
            }
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
                    boolean image = false;
                    for (String a : p.getAcceptTypes()) if (a != null && a.startsWith("image")) image = true;
                    Intent chooser = Intent.createChooser(i, image ? "Prendre ou choisir une photo" : "Choisir");
                    photoFile = null;
                    if (image) {
                        // Appareil photo : la photo est écrite dans le cache de l'appli puis renvoyée à la page
                        File dir = new File(getCacheDir(), "photos");
                        dir.mkdirs();
                        photoFile = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                        photoUri = FileProvider.getUriForFile(MainActivity.this, "fr.verhaeghe.irrigation.files", photoFile);
                        Intent cam = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri);
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    }
                    startActivityForResult(chooser, PICK_FILE);
                } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        if (state != null && state.getString("photoFile") != null) { photoFile = new File(state.getString("photoFile")); photoUri = state.getParcelable("photoUri"); }
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
        handleStep(getIntent());
    }

    /** Ouverture depuis le widget : aller directement à l'étape demandée. */
    private void handleStep(Intent i) {
        if (i == null) return;
        String s = i.getStringExtra("wstep");
        if (s == null) return;
        i.removeExtra("wstep");
        String code = "window.__openStep&&window.__openStep('" + s.replaceAll("[^a-z]", "") + "')";
        if (pageReady) js(code); else pendingStepJs = code;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleStep(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == INSTALL_PERM) {
            if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) installApk(pendingApk);
            else js("window.__upState&&window.__upState('denied')");
            return;
        }
        if (req == VOICE) {
            String t = "";
            if (res == RESULT_OK && data != null) {
                java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) t = r.get(0);
            }
            js("window.__voiceRes&&window.__voiceRes(" + org.json.JSONObject.quote(t) + ")");
            return;
        }
        if (req == PICK_FILE && fileCallback == null) {
            // L'appli a été relancée pendant la prise de vue : on récupère quand même la photo
            if (res == RESULT_OK && photoFile != null && photoFile.length() > 0) { File f = photoFile; new Thread(() -> sendPhotoToPage(f)).start(); }
            photoFile = null;
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
            if (res == RESULT_OK && out == null && photoFile != null && photoFile.length() > 0) out = new Uri[]{photoUri};
            photoFile = null;
            fileCallback.onReceiveValue(out);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b); web.saveState(b);
        if (photoFile != null) { b.putString("photoFile", photoFile.getPath()); b.putParcelable("photoUri", photoUri); }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    class Bridge {
        /** Identifiant propre à ce téléphone et à cette appli (reste le même après réinstallation). */
        @JavascriptInterface
        public String deviceId() {
            try { return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID); } catch (Exception e) { return ""; }
        }

        /** Dictée vocale (reconnaissance vocale d'Android, en français). Renvoie false si indisponible. */
        @JavascriptInterface
        public boolean listen() {
            try {
                Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dictez vos heures");
                if (i.resolveActivity(getPackageManager()) == null) return false;
                runOnUiThread(() -> { try { startActivityForResult(i, VOICE); } catch (Exception e) { js("window.__voiceErr&&window.__voiceErr('Reconnaissance vocale indisponible')"); } });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String deviceModel() { return Build.MANUFACTURER + " " + Build.MODEL; }

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

        /** Met à jour le widget d'écran d'accueil (heures du jour, intervention en cours, étapes remplies). */
        @JavascriptInterface
        public void setWidget(String total, String ot, String done, String hidden) {
            getSharedPreferences("widget", MODE_PRIVATE).edit()
                .putString("total", total).putString("ot", ot).putString("done", done).putString("hidden", hidden).apply();
            try { HeuresWidget.refresh(MainActivity.this); } catch (Exception ignored) {}
        }

        /** Informations complémentaires du widget : heures sup. du mois, totaux semaine et mois, dernière intervention. */
        @JavascriptInterface
        public void setWidgetInfo(String sup, String week, String month, String last) {
            getSharedPreferences("widget", MODE_PRIVATE).edit()
                .putString("sup", sup).putString("week", week).putString("month", month).putString("last", last).apply();
            try { HeuresWidget.refresh(MainActivity.this); } catch (Exception ignored) {}
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
