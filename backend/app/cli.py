from __future__ import annotations

import argparse
import getpass

from sqlalchemy import select

from .database import Base, SessionLocal, engine
from .models import AdminUser
from .security import hash_password


def main() -> None:
    parser = argparse.ArgumentParser(description="Tornado backend management")
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create-admin", help="create or reset an admin account")
    create.add_argument("username")
    args = parser.parse_args()
    if args.command == "create-admin":
        Base.metadata.create_all(bind=engine)
        password = getpass.getpass("New password: ")
        if len(password) < 12:
            raise SystemExit("Password must have at least 12 characters")
        with SessionLocal() as db:
            admin = db.scalar(
                select(AdminUser).where(AdminUser.username == args.username)
            )
            if admin is None:
                admin = AdminUser(
                    username=args.username, password_hash=hash_password(password)
                )
                db.add(admin)
            else:
                admin.password_hash = hash_password(password)
                admin.is_active = True
            db.commit()
        print("Admin account is ready.")


if __name__ == "__main__":
    main()
