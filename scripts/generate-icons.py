"""Export the shared plugin SVG as the Desktop PNG and macOS ICNS assets."""

from io import BytesIO
from pathlib import Path
import struct

import cairosvg
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'integrations/goland/src/main/resources/META-INF/pluginIcon.svg'
OUTPUT = ROOT / 'build/icons'
REPRESENTATIONS = (
    (b'icp4', 16), (b'ic11', 32),
    (b'icp5', 32), (b'ic12', 64),
    (b'ic07', 128), (b'ic13', 256),
    (b'ic08', 256), (b'ic14', 512),
    (b'ic09', 512), (b'ic10', 1024),
)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    raster = cairosvg.svg2png(url=str(SOURCE), output_width=1024, output_height=1024)
    with Image.open(BytesIO(raster)) as image:
        source = image.convert('RGBA')
    source.save(OUTPUT / 'reqws.png', optimize=True)
    entries = []
    for kind, size in REPRESENTATIONS:
        stream = BytesIO()
        source.resize((size, size), Image.Resampling.LANCZOS).save(
            stream, format='PNG', optimize=True,
        )
        payload = stream.getvalue()
        entries.append(kind + struct.pack('>I', len(payload) + 8) + payload)
    body = b''.join(entries)
    (OUTPUT / 'reqws.icns').write_bytes(b'icns' + struct.pack('>I', len(body) + 8) + body)
    print('Updated build/icons/reqws.png and build/icons/reqws.icns from pluginIcon.svg')


if __name__ == '__main__':
    main()
