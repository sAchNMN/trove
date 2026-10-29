"""Generate tiny, synthetic media fixtures. Never reads personal media."""

from pathlib import Path
import os
import subprocess

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/androidTest/assets"
OUTPUT.mkdir(parents=True, exist_ok=True)

for filename, make, model, color in (
    ("canon.jpg", "Canon", "Canon EOS R5", (28, 150, 115)),
    ("iphone.jpg", "Apple", "iPhone 15", (75, 123, 190)),
    ("unknown.jpg", "", "", (170, 170, 170)),
):
    image = Image.new("RGB", (96, 64), color)
    exif = Image.Exif()
    if model:
        exif[271] = make
        exif[272] = model
    image.save(OUTPUT / filename, exif=exif)

for filename, tags in (
    ("iphone.mov", {"com.apple.quicktime.make": "Apple", "com.apple.quicktime.model": "iPhone 15"}),
    ("unknown.mp4", {}),
    ("android.mp4", {"com.android.model": "V2415"}),
):
    args = [
        os.environ.get("FFMPEG", "ffmpeg"), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=size=96x64:rate=2",
        "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-movflags", "use_metadata_tags",
    ]
    for key, value in tags.items():
        args.extend(["-metadata", f"{key}={value}"])
    subprocess.run([*args, str(OUTPUT / filename)], check=True)

print(f"Generated 6 synthetic fixtures in {OUTPUT}")
