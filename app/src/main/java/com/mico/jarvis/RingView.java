package com.mico.jarvis;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Random;

public class RingView extends View {
    public static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;
    public volatile int state = IDLE;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] pa = new float[70], pr = new float[70], ps = new float[70], pz = new float[70];
    private float ang = 0, pulse = 0;

    public RingView(Context c) {
        super(c);
        Random r = new Random();
        for (int i = 0; i < 70; i++) {
            pa[i] = r.nextFloat() * 6.28f;
            pr[i] = 0.1f + r.nextFloat() * 0.8f;
            ps[i] = (r.nextFloat() - .5f) * .01f;
            pz[i] = 1 + r.nextFloat() * 2;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        float cX = W / 2f, cY = H / 2f;
        float dim = Math.min(W, H);
        float S = dim / 600f;
        float[] sp = {.004f, .012f, .05f, .02f};
        float speed = (state >= 0 && state < sp.length) ? sp[state] : .004f;
        ang += speed;
        long t = System.currentTimeMillis();
        float lvl = state == SPEAKING ? (float) (.5 + .5 * Math.sin(t / 90.0) * Math.sin(t / 230.0))
                : state == LISTENING ? (float) (.5 + .5 * Math.sin(t / 300.0)) : 0;
        pulse += (lvl - pulse) * .15f;

        // Draw ambient particles
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#3fd8ff"));
        p.setAlpha(190);
        for (int i = 0; i < 70; i++) {
            pa[i] += ps[i] * (1 + speed * 40);
            float rr = pr[i] * 230 * S * (1 + pulse * .08f);
            float px = cX + (float) Math.cos(pa[i] + ang) * rr;
            float py = cY + (float) Math.sin(pa[i] + ang) * rr;
            cv.drawCircle(px, py, pz[i] * S, p);
        }

        // Center core glow
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#3fd8ff"));
        p.setAlpha((int) Math.min(255, 60 + 80 * (1 + pulse)));
        cv.drawCircle(cX, cY, (36 + pulse * 14) * S, p);

        p.setColor(Color.parseColor("#cdeeff"));
        p.setAlpha(230);
        cv.drawCircle(cX, cY, 14 * S, p);

        // Core ring
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.parseColor("#3fd8ff"));
        p.setStrokeWidth(2.5f * S);
        p.setAlpha(230);
        cv.drawCircle(cX, cY, 48 * S * (1 + pulse * 0.05f), p);

        // Rotating inner segmented arcs
        RectF innerRect = new RectF(cX - 85 * S, cY - 85 * S, cX + 85 * S, cY + 85 * S);
        p.setStrokeWidth(3f * S);
        float arcStart1 = (float) Math.toDegrees(ang * 1.8);
        for (int a = 0; a < 3; a++) {
            cv.drawArc(innerRect, arcStart1 + a * 120, 70, false, p);
        }

        // Middle ring
        p.setStrokeWidth(1.5f * S);
        p.setAlpha(140);
        cv.drawCircle(cX, cY, 130 * S, p);

        // Rotating middle counter-arc
        RectF midRect = new RectF(cX - 165 * S, cY - 165 * S, cX + 165 * S, cY + 165 * S);
        p.setStrokeWidth(4f * S);
        p.setAlpha(200);
        float arcStart2 = (float) Math.toDegrees(-ang * 2.2);
        for (int a = 0; a < 4; a++) {
            cv.drawArc(midRect, arcStart2 + a * 90, 45, false, p);
        }

        // Outer ring
        p.setStrokeWidth(1.8f * S);
        p.setAlpha(170);
        float outerR = 215 * S * (1 + pulse * 0.04f);
        cv.drawCircle(cX, cY, outerR, p);

        // Outer rotating radar notches
        p.setStrokeWidth(2.5f * S);
        float arcStart3 = (float) Math.toDegrees(ang * 0.9);
        RectF outerRect = new RectF(cX - 235 * S, cY - 235 * S, cX + 235 * S, cY + 235 * S);
        for (int a = 0; a < 6; a++) {
            cv.drawArc(outerRect, arcStart3 + a * 60, 20, false, p);
        }

        postInvalidateOnAnimation();
    }
}