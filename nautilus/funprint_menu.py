"""Nautilus right-click "Print" for images -> Fun Print thermal printer (https://github.com/Auxnon/funprint)."""
import os
import shlex
import subprocess

from gi.repository import GObject, Nautilus

FUNPRINT = os.path.expanduser("~/.local/bin/funprint")


class FunPrintMenu(GObject.GObject, Nautilus.MenuProvider):
    def get_file_items(self, files):
        if not files or not all(f.get_mime_type().startswith("image/") and f.get_location().get_path() for f in files):
            return []
        item = Nautilus.MenuItem(name="FunPrint::print", label="Print", tip="Print on the thermal printer")
        item.connect("activate", self.on_print, [f.get_location().get_path() for f in files])
        return [item]

    def on_print(self, _item, paths):
        # Print one after another; report each result as a desktop notification.
        script = "".join(
            f"name={shlex.quote(os.path.basename(p))}; "
            f"if out=$({shlex.quote(FUNPRINT)} {shlex.quote(p)} 2>&1); "
            f'then notify-send -i printer "Printed" "$name"; '
            f'else notify-send -u critical -i printer "Print failed: $name" "$(echo "$out" | tail -n 1)"; fi; '
            for p in paths
        )
        subprocess.Popen(["sh", "-c", script], start_new_session=True,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
