package com.explapp.apkd;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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