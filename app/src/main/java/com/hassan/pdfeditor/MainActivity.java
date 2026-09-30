package com.hassan.pdfeditor;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.print.PrintManager;
import android.app.Notification;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://www.balady.gov.sa/ar";
    private static final String LICENSES_URL = "https://www.balady.gov.sa/ar/services/%D8%B1%D8%AE%D8%B5%D9%8A";
    private static final String SERVICES_URL = "https://www.balady.gov.sa/ar/services";
    private static final int REQ_FILE = 4201;
    private static final int REQ_NOTIFY = 4202;
    private static final String CHANNEL_ID = "permit_expiry";

    private FrameLayout root;
    private DbHelper db;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 61, 58));
        root = new FrameLayout(this);
        setContentView(root);
        db = new DbHelper(this);
        setupNotifications();
        showDashboard();
    }

    private void setupNotifications() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "تنبيهات انتهاء التصاريح", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("تنبيه للتصاريح والرخص القريبة من الانتهاء");
            nm.createNotificationChannel(ch);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void showDashboard() {
        destroyBrowser();
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page);

        TextView title = text("إدارة بلدي", 28, true);
        title.setTextColor(Color.rgb(11, 61, 58));
        page.addView(title);

        TextView sub = text("متابعة التصاريح والرخص من منصة بلدي", 15, false);
        sub.setTextColor(Color.DKGRAY);
        page.addView(sub, marginTop(4));

        List<DbHelper.Permit> all = db.listPermits("", "الكل");
        int active = 0, expired = 0, soon30 = 0, soon60 = 0;
        for (DbHelper.Permit p : all) {
            String s = p.status == null ? "" : p.status;
            long d = daysUntil(p.expiryDate);
            if (containsAny(s, "منته", "ملغ", "غير ساري")) expired++;
            else if (containsAny(s, "ساري", "فعال", "نشط")) active++;
            if (d >= 0 && d <= 30) soon30++;
            if (d >= 0 && d <= 60) soon60++;
        }

        page.addView(kpiRow("إجمالي", all.size(), "سارية", active), marginTop(18));
        page.addView(kpiRow("منتهية", expired, "≤ 30 يوم", soon30), marginTop(8));
        page.addView(kpiRow("≤ 60 يوم", soon60, "مزامنة", all.isEmpty() ? 0 : 1), marginTop(8));

        page.addView(section("الإجراءات"), marginTop(18));

        Button open = actionButton("فتح منصة بلدي");
        open.setOnClickListener(v -> showBrowser(HOME_URL,
                "سجّل الدخول بنفسك عبر نفاذ/OTP. التطبيق لا يخزن كلمة المرور."));
        page.addView(open, marginTop(8));

        Button licenses = actionButton("فتح «رخصي» وسحب البيانات");
        licenses.setOnClickListener(v -> showBrowser(LICENSES_URL,
                "بعد ظهور قائمة الرخص داخل حسابك اضغط «سحب بيانات الصفحة»."));
        page.addView(licenses, marginTop(8));

        Button list = actionButton("عرض التصاريح والرخص المحفوظة");
        list.setOnClickListener(v -> showPermits());
        page.addView(list, marginTop(8));

        Button upload = actionButton("إصدار / تجديد / رفع تصريح");
        upload.setOnClickListener(v -> showBrowser(SERVICES_URL,
                "اختر الخدمة المطلوبة. رفع المرفقات متاح من الهاتف، والإرسال النهائي يبقى بموافقتك."));
        page.addView(upload, marginTop(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button excel = smallButton("تصدير Excel");
        Button pdf = smallButton("تقرير PDF");
        excel.setOnClickListener(v -> exportExcel());
        pdf.setOnClickListener(v -> printReport());
        row.addView(excel, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams pdfLp = new LinearLayout.LayoutParams(0, dp(50), 1);
        pdfLp.setMarginStart(dp(8));
        row.addView(pdf, pdfLp);
        page.addView(row, marginTop(10));

        TextView note = text(
                "الأمان: تسجيل الدخول ونفاذ وOTP وCAPTCHA يتم تنفيذها بواسطتك فقط. " +
                "التطبيق يقرأ البيانات الظاهرة في جلسة حسابك بعد تسجيل الدخول ولا يحاول تجاوز وسائل الحماية.",
                12, false);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(18), 0, 0);
        page.addView(note);

        root.addView(scroll, match());
        maybeNotifyExpiring(soon30);
    }

    private View kpiRow(String a, int av, String b, int bv) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.addView(kpiCard(a, av), new LinearLayout.LayoutParams(0, dp(92), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(92), 1);
        p.setMarginStart(dp(8));
        row.addView(kpiCard(b, bv), p);
        return row;
    }

    private View kpiCard(String label, int value) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackgroundColor(Color.rgb(238, 247, 246));
        TextView n = text(String.valueOf(value), 28, true);
        n.setGravity(Gravity.CENTER);
        n.setTextColor(Color.rgb(11, 107, 99));
        TextView l = text(label, 13, false);
        l.setGravity(Gravity.CENTER);
        l.setTextColor(Color.DKGRAY);
        box.addView(n);
        box.addView(l);
        return box;
    }

    private void showPermits() {
        destroyBrowser();
        root.removeAllViews();

        LinearLayout outer = column();
        outer.setPadding(dp(12), dp(12), dp(12), dp(12));
        outer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button back = smallButton("لوحة التحكم");
        back.setOnClickListener(v -> showDashboard());
        TextView t = text("التصاريح والرخص", 20, true);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bar.addView(back, new LinearLayout.LayoutParams(dp(125), dp(48)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(48), 1);
        tlp.setMarginStart(dp(8));
        bar.addView(t, tlp);
        outer.addView(bar);

        EditText search = new EditText(this);
        search.setHint("بحث برقم الرخصة / المنشأة / النوع / البلدية");
        search.setTextDirection(View.TEXT_DIRECTION_RTL);
        outer.addView(search, marginTop(8));

        Spinner spinner = new Spinner(this);
        String[] filters = {"الكل", "ساري", "منته", "ملغ"};
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, filters));
        outer.addView(spinner, marginTop(6));

        ScrollView scroll = new ScrollView(this);
        LinearLayout listBox = column();
        listBox.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(listBox);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Runnable render = () -> renderPermitList(
                listBox, search.getText().toString(),
                spinner.getSelectedItem() == null ? "الكل" : spinner.getSelectedItem().toString());

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render.run(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { render.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        render.run();
        root.addView(outer, match());
    }

    private void renderPermitList(LinearLayout box, String search, String status) {
        box.removeAllViews();
        List<DbHelper.Permit> permits = db.listPermits(search, status);
        if (permits.isEmpty()) {
            TextView empty = text("لا توجد بيانات. افتح صفحة «رخصي» ثم اضغط سحب بيانات الصفحة.", 14, false);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(24), 0, 0);
            box.addView(empty);
            return;
        }
        for (DbHelper.Permit p : permits) {
            LinearLayout card = column();
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(Color.rgb(246, 248, 248));

            String headline = !p.permitNumber.isEmpty() ? "رقم: " + p.permitNumber : "تصريح";
            TextView h = text(headline, 17, true);
            h.setTextColor(Color.rgb(11, 61, 58));
            card.addView(h);

            if (!p.permitType.isEmpty()) card.addView(text("النوع: " + p.permitType, 14, false));
            if (!p.company.isEmpty()) card.addView(text("المنشأة/النشاط: " + p.company, 14, false));
            if (!p.status.isEmpty()) card.addView(text("الحالة: " + p.status, 14, false));
            if (!p.expiryDate.isEmpty()) card.addView(text("الانتهاء: " + p.expiryDate, 14, false));
            if (!p.municipality.isEmpty()) card.addView(text("البلدية/الأمانة: " + p.municipality, 14, false));

            if (!p.pdfUrl.isEmpty()) {
                Button openPdf = smallButton("فتح المرفق / PDF");
                openPdf.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.pdfUrl)));
                    } catch (Exception e) {
                        Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show();
                    }
                });
                card.addView(openPdf, marginTop(6));
            }
            box.addView(card, marginTop(8));
        }
    }

    private void showBrowser(String url, String hintText) {
        root.removeAllViews();
        webView = new WebView(this);

        LinearLayout outer = column();
        outer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(4));

        Button dash = smallButton("اللوحة");
        Button sync = smallButton("سحب البيانات");
        Button licenses = smallButton("رخصي");
        Button services = smallButton("الخدمات");

        dash.setOnClickListener(v -> showDashboard());
        sync.setOnClickListener(v -> syncCurrentPage());
        licenses.setOnClickListener(v -> webView.loadUrl(LICENSES_URL));
        services.setOnClickListener(v -> webView.loadUrl(SERVICES_URL));

        bar.addView(dash, new LinearLayout.LayoutParams(0, dp(46), 1));
        bar.addView(sync, withStartMargin(new LinearLayout.LayoutParams(0, dp(46), 1), 4));
        bar.addView(licenses, withStartMargin(new LinearLayout.LayoutParams(0, dp(46), 1), 4));
        bar.addView(services, withStartMargin(new LinearLayout.LayoutParams(0, dp(46), 1), 4));
        outer.addView(bar);

        TextView hint = text(hintText, 12, false);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(dp(10), dp(4), dp(10), dp(6));
        outer.addView(hint);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this,
                            "تعذر فتح مدير الملفات", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, len) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)));
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show();
            }
        });

        outer.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(outer, match());
        webView.loadUrl(url);
        db.audit("OPEN_BALADY", url);
    }

    private void syncCurrentPage() {
        if (webView == null) {
            Toast.makeText(this, "افتح منصة بلدي أولًا", Toast.LENGTH_SHORT).show();
            return;
        }

        String js =
                "(function(){" +
                "const tx=e=>(e&&e.innerText?e.innerText:'').replace(/\\s+/g,' ').trim();" +
                "const links=e=>Array.from(e.querySelectorAll('a[href]')).map(a=>a.href).filter(Boolean);" +
                "let headers=[],rows=[];" +
                "let tables=Array.from(document.querySelectorAll('table')).sort((a,b)=>b.querySelectorAll('tr').length-a.querySelectorAll('tr').length);" +
                "let table=tables.find(t=>t.querySelectorAll('tr').length>1);" +
                "if(table){" +
                " let hr=table.querySelector('thead tr')||table.querySelector('tr');" +
                " headers=Array.from(hr.querySelectorAll('th,td')).map(tx);" +
                " Array.from(table.querySelectorAll('tbody tr, tr')).forEach((tr,i)=>{" +
                "  let cs=Array.from(tr.querySelectorAll('td'));" +
                "  if(cs.length){rows.push({cells:cs.map(tx),links:links(tr)});}" +
                " });" +
                "}" +
                "if(!rows.length){" +
                " let cards=Array.from(document.querySelectorAll('[class*=card],[class*=license],[class*=permit],[class*=item]'))" +
                " .filter(c=>tx(c).length>20).slice(0,300);" +
                " cards.forEach(c=>rows.push({cells:[tx(c)],links:links(c)}));" +
                "}" +
                "return JSON.stringify({title:document.title,url:location.href,headers:headers,rows:rows});" +
                "})()";

        webView.evaluateJavascript(js, value -> {
            try {
                if (value == null || "null".equals(value)) throw new Exception("لا توجد نتيجة");
                Object unwrapped = new JSONTokener(value).nextValue();
                if (!(unwrapped instanceof String)) throw new Exception("صيغة غير متوقعة");
                JSONObject payload = new JSONObject((String) unwrapped);
                JSONArray headers = payload.optJSONArray("headers");
                JSONArray rows = payload.optJSONArray("rows");
                if (rows == null || rows.length() == 0) {
                    Toast.makeText(this,
                            "لم أجد جدولًا ظاهرًا. افتح قائمة «رخصي» داخل حسابك ثم أعد المحاولة.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                int saved = 0;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    DbHelper.Permit p = mapPermit(headers, row);
                    if (p == null) continue;
                    db.upsertPermit(p);
                    saved++;
                }
                db.audit("SYNC", "تمت مزامنة " + saved + " صف من " + payload.optString("url"));
                Toast.makeText(this,
                        "تم حفظ " + saved + " سجل. يمكنك رؤيتها من لوحة التحكم.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this,
                        "تعذر سحب البيانات: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private DbHelper.Permit mapPermit(JSONArray headers, JSONObject row) {
        JSONArray cells = row.optJSONArray("cells");
        if (cells == null || cells.length() == 0) return null;
        StringBuilder rawBuilder = new StringBuilder();
        for (int i = 0; i < cells.length(); i++) {
            if (i > 0) rawBuilder.append(" | ");
            rawBuilder.append(cells.optString(i));
        }
        String raw = rawBuilder.toString().trim();
        if (raw.length() < 3) return null;

        DbHelper.Permit p = new DbHelper.Permit();
        int no = findHeader(headers, "رقم الرخصة", "رقم الرخصه", "رقم التصريح", "رقم الطلب");
        int type = findHeader(headers, "نوع الرخصة", "نوع الرخصه", "نوع التصريح", "نوع الطلب", "الخدمة");
        int company = findHeader(headers, "المنشأة", "المنشاة", "اسم المنشأة", "النشاط", "المنشأة / النشاط");
        int issue = findHeader(headers, "تاريخ الإصدار", "تاريخ الاصدار");
        int expiry = findHeader(headers, "تاريخ الانتهاء", "تاريخ إنتهاء", "الانتهاء");
        int status = findHeader(headers, "الحالة", "حالة الرخصة", "حالة التصريح");
        int municipality = findHeader(headers, "البلدية", "الأمانة", "الامانة");

        p.permitNumber = cell(cells, no);
        if (p.permitNumber.isEmpty()) {
            Matcher m = Pattern.compile("(?<!\\d)\\d{6,}(?!\\d)").matcher(raw);
            if (m.find()) p.permitNumber = m.group();
        }
        if (p.permitNumber.isEmpty()) p.permitNumber = "ROW-" + Math.abs(raw.hashCode());

        p.permitType = cell(cells, type);
        p.company = cell(cells, company);
        p.issueDate = cell(cells, issue);
        p.expiryDate = cell(cells, expiry);
        p.status = cell(cells, status);
        p.municipality = cell(cells, municipality);

        if (p.status.isEmpty()) {
            if (containsAny(raw, "منتهية", "منتهي", "ملغاة", "ملغي")) p.status = "منتهية / غير سارية";
            else if (containsAny(raw, "سارية", "ساري", "فعال", "نشط")) p.status = "سارية";
        }

        if (p.expiryDate.isEmpty()) {
            Matcher dm = Pattern.compile("(20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]20\\d{2})").matcher(raw);
            String last = "";
            while (dm.find()) last = dm.group();
            p.expiryDate = last;
        }

        JSONArray links = row.optJSONArray("links");
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                String u = links.optString(i);
                String l = u.toLowerCase(Locale.ROOT);
                if (l.contains(".pdf") || l.contains("print") || l.contains("download")) {
                    p.pdfUrl = u;
                    break;
                }
            }
        }

        p.rawJson = row.toString();
        p.syncedAt = System.currentTimeMillis();
        return p;
    }

    private int findHeader(JSONArray headers, String... keys) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.length(); i++) {
            String h = headers.optString(i).replaceAll("\\s+", " ").trim();
            for (String k : keys) if (h.contains(k)) return i;
        }
        return -1;
    }

    private String cell(JSONArray cells, int index) {
        if (index < 0 || index >= cells.length()) return "";
        return cells.optString(index).replaceAll("\\s+", " ").trim();
    }

    private void exportExcel() {
        List<DbHelper.Permit> items = db.listPermits("", "الكل");
        if (items.isEmpty()) {
            Toast.makeText(this, "لا توجد بيانات للتصدير", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.append("<?mso-application progid=\"Excel.Sheet\"?>");
        x.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" ");
        x.append("xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Worksheet ss:Name=\"Permits\"><Table>");
        String[] heads = {"رقم التصريح/الرخصة","النوع","المنشأة/النشاط","تاريخ الإصدار","تاريخ الانتهاء","الحالة","البلدية/الأمانة"};
        x.append("<Row>");
        for (String h : heads) x.append(xlsCell(h));
        x.append("</Row>");
        for (DbHelper.Permit p : items) {
            x.append("<Row>")
                    .append(xlsCell(p.permitNumber))
                    .append(xlsCell(p.permitType))
                    .append(xlsCell(p.company))
                    .append(xlsCell(p.issueDate))
                    .append(xlsCell(p.expiryDate))
                    .append(xlsCell(p.status))
                    .append(xlsCell(p.municipality))
                    .append("</Row>");
        }
        x.append("</Table></Worksheet></Workbook>");

        String name = "Balady_Permits_" +
                new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".xls";
        try {
            OutputStream os;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.ms-excel");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("تعذر إنشاء الملف");
                os = getContentResolver().openOutputStream(uri);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new Exception("مجلد التنزيل غير متاح");
                os = new FileOutputStream(new File(dir, name));
            }
            if (os == null) throw new Exception("تعذر فتح الملف");
            os.write(x.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            db.audit("EXPORT_XLS", name);
            Toast.makeText(this, "تم حفظ Excel في التنزيلات: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String xlsCell(String s) {
        return "<Cell><Data ss:Type=\"String\">" + xml(s) + "</Data></Cell>";
    }

    private void printReport() {
        List<DbHelper.Permit> items = db.listPermits("", "الكل");
        if (items.isEmpty()) {
            Toast.makeText(this, "لا توجد بيانات للتقرير", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder h = new StringBuilder();
        h.append("<html dir='rtl'><head><meta charset='utf-8'><style>")
                .append("body{font-family:sans-serif;padding:20px}table{width:100%;border-collapse:collapse}")
                .append("th,td{border:1px solid #999;padding:6px;text-align:right}th{background:#eaf5f4}")
                .append("</style></head><body><h2>تقرير تصاريح ورخص بلدي</h2><table><tr>")
                .append("<th>الرقم</th><th>النوع</th><th>المنشأة</th><th>الانتهاء</th><th>الحالة</th></tr>");
        for (DbHelper.Permit p : items) {
            h.append("<tr><td>").append(html(p.permitNumber)).append("</td><td>")
                    .append(html(p.permitType)).append("</td><td>")
                    .append(html(p.company)).append("</td><td>")
                    .append(html(p.expiryDate)).append("</td><td>")
                    .append(html(p.status)).append("</td></tr>");
        }
        h.append("</table></body></html>");

        WebView report = new WebView(this);
        report.setVisibility(View.INVISIBLE);
        root.addView(report, new FrameLayout.LayoutParams(1, 1));
        report.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                pm.print("Balady_Permits",
                        view.createPrintDocumentAdapter("Balady_Permits"),
                        new PrintAttributes.Builder().build());
                db.audit("PRINT_REPORT", "Balady_Permits");
            }
        });
        report.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private void maybeNotifyExpiring(int soon30) {
        if (soon30 <= 0) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        android.content.SharedPreferences p = getSharedPreferences("alerts", MODE_PRIVATE);
        if (today.equals(p.getString("last", ""))) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("إدارة بلدي")
                .setContentText("لديك " + soon30 + " تصريح/رخصة تنتهي خلال 30 يومًا")
                .setContentIntent(pi)
                .setAutoCancel(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(301, b.build());
        p.edit().putString("last", today).apply();
    }

    private long daysUntil(String date) {
        if (date == null || date.trim().isEmpty()) return Long.MAX_VALUE;
        String d = date.trim();
        String[] formats = {"yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "dd-MM-yyyy"};
        for (String f : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.US);
                sdf.setLenient(false);
                Date target = sdf.parse(d);
                if (target == null) continue;
                java.util.Calendar c = java.util.Calendar.getInstance();
                java.util.Calendar t = java.util.Calendar.getInstance();
                c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
                c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
                t.setTime(target); t.set(java.util.Calendar.HOUR_OF_DAY, 0); t.set(java.util.Calendar.MINUTE, 0);
                t.set(java.util.Calendar.SECOND, 0); t.set(java.util.Calendar.MILLISECOND, 0);
                return (t.getTimeInMillis() - c.getTimeInMillis()) / 86400000L;
            } catch (Exception ignored) {}
        }
        return Long.MAX_VALUE;
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null) return false;
        for (String t : terms) if (text.contains(t)) return true;
        return false;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setGravity(Gravity.END);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextColor(Color.rgb(35, 35, 35));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, true);
        t.setTextColor(Color.rgb(11, 61, 58));
        return t;
    }

    private Button actionButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(11, 107, 99));
        return b;
    }

    private Button smallButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(12);
        return b;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dp);
        return p;
    }

    private LinearLayout.LayoutParams withStartMargin(LinearLayout.LayoutParams p, int value) {
        p.setMarginStart(dp(value));
        return p;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String html(String s) {
        return xml(s);
    }

    private void destroyBrowser() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                } else if (data != null && data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else if (webView != null) {
            showDashboard();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyBrowser();
        if (db != null) db.close();
        super.onDestroy();
    }
}
