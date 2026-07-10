package com.explapp.localsend;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 100;
    private static final int PICK_FOLDER = 101;
    private static final int PICK_OLD = 102;
    private static final int PERM = 103;
    private static final String PREFS = "localsend_settings";
    private static final String KEY_TARGET = "saved_target";
    private static final int PORT = 5051;

    private final ArrayList<Item> selected = new ArrayList<Item>();
    private TextView status;
    private TextView selectedView;
    private TextView myAddress;
    private EditText target;
    private ProgressBar progress;
    private LocalServer server;
    private SharedPreferences prefs;

    static class Item {
        String uri;
        String name;

        Item(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        requestStorageIfNeeded();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 22, 22, 22);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("الإرسال المحلي بين الهاتف والكمبيوتر");
        title.setTextSize(23);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("يعمل داخل شبكة Wi-Fi نفسها: هاتف ↔ هاتف، كمبيوتر → هاتف، وهاتف → كمبيوتر. الملفات المستلمة تحفظ في Download/LocalSend.");
        note.setPadding(0, 8, 0, 14);
        root.addView(note);

        myAddress = new TextView(this);
        myAddress.setTextSize(17);
        myAddress.setPadding(10, 14, 10, 14);
        root.addView(myAddress);

        Button start = button("تشغيل استقبال الملفات");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });
        root.addView(start);

        Button stop = button("إيقاف الاستقبال");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });
        root.addView(stop);

        target = new EditText(this);
        target.setHint("IP أو رابط الجهاز المستقبل مثل 192.168.1.20");
        target.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        target.setText(prefs.getString(KEY_TARGET, ""));
        root.addView(target);

        Button saveIp = button("حفظ عنوان IP");
        saveIp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTarget();
            }
        });
        root.addView(saveIp);

        Button files = button("اختيار عدة ملفات من Download");
        files.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        });
        root.addView(files);

        Button folder = button("اختيار مجلد كامل");
        folder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFolder();
            }
        });
        root.addView(folder);

        Button send = button("إرسال المحدد");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSelected();
            }
        });
        root.addView(send);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress);

        selectedView = new TextView(this);
        selectedView.setPadding(8, 14, 8, 14);
        root.addView(selectedView);

        status = new TextView(this);
        status.setPadding(12, 16, 12, 16);
        root.addView(status);

        setContentView(scroll);
        updateAddress();
        updateSelected();
        startServer();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERM);
        }
    }

    private void saveTarget() {
        String value = target.getText().toString().trim();
        prefs.edit().putString(KEY_TARGET, value).apply();
        Toast.makeText(this, "تم حفظ عنوان الجهاز", Toast.LENGTH_SHORT).show();
    }

    private void updateAddress() {
        String ip = getIp();
        myAddress.setText("عنوان هذا الجهاز للاستقبال:\nhttp://" + ip + ":" + PORT + "/upload\nيمكن فتحه من متصفح الكمبيوتر أو استخدامه في التطبيق الآخر.");
    }

    private String getIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (!address.isLoopbackAddress() && host != null && host.indexOf(':') < 0) {
                        return host;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private void startServer() {
        try {
            if (server != null) {
                server.stop();
            }
            server = new LocalServer(PORT);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            status.setText("الاستقبال يعمل الآن");
            updateAddress();
        } catch (Exception e) {
            status.setText("تعذر تشغيل الاستقبال: " + safeMessage(e));
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        status.setText("تم إيقاف الاستقبال");
    }

    private void pickFiles() {
        if (Build.VERSION.SDK_INT >= 19) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_FILES);
        } else {
            startActivityForResult(new Intent(this, FilePickerActivity.class), PICK_OLD);
        }
    }

    private void pickFolder() {
        if (Build.VERSION.SDK_INT >= 21) {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
        } else {
            startActivityForResult(new Intent(this, FilePickerActivity.class), PICK_OLD);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) {
            return;
        }
        try {
            if (request == PICK_OLD) {
                ArrayList<String> paths = data.getStringArrayListExtra("paths");
                if (paths != null) {
                    for (String path : paths) {
                        File file = new File(path);
                        addItem(Uri.fromFile(file), file.getName());
                    }
                }
            } else if (request == PICK_FOLDER) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    collectDoc(DocumentFile.fromTreeUri(this, uri));
                }
            } else {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        addItem(uri, nameOf(uri));
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    addItem(uri, nameOf(uri));
                }
            }
        } catch (Exception e) {
            status.setText("تعذر قراءة الملفات: " + safeMessage(e));
        }
        updateSelected();
    }

    private void collectDoc(DocumentFile document) {
        if (document == null) {
            return;
        }
        if (document.isFile()) {
            addItem(document.getUri(), document.getName());
            return;
        }
        for (DocumentFile child : document.listFiles()) {
            collectDoc(child);
        }
    }

    private void addItem(Uri uri, String name) {
        selected.add(new Item(uri.toString(), name == null ? "file" : name));
    }

    private String nameOf(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "file_" + System.currentTimeMillis();
    }

    private void updateSelected() {
        selectedView.setText(selected.isEmpty() ? "لم يتم اختيار ملفات" : "عدد الملفات المحددة: " + selected.size());
    }

    private String buildTargetUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null && value.startsWith("http://")) {
            host = value.substring(7);
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        int port = uri.getPort() > 0 ? uri.getPort() : PORT;
        String path = uri.getPath();
        if (path == null || path.length() == 0 || "/".equals(path)) {
            path = "/upload";
        }
        return "http://" + host + ":" + port + path;
    }

    private void sendSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "اختر ملفات أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        final String rawTarget = target.getText().toString().trim();
        final String base = buildTargetUrl(rawTarget);
        if (base.length() == 0 || base.contains("null")) {
            Toast.makeText(this, "اكتب عنوان الجهاز المستقبل", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(KEY_TARGET, rawTarget).apply();
        progress.setProgress(0);
        status.setText("بدأ الإرسال إلى " + base);

        new Thread(new Runnable() {
            @Override
            public void run() {
                int sent = 0;
                String error = "";
                for (Item item : selected) {
                    try {
                        sendOne(base, item, sent, selected.size());
                        sent++;
                        final int count = sent;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("تم إرسال " + count + " من " + selected.size());
                                progress.setProgress((count * 100) / selected.size());
                            }
                        });
                    } catch (Exception e) {
                        error = safeMessage(e);
                        break;
                    }
                }
                final int finalSent = sent;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalError.length() == 0) {
                            progress.setProgress(100);
                            status.setText("اكتمل الإرسال: " + finalSent + " ملف");
                        } else {
                            status.setText("توقف بعد " + finalSent + " ملف: " + finalError);
                        }
                    }
                });
            }
        }).start();
    }

    private void sendOne(String base, Item item, final int completedBefore, final int totalFiles) throws Exception {
        String separator = base.contains("?") ? "&" : "?";
        URL url = new URL(base + separator + "name=" + URLEncoder.encode(item.name, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-File-Name", URLEncoder.encode(item.name, "UTF-8"));

        InputStream input = open(item.uri);
        OutputStream output = connection.getOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        long transferred = 0;
        long totalBytes = sizeOf(item.uri);
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            transferred += read;
            if (totalBytes > 0) {
                final int filePercent = (int) Math.min(100, (transferred * 100L) / totalBytes);
                final int overall = (int) (((completedBefore * 100L) + filePercent) / Math.max(1, totalFiles));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progress.setProgress(overall);
                    }
                });
            }
        }
        output.flush();
        output.close();
        input.close();

        int code = connection.getResponseCode();
        String response = readSmall(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + (response.length() > 0 ? " - " + response : ""));
        }
    }

    private String readSmall(HttpURLConnection connection, int code) {
        InputStream stream = null;
        try {
            stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                return "";
            }
            byte[] data = new byte[1024];
            int count = stream.read(data);
            return count > 0 ? new String(data, 0, count, "UTF-8") : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            try {
                if (stream != null) stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private long sizeOf(String value) {
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse(value);
            if ("file".equals(uri.getScheme())) {
                return new File(uri.getPath()).length();
            }
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private InputStream open(String value) throws Exception {
        Uri uri = Uri.parse(value);
        if ("file".equals(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath()));
        }
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new Exception("تعذر فتح الملف");
        }
        return input;
    }

    private class LocalServer extends NanoHTTPD {
        LocalServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String path = session.getUri();
            if (Method.GET.equals(session.getMethod())) {
                return uploadPage();
            }
            if (!"/upload".equals(path)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found");
            }
            if (Method.PUT.equals(session.getMethod())) {
                return receiveRaw(session);
            }
            if (Method.POST.equals(session.getMethod())) {
                return receiveMultipart(session);
            }
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=utf-8", "Use PUT or POST");
        }

        private Response uploadPage() {
            String html = "<!doctype html><html lang='ar' dir='rtl'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>الإرسال المحلي</title><style>body{font-family:Arial;padding:20px;max-width:700px;margin:auto}button,input{font-size:18px;padding:12px;margin:8px 0;width:100%}#s{white-space:pre-wrap}</style></head><body>"
                    + "<h2>إرسال ملفات إلى الهاتف</h2><p>اختر عدة ملفات، وسيتم إرسالها واحدًا تلو الآخر بطريقة Streaming.</p>"
                    + "<input id='f' type='file' multiple><button onclick='send()'>إرسال الملفات</button><div id='s'>جاهز</div>"
                    + "<script>async function send(){var fs=document.getElementById('f').files,s=document.getElementById('s');if(!fs.length){s.textContent='اختر ملفات أولًا';return;}for(var i=0;i<fs.length;i++){s.textContent='إرسال '+(i+1)+' من '+fs.length+' : '+fs[i].name;var r=await fetch('/upload?name='+encodeURIComponent(fs[i].name),{method:'PUT',headers:{'Content-Type':'application/octet-stream'},body:fs[i]});if(!r.ok){s.textContent='فشل: HTTP '+r.status;return;}}s.textContent='اكتمل إرسال '+fs.length+' ملف';}</script></body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }

        private Response receiveRaw(IHTTPSession session) {
            String name = getIncomingName(session);
            try {
                OutputTarget target = createOutputTarget(name);
                InputStream input = session.getInputStream();
                copyStream(input, target.output);
                target.output.close();
                final String savedName = target.displayName;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("تم استلام: " + savedName);
                    }
                });
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "OK");
            } catch (Exception e) {
                final String message = safeMessage(e);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("فشل الاستقبال: " + message);
                    }
                });
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", message);
            }
        }

        private Response receiveMultipart(IHTTPSession session) {
            try {
                Map<String, String> files = new HashMap<String, String>();
                session.parseBody(files);
                if (files.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "No file");
                }
                int saved = 0;
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    File temp = new File(entry.getValue());
                    if (!temp.exists()) continue;
                    String name = getIncomingName(session);
                    if (files.size() > 1) name = entry.getKey() + "_" + name;
                    OutputTarget target = createOutputTarget(name);
                    InputStream input = new FileInputStream(temp);
                    copyStream(input, target.output);
                    input.close();
                    target.output.close();
                    saved++;
                }
                final int count = saved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("تم استلام " + count + " ملف من الكمبيوتر");
                    }
                });
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "OK " + saved);
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", safeMessage(e));
            }
        }

        private String getIncomingName(IHTTPSession session) {
            String name = null;
            List<String> values = session.getParameters().get("name");
            if (values != null && !values.isEmpty()) {
                name = values.get(0);
            }
            if ((name == null || name.length() == 0) && session.getHeaders().containsKey("x-file-name")) {
                name = session.getHeaders().get("x-file-name");
            }
            try {
                if (name != null) name = URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {
            }
            if (name == null || name.trim().length() == 0) {
                name = "file_" + System.currentTimeMillis();
            }
            return uniqueSafeName(name);
        }
    }

    static class OutputTarget {
        OutputStream output;
        String displayName;

        OutputTarget(OutputStream output, String displayName) {
            this.output = output;
            this.displayName = displayName;
        }
    }

    private OutputTarget createOutputTarget(String requestedName) throws Exception {
        String name = uniqueSafeName(requestedName);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalSend");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new Exception("تعذر إنشاء الملف في Download/LocalSend");
            }
            OutputStream output = getContentResolver().openOutputStream(uri);
            if (output == null) {
                throw new Exception("تعذر فتح الملف للحفظ");
            }
            return new OutputTarget(output, name);
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new Exception("تعذر إنشاء مجلد Download/LocalSend");
        }
        File destination = uniqueFile(directory, name);
        return new OutputTarget(new FileOutputStream(destination), destination.getName());
    }

    private void copyStream(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private String uniqueSafeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.length() == 0) cleaned = "file_" + System.currentTimeMillis();
        return cleaned;
    }

    private File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        while (file.exists()) {
            file = new File(directory, base + " (" + index + ")" + ext);
            index++;
        }
        return file;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? e.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }
}
