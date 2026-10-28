package com.invoiceapp.manager;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Offline wrapper: shows assets/index.html in a WebView and gives the page a small
 * "AndroidBridge" to save PDF / XLSX files into  Documents/InvoiceApp  and to print.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_PERM = 2;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0F172A"));
        if (Build.VERSION.SDK_INT < 29) {
            // old Android: allow sharing a plain file:// uri (no FileProvider needed)
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        }
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "Select backup (.xlsx)"), REQ_FILE);
                return true;
            }
        });

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Functions the web page can call as  AndroidBridge.xxx(...) */
    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64, String sub) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                return save(name, mime, data, sub == null ? "" : sub);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareFile(final String name, final String mime, String base64, String sub) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                final Uri uri = saveUri(name, mime, data, sub == null ? "" : sub);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.putExtra(Intent.EXTRA_SUBJECT, name);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Share bill"));
                    }
                });
                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    PrintDocumentAdapter adapter = web.createPrintDocumentAdapter("Invoice");
                    pm.print("Invoice", adapter,
                            new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                }
            });
        }
    }

    private String save(String name, String mime, byte[] data, String sub) throws Exception {
        saveUri(name, mime, data, sub);
        return "Documents/InvoiceApp/" + (sub.isEmpty() ? "" : sub + "/") + name;
    }

    /** Writes the file into Documents/InvoiceApp[/sub] (overwrites same name) and returns its Uri. */
    private Uri saveUri(String name, String mime, byte[] data, String sub) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            boolean isImg = mime != null && mime.startsWith("image/");
            String rel = (isImg ? "Pictures/InvoiceApp/" : "Documents/InvoiceApp/") + (sub.isEmpty() ? "" : sub + "/");
            Uri coll = isImg ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Files.getContentUri("external");
            Uri uri = null;
            Cursor c = getContentResolver().query(coll,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{name, rel}, null);
            if (c != null) {
                if (c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0));
                c.close();
            }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, name.toLowerCase().endsWith(".xlsm") ? "application/octet-stream" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = getContentResolver().insert(coll, v);
            }
            if (uri == null) throw new Exception("Cannot create file");
            OutputStream os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new Exception("Cannot open file");
            os.write(data);
            os.close();
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "InvoiceApp" + (sub.isEmpty() ? "" : "/" + sub));
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create folder (storage permission?)");
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
            return Uri.fromFile(f);
        }
    }
}
