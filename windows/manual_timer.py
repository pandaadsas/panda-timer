#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""火影替身手动计时器（单框版）

功能：
  - 按热键（默认左 Alt）立刻开始倒计时，与屏幕内容无关
  - 数字精确到 0.01 秒，16ms 刷新
  - 倒计时时长、热键可自定义，配置持久化
  - 面板整体透明度可调，窗口置顶、无边框、可拖动
  - 空闲时显示 "?"；剩余不足 2 秒数字转红；归零后停在红色 0.00

依赖：仅标准库 tkinter + ctypes
"""

import ctypes
import ctypes.wintypes as wintypes
import json
import os
import sys
import time
import tkinter as tk
import tkinter.font as tkfont
from tkinter import messagebox, simpledialog

user32 = ctypes.windll.user32

VK_LMENU = 0xA4  # 左 Alt

# 常用虚拟键名，用于配置显示
VK_NAMES = {
    0x08: "Backspace", 0x09: "Tab", 0x0D: "Enter", 0x10: "Shift", 0x11: "Ctrl",
    0x12: "Alt", 0x13: "Pause", 0x14: "CapsLock", 0x1B: "Esc", 0x20: "空格",
    0x21: "PageUp", 0x22: "PageDown", 0x23: "End", 0x24: "Home",
    0x25: "←", 0x26: "↑", 0x27: "→", 0x28: "↓", 0x2D: "Insert", 0x2E: "Delete",
    0xA0: "左Shift", 0xA1: "右Shift", 0xA2: "左Ctrl", 0xA3: "右Ctrl",
    0xA4: "左Alt", 0xA5: "右Alt",
    0xBA: ";", 0xBB: "=", 0xBC: ",", 0xBD: "-", 0xBE: ".", 0xBF: "/",
    0xC0: "`", 0xDB: "[", 0xDC: "\\", 0xDD: "]", 0xDE: "'",
}
for _i in range(1, 25):
    VK_NAMES[0x6F + _i] = f"F{_i}"
for _c in range(ord("0"), ord("9") + 1):
    VK_NAMES[_c] = chr(_c)
for _c in range(ord("A"), ord("Z") + 1):
    VK_NAMES[_c] = chr(_c)
for _i in range(10):
    VK_NAMES[0x60 + _i] = f"小键盘{_i}"


def vk_name(vk):
    return VK_NAMES.get(vk, f"VK_{vk}")


def key_down(vk):
    return bool(user32.GetAsyncKeyState(vk) & 0x8000)


CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULTS = {
    "duration": 15.0,
    "hotkey_vk": VK_LMENU,
    "alpha": 0.80,
    "pos": None,
    "size": None,       # 窗口 [宽, 高]，None 则按字号推算
    "font_size": 52,
}

BG = "#141414"
FG = "#EAEAEA"
FG_DIM = "#8A8A8A"
FG_ALERT = "#FF3B30"
FG_ACCENT = "#4CD964"
FG_GRIP = "#4A4A4A"

WARN_SECS = 2.0      # 剩余不足该秒数时数字转红
EDGE = 9             # 边缘拖拽感应宽度（像素）
MIN_W = 132
MIN_H = 68

CURSORS = {
    "n": "size_ns", "s": "size_ns",
    "e": "size_we", "w": "size_we",
    "nw": "size_nw_se", "se": "size_nw_se",
    "ne": "size_ne_sw", "sw": "size_ne_sw",
}


class ManualTimer:
    def __init__(self):
        self.cfg = self.load_config()

        self.root = tk.Tk()
        self.root.title("替身计时器")
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", self.cfg["alpha"])
        self.root.configure(bg=BG)

        # ---- 布局 ----
        self.frame = tk.Frame(self.root, bg=BG, highlightthickness=1,
                              highlightbackground="#3A3A3A")
        self.frame.pack(fill="both", expand=True)

        self.lbl_main = tk.Label(
            self.frame, text="?", font=("Consolas", self.cfg["font_size"], "bold"),
            bg=BG, fg=FG_DIM,
        )
        self.lbl_main.pack(expand=True, padx=22, pady=(10, 0))

        self.lbl_hint = tk.Label(
            self.frame, text="", font=("Microsoft YaHei UI", 9),
            bg=BG, fg=FG_DIM,
        )
        self.lbl_hint.pack(pady=(0, 8))
        # 用于测量提示文字像素宽度，决定窄窗口下是否降级/隐藏
        self._hint_font = tkfont.Font(
            root=self.root, family="Microsoft YaHei UI", size=9
        )
        self.lbl_hint.config(font=self._hint_font)

        # 右下角缩放提示（三条斜线，纯视觉）
        self.grip = tk.Canvas(self.frame, width=16, height=16, bg=BG,
                              highlightthickness=0, bd=0,
                              cursor="size_nw_se")
        self.grip.place(relx=1.0, rely=1.0, anchor="se")
        for _i in range(3):
            _o = 3 + _i * 4
            self.grip.create_line(16 - _o, 16, 16, 16 - _o,
                                  fill=FG_GRIP, width=1)

        self.root.update_idletasks()
        self.place_window()

        # ---- 状态 ----
        self.state = "idle"          # idle | running | done
        self.start_ts = 0.0
        self.prev_down = False
        self.capturing = False
        self._drag = None
        self._resize = None

        # ---- 交互绑定 ----
        targets = (self.root, self.frame, self.lbl_main, self.lbl_hint, self.grip)
        for widget in targets:
            widget.bind("<Button-1>", self.on_press)
            widget.bind("<B1-Motion>", self.on_drag)
            widget.bind("<ButtonRelease-1>", self.on_release)
            widget.bind("<Button-3>", self.show_menu)
            widget.bind("<Motion>", self.on_motion)
        self.root.bind("<Leave>", lambda e: self.root.config(cursor=""))

        self.build_menu()
        self.refresh_hint()
        self.tick()

    # ---------- 配置 ----------
    def load_config(self):
        cfg = dict(DEFAULTS)
        try:
            if os.path.exists(CONFIG_PATH):
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    cfg.update(json.load(f))
        except Exception:
            pass
        return cfg

    def save_config(self):
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.cfg, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def place_window(self):
        size = self.cfg.get("size")
        if size and len(size) == 2:
            w = max(MIN_W, int(size[0]))
            h = max(MIN_H, int(size[1]))
            self.cfg["font_size"] = self.fit_font(w, h)
            self.lbl_main.config(font=("Consolas", self.cfg["font_size"], "bold"))
        else:
            w = self.win_width()
            h = self.win_height()
            self.cfg["size"] = [w, h]
        pos = self.cfg.get("pos")
        if pos and len(pos) == 2:
            x, y = pos
        else:
            sw = self.root.winfo_screenwidth()
            x, y = (sw - w) // 2, 30
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def win_width(self):
        return max(220, int(self.cfg["font_size"] * 4.3))

    def win_height(self):
        return int(self.cfg["font_size"] * 1.75) + 46

    def fit_font(self, w, h):
        """由窗口尺寸反推字号，两个方向取小值，保证数字不被裁切。

        系数与 win_width / win_height 严格互逆，保证「按字号定尺寸」
        和「按尺寸定字号」来回换算不漂移。
        """
        f_w = w / 4.3
        f_h = (h - 46) / 1.75
        return max(14, min(200, int(round(min(f_w, f_h)))))

    def apply_fit_font(self, w, h, save=False):
        f = self.fit_font(w, h)
        if f != self.cfg["font_size"]:
            self.cfg["font_size"] = f
            self.lbl_main.config(font=("Consolas", f, "bold"))
        self.cfg["size"] = [w, h]
        self.refresh_hint(w)
        if save:
            self.save_config()

    # ---------- 窗口拖动 / 缩放 ----------
    def hit_zone(self, x, y):
        """根据窗口内坐标判断落在哪个缩放边，返回 'n'/'se'/None 等。"""
        w = self.root.winfo_width()
        h = self.root.winfo_height()
        left, right = x <= EDGE, x >= w - EDGE
        top, bottom = y <= EDGE, y >= h - EDGE
        if top and left:
            return "nw"
        if top and right:
            return "ne"
        if bottom and left:
            return "sw"
        if bottom and right:
            return "se"
        if top:
            return "n"
        if bottom:
            return "s"
        if left:
            return "w"
        if right:
            return "e"
        return None

    def on_motion(self, e):
        if self._resize:
            return
        zone = self.hit_zone(e.x_root - self.root.winfo_rootx(),
                             e.y_root - self.root.winfo_rooty())
        self.root.config(cursor=CURSORS.get(zone, ""))

    def on_press(self, e):
        wx = e.x_root - self.root.winfo_rootx()
        wy = e.y_root - self.root.winfo_rooty()
        zone = self.hit_zone(wx, wy)
        if zone:
            self._drag = None
            self._resize = (zone, e.x_root, e.y_root,
                            self.root.winfo_width(), self.root.winfo_height(),
                            self.root.winfo_x(), self.root.winfo_y())
        else:
            self._resize = None
            self._drag = (e.x_root, e.y_root,
                          self.root.winfo_x(), self.root.winfo_y())

    def on_drag(self, e):
        if self._resize:
            zone, sx, sy, w0, h0, x0, y0 = self._resize
            dx, dy = e.x_root - sx, e.y_root - sy
            x, y, w, h = x0, y0, w0, h0
            if "e" in zone:
                w = max(MIN_W, w0 + dx)
            if "s" in zone:
                h = max(MIN_H, h0 + dy)
            if "w" in zone:
                w = max(MIN_W, w0 - dx)
                x = x0 + (w0 - w)
            if "n" in zone:
                h = max(MIN_H, h0 - dy)
                y = y0 + (h0 - h)
            self.root.geometry(f"{w}x{h}+{x}+{y}")
            self.apply_fit_font(w, h)
            return
        if not self._drag:
            return
        px, py, wx, wy = self._drag
        self.root.geometry(f"+{wx + e.x_root - px}+{wy + e.y_root - py}")

    def on_release(self, e):
        if self._resize:
            self._resize = None
            self.apply_fit_font(self.root.winfo_width(),
                                self.root.winfo_height(), save=True)
        self._drag = None
        self.cfg["pos"] = [self.root.winfo_x(), self.root.winfo_y()]
        self.save_config()

    # ---------- 菜单 ----------
    def build_menu(self):
        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="开始倒计时", command=self.start)
        self.menu.add_command(label="重置", command=self.reset)
        self.menu.add_separator()
        self.menu.add_command(label="设置倒计时秒数…", command=self.ask_duration)
        self.menu.add_command(label="设置热键…", command=self.begin_capture)
        self.menu.add_command(label="设置字号…", command=self.ask_font)
        self.menu.add_command(label="恢复默认大小", command=self.reset_size)

        self.alpha_menu = tk.Menu(self.menu, tearoff=0)
        for pct in (100, 90, 80, 70, 60, 50, 40, 30):
            self.alpha_menu.add_command(
                label=f"{pct}%", command=lambda p=pct: self.set_alpha(p / 100)
            )
        self.menu.add_cascade(label="面板透明度", menu=self.alpha_menu)

        self.menu.add_separator()
        self.menu.add_command(label="退出", command=self.quit_app)

    def show_menu(self, e):
        try:
            self.menu.tk_popup(e.x_root, e.y_root)
        finally:
            self.menu.grab_release()

    # ---------- 行为 ----------
    def set_alpha(self, a):
        a = max(0.25, min(1.0, a))
        self.cfg["alpha"] = a
        self.root.attributes("-alpha", a)
        self.save_config()

    def ask_duration(self):
        v = simpledialog.askfloat(
            "倒计时秒数", "请输入倒计时时长（秒，可带小数）：",
            initialvalue=self.cfg["duration"], minvalue=0.5, maxvalue=120.0,
            parent=self.root,
        )
        if v:
            self.cfg["duration"] = float(v)
            self.save_config()
            self.reset()
            self.refresh_hint()

    def ask_font(self):
        v = simpledialog.askinteger(
            "字号", "数字字号：", initialvalue=self.cfg["font_size"],
            minvalue=14, maxvalue=200, parent=self.root,
        )
        if v:
            self.cfg["font_size"] = int(v)
            self.lbl_main.config(font=("Consolas", v, "bold"))
            w, h = self.win_width(), self.win_height()
            self.cfg["size"] = [w, h]
            self.save_config()
            self.root.geometry(
                f"{w}x{h}+{self.root.winfo_x()}+{self.root.winfo_y()}"
            )

    def reset_size(self):
        """恢复默认尺寸并回到屏幕上方居中。"""
        self.cfg["font_size"] = DEFAULTS["font_size"]
        self.lbl_main.config(
            font=("Consolas", DEFAULTS["font_size"], "bold")
        )
        w, h = self.win_width(), self.win_height()
        self.cfg["size"] = [w, h]
        sw = self.root.winfo_screenwidth()
        x, y = (sw - w) // 2, 30
        self.cfg["pos"] = [x, y]
        self.save_config()
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def begin_capture(self):
        self.capturing = True
        self.lbl_main.config(text="按键", fg=FG_ACCENT,
                             font=("Microsoft YaHei UI", 26, "bold"))
        self.lbl_hint.config(text="请按下你想用的那个键…")

    def set_hotkey(self, vk):
        self.capturing = False
        self.cfg["hotkey_vk"] = vk
        self.save_config()
        self.refresh_hint()
        self.lbl_main.config(font=("Consolas", self.cfg["font_size"], "bold"))
        self.reset()

    def refresh_hint(self, avail_w=None):
        # 窄窗口放不下完整提示时逐步降级，避免文字被窗口裁断
        if avail_w is None:
            if self.root.winfo_width() <= 1:
                self.root.update_idletasks()
            avail_w = self.root.winfo_width()
        hk = vk_name(self.cfg["hotkey_vk"])
        dur = f"{self.cfg['duration']:g}"
        avail = avail_w - 12
        for text in (f"{hk} 开始 · 右键设置 · {dur}s", f"{hk} · {dur}s"):
            if self._hint_font.measure(text) <= avail:
                self.lbl_hint.config(text=text)
                if not self.lbl_hint.winfo_ismapped():
                    self.lbl_hint.pack(pady=(0, 8))
                return
        self.lbl_hint.pack_forget()

    def start(self):
        if self.capturing:
            return
        # 任何状态下按一次热键都立刻从 0 重新计时
        self.state = "running"
        self.start_ts = time.perf_counter()
        self.lbl_main.config(text=f"{self.cfg['duration']:.2f}", fg=FG)

    def reset(self):
        self.state = "idle"
        self.lbl_main.config(text="?", fg=FG_DIM)
        if self.capturing:
            self.lbl_main.config(font=("Consolas", self.cfg["font_size"], "bold"))

    def quit_app(self):
        self.cfg["pos"] = [self.root.winfo_x(), self.root.winfo_y()]
        self.save_config()
        self.root.destroy()

    # ---------- 主循环 ----------
    def tick(self):
        now = time.perf_counter()

        # 热键检测（上升沿）
        vk = self.cfg["hotkey_vk"]
        down = key_down(vk)
        if down and not self.prev_down:
            self.start()
        self.prev_down = down

        # 捕获新热键
        if self.capturing:
            for cand in range(8, 255):
                if cand in (1, 2, 4, 5, 6):
                    continue
                if key_down(cand):
                    self.set_hotkey(cand)
                    break

        # 刷新显示
        if self.state == "running":
            left = self.cfg["duration"] - (now - self.start_ts)
            if left > 0:
                self.lbl_main.config(
                    text=f"{left:.2f}",
                    fg=FG_ALERT if left <= WARN_SECS else FG,
                )
            else:
                # 归零：停在红色 0.00，不闪烁，等下一次按键重新开始
                self.state = "done"
                self.lbl_main.config(text="0.00", fg=FG_ALERT)

        self.root.after(16, self.tick)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    try:
        ManualTimer().run()
    except Exception as exc:
        ctypes.windll.user32.MessageBoxW(0, f"启动失败：{exc}", "替身计时器", 0x10)
        sys.exit(1)
