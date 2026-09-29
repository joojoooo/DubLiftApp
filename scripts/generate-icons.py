#!/usr/bin/env python3
"""Render Android icons from the DubLift submodule's transparent SVG.

Requires ImageMagick (`convert`) and Pillow. Run after updating the submodule.
"""

from io import BytesIO
from math import hypot
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageColor


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "DubLift/internal/dublift/web/icon.svg"
RES = ROOT / "app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def svg_colors() -> tuple[str, str]:
    root = ET.parse(SOURCE).getroot()
    namespace = "{http://www.w3.org/2000/svg}"
    triangle = root.find(f"{namespace}path")
    marks = root.find(f"{namespace}g")
    if triangle is None or marks is None:
        raise ValueError("Expected a triangle path and colored marks in icon.svg")
    fill = re.search(r"(?:^|;)fill:(#[0-9a-fA-F]{6})", triangle.get("style", ""))
    accent = marks.get("fill")
    if fill is None or accent is None:
        raise ValueError("Could not read the two icon colors from icon.svg")
    return fill.group(1), accent


def render_svg() -> Image.Image:
    result = subprocess.run(
        ["convert", "-background", "none", "-density", "96", str(SOURCE),
         "-strip", "-depth", "8", "PNG32:-"],
        check=True,
        capture_output=True,
    )
    image = Image.open(BytesIO(result.stdout)).convert("RGBA")
    bounds = image.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0).getbbox()
    if bounds is None:
        raise ValueError("icon.svg rendered without visible artwork")
    return image.crop(bounds)


def monochrome_mark(image: Image.Image, dark: str, accent: str) -> Image.Image:
    dark_green = ImageColor.getrgb(dark)[1]
    accent_green = ImageColor.getrgb(accent)[1]
    if accent_green - dark_green < 32:
        raise ValueError("The SVG colors need distinct green channels for the icon mask")

    # Keep the dark triangle; the lime arrow and bars become transparent holes.
    dark_fraction = image.getchannel("G").point(
        lambda green: max(0, min(255, round(
            (accent_green - green) * 255 / (accent_green - dark_green)
        )))
    )
    alpha = ImageChops.multiply(image.getchannel("A"), dark_fraction)
    mark = Image.new("RGBA", image.size, "white")
    mark.putalpha(alpha)
    return mark


def placed(
    mark: Image.Image, canvas_dp: int, mark_height_dp: int,
    scale: float, x_offset_dp: float = 0,
) -> Image.Image:
    size = round(canvas_dp * scale)
    height = round(mark_height_dp * scale)
    width = round(mark.width * height / mark.height)
    artwork = mark.resize((width, height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    x = round((size - width) / 2 + x_offset_dp * scale)
    y = round((size - height) / 2)
    canvas.alpha_composite(artwork, (x, y))
    return canvas


def check_safe_circle(foreground: Image.Image) -> None:
    # Android guarantees the central 66dp circle stays visible under launcher masks.
    scale = foreground.width / 108
    center = foreground.width / 2
    alpha = foreground.getchannel("A")
    radius = max(
        hypot(x + 0.5 - center, y + 0.5 - center) / scale
        for y in range(foreground.height)
        for x in range(foreground.width)
        if alpha.getpixel((x, y)) >= 128
    )
    if radius > 32:
        raise ValueError(f"Launcher artwork exceeds the padded safe circle: {radius:.2f}dp")


def generate() -> None:
    dark, accent = svg_colors()
    color = render_svg()
    mono = monochrome_mark(color, dark, accent)

    # The triangle's visible mass is left-heavy. Shift it right on a transparent
    # canvas so the play button looks centered under any launcher mask.
    # A 48dp mark leaves room inside the adaptive icon's 66dp safe circle.
    for density, scale in DENSITIES.items():
        mipmap = RES / f"mipmap-{density}"
        drawable = RES / f"drawable-{density}"
        mipmap.mkdir(parents=True, exist_ok=True)
        drawable.mkdir(parents=True, exist_ok=True)

        foreground = placed(color, 108, 48, scale, x_offset_dp=5.25)
        themed = placed(mono, 108, 48, scale, x_offset_dp=5.25)
        if density == "xxxhdpi":
            check_safe_circle(foreground)
        foreground.save(mipmap / "ic_launcher_foreground.png", optimize=True)
        themed.save(mipmap / "ic_launcher_monochrome.png", optimize=True)

        # Legacy launchers also receive only the play button on transparency.
        placed(color, 48, 30, scale, x_offset_dp=3.25).save(
            mipmap / "ic_launcher.png", optimize=True
        )
        placed(mono, 24, 22, scale).save(drawable / "ic_notification.png", optimize=True)


if __name__ == "__main__":
    generate()
