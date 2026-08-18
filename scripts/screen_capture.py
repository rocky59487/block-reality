"""Utility module for capturing desktop and window contents via Windows GDI."""

import os
import sys

def is_windows():
    return os.name == "nt"

def attach_to_desktop():
    if not is_windows():
        return False
    try:
        import ctypes
        user32 = ctypes.windll.user32
        hwinsta = user32.OpenWindowStationW('WinSta0', False, 0x0000037F)
        if hwinsta:
            user32.SetProcessWindowStation(hwinsta)
        hdesk = user32.OpenDesktopW('Default', 0, False, 0x01FF)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
        return True
    except Exception as e:
        sys.stderr.write(f"[ScreenCapture] Warning: desktop attachment failed: {e}\n")
        return False

def find_minecraft_window():
    if not is_windows():
        return None, None
    try:
        import win32gui
    except ImportError:
        return None, None

    mc_hwnds = []
    def enum_cb(hwnd, extra):
        if win32gui.IsWindowVisible(hwnd):
            title = win32gui.GetWindowText(hwnd)
            if "Minecraft" in title or "LWJGL" in title:
                mc_hwnds.append((hwnd, title))
    win32gui.EnumWindows(enum_cb, None)
    if mc_hwnds:
        return mc_hwnds[0][0], mc_hwnds[0][1]
    return None, None

def capture_rect(x, y, w, h):
    if not is_windows():
        raise OSError("capture_rect is only supported on Windows")

    import ctypes
    import cv2
    import numpy as np

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

    hdcScreen = user32.GetDC(0)
    hdcMem = gdi32.CreateCompatibleDC(hdcScreen)
    hbm = gdi32.CreateCompatibleBitmap(hdcScreen, w, h)
    gdi32.SelectObject(hdcMem, hbm)

    SRCCOPY = 0x00CC0020
    gdi32.BitBlt(hdcMem, 0, 0, w, h, hdcScreen, x, y, SRCCOPY)

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ('biSize', ctypes.c_uint32),
            ('biWidth', ctypes.c_int32),
            ('biHeight', ctypes.c_int32),
            ('biPlanes', ctypes.c_uint16),
            ('biBitCount', ctypes.c_uint16),
            ('biCompression', ctypes.c_uint32),
            ('biSizeImage', ctypes.c_uint32),
            ('biXPelsPerMeter', ctypes.c_int32),
            ('biYPelsPerMeter', ctypes.c_int32),
            ('biClrUsed', ctypes.c_uint32),
            ('biClrImportant', ctypes.c_uint32),
        ]

    bmi = BITMAPINFOHEADER()
    bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.biWidth = w
    bmi.biHeight = -h  # top-down
    bmi.biPlanes = 1
    bmi.biBitCount = 32
    bmi.biCompression = 0

    buf = (ctypes.c_char * (w * h * 4))()
    gdi32.GetDIBits(hdcMem, hbm, 0, h, buf, ctypes.byref(bmi), 0)

    # Clean up GDI objects
    gdi32.DeleteObject(hbm)
    gdi32.DeleteDC(hdcMem)
    user32.ReleaseDC(0, hdcScreen)

    arr = np.frombuffer(buf, dtype=np.uint8).reshape((h, w, 4))
    # Return BGR for OpenCV
    return cv2.cvtColor(arr, cv2.COLOR_BGRA2BGR)

if __name__ == "__main__":
    attach_to_desktop()
    hwnd, title = find_minecraft_window()
    if hwnd:
        print(f"Found Minecraft window: '{title}' (HWND: {hwnd})")
    else:
        print("Minecraft window not found.")
