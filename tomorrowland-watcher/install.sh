#!/usr/bin/env bash
# Richtet den Watcher auf einem Raspberry Pi ein (venv + systemd-Timer).
# Aufruf:  ./install.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNIT_DIR="$HOME/.config/systemd/user"

echo "==> Projektverzeichnis: $PROJECT_DIR"

if [ "$PROJECT_DIR" != "$HOME/tomorrowland-watcher" ]; then
  echo "!! Die systemd-Unit erwartet das Projekt unter \$HOME/tomorrowland-watcher."
  echo "   Verschiebe den Ordner dorthin oder passe die Pfade in systemd/*.service an."
fi

echo "==> Virtualenv anlegen"
python3 -m venv "$PROJECT_DIR/.venv"
"$PROJECT_DIR/.venv/bin/pip" install --quiet --upgrade pip
"$PROJECT_DIR/.venv/bin/pip" install --quiet -r "$PROJECT_DIR/requirements.txt"

if [ ! -f "$PROJECT_DIR/config.json" ]; then
  cp "$PROJECT_DIR/config.example.json" "$PROJECT_DIR/config.json"
  echo "==> config.json aus der Vorlage erzeugt -- jetzt ntfy-Topic eintragen!"
fi

echo "==> systemd-Units installieren"
mkdir -p "$UNIT_DIR"
cp "$PROJECT_DIR/systemd/tomorrowland-watcher.service" "$UNIT_DIR/"
cp "$PROJECT_DIR/systemd/tomorrowland-watcher.timer" "$UNIT_DIR/"
systemctl --user daemon-reload
systemctl --user enable --now tomorrowland-watcher.timer

# Damit der Timer auch laeuft, wenn niemand per SSH eingeloggt ist.
if command -v loginctl >/dev/null; then
  loginctl enable-linger "$USER" || echo "!! 'loginctl enable-linger' braucht ggf. sudo"
fi

echo
echo "Fertig. Naechste Schritte:"
echo "  1) nano config.json          # ntfy-Topic eintragen (frei erfunden, aber schwer zu raten)"
echo "  2) ./.venv/bin/python watcher.py --check-sources"
echo "  3) ./.venv/bin/python watcher.py --test-notify"
echo "  4) systemctl --user list-timers tomorrowland-watcher.timer"
