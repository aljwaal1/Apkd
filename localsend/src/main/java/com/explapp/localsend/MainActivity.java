package com.explapp.localsend;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends Activity {
    private static final int PICK_FILES=100, PICK_FOLDER=101, PICK_OLD=102, PERM=103;
    private final ArrayList<Item> selected = new ArrayList<>();
    private TextView status, selectedView, myAddress;
    private EditText target;
    private LocalServer server;

    static class Item { String uri,name; Item(String u,String n){uri=u;name=n;} }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestStorageIfNeeded();

        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(22,22,22,22);
        scroll.addView(root);

        TextView title=new TextView(this); title.setText("الإرسال المحلي بين هاتفين"); title.setTextSize(23); root.addView(title);
        TextView note=new TextView(this); note.setText("يجب أن يكون الهاتفان على شبكة Wi‑Fi نفسها. الملفات المستلمة تحفظ في Download/LocalSend."); note.setPadding(0,8,0,14); root.addView(note);

        myAddress=new TextView(this); myAddress.setTextSize(17); myAddress.setPadding(10,14,10,14); root.addView(myAddress);

        Button start=button("تشغيل استقبال الملفات"); start.setOnClickListener(v->startServer()); root.addView(start);
        Button stop=button("إيقاف الاستقبال"); stop.setOnClickListener(v->stopServer()); root.addView(stop);

        target=new EditText(this); target.setHint("عنوان الهاتف المستقبل مثل 192.168.1.20"); target.setInputType(InputType.TYPE_CLASS_TEXT); root.addView(target);

        Button files=button("اختيار عدة ملفات من Download"); files.setOnClickListener(v->pickFiles()); root.addView(files);
        Button folder=button("اختيار مجلد كامل"); folder.setOnClickListener(v->pickFolder()); root.addView(folder);
        Button send=button("إرسال المحدد"); send.setOnClickListener(v->sendSelected()); root.addView(send);

        selectedView=new TextView(this); selectedView.setPadding(8,14,8,14); root.addView(selectedView);
        status=new TextView(this); status.setPadding(12,16,12,16); root.addView(status);

        setContentView(scroll); updateAddress(); updateSelected();
    }

    private Button button(String s){ Button b=new Button(this); b.setText(s); return b; }

    private void requestStorageIfNeeded(){
        if(Build.VERSION.SDK_INT>=23 && Build.VERSION.SDK_INT<=28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},PERM);
    }

    private void updateAddress(){ String ip=getIp(); myAddress.setText("عنوان هذا الهاتف للاستقبال:\nhttp://"+ip+":5051/upload"); }

    private String getIp(){
        try{
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(InetAddress a:Collections.list(ni.getInetAddresses()))
                    if(!a.isLoopbackAddress() && a.getHostAddress().indexOf(':')<0) return a.getHostAddress();
        }catch(Exception ignored){}
        return "0.0.0.0";
    }

    private void startServer(){
        try{ if(server!=null) server.stop(); server=new LocalServer(5051); server.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false); status.setText("الاستقبال يعمل الآن"); updateAddress(); }
        catch(Exception e){ status.setText("تعذر تشغيل الاستقبال: "+e.getMessage()); }
    }
    private void stopServer(){ if(server!=null){server.stop();server=null;} status.setText("تم إيقاف الاستقبال"); }

    private void pickFiles(){
        if(Build.VERSION.SDK_INT>=19){
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_FILES);
        }else startActivityForResult(new Intent(this,FilePickerActivity.class),PICK_OLD);
    }

    private void pickFolder(){
        if(Build.VERSION.SDK_INT>=21) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_FOLDER);
        else startActivityForResult(new Intent(this,FilePickerActivity.class),PICK_OLD);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data); if(result!=RESULT_OK||data==null)return;
        try{
            if(request==PICK_OLD){ ArrayList<String> p=data.getStringArrayListExtra("paths"); if(p!=null)for(String s:p)addItem(Uri.fromFile(new File(s)),new File(s).getName()); }
            else if(request==PICK_FOLDER){ Uri u=data.getData(); if(u!=null){ getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION); collectDoc(DocumentFile.fromTreeUri(this,u)); } }
            else {
                if(data.getClipData()!=null) for(int x=0;x<data.getClipData().getItemCount();x++){Uri u=data.getClipData().getItemAt(x).getUri();addItem(u,nameOf(u));}
                else if(data.getData()!=null){Uri u=data.getData();addItem(u,nameOf(u));}
            }
        }catch(Exception e){status.setText("تعذر قراءة الملفات: "+e.getMessage());}
        updateSelected();
    }

    private void collectDoc(DocumentFile d){ if(d==null)return; if(d.isFile()){addItem(d.getUri(),d.getName());return;} for(DocumentFile c:d.listFiles())collectDoc(c); }
    private void addItem(Uri u,String n){ selected.add(new Item(u.toString(),n==null?"file":n)); }
    private String nameOf(Uri u){
        Cursor c=null; try{c=getContentResolver().query(u,null,null,null,null); if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}
        return "file_"+System.currentTimeMillis();
    }
    private void updateSelected(){ selectedView.setText(selected.isEmpty()?"لم يتم اختيار ملفات":"عدد الملفات المحددة: "+selected.size()); }

    private void sendSelected(){
        if(selected.isEmpty()){Toast.makeText(this,"اختر ملفات أولًا",Toast.LENGTH_SHORT).show();return;}
        String host=target.getText().toString().trim(); if(host.length()==0){Toast.makeText(this,"اكتب IP الهاتف المستقبل",Toast.LENGTH_SHORT).show();return;}
        final String base=host.startsWith("http")?host:"http://"+host+":5051/upload";
        status.setText("بدأ الإرسال...");
        new Thread(()->{
            int ok=0; String error="";
            for(Item item:selected){ try{sendOne(base,item);ok++;final int n=ok;runOnUiThread(()->status.setText("تم إرسال "+n+" من "+selected.size()));}catch(Exception e){error=e.getMessage();break;} }
            final int sent=ok; final String err=error; runOnUiThread(()->status.setText(err.length()==0?"اكتمل الإرسال: "+sent+" ملف":"توقف بعد "+sent+" ملف: "+err));
        }).start();
    }

    private void sendOne(String base,Item item)throws Exception{
        String sep=base.contains("?")?"&":"?";
        URL url=new URL(base+sep+"name="+URLEncoder.encode(item.name,"UTF-8"));
        HttpURLConnection c=(HttpURLConnection)url.openConnection(); c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestMethod("PUT");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/octet-stream");
        InputStream in=open(item.uri); OutputStream out=c.getOutputStream(); byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.flush();out.close();in.close();
        int code=c.getResponseCode();c.disconnect();if(code<200||code>=300)throw new Exception("HTTP "+code);
    }
    private InputStream open(String value)throws Exception{Uri u=Uri.parse(value);if("file".equals(u.getScheme()))return new FileInputStream(new File(u.getPath()));InputStream in=getContentResolver().openInputStream(u);if(in==null)throw new Exception("تعذر فتح الملف");return in;}

    private class LocalServer extends NanoHTTPD{
        LocalServer(int p){super(p);}
        @Override public Response serve(IHTTPSession s){
            if(Method.GET.equals(s.getMethod())) return newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8","<html dir='rtl'><body><h2>الهاتف جاهز لاستقبال الملفات</h2></body></html>");
            if(!Method.PUT.equals(s.getMethod()))return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,"text/plain","PUT only");
            try{
                Map<String,String> files=new HashMap<>();s.parseBody(files);String temp=files.get("postData");String name=s.getParameters().containsKey("name")?s.getParameters().get("name").get(0):"file_"+System.currentTimeMillis();
                saveReceived(new File(temp),safe(name));runOnUiThread(()->status.setText("تم استلام: "+name));return newFixedLengthResponse("OK");
            }catch(Exception e){return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.toString());}
        }
    }

    private String safe(String n){return n.replaceAll("[\\\\/:*?\"<>|]","_");}
    private void saveReceived(File src,String name)throws Exception{
        InputStream in=new FileInputStream(src);OutputStream out;
        if(Build.VERSION.SDK_INT>=29){ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LocalSend");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("تعذر إنشاء الملف");out=getContentResolver().openOutputStream(u);}
        else{File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"LocalSend");if(!dir.exists()&&!dir.mkdirs())throw new Exception("تعذر إنشاء المجلد");out=new FileOutputStream(new File(dir,name));}
        byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();out.close();in.close();
    }

    @Override protected void onDestroy(){if(server!=null)server.stop();super.onDestroy();}
}
