# بک‌اند و پنل مدیریت Tornado VPN

نسخه فعلی بک‌اند `2.1.0` است. کانال اصلی دریافت تنظیمات در کلاینت جدید، PNG
امضاشده‌ی `/assets/tornado-config.png` است؛ این مسیر به Firebase یا Play
Integrity وابسته نیست و برای نصب Google Play و APK مستقیم تلگرام کار می‌کند.
راهنمای استقرار مرحله‌ای در `DEPLOY_IMAGE_BOOTSTRAP_FA.md` قرار دارد.

این پروژه بک‌اند مستقل سازگار با کلاینت‌های **Tornado VPN** با پکیج
`com.vpn.tornadovpn` است. پنل کاملاً فارسی و RTL است و فونت Vazirmatn به‌صورت
محلی سرو می‌شود. استقرار پیش‌فرض برای `https://bartarindl.ir` و پورت لوکال
`127.0.0.1:8010` آماده شده و به سرویس قبلی روی پورت 8000 دست نمی‌زند.

## قابلیت‌های پیاده‌شده

- داشبورد واقعی: سرورهای فعال، وضعیت AdMob، درخواست‌های موفق/ردشده، نسخه نصب‌ها،
  نصب فعال ۱/۷/۳۰ روزه و آمار واقعی Firebase/GA4 در صورت اتصال Property.
- CRUD کامل سرورها؛ استخراج خودکار پروتکل، تگ، هاست، پورت، IP و کشور از لینک‌های
  VMess، VLESS، Trojan، Shadowsocks، SOCKS، HTTP، Hysteria2 و TUIC.
- CRUD مستقل «سرورهای تبلیغات» برای تونل موقت Splash؛ این سرورها در جدول جدا و
  به‌صورت رمزگذاری‌شده ذخیره و فقط در آرایه `adServers` فرستاده می‌شوند و وارد `servers` عادی
  نمی‌شوند.
- رمزنگاری کانفیگ‌های ذخیره‌شده با AES-256-GCM؛ کانفیگ در لیست پنل یا audit log
  نمایش داده نمی‌شود.
- تنظیم چهار جایگاه AdMob: قبل اتصال، بعد اتصال، App Open و Splash؛ روشن/خاموش، Unit ID،
  timeout، فاصله نمایش، تناوب و سقف روزانه. UMP، test mode و تنظیم legacy نسخه فعلی
  نیز وجود دارد.
- کنترل حداقل/حداکثر Version Code، خطای `426 UPDATE_REQUIRED`، پیام اجباری، لینک
  مستقیم و لینک Google Play.
- تنظیم لینک حریم خصوصی، قوانین، پشتیبانی، اشتراک‌گذاری، وب‌سایت، maintenance و TTL.
- کانال اصلی PNG امضاشده با قرارداد `TCI1` و steganography سازگار با ابزار
  Auyer؛ بدون وابستگی Runtime به Firebase، Play Integrity یا Redis.
- مسیر مستقیم و زاپاس `translate.goog`، cache-buster پنج‌دقیقه‌ای، ETag،
  `no-transform` و اعتبار زمانی payload.
- مسیر JSON قدیمی فقط در صورت اجرای helper با حالت rollout موقتاً باز می‌ماند و
  پس از انتشار نسخه جدید با یک دستور خاموش می‌شود.
- rate limit، آمار نصب/IP هش‌شده، رمزنگاری کانفیگ در دیتابیس، کوکی امن، CSRF،
  CSP و audit trail همچنان فعال‌اند.

## اجرای سریع با Docker

پیش‌نیاز: Docker و Docker Compose، یک دامنه و TLS معتبر.

```bash
python3 scripts/generate_secrets.py --write-env .env
python3 scripts/configure_image_bootstrap.py lockdown --env-file .env
```

دستور اول `.env` را با کلیدهای تصادفی معتبر می‌سازد و رمز پنل را یک‌بار چاپ
می‌کند. مقادیر اصلی از قبل در `.env.example` تنظیم شده‌اند:

```dotenv
PUBLIC_BASE_URL=https://bartarindl.ir
EXPECTED_PACKAGE_NAME=com.vpn.tornadovpn
IMAGE_BOOTSTRAP_ENABLED=true
SECURE_BOOTSTRAP_ENABLED=false
LEGACY_BOOTSTRAP_ENABLED=false
```

اگر لازم است نسخه‌های خیلی قدیمی در زمان rollout موقتاً مسیر JSON قبلی را
بگیرند، به‌جای `lockdown` این دستور را اجرا کنید:

```bash
python3 scripts/configure_image_bootstrap.py rollout --env-file .env
```

و سرویس را بسازید:

```bash
docker compose up -d --build
docker compose logs -f backend
```

پنل روی `http://127.0.0.1:8010/admin` در دسترس است؛ آن را مستقیماً روی اینترنت
باز نکنید. تنظیم Nginx مخصوص دامنه در `deploy/nginx-bartarindl.ir.conf` قرار دارد.
راهنمای کامل استقرار در `DEPLOY_IMAGE_BOOTSTRAP_FA.md` است.

