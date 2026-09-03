from __future__ import annotations

import argparse
import base64
import secrets
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate Tornado backend secrets")
    parser.add_argument(
        "--write-env",
        metavar="PATH",
        help="create an env file from .env.example and fill all generated secrets",
    )
    parser.add_argument(
        "--signing-key",
        metavar="PATH",
        help="optionally generate the ECDSA key outside Docker",
    )
    parser.add_argument(
        "--env-only", action="store_true", help="print env secrets only (legacy alias)"
    )
    args = parser.parse_args()

    values = {
        "SECRET_KEY": secrets.token_urlsafe(48),
        "FIELD_ENCRYPTION_KEY_B64": base64.b64encode(
            secrets.token_bytes(32)
        ).decode("ascii"),
        "POSTGRES_PASSWORD": secrets.token_urlsafe(36),
        "ADMIN_PASSWORD": secrets.token_urlsafe(24),
    }

    if args.write_env:
        target_env = Path(args.write_env)
        if target_env.exists():
            raise SystemExit(f"Refusing to overwrite existing env file: {target_env}")
        template = Path(".env.example").read_text(encoding="utf-8")
        replacements = {
            "SECRET_KEY=CHANGE_ME_WITH_A_LONG_RANDOM_VALUE": (
                f"SECRET_KEY={values['SECRET_KEY']}"
            ),
            "FIELD_ENCRYPTION_KEY_B64=CHANGE_ME_WITH_32_BYTE_BASE64": (
                f"FIELD_ENCRYPTION_KEY_B64={values['FIELD_ENCRYPTION_KEY_B64']}"
            ),
            "POSTGRES_PASSWORD=CHANGE_ME_WITH_A_LONG_DATABASE_PASSWORD": (
                f"POSTGRES_PASSWORD={values['POSTGRES_PASSWORD']}"
            ),
            "ADMIN_PASSWORD=CHANGE_ME_WITH_A_STRONG_UNIQUE_PASSWORD": (
                f"ADMIN_PASSWORD={values['ADMIN_PASSWORD']}"
            ),
        }
        for source, replacement in replacements.items():
            if source not in template:
                raise SystemExit(f"Missing placeholder in .env.example: {source}")
            template = template.replace(source, replacement)
        target_env.write_text(template, encoding="utf-8")
        target_env.chmod(0o600)
        print(f"Environment written to {target_env}")
        print("ADMIN_USERNAME=admin")
        print(f"ADMIN_PASSWORD={values['ADMIN_PASSWORD']}")
    else:
        for name, value in values.items():
            print(f"{name}={value}")

    if args.signing_key:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        target_key = Path(args.signing_key)
        if target_key.exists():
            raise SystemExit(f"Refusing to overwrite existing key: {target_key}")
        target_key.parent.mkdir(parents=True, exist_ok=True)
        key = ec.generate_private_key(ec.SECP256R1())
        target_key.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        target_key.chmod(0o600)
        public_der = key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        print(
            "HAIMA_SERVER_SIGNING_PUBLIC_KEY="
            + base64.b64encode(public_der).decode("ascii")
        )
        print(f"Signing private key written to {target_key}")


if __name__ == "__main__":
    main()
