#!/usr/bin/env python3
"""Read deployment configuration as data, never as executable shell input."""

import argparse
import base64
import getpass
import ipaddress
import os
from pathlib import Path
import re
import secrets
import stat
import sys

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / "deploy/aws/.env"
TEMPLATE = ROOT / "deploy/aws/.env.example"
REQUIRED = (
    "STUDINA_DOMAIN", "ACME_EMAIL", "GEMINI_API_KEY",
    "AI_CREDENTIALS_ENCRYPTION_KEY", "DIRECTUS_SECRET",
    "DIRECTUS_ADMIN_EMAIL", "DIRECTUS_ADMIN_PASSWORD",
    "POSTGRES_PASSWORD", "RAG_DB_PASSWORD", "AI_CHAT_MODEL",
    "GEMINI_EMBEDDING_MODEL",
)


def fail(message):
    raise ValueError(message)


def read_env(path):
    result = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            fail(f"Formato inválido en {path.name}, línea {number}.")
        if key in result:
            fail(f"Variable repetida: {key}.")
        # Deliberately small, interpolation-free subset of Compose's dotenv format.
        # Generated secrets, email addresses, hostnames and model IDs fit this subset.
        if any(character.isspace() for character in value) or any(
            character in value for character in "'\"`$#\\"
        ):
            fail(f"{key}: usá un valor sin comillas, espacios ni interpolación.")
        result[key] = value
    return result


def check_domain(value):
    if len(value) > 253 or "." not in value or not re.fullmatch(
        r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
        r"[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?", value
    ):
        fail("STUDINA_DOMAIN debe ser un dominio DNS, sin https://, puerto ni ruta.")
    if value in {"example.com", "example.net", "example.org"} or value.endswith(
        (".example.com", ".example.net", ".example.org", ".example", ".invalid", ".localhost", ".local")
    ):
        fail("STUDINA_DOMAIN debe ser tu dominio público real.")


def check_public_ip(value):
    if not value:
        return
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError:
        fail("STUDINA_PUBLIC_IP debe ser una IPv4 pública, sin protocolo, puerto ni ruta.")
    if not address.is_global or address.is_multicast:
        fail("STUDINA_PUBLIC_IP debe ser una IPv4 pública unicast.")


def check(values):
    for key in REQUIRED:
        value = values.get(key, "")
        if not value or re.search(r"(?i)(replace[_-]?me|change[_-]?me|reemplazar|your[_-]|<|>)", value):
            fail(f"Falta configurar un valor real para {key}.")
    check_domain(values["STUDINA_DOMAIN"])
    check_public_ip(values.get("STUDINA_PUBLIC_IP", ""))
    for key in ("ACME_EMAIL", "DIRECTUS_ADMIN_EMAIL"):
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", values[key]):
            fail(f"{key} debe ser una dirección de correo válida.")
    for key in ("DIRECTUS_SECRET", "DIRECTUS_ADMIN_PASSWORD", "POSTGRES_PASSWORD", "RAG_DB_PASSWORD"):
        if len(values[key]) < 24:
            fail(f"{key} debe tener al menos 24 caracteres; usá setup-env.sh.")
    try:
        encryption_key = base64.b64decode(values["AI_CREDENTIALS_ENCRYPTION_KEY"], validate=True)
    except ValueError:
        fail("AI_CREDENTIALS_ENCRYPTION_KEY debe ser Base64 válido.")
    if len(encryption_key) != 32:
        fail("AI_CREDENTIALS_ENCRYPTION_KEY debe codificar exactamente 32 bytes.")


def setup(domain, email):
    if ENV.exists():
        fail("deploy/aws/.env ya existe. Se conserva sin cambios; editá ese archivo para actualizarlo.")
    values = read_env(TEMPLATE)
    values.update({
        "STUDINA_DOMAIN": domain.lower(), "ACME_EMAIL": email,
        "DIRECTUS_ADMIN_EMAIL": email,
        "AI_CREDENTIALS_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
    })
    for key in ("DIRECTUS_SECRET", "DIRECTUS_ADMIN_PASSWORD", "POSTGRES_PASSWORD", "RAG_DB_PASSWORD"):
        values[key] = secrets.token_urlsafe(36)
    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        if not sys.stdin.isatty():
            fail("En ejecución no interactiva, pasá GEMINI_API_KEY en el entorno.")
        api_key = getpass.getpass("GEMINI_API_KEY (entrada oculta): ")
    values["GEMINI_API_KEY"] = api_key
    check(values)
    # Validate safe serialization before writing anything.
    for key, value in values.items():
        if any(character.isspace() for character in value) or any(character in value for character in "'\"`$#\\"):
            fail(f"{key} contiene caracteres no admitidos por el formato .env del deploy.")
    rendered = []
    for line in TEMPLATE.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key = line.split("=", 1)[0]
            line = f"{key}={values[key]}"
        rendered.append(line)
    ENV.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write("\n".join(rendered) + "\n")
    print("Creado deploy/aws/.env con permisos 0600. No se imprimieron credenciales.")
    print("Guardá una copia segura: la clave de cifrado permite recuperar las API keys personales.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    configure = commands.add_parser("setup")
    configure.add_argument("domain")
    configure.add_argument("email")
    commands.add_parser("check")
    commands.add_parser("keys")
    get = commands.add_parser("get")
    get.add_argument("key", choices=("STUDINA_DOMAIN", "DIRECTUS_ADMIN_EMAIL"))
    args = parser.parse_args()
    if args.command == "setup":
        setup(args.domain, args.email)
        return
    if not ENV.is_file():
        fail("Falta deploy/aws/.env. Ejecutá scripts/aws/setup-env.sh DOMINIO EMAIL.")
    if stat.S_IMODE(ENV.stat().st_mode) & 0o077:
        fail("deploy/aws/.env permite acceso a otros usuarios. Ejecutá chmod 600 deploy/aws/.env.")
    values = read_env(ENV)
    check(values)
    if args.command == "keys":
        # Older deployments may omit this optional key; never inherit it from a dev shell.
        print("\n".join(dict.fromkeys((*values, "STUDINA_PUBLIC_IP"))))
    elif args.command == "get":
        print(values[args.key])


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"Configuración AWS: {error}", file=sys.stderr)
        sys.exit(1)
