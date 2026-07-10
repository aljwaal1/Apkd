package com.explapp.localsend;

import android.Manifest;
import android.app.Activity;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 100;
    private static final int PICK_FOLDER = 101;
    private static final int PICK_OLD = 102;
    private static final int PERM = 103;
    private static final int PORT = 5051;
    private static final String MAGIC = "EXPLSEND2";
    private static final String PREFS = "localsend_settings";
    private static final String KEY_TARGET = "saved_target";

    private final ArrayList<Item> selected = new ArrayList<Item>();
    private TextView status;
    private TextView selectedView;
    private TextView myAddress;
    private EditText target;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private volatile boolean receiving;
    private ServerSocket serverSocket;
    private Thread serverThread;

    static class Item {
        String uri;
        String name;
        Item(String uri, String name) { this.uri = uri; this.name = name; }
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
        title.setText("الإرسال المحلي الموحد V2");
        title.setTextSize(23);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("هاتف ↔ هاتف، كمبيوتر ↔ هاتف. يعمل داخل شبكة Wi‑Fi نفسها عبر Streaming مباشر دون HTTP. الملفات المستلمة تحفظ في Download/LocalSend.");
        note.setPadding(0, 8, 0, 14);
        root.addView(note);

        myAddress = new TextView(this);
        myAddress.setTextSize(17);
        myAddress.setPadding(10, 14, 10, 14);
        root.addView(myAddress);

        Button start = button("تشغيل استقبال الملفات");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startReceiver(); }
        });
        root.addView(start);

        Button stop = button("إيقاف الاستقبال");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopReceiver(); }
        });
        root.addView(stop);

        target = new EditText(this);
        target.setHint("IP الجهاز المستقبل مثل 192.168.1.20");
        target.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        target.setText(prefs.getString(KEY_TARGET, ""));
        root.addView(target);

        Button saveIp = button("حفظ عنوان IP");
        saveIp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveTarget(); }
        });
        root.addView(saveIp);

        Button files = button("اختيار عدة ملفات من Download");
        files.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFiles(); }
        });
        root.addView(files);

        Button folder = button("اختيار مجلد كامل");
        folder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });
        root.addView(folder);

        Button send = button("إرسال المحدد");
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendSelected(); }
        });
        root.addView(send);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
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
        startReceiver();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERM);
        }
    }

    private void saveTarget() {
        String value = cleanHost(target.getText().toString());
        target.setText(value);
        prefs.edit().putString(KEY_TARGET, value).apply();
        Toast.makeText(this, "تم حفظ عنوان الجهاز", Toast.LENGTH_SHORT).show();
    }

    private String cleanHost(String value) {
        if (value == null) return "";
        String v = value.trim();
        v = v.replace("http://", "").replace("https://", "");
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        int colon = v.indexOf(':');
        if (colon >= 0) v = v.substring(0, colon);
        return v.trim();
    }

    private void updateAddress() {
        myAddress.setText("عنوان هذا الجهاز للاستقبال:\n" + getIp() + ":" + PORT + "\nاستخدم هذا العنوان في الهاتف أو أداة ويندوز الأخرى.");
    }

    private String getIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String h = a.getHostAddress();
                    if (!a.isLoopbackAddress() && h != null && h.indexOf(':') < 0) return h;
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private synchronized void startReceiver() {
        if (receiving) {
            status.setText("الاستقبال يعمل الآن على المنفذ " + PORT);
            return;
        }
        receiving = true;
        serverThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    serverSocket.setReuseAddress(true);
                    ui("الاستقبال يعمل الآن على المنفذ " + PORT, -1);
                    while (receiving) {
                        Socket socket = serverSocket.accept();
                        handleIncoming(socket);
                    }
                } catch (Exception e) {
                    if (receiving) ui("تعذر تشغيل الاستقبال: " + safeMessage(e), -1);
                } finally {
                    closeServer();
                }
            }
        });
        serverThread.start();
    }

    private synchronized void stopReceiver() {
        receiving = false;
        closeServer();
        status.setText("تم إيقاف الاستقبال");
    }

    private void closeServer() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }

    private void handleIncoming(Socket socket) {
        DataInputStream in = null;
        DataOutputStream out = null;
        OutputStream fileOut = null;
        try {
            socket.setSoTimeout(120000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            String magic = in.readUTF();
            if (!MAGIC.equals(magic)) throw new Exception("بروتوكول غير متوافق");
            String name = safeName(in.readUTF());
            long size = in.readLong();
            if (size < 0) throw new Exception("حجم ملف غير صالح");

            SaveTarget saveTarget = createSaveTarget(name);
            fileOut = saveTarget.output;
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            while (received < size) {
                int need = (int)Math.min(buffer.length, size - received);
                int n = in.read(buffer, 0, need);
                if (n < 0) throw new Exception("انقطع الاتصال قبل اكتمال الملف");
                fileOut.write(buffer, 0, n);
                received += n;
                final int percent = size == 0 ? 100 : (int)((received * 100L) / size);
                ui("جاري الاستقبال: " + name + " — " + percent + "%", percent);
            }
            fileOut.flush();
            fileOut.close();
            fileOut = null;
            out.writeUTF("OK");
            out.flush();
            ui("تم استلام: " + name, 100);
        } catch (Exception e) {
            try { if (out != null) { out.writeUTF("ERROR:" + safeMessage(e)); out.flush(); } } catch (Exception ignored) {}
            ui("فشل الاستقبال: " + safeMessage(e), -1);
        } finally {
            try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void pickFiles() {
        if (Build.VERSION.SDK_INT >= 19) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, PICK_FILES);
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
        if (result != RESULT_OK || data == null) return;
        try {
            if (request == PICK_OLD) {
                ArrayList<String> paths = data.getStringArrayListExtra("paths");
                if (paths != null) for (String p : paths) addItem(Uri.fromFile(new File(p)), new File(p).getName());
            } else if (request == PICK_FOLDER) {
                Uri uri = data.getData();
                if (uri != null) collectDoc(DocumentFile.fromTreeUri(this, uri));
            } else if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    addItem(uri, nameOf(uri));
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                addItem(uri, nameOf(uri));
            }
        } catch (Exception e) {
            status.setText("تعذر قراءة الملفات: " + safeMessage(e));
        }
        updateSelected();
    }

    private void collectDoc(DocumentFile d) {
        if (d == null) return;
        if (d.isFile()) { addItem(d.getUri(), d.getName()); return; }
        for (DocumentFile child : d.listFiles()) collectDoc(child);
    }

    private void addItem(Uri uri, String name) {
        selected.add(new Item(uri.toString(), name == null ? "file" : name));
    }

    private String nameOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "file_" + System.currentTimeMillis();
    }

    private long sizeOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).length();
        return -1;
    }

    private void updateSelected() {
        selectedView.setText(selected.isEmpty() ? "لم يتم اختيار ملفات" : "عدد الملفات المحددة: " + selected.size());
    }

    private void sendSelected() {
        if (selected.isEmpty()) { Toast.makeText(this, "اختر ملفات أولًا", Toast.LENGTH_SHORT).show(); return; }
        final String host = cleanHost(target.getText().toString());
        if (host.length() == 0) { Toast.makeText(this, "اكتب IP الجهاز المستقبل", Toast.LENGTH_SHORT).show(); return; }
        prefs.edit().putString(KEY_TARGET, host).apply();
        target.setText(host);
        progress.setProgress(0);
        status.setText("بدأ الإرسال إلى " + host + ":" + PORT);

        new Thread(new Runnable() {
            @Override public void run() {
                int sent = 0;
                for (Item item : selected) {
                    try {
                        sendOne(host, item);
                        sent++;
                        ui("تم إرسال " + sent + " من " + selected.size(), 100);
                    } catch (Exception e) {
                        ui("توقف بعد " + sent + " ملف: " + safeMessage(e), -1);
                        return;
                    }
                }
                ui("اكتمل الإرسال: " + sent + " ملف", 100);
            }
        }).start();
    }

    private void sendOne(String host, Item item) throws Exception {
        Uri uri = Uri.parse(item.uri);
        long size = sizeOf(uri);
        if (size < 0) throw new Exception("تعذر معرفة حجم الملف: " + item.name);

        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, PORT), 15000);
        socket.setSoTimeout(120000);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        DataInputStream reply = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        InputStream in = open(uri);
        try {
            out.writeUTF(MAGIC);
            out.writeUTF(item.name);
            out.writeLong(size);
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                final int percent = size == 0 ? 100 : (int)((done * 100L) / size);
                ui("جاري إرسال: " + item.name + " — " + percent + "%", percent);
            }
            out.flush();
            String response = reply.readUTF();
            if (!"OK".equals(response)) throw new Exception(response);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private InputStream open(Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("تعذر فتح الملف");
        return in;
    }

    static class SaveTarget {
        OutputStream output;
        SaveTarget(OutputStream output) { this.output = output; }
    }

    private SaveTarget createSaveTarget(String name) throws Exception {
        String safe = safeName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalSend");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("تعذر إنشاء الملف");
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("تعذر فتح ملف الحفظ");
            return new SaveTarget(out);
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد LocalSend");
        File file = uniqueFile(dir, safe);
        return new SaveTarget(new BufferedOutputStream(new FileOutputStream(file), 64 * 1024));
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int i = 1;
        while ((f = new File(dir, base + " (" + i + ")" + ext)).exists()) i++;
        return f;
    }

    private String safeName(String name) {
        if (name == null || name.trim().length() == 0) return "file_" + System.currentTimeMillis();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String safeMessage(Exception e) {
        if (e instanceof SocketTimeoutException) return "انتهت مهلة الاتصال";
        String m = e.getMessage();
        return (m == null || m.length() == 0) ? e.getClass().getSimpleName() : m;
    }

    private void ui(final String text, final int percent) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                status.setText(text);
                if (percent >= 0) progress.setProgress(percent);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopReceiver();
        super.onDestroy();
    }
}
