"""Enhanced window focusing and content capture utilities via Windows GDI / PrintWindow."""

import os
import sys
import time

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
        sys.stderr.write(f"[EnhancedCapture] Warning: desktop attachment failed: {e}\n")
        return False

def force_foreground_window(hwnd):
    if not is_windows():
        return
    import ctypes
    user32 = ctypes.windll.user32
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.BringWindowToTop(hwnd)
    cur_thread = user32.GetCurrentThreadId()
    target_thread = user32.GetWindowThreadProcessId(hwnd, None)
    user32.AttachThreadInput(cur_thread, target_thread, True)
    user32.SetForegroundWindow(hwnd)
    user32.SetFocus(hwnd)
    user32.AttachThreadInput(cur_thread, target_thread, False)

def capture_window_content(hwnd):
    if not is_windows():
        raise OSError("capture_window_content is only supported on Windows")

    import ctypes
    import win32gui
    import cv2
    import numpy as np

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

    rect = win32gui.GetWindowRect(hwnd)
    x, y, r, b = rect
    w = max(100, r - x)
    h = max(100, b - y)

    # Method A: Direct window DC with PrintWindow
    hwndDC = user32.GetWindowDC(hwnd)
    hdcMem = gdi32.CreateCompatibleDC(hwndDC)
    hbm = gdi32.CreateCompatibleBitmap(hwndDC, w, h)
    gdi32.SelectObject(hdcMem, hbm)

    # Try PrintWindow PW_RENDERFULLCONTENT (flag 2)
    user32.PrintWindow(hwnd, hdcMem, 2)

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
    bmi.biHeight = -h
    bmi.biPlanes = 1
    bmi.biBitCount = 32
    bmi.biCompression = 0
    buf = (ctypes.c_char * (w * h * 4))()
    gdi32.GetDIBits(hdcMem, hbm, 0, h, buf, ctypes.byref(bmi), 0)

    gdi32.DeleteObject(hbm)
    gdi32.DeleteDC(hdcMem)
    user32.ReleaseDC(hwnd, hwndDC)

    arr = np.frombuffer(buf, dtype=np.uint8).reshape((h, w, 4))
    img = cv2.cvtColor(arr, cv2.COLOR_BGRA2BGR)

    # If PrintWindow gave pitch black, fallback to Desktop DC BitBlt with window foreground
    if img.mean() < 5.0:
        hdcScreen = user32.GetDC(0)
        hdcMem2 = gdi32.CreateCompatibleDC(hdcScreen)
        hbm2 = gdi32.CreateCompatibleBitmap(hdcScreen, w, h)
        gdi32.SelectObject(hdcMem2, hbm2)
        gdi32.BitBlt(hdcMem2, 0, 0, w, h, hdcScreen, x, y, 0x00CC0020)
        buf2 = (ctypes.c_char * (w * h * 4))()
        gdi32.GetDIBits(hdcMem2, hbm2, 0, h, buf2, ctypes.byref(bmi), 0)
        gdi32.DeleteObject(hbm2)
        gdi32.DeleteDC(hdcMem2)
        user32.ReleaseDC(0, hdcScreen)
        arr2 = np.frombuffer(buf2, dtype=np.uint8).reshape((h, w, 4))
        img = cv2.cvtColor(arr2, cv2.COLOR_BGRA2BGR)

    return img

if __name__ == "__main__":
    attach_to_desktop()
    print("Enhanced capture module verified.")
