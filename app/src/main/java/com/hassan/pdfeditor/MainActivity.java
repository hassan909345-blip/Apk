package com.hassan.pdfeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;

    private EditablePdfView pdfView;
    private TextView pageLabel;
    private Button editButton;
    private Button prevButton;
    private Button nextButton;
    private Button saveButton;

    private ParcelFileDescriptor sourceDescriptor;
    private PdfRenderer renderer;
    private Uri sourceUri;
    private int currentPage = 0;
    private Bitmap currentBitmap;
    private boolean editMode = false;

    private final Map<Integer, List<TextEdit>> editsByPage = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIncomingIntent(getIntent());
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 20, 20));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(8, 8, 8, 8);

        Button openButton = makeButton("فتح PDF");
        editButton = makeButton("تعديل");
        saveButton = makeButton("حفظ نسخة");
        Button undoButton = makeButton("تراجع");

        top.addView(openButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(editButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(undoButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(saveButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        pdfView = new EditablePdfView(this);
        root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(pdfView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(8, 8, 8, 8);

        prevButton = makeButton("السابق");
        nextButton = makeButton("التالي");
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setTextSize(16f);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setText("افتح ملف PDF");

        bottom.addView(prevButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(pageLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(nextButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bottom, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        openButton.setOnClickListener(v -> choosePdf());
        editButton.setOnClickListener(v -> toggleEditMode());
        saveButton.setOnClickListener(v -> chooseSaveLocation());
        prevButton.setOnClickListener(v -> showPage(currentPage - 1));
        nextButton.setOnClickListener(v -> showPage(currentPage + 1));
        undoButton.setOnClickListener(v -> undoLastEdit());
        pdfView.setOnPageTapListener(this::showEditDialog);

        setControlsEnabled(false);
    }

    private void setControlsEnabled(boolean enabled) {
        editButton.setEnabled(enabled);
        prevButton.setEnabled(enabled);
        nextButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
    }

    private void choosePdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, REQ_OPEN);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openPdf(intent.getData());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            try {
                final int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
            openPdf(uri);
        } else if (requestCode == REQ_SAVE) {
            saveEditedPdf(uri);
        }
    }

    private void openPdf(Uri uri) {
        closePdf();
        try {
            sourceUri = uri;
            sourceDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (sourceDescriptor == null) throw new IOException("لا يمكن فتح الملف");
            renderer = new PdfRenderer(sourceDescriptor);
            if (renderer.getPageCount() == 0) throw new IOException("الملف لا يحتوي على صفحات");
            editsByPage.clear();
            currentPage = 0;
            setControlsEnabled(true);
            showPage(0);
            Toast.makeText(this, "تم فتح PDF", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            closePdf();
        }
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;

        try (PdfRenderer.Page page = renderer.openPage(index)) {
            currentPage = index;
            int w = page.getWidth();
            int h = page.getHeight();
            int maxSide = 2400;
            float scale = Math.min(2f, maxSide / (float) Math.max(w, h));
            scale = Math.max(1f, scale);

            Bitmap bitmap = Bitmap.createBitmap(
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)),
                    Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);

            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
            currentBitmap = bitmap;
            pdfView.setBitmap(bitmap);
            pdfView.setEdits(editsByPage.get(index));
            pageLabel.setText((index + 1) + " / " + renderer.getPageCount());
            prevButton.setEnabled(index > 0);
            nextButton.setEnabled(index < renderer.getPageCount() - 1);
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في عرض الصفحة", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleEditMode() {
        if (renderer == null) return;
        editMode = !editMode;
        pdfView.setEditMode(editMode);
        editButton.setText(editMode ? "اضغط مكان النص" : "تعديل");
        Toast.makeText(this,
                editMode ? "اضغط على الكلمة أو الرقم الذي تريد تغييره" : "تم إيقاف وضع التعديل",
                Toast.LENGTH_SHORT).show();
    }

    private void showEditDialog(float nx, float ny) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        EditText textInput = new EditText(this);
        textInput.setHint("اكتب الكلمة أو الرقم الجديد");
        textInput.setSingleLine(false);
        textInput.setTextDirection(View.TEXT_DIRECTION_LOCALE);

        EditText sizeInput = new EditText(this);
        sizeInput.setHint("حجم الخط");
        sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeInput.setText("18");

        box.addView(textInput);
        box.addView(sizeInput);

        new AlertDialog.Builder(this)
                .setTitle("تعديل النص")
                .setMessage("سيتم تغطية النص القديم وكتابة النص الجديد مكانه.")
                .setView(box)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    String text = textInput.getText().toString();
                    if (text.trim().isEmpty()) return;

                    float size = 18f;
                    try {
                        size = Float.parseFloat(sizeInput.getText().toString());
                    } catch (Exception ignored) {
                    }
                    size = Math.max(8f, Math.min(96f, size));

                    List<TextEdit> list = editsByPage.computeIfAbsent(currentPage, k -> new ArrayList<>());
                    list.add(new TextEdit(nx, ny, text, size));
                    pdfView.setEdits(list);

                    editMode = false;
                    pdfView.setEditMode(false);
                    editButton.setText("تعديل");
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void undoLastEdit() {
        List<TextEdit> list = editsByPage.get(currentPage);
        if (list == null || list.isEmpty()) {
            Toast.makeText(this, "لا يوجد تعديل للتراجع عنه", Toast.LENGTH_SHORT).show();
            return;
        }
        list.remove(list.size() - 1);
        pdfView.setEdits(list);
    }

    private void chooseSaveLocation() {
        if (renderer == null || sourceUri == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, "edited_document.pdf");
        startActivityForResult(intent, REQ_SAVE);
    }

    private void saveEditedPdf(Uri outputUri) {
        if (renderer == null) return;
        setControlsEnabled(false);
        pageLabel.setText("جاري الحفظ...");

        new Thread(() -> {
            PdfDocument out = new PdfDocument();
            try {
                int pageCount = renderer.getPageCount();
                for (int i = 0; i < pageCount; i++) {
                    try (PdfRenderer.Page sourcePage = renderer.openPage(i)) {
                        int pageW = sourcePage.getWidth();
                        int pageH = sourcePage.getHeight();
                        float renderScale = 2f;

                        Bitmap pageBitmap = Bitmap.createBitmap(
                                Math.max(1, Math.round(pageW * renderScale)),
                                Math.max(1, Math.round(pageH * renderScale)),
                                Bitmap.Config.ARGB_8888);
                        pageBitmap.eraseColor(Color.WHITE);

                        Matrix matrix = new Matrix();
                        matrix.setScale(renderScale, renderScale);
                        sourcePage.render(pageBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);

                        PdfDocument.PageInfo info =
                                new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                        PdfDocument.Page outPage = out.startPage(info);
                        Canvas canvas = outPage.getCanvas();
                        canvas.drawBitmap(pageBitmap, null, new RectF(0, 0, pageW, pageH),
                                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));

                        List<TextEdit> pageEdits = editsByPage.get(i);
                        if (pageEdits != null) {
                            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                            textPaint.setColor(Color.BLACK);
                            Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                            coverPaint.setColor(Color.WHITE);

                            for (TextEdit edit : pageEdits) {
                                float x = edit.normalizedX * pageW;
                                float baseline = edit.normalizedY * pageH;
                                textPaint.setTextSize(edit.textSize);
                                float textW = textPaint.measureText(edit.text);
                                Paint.FontMetrics fm = textPaint.getFontMetrics();
                                float p = Math.max(2f, edit.textSize * 0.12f);

                                canvas.drawRect(x - p, baseline + fm.top - p,
                                        x + textW + p, baseline + fm.bottom + p, coverPaint);
                                canvas.drawText(edit.text, x, baseline, textPaint);
                            }
                        }

                        out.finishPage(outPage);
                        pageBitmap.recycle();
                    }
                }

                try (ParcelFileDescriptor pfd =
                             getContentResolver().openFileDescriptor(outputUri, "w");
                     FileOutputStream stream =
                             pfd == null ? null : new FileOutputStream(pfd.getFileDescriptor())) {
                    if (stream == null) throw new IOException("تعذر إنشاء الملف");
                    out.writeTo(stream);
                    stream.flush();
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "تم حفظ نسخة PDF المعدلة", Toast.LENGTH_LONG).show();
                    setControlsEnabled(true);
                    showPage(currentPage);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setControlsEnabled(true);
                    showPage(currentPage);
                });
            } finally {
                out.close();
            }
        }).start();
    }

    private void closePdf() {
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (sourceDescriptor != null) {
            try {
                sourceDescriptor.close();
            } catch (IOException ignored) {
            }
            sourceDescriptor = null;
        }
        sourceUri = null;
        if (pdfView != null) {
            pdfView.setBitmap(null);
            pdfView.setEdits(null);
        }
        if (pageLabel != null) pageLabel.setText("افتح ملف PDF");
        if (editButton != null) setControlsEnabled(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closePdf();
    }
}
