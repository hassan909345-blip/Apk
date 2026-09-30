package com.hassan.pdfeditor;

public class TextEdit {
    public final float normalizedX;
    public final float normalizedY;
    public final String text;
    public final float textSize;

    public TextEdit(float normalizedX, float normalizedY, String text, float textSize) {
        this.normalizedX = normalizedX;
        this.normalizedY = normalizedY;
        this.text = text;
        this.textSize = textSize;
    }
}
