# funprint

Print images from the Linux command line (or the GNOME Files right-click menu) on cheap
"Fun Print" Bluetooth thermal printers, without the phone app.

Tested on a **PD01**. It should also work with other printers that use the same
"cat printer" BLE protocol (GB01/02/03, GT01, MX05/06/08/10, YT01, …). MXW01-family
printers use a different protocol and are not supported.

## Install

Requires [uv](https://docs.astral.sh/uv/) and BlueZ.

```sh
git clone https://github.com/Auxnon/funprint && cd funprint
uv tool install -e .
```

This installs two identical commands, `print` and `funprint`. (zsh has a builtin
called `print` that shadows the command, so use `funprint` there.)

## Usage

```sh
print photo.jpg                 # dithered, normal darkness
print photo.jpg dark            # or: light   (also --dark / --light)
print logo.png -t 128           # hard black/white threshold, good for text and logos
print wide.png -r               # rotate 90° to print a landscape image lengthwise
print photo.jpg --preview out.png   # just write the 1-bit image, don't print
print --scan                    # list printers nearby
```

Make sure the printer is on and **not connected to the phone app** (it stops
advertising while connected).

The first print scans for a printer and remembers its address in
`~/.config/funprint/printer`. After that it connects to it directly and only
scans again if that printer can't be reached. Override with `-a <address>` or
`FUNPRINT_ADDRESS`.

Fine tuning: `-e/--energy` (0–65535) and `-s/--speed` (lower = darker) override
light/dark; `--rate` sets the Bluetooth send rate in bytes/s (default 16000;
lower it if prints come out with missing rows); `-v` logs printer notifications.

## Right-click "Print" in GNOME Files (Nautilus)

Needs `nautilus-python` (`sudo dnf install nautilus-python` /
`sudo apt install python3-nautilus`).

```sh
mkdir -p ~/.local/share/nautilus-python/extensions
ln -s "$PWD/nautilus/funprint_menu.py" ~/.local/share/nautilus-python/extensions/
nautilus -q   # restart Files
```

Selecting one or more images and choosing **Print** prints them in order, with a
desktop notification for each result.

## Android: "Print" share target

`android/` is a tiny app (no launcher icon, no UI) that adds **Print** to the
Android share sheet. Share one or more images to it and they print; a toast says
"Printing…" and then "Printed" (or why it failed). It uses the same printer logic
as the CLI: it connects to the remembered printer, and only scans if there isn't one
or it can't be reached, then remembers what it found.

The first share asks for Bluetooth permission, and for notifications. Notifications
are optional, but without them Android 13+ hides the final "Printed" toast. It also
asks to turn Bluetooth on if it's off. A "Printing…" notification shows while the
job runs.

On Boox devices the system may "freeze" newly installed apps; sharing to **Print**
still works, because picking it from the share sheet unfreezes it.

Build it (JDK 17+ and the Android SDK; `ANDROID_HOME` set or `android/local.properties`):

```sh
cd android
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with your local debug key so it can be sideloaded.

## Protocol notes

Service `ae30`, write characteristic `ae01` (write without response), notify `ae02`.
Packets are `51 78 <cmd> 00 <len lo> <len hi> <data…> <crc8(data)> ff`, CRC-8 poly
0x07. An image is sent as one `a2` command per 384-px row (48 bytes, LSB = leftmost
pixel) between "lattice" start/end commands, with energy (`af`), speed (`bd`) and
feed (`a1`). The printer sends `ae` notifications to pause/resume the sender. The PD01
advertises service `af30` but uses `ae30` for printing.

Protocol details come from earlier reverse-engineering work, notably
[rbaron/catprinter](https://github.com/rbaron/catprinter) and
[NaitLee/Cat-Printer](https://github.com/NaitLee/Cat-Printer).

## License

MIT
