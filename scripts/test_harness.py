import os
import sys
import time
import glob
import shutil
import subprocess
import threading
import argparse

try:
    sys.stdout.reconfigure(line_buffering=True)
    sys.stderr.reconfigure(line_buffering=True)
except Exception:
    pass

class TestHarness:
    def __init__(self, root_dir=None, art_dir=None):
        if root_dir is None:
            root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        self.root_dir = os.path.abspath(root_dir)
        self.forge_dir = os.path.join(self.root_dir, "forge")
        self.evidence_dir = os.path.join(self.root_dir, "evidence")
        self.frames_dir = os.path.join(self.evidence_dir, "frames")
        self.done_file = os.path.join(self.evidence_dir, "harness_done.txt")
        self.art_dir = art_dir or os.environ.get("ANTIGRAVITY_ARTIFACTS_DIR", "")
        
        os.makedirs(self.evidence_dir, exist_ok=True)
        os.makedirs(self.frames_dir, exist_ok=True)
        if self.art_dir:
            os.makedirs(self.art_dir, exist_ok=True)

    def clean_previous_runs(self):
        print("[Harness] Cleaning previous run artifacts...")
        if os.path.exists(self.done_file):
            try:
                os.remove(self.done_file)
            except Exception:
                pass
        for f in glob.glob(os.path.join(self.frames_dir, "*.png")):
            try:
                os.remove(f)
            except Exception:
                pass

    def run(self, timeout=120):
        self.clean_previous_runs()
        
        print("[Harness] Launching client in automated test mode (BR_AUTO_TEST=1)...")
        env = os.environ.copy()
        env["BR_AUTO_TEST"] = "1"

        # Ensure JAVA_HOME bin is in PATH if JAVA_HOME is configured
        java_home = env.get("JAVA_HOME")
        if java_home:
            java_bin = os.path.join(java_home, "bin")
            if os.path.isdir(java_bin) and java_bin not in env.get("PATH", ""):
                env["PATH"] = java_bin + os.pathsep + env.get("PATH", "")

        is_windows = os.name == "nt"
        gradle_cmd = "gradlew.bat" if is_windows else "./gradlew"
        cmd = ["cmd.exe", "/c", gradle_cmd, "runClient"] if is_windows else [gradle_cmd, "runClient"]

        proc = subprocess.Popen(
            cmd,
            cwd=self.forge_dir,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )

        def _log():
            if proc.stdout:
                for line in proc.stdout:
                    if "[AutoTest]" in line or "sidecar ready" in line or "BUILD" in line:
                        print(line.strip())

        threading_log = threading.Thread(target=_log, daemon=True)
        threading_log.start()

        print("[Harness] Waiting for automated test execution to complete...")
        t0 = time.time()
        completed = False
        while time.time() - t0 < timeout:
            if os.path.exists(self.done_file):
                print(f"[Harness] Test harness completed in {time.time() - t0:.1f}s!")
                completed = True
                break
            time.sleep(1)

        time.sleep(2)
        try:
            proc.terminate()
        except Exception:
            pass

        # Compile recorded frames into video
        self.compile_video()
        if self.art_dir:
            self.copy_to_artifacts()
        return completed

    def compile_video(self, output_path="evidence/demo_walkthrough.mp4", fps=15):
        try:
            import cv2
        except ImportError:
            print("[Harness] cv2 not available, skipping video compilation.")
            return

        frames = sorted(glob.glob(os.path.join(self.frames_dir, "frame_*.png")))
        print(f"[Harness] Found {len(frames)} native GPU frames in {self.frames_dir}")
        if not frames:
            print("[Harness] No frames found to compile video.")
            return

        first = cv2.imread(frames[0])
        if first is None:
            print("[Harness] Unable to read initial frame.")
            return

        h, w, _ = first.shape
        w = w - (w % 2)
        h = h - (h % 2)
        print(f"[Harness] Video resolution: {w}x{h} @ {fps} FPS")

        full_output = os.path.join(self.root_dir, output_path) if not os.path.isabs(output_path) else output_path
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(full_output, fourcc, float(fps), (w, h))
        for f in frames:
            img = cv2.imread(f)
            if img is not None:
                if img.shape[1] != w or img.shape[0] != h:
                    img = cv2.resize(img, (w, h))
                out.write(img)
        out.release()
        if os.path.exists(full_output):
            print(f"[Harness] Saved walkthrough MP4 to {full_output} ({os.path.getsize(full_output):,} bytes)!")

    def copy_to_artifacts(self):
        if not self.art_dir or not os.path.isdir(self.art_dir):
            return
        print(f"[Harness] Copying results to artifact directory {self.art_dir}...")
        for f in glob.glob(os.path.join(self.evidence_dir, "*")):
            if os.path.isfile(f):
                dst = os.path.join(self.art_dir, os.path.basename(f))
                shutil.copy2(f, dst)
                print(f"  Copied {os.path.basename(f)} ({os.path.getsize(f):,} bytes)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Block Reality Automated Test Harness")
    parser.add_argument("--root", default=None, help="Root directory of the project")
    parser.add_argument("--artifacts", default=None, help="Destination directory for test artifacts")
    parser.add_argument("--timeout", type=int, default=120, help="Timeout in seconds")
    args = parser.parse_args()

    harness = TestHarness(root_dir=args.root, art_dir=args.artifacts)
    ok = harness.run(timeout=args.timeout)
    sys.exit(0 if ok else 1)
