"""Erzeugt den Signier-Schluessel fuer die Bayern-Navi-APK -- ohne Java.

Gleiches Verfahren wie bei der Fitness-App: privater Schluessel + selbstsigniertes
Zertifikat als PKCS12 (python-cryptography). Android/apksigner liest PKCS12 genauso wie JKS.

Wozu: Android installiert ein Update nur, wenn es mit DEMSELBEN Schluessel signiert ist.
Der Schluessel gehoert deshalb NIE ins (oeffentliche) Repo, sondern als Secret zu GitHub.

Aufruf:  python tools/make_keystore.py
Ergebnis (alles in signing/, per .gitignore ausgeschlossen):
  navi.p12          der Keystore selbst  -> SICHERN, ausserhalb des Repos!
  navi.p12.base64   dasselbe fuer das GitHub-Secret
  secrets.env       Alias + Passwort, wird von setup_github.ps1 gelesen
Weiter mit:  powershell -ExecutionPolicy Bypass -File tools\\setup_github.ps1
"""
import base64
import datetime as dt
import secrets
import string
import sys
from pathlib import Path

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import NameOID
except ImportError:
    sys.exit("Fehlt: python -m pip install cryptography")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "signing"
ALIAS = "navi"
YEARS = 30


def make_password(n: int = 32) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(n))


def main() -> None:
    p12_path = OUT / "navi.p12"
    if p12_path.exists():
        sys.exit(
            f"{p12_path} existiert bereits.\n"
            "Ein zweiter Schluessel wuerde die App-Signatur aendern -- Updates wuerden dann\n"
            "abgelehnt. Wenn du das wirklich willst, loesche signing/ von Hand."
        )
    OUT.mkdir(exist_ok=True)
    password = make_password()
    print("Schluessel wird erzeugt ...")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "Bayern Navi"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Privat"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, "DE"),
    ])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=365 * YEARS))
        .sign(key, hashes.SHA256())
    )
    blob = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(), key=key, cert=cert, cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(password.encode()),
    )
    p12_path.write_bytes(blob)
    (OUT / "navi.p12.base64").write_text(base64.b64encode(blob).decode(), encoding="utf-8")
    (OUT / "secrets.env").write_text(
        f"NAVI_KEY_ALIAS={ALIAS}\nNAVI_STORE_PASSWORD={password}\nNAVI_KEY_PASSWORD={password}\n",
        encoding="utf-8",
    )
    print(f"""
Fertig: {p12_path}  (Alias {ALIAS}, gueltig bis {(now + dt.timedelta(days=365 * YEARS)).date()})

WICHTIG: Ordner signing/ ausserhalb des Projekts sichern (OneDrive, Passwort-Manager).
Ohne ihn sind spaeter keine Updates mehr moeglich, nur Neuinstallation.

Weiter mit:  powershell -ExecutionPolicy Bypass -File tools\\setup_github.ps1
""")


if __name__ == "__main__":
    main()
