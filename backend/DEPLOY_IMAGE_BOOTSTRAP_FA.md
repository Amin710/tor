# استقرار کانال تصویری Tornado

کانال تصویری برای دریافت تنظیمات به Firebase و Play Integrity وابسته نیست و
برای نسخه Google Play و APK مستقیم تلگرام یکسان کار می‌کند. اطلاعات پنل،
سرورهای عادی، سرور تبلیغات، AdMob و سیاست آپدیت بدون Migration جدید حفظ
می‌شوند.

## Rollout

ابتدا از `.env` بکاپ بگیر و فایل‌های نسخه جدید را استخراج کن. سپس:

```bash
cd ~/tornado-backend
python3 scripts/configure_image_bootstrap.py rollout
docker compose config --quiet
docker compose build backend
docker compose up -d --no-deps --force-recreate backend
curl -fsS http://127.0.0.1:8010/readyz
curl -fsS -D - -o /dev/null https://bartarindl.ir/assets/tornado-config.png
```

در حالت Rollout، مسیر JSON قدیمی موقتاً باز می‌ماند تا نسخه‌های نصب‌شده قبلی
قطع نشوند. کانال جدید از این مسیر استفاده می‌کند:

```text
https://bartarindl.ir/assets/tornado-config.png
```

مسیر زاپاس کلاینت:

```text
https://bartarindl-ir.translate.goog/assets/tornado-config.png?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en
```

بعد از بالا آمدن سرویس، تست کامل direct و زاپاس را داخل کانتینر اجرا کن:

```bash
docker compose exec backend python scripts/verify_image_bootstrap.py
```

خروجی صحیح با `IMAGE_BOOTSTRAP_OK` شروع می‌شود. این تست فقط متادیتا را چاپ
می‌کند و کانفیگ سرورها را وارد log نمی‌کند.

## Lockdown

بعد از اینکه نسخه جدید کلاینت از هر دو مسیر تنظیمات گرفت و متصل شد، endpoint
خام قدیمی را ببند:

```bash
cd ~/tornado-backend
python3 scripts/configure_image_bootstrap.py lockdown
docker compose up -d --no-deps --force-recreate backend
```

بررسی تنظیمات:

```bash
grep -E '^(IMAGE_BOOTSTRAP_ENABLED|SECURE_BOOTSTRAP_ENABLED|LEGACY_BOOTSTRAP_ENABLED)=' .env
```

خروجی نهایی باید چنین باشد:

```text
IMAGE_BOOTSTRAP_ENABLED=true
SECURE_BOOTSTRAP_ENABLED=false
LEGACY_BOOTSTRAP_ENABLED=false
```

برای برگشت موقت endpoint قدیمی:

```bash
python3 scripts/configure_image_bootstrap.py compat
docker compose up -d --no-deps --force-recreate backend
```

هیچ‌وقت `docker compose down -v` اجرا نکن؛ Volume دیتابیس و کلید امضا را پاک
می‌کند.
