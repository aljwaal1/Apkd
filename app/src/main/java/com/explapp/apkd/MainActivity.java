package com.explapp.apkd;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        web.addJavascriptInterface(new GitHubBridge(), "NativeGitHub");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                downloadInBackground(url, contentDisposition, mimeType);
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private class GitHubBridge {
        @JavascriptInterface
        public void get(final String path, final String token, final String requestId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int status = 0;
                    String body = "";
                    HttpsURLConnection connection = null;
                    try {
                        SSLContext context = SSLContext.getInstance("TLSv1.2");
                        context.init(null, null, null);

                        URL url = new URL("https://api.github.com" + path);
                        connection = (HttpsURLConnection) url.openConnection();
                        connection.setSSLSocketFactory(context.getSocketFactory());
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(30000);
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Accept", "application/vnd.github+json");
                        connection.setRequestProperty("User-Agent", "Apkd-Legacy-Android-4.4");
                        if (token != null && token.trim().length() > 0) {
                            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
                        }

                        status = connection.getResponseCode();
                        InputStream stream = status >= 200 && status < 400
                                ? connection.getInputStream()
                                : connection.getErrorStream();
                        body = readAll(stream);
                    } catch (Exception e) {
                        body = "{\"message\":" + JSONObject.quote(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}";
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                    sendNativeResult(requestId, status, body);
                }
            }).start();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close();
        return result.toString();
    }

    private void sendNativeResult(final String requestId, final int status, final String body) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String script = "javascript:window.__nativeGitHubResult(" +
                        JSONObject.quote(requestId) + "," + status + "," + JSONObject.quote(body) + ")";
                web.loadUrl(script);
            }
        });
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (isApkUrl(url)) {
            downloadInBackground(url, null, "application/vnd.android.package-archive");
            return true;
        }
        if (isExternalLink(url)) {
            openExternal(url);
            return true;
        }
        return false;
    }

    private boolean isApkUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".apk") || lower.contains("application/vnd.android.package-archive");
    }

    private boolean isExternalLink(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void downloadInBackground(String url, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition,
                    mimeType == null ? "application/vnd.android.package-archive" : mimeType);
            if (!fileName.toLowerCase().endsWith(".apk")) fileName += ".apk";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("جاري تنزيل ملف التطبيق");
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "بدأ التنزيل في الخلفية", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر التنزيل الداخلي، سيتم فتح المتصفح", Toast.LENGTH_SHORT).show();
            openExternal(url);
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق لفتح الرابط", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
