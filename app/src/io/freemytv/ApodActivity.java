package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.concurrent.ExecutorService;

/**
 * About this photo (ⓘ under the shelf): NASA's photo whole on the left, and on the right its title, date, NASA's
 * description and the credit (the mockup's #apod-details). ↑ ↓ scroll the description, → reaches ×, Back closes.
 */
public final class ApodActivity extends Activity {
    private ApodView view;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        Apod.Photo p = Apod.get(this).shown();
        if (p == null) {
            finish();
            return;
        }
        view = new ApodView(this, p, Apod.get(this).shownFile(), Apod.get(this).backdrop());
        setContentView(view);
    }

    @Override protected void onDestroy() {
        if (view != null) view.stop();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (view == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: view.move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: view.move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: view.move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: view.move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (view.closeFocused()) finish();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    static final class ApodView extends View implements Choreographer.FrameCallback {
        private static final ExecutorService LOADER = Ui.background();
        private static final int MUTED = 0xFFAEBBD3;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Apod.Photo photo;
        private final File file;
        private final Backdrop backdrop;
        private final Spring scroll = new Spring(0.34f, 1f, 0), closeFocus = new Spring(0.26f, 0.85f, 0);
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF full = new RectF(), sheet = new RectF(), pane = new RectF(), pic = new RectF(), text = new RectF(), close = new RectF();
        private Paint eyebrow, date, small, hint, cross;
        private TextPaint titlePaint, bodyPaint;
        private StaticLayout titleLayout, bodyLayout, authorLayout;
        private volatile Bitmap bitmap;
        private float k = 1, bodyTop, bodyBottom;
        private long lastNanos;
        private boolean stopped, onClose;

        ApodView(Activity a, Apod.Photo photo, File file, Backdrop backdrop) {
            super(a);
            this.photo = photo;
            this.file = file;
            this.backdrop = backdrop;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setContentDescription(photo.title + ". " + photo.date + ". " + photo.explanation);
        }

        void stop() {
            stopped = true;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        boolean closeFocused() {
            return onClose;
        }

        /** ↑ ↓ scroll the description; → (or ↑ at its top) goes to ×, ← or ↓ come back. */
        void move(int dx, int dy) {
            float max = maxScroll();
            if (onClose) {
                if (dx < 0 || dy > 0) onClose = false;
            } else if (dx > 0 || (dy < 0 && scroll.target <= 0.5f)) {
                onClose = true;
            } else if (dy != 0) {
                scroll.target = Math.max(0, Math.min(max, scroll.target + dy * 200 * k));
            }
            closeFocus.target = onClose ? 1 : 0;
            startFrames();
        }

        private float maxScroll() {
            return bodyLayout == null ? 0 : Math.max(0, bodyLayout.getHeight() - (bodyBottom - bodyTop));
        }

        private void startFrames() {
            lastNanos = 0;
            Choreographer.getInstance().removeFrameCallback(this);
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void doFrame(long nanos) {
            if (stopped) return;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
            lastNanos = nanos;
            boolean moving = scroll.step(dt) | closeFocus.step(dt);
            invalidate();
            if (moving) Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            eyebrow = Ui.text(Ui.semibold, 18 * k, MUTED);
            eyebrow.setLetterSpacing(0.2f);
            date = Ui.numbers(Ui.medium, 22 * k, MUTED);
            small = Ui.text(Ui.medium, 18 * k, MUTED);
            hint = Ui.text(Ui.medium, 18 * k, 0xFF8F9BB0);
            cross = new Paint(Paint.ANTI_ALIAS_FLAG);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeWidth(3.5f * k);
            cross.setStrokeCap(Paint.Cap.ROUND);
            titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 46 * k, 0xFFF5F5F7));
            bodyPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xFFF5F5F7));
            stroke.setStrokeWidth(2 * k);

            full.set(0, 0, w, h);
            sheet.set(80 * k, 80 * k, w - 80 * k, h - 80 * k);
            pane.set(sheet.left, sheet.top, sheet.centerX(), sheet.bottom);
            text.set(sheet.centerX() + 52 * k, sheet.top + 60 * k, sheet.right - 52 * k, sheet.bottom - 44 * k);
            close.set(sheet.right - 44 * k - 56 * k, sheet.top + 40 * k, sheet.right - 44 * k, sheet.top + 96 * k);

            int tw = Math.max(1, Math.round(text.width()));
            titleLayout = StaticLayout.Builder.obtain(photo.title, 0, photo.title.length(), titlePaint, Math.max(1, tw - Math.round(44 * k)))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.2f).setIncludePad(false).build();
            String credit = photo.credit.isEmpty() ? "NASA APOD" : Ui.tr("Crédito da imagem: ", "Image credit: ", "Crédito de la imagen: ") + photo.credit;
            authorLayout = StaticLayout.Builder.obtain(credit, 0, credit.length(), new TextPaint(small), tw)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.5f).setIncludePad(false).build();
            String body = photo.explanation.isEmpty()
                    ? Ui.tr("Sem descrição para esta foto.", "No description available for this image.", "No hay descripción para esta foto.")
                    : photo.explanation;
            bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length(), bodyPaint, Math.max(1, tw - Math.round(24 * k)))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.65f).setIncludePad(false).build();
            bodyTop = text.top + 36 * k + titleLayout.getHeight() + 16 * k + 22 * k + 36 * k;
            bodyBottom = text.bottom - 20 * k - authorLayout.getHeight() - 32 * k - 20 * k;
            load(Math.round(pane.width() - 64 * k), Math.round(pane.height() - 64 * k));
        }

        /** The photo, decoded at about the pane's size in the background. */
        private void load(int w, int h) {
            if (file == null || bitmap != null) return;
            LOADER.execute(() -> {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getPath(), bounds);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    while (bounds.outWidth / (opts.inSampleSize * 2) >= w || bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
                    Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
                    if (b == null) return;
                    Bitmap g = Backdrop.gpu(b);
                    main.post(() -> {
                        bitmap = g;
                        invalidate();
                    });
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("APOD full view: " + e.getMessage());
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            if (titleLayout == null) return;
            int w = getWidth(), h = getHeight();
            // Behind the sheet: the home screen's photo, dimmed (the mockup's scrim).
            if (backdrop != null && backdrop.image != null) c.drawBitmap(backdrop.image, null, full, image);
            else c.drawColor(0xFF04080F);
            c.drawColor(0x9E040811);

            float r = 48 * k;
            fill.setColor(0xF0121722);
            c.drawRoundRect(sheet, r, r, fill);

            // The photo, whole (contain), on a darker pane.
            c.save();
            c.clipRect(pane);
            fill.setColor(0x73000000);
            c.drawRoundRect(pane.left, pane.top, pane.right + r, pane.bottom, r, r, fill);
            Bitmap b = bitmap;
            if (b != null) {
                float aw = pane.width() - 64 * k, ah = pane.height() - 64 * k;
                float s = Math.min(aw / b.getWidth(), ah / b.getHeight());
                float bw = b.getWidth() * s, bh = b.getHeight() * s;
                pic.set(pane.centerX() - bw / 2, pane.centerY() - bh / 2, pane.centerX() + bw / 2, pane.centerY() + bh / 2);
                c.drawBitmap(b, null, pic, image);
            }
            c.restore();
            stroke.setColor(0x1FFFFFFF);
            c.drawLine(pane.right, sheet.top, pane.right, sheet.bottom, stroke);
            stroke.setColor(0x33FFFFFF);
            c.drawRoundRect(sheet, r, r, stroke);

            float x = text.left, y = text.top;
            c.drawText(Ui.tr("FOTO ASTRONÔMICA DO DIA", "ASTRONOMY PICTURE OF THE DAY", "FOTO ASTRONÓMICA DEL DÍA"), x, y + 18 * k, eyebrow);
            y += 36 * k;
            c.save();
            c.translate(x, y);
            titleLayout.draw(c);
            c.restore();
            y += titleLayout.getHeight() + 16 * k;
            c.drawText(photo.date, x, y + 22 * k, date);

            c.save();
            c.clipRect(x, bodyTop, text.right, bodyBottom);
            c.translate(x, bodyTop - scroll.value);
            bodyLayout.draw(c);
            c.restore();

            // The scroll bar, when there is more to read.
            float max = maxScroll();
            if (max > 0) {
                float track = bodyBottom - bodyTop, thumb = Math.max(40 * k, track * track / bodyLayout.getHeight());
                float ty = bodyTop + (track - thumb) * (scroll.value / max);
                fill.setColor(0x40FFFFFF);
                c.drawRoundRect(text.right - 4 * k, ty, text.right, ty + thumb, 2 * k, 2 * k, fill);
            }

            float ay = text.bottom - 20 * k - authorLayout.getHeight() - 20 * k;
            c.save();
            c.translate(x, ay);
            authorLayout.draw(c);
            c.restore();
            c.drawText(Ui.tr("↑ ↓ para rolar · Voltar para fechar", "↑ ↓ to scroll · Back to close", "↑ ↓ para desplazar · Atrás para cerrar"),
                    x, text.bottom, hint);

            float f = Ui.clamp(closeFocus.value);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.094f + 0.906f * f));
            c.drawCircle(close.centerX(), close.centerY(), close.width() / 2, fill);
            // Two strokes, not the × glyph: the font places it off center.
            float arm = 9 * k, cx = close.centerX(), cy = close.centerY();
            cross.setColor(f > 0.5f ? 0xFF151820 : 0xFFFFFFFF);
            c.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross);
            c.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross);
        }
    }
}