## اجرای محلی بدون Docker

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[test]'
python3 scripts/generate_secrets.py --write-env .env
# برای اجرای محلی: ENVIRONMENT=development و SECURE_COOKIES=false را در .env بگذارید.
alembic upgrade head
uvicorn app.main:app --reload
```

## شمارش اکتیوها و GA4

کانال تصویر هیچ نیازی به Firebase یا `google-services.json` ندارد. استفاده از
GA4 برای کارت‌های آماری پنل کاملاً اختیاری است و جزو مسیر راه‌اندازی یا دریافت
کانفیگ محسوب نمی‌شود.

داشبورد دو آمار جدا نشان می‌دهد:

1. **Bootstrap Active**: برای کلاینت‌های قدیمی که هنوز endpoint JSON را مصرف
   می‌کنند.
2. **GA4 Active**: فقط در صورت تنظیم اختیاری GA4 Data API.

در دیتابیس شناسه نصب و IP فقط به‌شکل HMAC ذخیره می‌شوند.

## قرارداد Bootstrap کلاینت Android

payload داخل PNG همچنان schema `1` دارد و شامل سرورهای اصلی، سرورهای تبلیغ،
AdMob، تنظیمات اپ و سیاست آپدیت است. بک‌اند JSON canonical را امضا می‌کند و بعد
آن را در تصویر پایه قرار می‌دهد:

```json
{
  "servers": [],
  "adServers": [
    {"id": "ad-de-01", "config": "vless://...", "priority": 10, "enabled": true}
  ],
  "ads": {
    "placements": {
      "splash": {
        "enabled": true,
        "format": "app_open",
        "unitId": "ca-app-pub-.../...",
        "everyNActions": 1,
        "cooldownSeconds": 0,
        "timeoutMs": 12000,
        "maxPerDay": 0
      }
    }
  }
}
```

نبودن سرور تبلیغ باعث رد Bootstrap نمی‌شود و `adServers: []` برمی‌گردد. اما نبودن
سرور عادی همچنان خطای `no_servers` است.

## اتصال به کلاینت Android

کلاینت URLهای تصویر و کلید عمومی ECDSA فعلی سرور را به‌صورت Build-time دارد:

```properties
TORNADO_CONFIG_IMAGE_PRIMARY_URL=https://bartarindl.ir/assets/tornado-config.png
TORNADO_CONFIG_IMAGE_FALLBACK_URL=https://bartarindl-ir.translate.goog/assets/tornado-config.png?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en
HAIMA_SERVER_SIGNING_KEY_ID=tornado-signing-2026-01
HAIMA_SERVER_SIGNING_PUBLIC_KEY=...
```

TLS Pin استفاده نمی‌شود تا تمدید گواهی دامنه باعث قطعی نشود؛ HTTPS انتقال را و
امضای لایه اپلیکیشن اصالت payload را بررسی می‌کند. helper و verifier در پوشه
`scripts/` و مراحل rollout در `DEPLOY_IMAGE_BOOTSTRAP_FA.md` آمده است.

## نکات مهم AdMob و آپدیت

- `AdMob App ID` در AndroidManifest مقدار compile-time است. تغییر آن در پنل برای
  مرجع/نسخه بعدی ذخیره می‌شود اما بدون بیلد تازه Manifest را عوض نمی‌کند.
- کلاینت‌های قدیمی فیلدهای legacy و سه جایگاه قبلی را مصرف می‌کنند؛ کلاینت جدید
  `adServers` و جایگاه `splash` را نیز می‌شناسد.
- پاسخ 426، پیام آپدیت اجباری و دو لینک مستقیم/Google Play در کلاینت پشتیبانی می‌شوند.

## پشتیبان‌گیری و امنیت عملیاتی

- از volume دیتابیس و volume `tornado_data` شامل کلید امضا جداگانه و رمز‌شده backup بگیرید.
- `FIELD_ENCRYPTION_KEY_B64` و کلید signing را در Git، image یا لاگ قرار ندهید.
- برای production کلید ECDSA را در KMS/HSM نگهداری و rotation دوره‌ای اجرا کنید.
- TTL کوتاه (پیش‌فرض ۱۵ دقیقه)، لغو سریع کانفیگ و تعویض دوره‌ای سرورها ضروری است؛
  هیچ کلاینتی نمی‌تواند استخراج credential در حافظه دستگاه روت‌شده را مطلقاً ناممکن کند.
- endpoint پنل را با firewall/VPN مدیریتی یا IP allowlist نیز محدود کنید.

## تست و سلامت

```bash
pytest
curl http://127.0.0.1:8010/healthz
curl http://127.0.0.1:8010/readyz
```

مهاجرت‌ها با Alembic اجرا می‌شوند. قبل از ارتقای production از PostgreSQL backup
بگیرید و سپس `alembic upgrade head` را اجرا کنید.
