package com.hassan.pdfeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.List;

public class EditablePdfView extends View {
    public interface OnPageTapListener {
        void onPageTap(float normalizedX, float normalizedY);
    }

    private Bitmap bitmap;
    private List<TextEdit> edits = Collections.emptyList();
    private OnPageTapListener tapListener;
    private boolean editMode = false;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pageRect = new RectF();

    public EditablePdfView(Context context) {
        super(context);
        init();
    }

    public EditablePdfView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(Color.rgb(35, 35, 35));
        coverPaint.setColor(Color.WHITE);
        textPaint.setColor(Color.BLACK);
        setClickable(true);
    }

    public void setBitmap(Bitmap value) {
        bitmap = value;
        invalidate();
    }

    public void setEdits(List<TextEdit> value) {
        edits = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    public void setEditMode(boolean value) {
        editMode = value;
        invalidate();
    }

    public void setOnPageTapListener(OnPageTapListener value) {
        tapListener = value;
    }

    private void updatePageRect() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) {
            pageRect.setEmpty();
            return;
        }
        float sx = getWidth() / (float) bitmap.getWidth();
        float sy = getHeight() / (float) bitmap.getHeight();
        float scale = Math.min(sx, sy);
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        pageRect.set(left, top, left + w, top + h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        updatePageRect();
        canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint);

        float pageScale = pageRect.width() / bitmap.getWidth();
        for (TextEdit edit : edits) {
            float x = pageRect.left + edit.normalizedX * pageRect.width();
            float baseline = pageRect.top + edit.normalizedY * pageRect.height();
            float size = Math.max(12f, edit.textSize * pageScale * 2f);
            textPaint.setTextSize(size);
            float width = textPaint.measureText(edit.text);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float pad = Math.max(3f, size * 0.12f);
            canvas.drawRect(x - pad, baseline + fm.top - pad,
                    x + width + pad, baseline + fm.bottom + pad, coverPaint);
            canvas.drawText(edit.text, x, baseline, textPaint);
        }

        if (editMode) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            p.setColor(Color.rgb(255, 193, 7));
            canvas.drawRect(pageRect, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && editMode && tapListener != null && bitmap != null) {
            updatePageRect();
            float x = event.getX();
            float y = event.getY();
            if (pageRect.contains(x, y)) {
                tapListener.onPageTap(
                        (x - pageRect.left) / pageRect.width(),
                        (y - pageRect.top) / pageRect.height());
                performClick();
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
