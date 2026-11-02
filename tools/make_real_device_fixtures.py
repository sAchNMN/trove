"""Generate synthetic JPEGs with EXIF device + capture time for real-device QA.

Writes only into app/build/realdevice-fixtures. Never reads personal media.
"""

from pathlib import Path

from PIL import Image

OUTPUT = Path(__file__).resolve().parents[1] / "app/build/realdevice-fixtures"
OUTPUT.mkdir(parents=True, exist_ok=True)

FIXTURES = (
    # name, make, model, (y, m, d, hh, mm, ss) or None, colour
    ("canon-a.jpg", "Canon", "Canon EOS R5", (2024, 5, 12, 10, 30, 0), (28, 150, 115)),
    ("canon-b.jpg", "Canon", "Canon EOS R5", (2024, 5, 28, 16, 0, 0), (30, 120, 140)),
    ("iphone-a.jpg", "Apple", "iPhone 15", (2024, 7, 3, 8, 15, 0), (75, 123, 190)),
    ("canon-notime.jpg", "Canon", "Canon EOS R5", None, (120, 60, 160)),
    ("iphone-notime.jpg", "Apple", "iPhone 15", None, (160, 90, 40)),
)

for name, make, model, when, colour in FIXTURES:
    image = Image.new("RGB", (96, 64), colour)
    exif = Image.Exif()
    exif[271] = make       # Make
    exif[272] = model      # Model
    if when:
        # DateTimeOriginal belongs in the Exif sub-IFD (0x8769), not IFD0,
        # otherwise readers such as metadata-extractor never see it.
        stamp = "%04d:%02d:%02d %02d:%02d:%02d" % when
        sub = exif.get_ifd(0x8769)
        sub[36867] = stamp  # DateTimeOriginal
        sub[36868] = stamp  # DateTimeDigitized
        exif[306] = stamp   # DateTime
    image.save(OUTPUT / name, exif=exif)

print(f"Generated {len(FIXTURES)} synthetic fixtures in {OUTPUT}")
for path in sorted(OUTPUT.glob("*.jpg")):
    print(f"  {path.name} {path.stat().st_size} bytes")