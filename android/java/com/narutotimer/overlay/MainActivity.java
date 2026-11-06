package com.narutotimer.overlay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREF = "cfg";
    private static final int REQ_NOTIF = 1001;

    private SharedPreferences sp;
    private EditText etDur;
    private SeekBar sbSize, sbAlpha;
    private TextView tvSizeVal, tvAlphaVal, tvStatus;
    private Button btnToggle;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        setContentView(buildUi());
        loadValues();
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    // ---------------- UI ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF101010);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(36));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("替身计时器", 26, 0xFFFFFFFF);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = label("点上屏幕里的小方块，立刻开始倒数。", 14, 0xFF9A9A9A);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSub.topMargin = dp(8);
        root.addView(sub, lpSub);

        // ---- 时长 ----
        root.addView(label("倒计时时长（秒）", 13, 0xFF9A9A9A), sectionLp());
        etDur = new EditText(this);
        etDur.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDur.setTextSize(18);
        etDur.setTextColor(0xFFFFFFFF);
        etDur.setHintTextColor(0xFF666666);
        etDur.setHint("15");
        etDur.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (loading) return;
                try {
                    float v = Float.parseFloat(s.toString().trim());
                    if (v >= 0.5f && v <= 120f) {
                        sp.edit().putFloat("duration", v).apply();
                        TimerService.notifyChanged();
                    }
                } catch (Exception ignored) {}
            }
        });
        root.addView(etDur);

        // ---- 大小 ----
        tvSizeVal = label("大小", 13, 0xFF9A9A9A);
        root.addView(tvSizeVal, sectionLp());
        sbSize = new SeekBar(this);
        tintSeekBar(sbSize);
        sbSize.setMax(60);   // 20..80 sp
        sbSize.setOnSeekBarChangeListener(new SimpleSeek() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int size = 20 + p;
                tvSizeVal.setText("大小  ·  " + size);
                if (!loading) {
                    sp.edit().putInt("size", size).apply();
                    TimerService.notifyChanged();
                }
            }
        });
        root.addView(sbSize);

        // ---- 透明度 ----
        tvAlphaVal = label("面板透明度", 13, 0xFF9A9A9A);
        root.addView(tvAlphaVal, sectionLp());
        sbAlpha = new SeekBar(this);
        tintSeekBar(sbAlpha);
        sbAlpha.setMax(70);  // 30..100 %
        sbAlpha.setOnSeekBarChangeListener(new SimpleSeek() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int a = 30 + p;
                tvAlphaVal.setText("面板透明度  ·  " + a + "%");
                if (!loading) {
                    sp.edit().putInt("alpha", a).apply();
                    TimerService.notifyChanged();
                }
            }
        });
        root.addView(sbAlpha);

        // ---- 开关 ----
        btnToggle = new Button(this);
        btnToggle.setText("显示悬浮窗");
        btnToggle.setTextSize(16);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lpBtn.topMargin = dp(24);
        btnToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onToggle();
            }
        });
        root.addView(btnToggle, lpBtn);

        Button btnReset = new Button(this);
        btnReset.setText("重置两个框的位置");
        btnReset.setTextSize(14);
        LinearLayout.LayoutParams lpRst = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lpRst.topMargin = dp(10);
        btnReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                TimerService.resetPositions();
                Toast.makeText(MainActivity.this, "位置已重置", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnReset, lpRst);

        // ---- 状态 ----
        tvStatus = label("", 13, 0xFF9A9A9A);
        tvStatus.setGravity(Gravity.START);
        LinearLayout.LayoutParams lpSt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSt.topMargin = dp(16);
        root.addView(tvStatus, lpSt);

        // ---- 说明 ----
        TextView tips = label(
                "\n使用说明\n"
                        + "· 屏幕上会出现两个窗：一个只显示秒数，一个是要点的小方块\n"
                        + "· 点小方块：立刻重新开始倒数（点数字窗也可以）\n"
                        + "· 两个窗都能拖动，位置各自记住\n"
                        + "· 上面的「大小」进度条会等比调整两个窗\n"
                        + "· 剩余 2 秒内数字变红，归零停在红色 0.00\n"
                        + "· 长按任意一个窗：回到这个设置页\n\n"
                        + "OPPO 用户必看\n"
                        + "游戏时如果窗消失或被冻结，去「设置 → 电池 → 应用耗电管理」把本应用设为「允许后台运行」，"
                        + "并在「应用管理 → 自启动」里允许本应用。\n\n"
                        + "这个应用不录屏、不开无障碍、不模拟点击，只画两个悬浮窗，游戏侧完全看不到它。",
                13, 0xFF808080);
        LinearLayout.LayoutParams lpTip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTip.topMargin = dp(26);
        root.addView(tips, lpTip);

        return sv;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        return lp;
    }

    /** 深色背景上默认滑块几乎看不见，统一换成亮绿轨道。 */
    private void tintSeekBar(SeekBar sb) {
        sb.setProgressTintList(ColorStateList.valueOf(0xFF4CD964));
        sb.setThumbTintList(ColorStateList.valueOf(0xFF4CD964));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3A3A));
        sb.setPadding(dp(6), dp(10), dp(6), dp(10));
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar sb) {}
        public void onStopTrackingTouch(SeekBar sb) {}
    }

    // ---------------- 数据 ----------------

    private void loadValues() {
        loading = true;
        float dur = sp.getFloat("duration", 15f);
        int size = sp.getInt("size", 40);
        int alpha = sp.getInt("alpha", 85);

        etDur.setText(fmt(dur));
        sbSize.setProgress(Math.max(0, Math.min(60, size - 20)));
        sbAlpha.setProgress(Math.max(0, Math.min(70, alpha - 30)));

        tvSizeVal.setText("大小  ·  " + size);
        tvAlphaVal.setText("面板透明度  ·  " + alpha + "%");
        loading = false;
    }

    private String fmt(float v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    // ---------------- 逻辑 ----------------

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void refreshState() {
        boolean perm = hasOverlayPermission();
        boolean running = TimerService.RUNNING;

        if (!perm) {
            btnToggle.setText("授予悬浮窗权限");
            tvStatus.setText("还没有悬浮窗权限，点上面按钮去开启。");
        } else if (running) {
            btnToggle.setText("关闭悬浮窗");
            tvStatus.setText("悬浮窗运行中。现在可以切到游戏了。");
        } else {
            btnToggle.setText("显示悬浮窗");
            tvStatus.setText("已授权，点上面按钮在屏幕上显示计时窗。");
        }
    }

    private void onToggle() {
        if (!hasOverlayPermission()) {
            gotoOverlaySettings();
            return;
        }
        if (TimerService.RUNNING) {
            stopService(new Intent(this, TimerService.class));
            TimerService.RUNNING = false;
            Toast.makeText(this, "已关闭悬浮窗", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(this, TimerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "悬浮窗已显示，切到游戏即可", Toast.LENGTH_SHORT).show();
        }
        btnToggle.postDelayed(new Runnable() {
            public void run() {
                refreshState();
            }
        }, 300);
    }

    private void gotoOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                Toast.makeText(this, "请手动到 设置→应用→显示悬浮窗 里授权",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }
}
