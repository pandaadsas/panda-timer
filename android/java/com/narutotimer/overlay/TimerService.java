package com.narutotimer.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 悬浮窗计时服务：两个窗。
 *   数字窗 —— 只显示秒数，可拖动。
 *   点击窗 —— 一个小方块，专门接收点击，可拖动。
 *
 * 只做画窗和算秒数。不录屏、不开无障碍、不注入进程、不模拟输入。
 */
public class TimerService extends Service {

    public static volatile boolean RUNNING = false;
    private static TimerService instance;

    private static final int COL_FG = 0xFFEAEAEA;
    private static final int COL_DIM = 0xFF8A8A8A;
    private static final int COL_ALERT = 0xFFFF3B30;
    private static final int COL_DOT = 0xFF9A9A9A;
    private static final double WARN_SECS = 2.0;

    private SharedPreferences sp;
    private WindowManager wm;

    private LinearLayout numRoot;
    private TextView tvTime;
    private FrameLayout tapRoot;
    private View tapDot;

    private WindowManager.LayoutParams lpNum, lpTap;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float duration = 15f;
    private long startNanos = 0L;
    private boolean running = false;
    private boolean done = false;
    private int touchSlop = 24;
    private float baseAlpha = 0.85f;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            double left = duration - (System.nanoTime() - startNanos) / 1_000_000_000.0;
            if (left > 0) {
                tvTime.setText(String.format(Locale.US, "%.2f", left));
                tvTime.setTextColor(left <= WARN_SECS ? COL_ALERT : COL_FG);
                handler.postDelayed(this, 16);
            } else {
                running = false;
                done = true;
                tvTime.setText("0.00");
                tvTime.setTextColor(COL_ALERT);
            }
        }
    };

    public static void notifyChanged() {
        final TimerService s = instance;
        if (s != null) {
            s.handler.post(new Runnable() {
                @Override
                public void run() {
                    s.applySettings();
                }
            });
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int sp2px(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        RUNNING = true;
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        startForegroundNotice();
        try {
            createNumberWindow();
            createTapWindow();
            applySettings();
            showIdle();
        } catch (Exception e) {
            RUNNING = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (numRoot != null && numRoot.getParent() != null) wm.removeView(numRoot);
        } catch (Exception ignored) {
        }
        try {
            if (tapRoot != null && tapRoot.getParent() != null) wm.removeView(tapRoot);
        } catch (Exception ignored) {
        }
        instance = null;
        RUNNING = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 通知 ----------------

    private void startForegroundNotice() {
        String ch = "timer";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel c = new NotificationChannel(
                        ch, "计时器", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        b.setContentTitle("替身计时器运行中")
                .setContentText("点小方块即可开始倒数")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true);
        startForeground(1, b.build());
    }

    // ---------------- 两个窗 ----------------

    private WindowManager.LayoutParams baseParams(int x, int y) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    private void createNumberWindow() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        numRoot = new LinearLayout(this);
        numRoot.setOrientation(LinearLayout.VERTICAL);
        numRoot.setGravity(Gravity.CENTER);
        numRoot.setPadding(dp(10), dp(4), dp(10), dp(4));
        numRoot.setBackground(roundBg(0xFF141414, 0xFF3A3A3A, 12));

        tvTime = new TextView(this);
        tvTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTime.setTextColor(COL_DIM);
        tvTime.setText("?");
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setIncludeFontPadding(false);
        numRoot.addView(tvTime);

        lpNum = baseParams(sp.getInt("pos_x", dp(24)), sp.getInt("pos_y", dp(160)));
        wm.addView(numRoot, lpNum);
        attachTouch(numRoot, lpNum, "pos", false);
    }

    private void createTapWindow() {
        tapRoot = new FrameLayout(this);
        tapRoot.setBackground(roundBg(0xFF141414, 0xFF4A4A4A, 10));

        tapDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(COL_DOT);
        tapDot.setBackground(dot);
        tapRoot.addView(tapDot, new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER));

        lpTap = baseParams(sp.getInt("tap_x", dp(24)), sp.getInt("tap_y", dp(300)));
        wm.addView(tapRoot, lpTap);
        attachTouch(tapRoot, lpTap, "tap", true);
    }

    private GradientDrawable roundBg(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    // ---------------- 拖动 / 点击 ----------------

    private void attachTouch(final View v, final WindowManager.LayoutParams lp,
                             final String keyPrefix, final boolean isTap) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int originX, originY;
            boolean moved;
            long downAt;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        originX = lp.x;
                        originY = lp.y;
                        moved = false;
                        downAt = SystemClock.uptimeMillis();
                        if (isTap) view.setAlpha(baseAlpha * 0.55f);
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX;
                        float my = e.getRawY() - downY;
                        if (!moved && (Math.abs(mx) > touchSlop || Math.abs(my) > touchSlop)) {
                            moved = true;
                            if (isTap) view.setAlpha(baseAlpha);
                        }
                        if (moved) {
                            lp.x = originX + (int) mx;
                            lp.y = originY + (int) my;
                            clamp(v, lp);
                            try {
                                wm.updateViewLayout(v, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (isTap) view.setAlpha(baseAlpha);
                        if (moved) {
                            sp.edit().putInt(keyPrefix + "_x", lp.x)
                                    .putInt(keyPrefix + "_y", lp.y).apply();
                        } else if (SystemClock.uptimeMillis() - downAt >= 500) {
                            openSettings();
                        } else {
                            if (isTap) flashTap();
                            startTimer();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        if (isTap) view.setAlpha(baseAlpha);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void flashTap() {
        if (tapDot == null) return;
        tapDot.setAlpha(1f);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFEAEAEA);
        tapDot.setBackground(dot);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tapDot == null) return;
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(COL_DOT);
                tapDot.setBackground(d);
            }
        }, 130);
    }

    private void clamp(View v, WindowManager.LayoutParams lp) {
        DisplayMetrics m = new DisplayMetrics();
        try {
            wm.getDefaultDisplay().getMetrics(m);
        } catch (Exception e) {
            return;
        }
        int w = v.getWidth() > 0 ? v.getWidth() : dp(80);
        int h = v.getHeight() > 0 ? v.getHeight() : dp(60);
        lp.x = Math.max(-w / 2, Math.min(lp.x, m.widthPixels - w / 2));
        lp.y = Math.max(-h / 2, Math.min(lp.y, m.heightPixels - h / 2));
    }

    // ---------------- 设置 ----------------

    private void applySettings() {
        if (numRoot == null) return;
        duration = sp.getFloat("duration", 15f);
        int size = sp.getInt("size", 40);
        int alpha = sp.getInt("alpha", 85);
        baseAlpha = Math.max(0.3f, Math.min(1f, alpha / 100f));

        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        numRoot.setAlpha(baseAlpha);

        // 数字窗：宽度固定为 5 个字符，避免 "?" 与 "15.00" 之间跳动
        lpNum.width = sp2px(size * 3.35f) + dp(20);
        lpNum.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try {
            wm.updateViewLayout(numRoot, lpNum);
        } catch (Exception ignored) {
        }

        // 点击窗：正方形，随进度条等比缩放
        int side = Math.max(dp(42), Math.min(dp(104), sp2px(size * 1.35f)));
        lpTap.width = side;
        lpTap.height = side;
        int dot = Math.max(dp(6), Math.min(dp(16), side / 7));
        if (tapDot != null) {
            FrameLayout.LayoutParams dlp =
                    (FrameLayout.LayoutParams) tapDot.getLayoutParams();
            if (dlp != null) {
                dlp.width = dot;
                dlp.height = dot;
                tapDot.setLayoutParams(dlp);
            }
        }
        tapRoot.setAlpha(baseAlpha);
        try {
            wm.updateViewLayout(tapRoot, lpTap);
        } catch (Exception ignored) {
        }

        if (!running && !done) showIdle();
    }

    /** 把两个窗都拉回屏幕内（设置页「重置位置」用）。 */
    public static void resetPositions() {
        final TimerService s = instance;
        if (s == null) return;
        s.handler.post(new Runnable() {
            @Override
            public void run() {
                s.doResetPositions();
            }
        });
    }

    private void doResetPositions() {
        if (numRoot == null) return;
        lpNum.x = dp(24);
        lpNum.y = dp(160);
        lpTap.x = dp(24);
        lpTap.y = dp(320);
        try {
            wm.updateViewLayout(numRoot, lpNum);
            wm.updateViewLayout(tapRoot, lpTap);
        } catch (Exception ignored) {
        }
        sp.edit()
                .putInt("pos_x", lpNum.x).putInt("pos_y", lpNum.y)
                .putInt("tap_x", lpTap.x).putInt("tap_y", lpTap.y)
                .apply();
    }

    private void showIdle() {
        tvTime.setText("?");
        tvTime.setTextColor(COL_DIM);
    }

    private void startTimer() {
        startNanos = System.nanoTime();
        running = true;
        done = false;
        tvTime.setText(String.format(Locale.US, "%.2f", duration));
        tvTime.setTextColor(COL_FG);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
