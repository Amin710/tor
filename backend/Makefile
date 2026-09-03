.PHONY: install run test migrate

install:
	python3 -m pip install -e '.[test]'

run:
	uvicorn app.main:app --reload --host 127.0.0.1 --port 8010

test:
	pytest

migrate:
	alembic upgrade head
