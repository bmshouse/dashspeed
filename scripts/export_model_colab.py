# DashSpeed - YOLO11n "band" TFLite export (288x800, fp16 weights)
#
# Run this in Google Colab (free tier is sufficient) to produce yolo11n_fp16_band_288x800.tflite
# (yolo11n_fp32_band_288x800.tflite if the installed ultralytics cannot export fp16).
# After the cell finishes, download the file from the Colab file browser (left panel).
#
# Steps:
#   1. Open https://colab.research.google.com
#   2. Create a new notebook
#   3. Paste this script into a code cell and run it
#   4. Download yolo11n_fp16_band_288x800.tflite from the file browser
#   5. Place it in  app/src/main/assets/
#
# The app benchmarks every .tflite in its assets at first run (see CLAUDE.md, "Model selection"), so the
# file name does not matter; adding or replacing a file triggers a re-benchmark.
#
# Why these settings:
#   imgsz=(288, 800)  A "band" model: a wide strip (H x W, both multiples of 32) covering the horizon
#                     band of the camera frame. The app recognises it by its aspect ratio
#                     (FrameGeometry.isBandModel) and crops the frame to match.
#   half=True         fp16 weights (needs ultralytics 8.3.x; see Export below). Input and output tensors stay float32, as the app requires, so
#                     decoding is unchanged; the GPU delegate usually runs fp16 faster and cooler.
#   yolo11n           About 25% fewer FLOPs than yolov8n at similar accuracy. Same head layout
#                     ([1, 84, N] output, COCO classes), so the decoder needs no changes.

# -- Install -------------------------------------------------------------------------------------
# !pip install ultralytics tensorflow --quiet

import pathlib
import shutil

from ultralytics import YOLO

IMGSZ = (288, 800)  # (height, width)
OUT_NAME = "yolo11n_fp16_band_288x800.tflite"

# -- Export --------------------------------------------------------------------------------------
# Older ultralytics (8.3 series, format="tflite") can export fp16 with half=True. Newer releases export
# through "litert" and reject FP16 (AssertionError: quantize=16 is not supported). In that case this falls
# back to fp32, which is still lighter than the 336x960 YOLOv8n; to get fp16 pin the older package first:
#   !pip install "ultralytics==8.3.*" tensorflow --quiet
model = YOLO("yolo11n.pt")
precision = "float16"
try:
    model.export(format="tflite", half=True, imgsz=IMGSZ)
except AssertionError as err:
    print(f"fp16 export not supported by this ultralytics version ({err}); exporting fp32 instead.")
    precision = "float32"
    model.export(format="tflite", imgsz=IMGSZ)
    OUT_NAME = OUT_NAME.replace("fp16", "fp32")

# -- Collect -------------------------------------------------------------------------------------
# ultralytics writes <name>_saved_model/<name>_float16.tflite (and a float32 twin); newer versions may
# name the file differently, so fall back to the newest non-int8 .tflite under the working directory.
candidates = sorted(pathlib.Path(".").rglob(f"*{precision}*.tflite"))
if not candidates:
    candidates = sorted(
        (p for p in pathlib.Path(".").rglob("*.tflite") if "int8" not in p.name and "yolo11n" in str(p)),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )

if not candidates:
    print("No .tflite found - check the export output above for errors.")
else:
    dest = pathlib.Path(OUT_NAME)
    shutil.copyfile(candidates[0], dest)
    print(f"Exported {dest}  ({dest.stat().st_size // 1024} KB) from {candidates[0]}")

    # -- Verify: tensor shapes and dtypes the app expects ---------------------------------------
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(dest))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print("input :", inp["shape"], inp["dtype"].__name__)
    print("output:", out["shape"], out["dtype"].__name__)
    assert inp["dtype"].__name__ == "float32", "app needs float32 input"
    assert out["dtype"].__name__ == "float32", "app needs float32 output"
    print("Download it from the Colab file browser (folder icon on the left).")
