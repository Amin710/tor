# استقرار امن Bootstrap v2 بدون قطعی

این ارتقا دو مرحله‌ای است. در مرحله اول کلاینت قدیمی و جدید هم‌زمان کار می‌کنند؛
بعد از مشاهده درخواست‌های موفق نسخه جدید، مسیر plaintext قدیمی بسته می‌شود.

## پیش‌نیاز یک‌باره Firebase

در Firebase پروژه `tornado-cc249` برای پکیج `com.vpn.tornadovpn`:

1. SHA-256 بخش **Play App Signing** را ثبت کنید.
2. در App Check ارائه‌دهنده Play Integrity را فعال کنید.
3. فایل Admin SDK همان پروژه باید روی سرور در مسیر
   `secrets/firebase-admin.json` موجود باشد.

بررسی روی سرور:

```bash
cd ~/tornado-backend
test -s secrets/firebase-admin.json && echo FIREBASE_OK
grep -E '^(FIREBASE_PROJECT_ID|FIREBASE_APP_ID)=' .env
```

## مرحله ۱: rollout سازگار

اسکریپت زیر فقط متغیرهای امنیت v2 را تنظیم و قبل از تغییر، backup زمان‌دار از
`.env` ایجاد می‌کند. رمزها و کلیدهای فعلی را تغییر نمی‌دهد.

```bash
cd ~/tornado-backend
python3 scripts/configure_security_v2.py rollout
docker compose config --quiet
docker compose build backend
docker compose up -d redis
docker compose up -d --no-deps --force-recreate backend
```

کانفیگ فعال Nginx/Certbot را با فایل نمونه overwrite نکنید. فقط یک بار مطمئن شوید
در همان `server` فعال، هدر زیر مقدار ورودی کاربر را append نمی‌کند:

```nginx
proxy_set_header X-Forwarded-For $remote_addr;
```

پس از هر تغییر Nginx:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

بررسی migration و سلامت:

```bash
docker compose exec backend alembic current
docker compose ps
curl -fsS http://127.0.0.1:8010/healthz
curl -fsS http://127.0.0.1:8010/readyz
curl -fsS https://bartarindl.ir/v1/public-info
```

در این مرحله باید این دو مقدار برقرار باشند:

```dotenv
SECURE_BOOTSTRAP_ENABLED=true
LEGACY_BOOTSTRAP_ENABLED=true
```

کلاینت جدید را ابتدا در Internal/Closed Testing نصب کنید. سپس موفقیت v2 را ببینید:

```bash
docker compose exec postgres psql -U tornado -d tornado -c \
"SELECT created_at, version_code, accepted, reason FROM bootstrap_events WHERE reason LIKE '%v2%' ORDER BY created_at DESC LIMIT 30;"
```

## مرحله ۲: بستن مسیر عمومی قدیمی

بعد از مشاهده `accepted_v2` و اجباری‌کردن نسخه جدید در پنل:

```bash
cd ~/tornado-backend
python3 scripts/configure_security_v2.py lockdown
docker compose up -d --no-deps --force-recreate backend
curl -fsS http://127.0.0.1:8010/readyz
```

تست بسته‌شدن legacy باید HTTP 426 بدهد و نباید `vless://` در پاسخ باشد:

```bash
curl -sS -o /tmp/tornado-legacy-check.json -w '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  https://bartarindl.ir/v1/android/bootstrap \
  -d '{"installationId":"legacy-security-check","packageName":"com.vpn.tornadovpn","versionCode":2000000,"versionName":"2000000"}'
grep -q 'vless://' /tmp/tornado-legacy-check.json && echo LEAK || echo LEGACY_CLOSED
```

## بازگشت اضطراری بدون دست‌زدن به داده‌ها

اگر قبل از انتشار کامل کلاینت جدید مجبور به برگشت شدید:

```bash
python3 scripts/configure_security_v2.py compat
docker compose up -d --no-deps --force-recreate backend
```

این فقط مسیر legacy را دوباره روشن می‌کند. هیچ‌وقت این دستور را اجرا نکنید:

```text
docker compose down -v
```

حذف volume باعث از بین رفتن دیتابیس، کلید امضای پاسخ و ناسازگاری کلاینت می‌شود.

## رفتار هنگام اختلال Redis یا Google

- خرابی Redis، Uvicorn را متوقف نمی‌کند: `/healthz` پاسخ می‌دهد ولی `/readyz` و
  مسیر امن موقتاً 503 می‌دهند.
- دستگاه جدید بدون App Check معتبر هیچ کانفیگی نمی‌گیرد.
- دستگاهی که قبلاً تأیید شده، بعد از پایان دوره ۳۰روزه فقط تا ۷۲ ساعت و فقط با
  همان credential و کلید Keystore می‌تواند از grace استفاده کند.
- token، credential و کانفیگ خام در log، Redis یا جدول event ذخیره نمی‌شوند؛
  credential موقت Redis نیز جداگانه رمز و به session/installation bind می‌شود.
