# DashSpeed — YOLOv8n INT8 TFLite export script
#
# Run this in Google Colab (free tier is sufficient) to produce yolov8n_int8.tflite.
# After the cell finishes, download the file from the Colab file browser (left panel).
#
# Steps:
#   1. Open https://colab.research.google.com
#   2. Create a new notebook
#   3. Paste this script into a code cell and run it
#   4. Download yolov8n_int8.tflite from the file browser
#   5. Place it in  app/src/main/assets/yolov8n_int8.tflite
#   6. Set modelUrl in gradle.properties to a direct download link (GitHub Release, etc.)
#      so future builds fetch it automatically.

# ── Install ──────────────────────────────────────────────────────────────────
# !pip install ultralytics tensorflow --quiet

from ultralytics import YOLO

# ── Export ───────────────────────────────────────────────────────────────────
# imgsz=416  → output shape [1, 84, 3549]  (matches VehicleDetector's decode logic)
# int8=True  → full-integer quantisation with coco8 calibration images (4 imgs)
#              WARNING: for best INT8 quality, point 'data' at a larger calibration set.
#              A few hundred real dashcam frames is ideal; coco8 is the minimal default.
model = YOLO("yolov8n.pt")
model.export(format="tflite", int8=True, imgsz=416)

# ── Verify ───────────────────────────────────────────────────────────────────
import pathlib

candidates = list(pathlib.Path(".").rglob("*int8*.tflite"))
if not candidates:
    # Some ultralytics versions name it without 'int8'
    candidates = list(pathlib.Path(".").rglob("*.tflite"))

if candidates:
    src = candidates[0]
    dest = pathlib.Path("yolov8n_int8.tflite")
    dest.write_bytes(src.read_bytes())
    size_kb = dest.stat().st_size // 1024
    print(f"✓  Exported {dest}  ({size_kb} KB)")
    print("   Download it from the Colab file browser (folder icon on the left).")
else:
    print("✗  No .tflite file found — check export output above for errors.")
