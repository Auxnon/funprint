"""Print an image on a "Fun Print" BLE thermal printer (PD01 / GB0x / MX0x / YT01 "cat printer" protocol).

  print photo.jpg                 # print on the saved printer (scans only if it can't be reached)
  print photo.jpg dark            # darker; also: light
  print logo.png -t 128           # hard black/white instead of dithering
  print photo.jpg --preview out.png
  print --scan                    # list printers nearby
"""
import argparse
import asyncio
import os
import sys
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from PIL import Image, ImageOps

TX_CHAR = "0000ae01-0000-1000-8000-00805f9b34fb"
RX_CHAR = "0000ae02-0000-1000-8000-00805f9b34fb"
ADVERT_SERVICES = {"0000ae30-0000-1000-8000-00805f9b34fb", "0000af30-0000-1000-8000-00805f9b34fb"}
NAME_PREFIXES = ("GB0", "GT0", "MX0", "MX1", "YT0", "X5", "X6", "X7", "MXW", "BQ", "LY", "AI0", "PD0")

CONFIG = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "funprint" / "printer"

# (energy, speed): higher energy / lower speed = darker
DARKNESS = {"light": (6000, 40), "normal": (12000, 32), "dark": (30000, 24)}


def crc8(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def cmd(code: int, data: bytes) -> bytes:
    n = len(data)
    return bytes([0x51, 0x78, code, 0x00, n & 0xFF, n >> 8]) + data + bytes([crc8(data), 0xFF])


def feed(lines: int) -> bytes:
    return cmd(0xA1, bytes([lines & 0xFF, lines >> 8]))


LATTICE_START = cmd(0xA6, bytes([0xAA, 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C]))
LATTICE_END = cmd(0xA6, bytes([0xAA, 0x55, 0x17, 0, 0, 0, 0, 0, 0, 0, 0x17]))


def prepare(path: str, width: int, rotate: bool, threshold: int | None) -> Image.Image:
    img = ImageOps.exif_transpose(Image.open(path))
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGBA", img.size, "white")
        img = Image.alpha_composite(bg, img)
    img = img.convert("L")
    if rotate:
        img = img.rotate(90, expand=True)
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    img = ImageOps.autocontrast(img, cutoff=1)
    if threshold is not None:
        return img.point(lambda p: 255 if p > threshold else 0, "1")
    return img.convert("1")  # Floyd-Steinberg dither


def image_commands(img: Image.Image, energy: int, speed: int, feed_after: int) -> list[bytes]:
    out = [
        cmd(0xA3, b"\x00"),                                   # get device state
        cmd(0xA4, b"\x33"),                                   # quality
        cmd(0xAF, bytes([energy & 0xFF, energy >> 8])),       # energy (darkness)
        cmd(0xBE, b"\x00"),                                   # image mode
        cmd(0xBD, bytes([speed])),                            # print speed
        LATTICE_START,
    ]
    px = img.load()
    w = img.width
    for y in range(img.height):
        row = bytearray(w // 8)
        for x in range(w):
            if px[x, y] == 0:  # black pixel -> burn
                row[x >> 3] |= 1 << (x & 7)
        out.append(cmd(0xA2, bytes(row)))
    out += [feed(feed_after), cmd(0xBD, b"\x08"), LATTICE_END, cmd(0xA3, b"\x00")]
    return out


async def find_printer(timeout: float = 8.0):
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    hits = []
    for d, adv in found.values():
        name = d.name or adv.local_name or ""
        if ADVERT_SERVICES & {u.lower() for u in adv.service_uuids} or name.upper().startswith(NAME_PREFIXES):
            hits.append((d, name, adv.rssi))
    return sorted(hits, key=lambda h: -h[2])


def saved_address() -> str | None:
    try:
        return CONFIG.read_text().strip() or None
    except OSError:
        return None


async def connect(address: str | None) -> BleakClient:
    """Connect to the given/saved printer; only scan if it can't be reached."""
    target = address or os.environ.get("FUNPRINT_ADDRESS") or saved_address()
    if target:
        try:
            client = BleakClient(target, timeout=10)
            await client.connect()
            return client
        except (BleakError, asyncio.TimeoutError, OSError):
            if address:
                sys.exit(f"Can't reach printer {address}. Is it on, and disconnected from the phone app?")
        print(f"Printer {target} not reachable, scanning...", file=sys.stderr)
    hits = await find_printer()
    if not hits:
        sys.exit("No printer found. Is it on, and disconnected from the phone app?")
    dev, name, _ = hits[0]
    if name.upper().startswith("MXW"):
        sys.exit("MXW01-family printers use a different protocol; not supported yet.")
    print(f"Using {name} ({dev.address}); saved as default", file=sys.stderr)
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(dev.address + "\n")
    client = BleakClient(dev, timeout=20)
    await client.connect()
    return client


async def send(address: str | None, packets: list[bytes], verbose: bool, rate: int):
    paused = asyncio.Event()
    paused.set()
    loop = asyncio.get_running_loop()
    t0 = loop.time()

    def on_notify(_, data: bytearray):
        if verbose:
            print(f"\n{loop.time() - t0:6.2f}s <- {data.hex()}")
        # 51 78 ae 00 01 00 <10=pause|00=resume>: printer flow control
        if len(data) >= 7 and data[2] == 0xAE:
            paused.clear() if data[6] else paused.set()

    client = await connect(address)
    try:
        try:
            await client._backend._acquire_mtu()  # BlueZ reports 23 until asked
        except Exception:
            pass
        try:
            await client.start_notify(RX_CHAR, on_notify)
        except Exception:
            pass
        chunk = max(20, (client.mtu_size or 23) - 3)
        buf = b"".join(packets)
        total = len(buf)
        # Writes return as soon as BlueZ queues them, so pace by wall clock: stay
        # ahead of the printhead and let the printer's pause/resume do the rest.
        t0 = start = loop.time()
        for i in range(0, total, chunk):
            if not paused.is_set():
                await paused.wait()
                start = loop.time() - i / rate
            await client.write_gatt_char(TX_CHAR, buf[i:i + chunk], response=False)
            ahead = start + (i + chunk) / rate - loop.time()
            if ahead > 0:
                await asyncio.sleep(ahead)
            if i % (chunk * 10) == 0:
                print(f"\r{100 * i // total:3d}%", end="", flush=True)
        print("\r100%")
        await asyncio.sleep(2)  # let the buffer drain before disconnecting
    finally:
        await client.disconnect()


def main():
    ap = argparse.ArgumentParser(prog="print", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image", nargs="?")
    ap.add_argument("darkness", nargs="?", choices=DARKNESS, default="normal", help="light | normal | dark")
    ap.add_argument("--dark", dest="darkness", action="store_const", const="dark")
    ap.add_argument("--light", dest="darkness", action="store_const", const="light")
    ap.add_argument("-a", "--address", help="printer BLE address (default: last used, else scan)")
    ap.add_argument("-w", "--width", type=int, default=384, help="printhead width in px (default 384)")
    ap.add_argument("-e", "--energy", type=int, help="exact darkness 0-65535 (overrides light/dark)")
    ap.add_argument("-s", "--speed", type=int, help="exact print speed, lower = darker (overrides light/dark)")
    ap.add_argument("-t", "--threshold", type=int, help="hard black/white threshold 0-255 instead of dithering")
    ap.add_argument("-r", "--rotate", action="store_true", help="rotate 90° (print landscape images lengthwise)")
    ap.add_argument("--feed", type=int, default=80, help="lines to feed after printing (default 80)")
    ap.add_argument("--preview", metavar="PNG", help="write the dithered image here and don't print")
    ap.add_argument("--scan", action="store_true", help="list nearby printers and exit")
    ap.add_argument("--rate", type=int, default=16000, help="send rate in bytes/s (default 16000)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if args.scan:
        hits = asyncio.run(find_printer())
        for d, name, rssi in hits:
            print(f"{d.address}  {name or '?':12} rssi {rssi}")
        if not hits:
            print("No printers found.")
        return
    if not args.image:
        ap.error("an image is required")

    energy, speed = DARKNESS[args.darkness]
    energy = args.energy if args.energy is not None else energy
    speed = args.speed if args.speed is not None else speed

    img = prepare(args.image, args.width, args.rotate, args.threshold)
    if args.preview:
        img.save(args.preview)
        print(f"Wrote {args.preview} ({img.width}x{img.height})")
        return
    print(f"Printing {img.width}x{img.height}px (~{img.height / 8:.0f} mm, {args.darkness})")
    try:
        asyncio.run(send(args.address, image_commands(img, energy, speed, args.feed), args.verbose, args.rate))
    except KeyboardInterrupt:
        sys.exit("\nCancelled.")


if __name__ == "__main__":
    main()
