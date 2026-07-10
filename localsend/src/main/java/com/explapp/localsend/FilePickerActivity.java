package com.explapp.localsend;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public class FilePickerActivity extends Activity {
    private File current;
    private final ArrayList<File> items = new ArrayList<>();
    private final ArrayList<String> selected = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView path;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!current.exists()) current = Environment.getExternalStorageDirectory();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,18,18,18);
        root.setBackgroundColor(Color.rgb(238,247,255));

        path = new TextView(this);
        path.setTextSize(15);
        path.setPadding(8,8,8,12);
        root.addView(path);

        Button chooseFolder = new Button(this);
        chooseFolder.setText("اختيار المجلد الحالي كاملًا");
        chooseFolder.setOnClickListener(v -> returnFolder(current));
        root.addView(chooseFolder);

        Button done = new Button(this);
        done.setText("إرسال الملفات المحددة");
        done.setOnClickListener(v -> finishSelection());
        root.addView(done);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, new ArrayList<String>());
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1,0,1));

        list.setOnItemClickListener((p,v,pos,id) -> {
            File f = items.get(pos);
            if (f.isDirectory()) {
                list.setItemChecked(pos,false);
                current = f;
                load();
            } else {
                String value = f.getAbsolutePath();
                if (list.isItemChecked(pos)) { if (!selected.contains(value)) selected.add(value); }
                else selected.remove(value);
            }
        });
        setContentView(root);
        load();
    }

    private void load() {
        path.setText("المجلد: " + current.getAbsolutePath());
        items.clear(); adapter.clear();
        File[] files = current.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            Collections.addAll(items, files);
            for (File f : files) adapter.add((f.isDirectory() ? "📁 " : "☐ ") + f.getName());
        }
        adapter.notifyDataSetChanged();
    }

    private void returnFolder(File folder) {
        ArrayList<String> all = new ArrayList<>();
        collect(folder, all);
        if (all.isEmpty()) { Toast.makeText(this,"المجلد فارغ",Toast.LENGTH_SHORT).show(); return; }
        Intent data = new Intent(); data.putStringArrayListExtra("paths", all); setResult(RESULT_OK,data); finish();
    }

    private void collect(File f, ArrayList<String> out) {
        if (f == null) return;
        if (f.isFile()) { out.add(f.getAbsolutePath()); return; }
        File[] files = f.listFiles();
        if (files != null) for (File child : files) collect(child,out);
    }

    private void finishSelection() {
        if (selected.isEmpty()) { Toast.makeText(this,"حدد ملفًا واحدًا على الأقل",Toast.LENGTH_SHORT).show(); return; }
        Intent data = new Intent(); data.putStringArrayListExtra("paths", selected); setResult(RESULT_OK,data); finish();
    }

    @Override public void onBackPressed() {
        File parent = current.getParentFile();
        if (parent != null && !current.equals(Environment.getExternalStorageDirectory())) { current = parent; load(); }
        else super.onBackPressed();
    }
}
