# ارتقای بک‌اند برای Ad Servers و جایگاه Splash

این ارتقا داده‌های `vpn_servers` فعلی را تغییر نمی‌دهد. جدول مستقل
`ad_vpn_servers` و placement جدید `splash` به‌صورت خودکار با Alembic ساخته می‌شوند.

## ۱. پشتیبان‌گیری

پیش از جایگزینی سورس از PostgreSQL و فایل `.env` پشتیبان بگیرید. دستور
`docker compose down -v` اجرا نکنید؛ volume دیتابیس و کلید امضا را حذف می‌کند.

```bash
cd ~/tornado-backend
docker compose exec -T postgres pg_dump -U tornado -d tornado -Fc > tornado-before-ad-splash.dump
cp .env .env.before-ad-splash
```

## ۲. ساخت و اجرای نسخه جدید

فایل `.env`، پوشه `secrets` و volumeهای فعلی را حفظ کنید، سپس سورس نسخه جدید را
جایگزین و اجرا کنید:

```bash
cd ~/tornado-backend
docker compose build backend
docker compose up -d backend
docker compose logs --tail=100 backend
```

فرمان شروع کانتینر قبل از Uvicorn، `alembic upgrade head` را اجرا می‌کند. تأیید:

```bash
docker compose exec backend alembic current
curl -fsS https://bartarindl.ir/readyz
```

Revision باید `20260829_0002` و health باید `ready` باشد.

## ۳. تنظیم پنل

1. وارد `https://bartarindl.ir/admin/ad-servers` شوید و حداقل یک کانفیگ مخصوص
   تبلیغات اضافه، فعال و اولویت‌بندی کنید.
2. وارد `https://bartarindl.ir/admin/admob` شوید.
3. برای خروجی تستی `1000017`، گزینه‌های «AdMob فعال» و «حالت تست» را روشن و UMP را
   خاموش کنید.
4. جایگاه‌های «اتصال با تبلیغ» و «اسپلش با سرور تبلیغ» را فعال و timeout را مثلاً
   `12000` تا `20000` میلی‌ثانیه ذخیره کنید. در حالت تست Unit ID می‌تواند خالی باشد؛
   کلاینت `1000017` شناسه‌های رسمی تست گوگل را به‌اجبار جایگزین می‌کند.
5. جایگاه اسپلش همیشه از فرمت رسمی `app_open` و جایگاه اتصال از `interstitial`
   استفاده می‌کند.
6. سرورهای تبلیغ را داخل بخش سرورهای عادی تکرار نکنید.

در خروجی درآمدی بعدی باید حالت تست خاموش، App ID واقعی هنگام Build و Unit IDهای
واقعی در پنل ثبت شوند. خروجی `1000017` عمداً درآمد واقعی ایجاد نمی‌کند.

## ۴. تست قرارداد بدون افشای کانفیگ

این نمونه فقط شناسه و اولویت را چاپ می‌کند و config محرمانه را از خروجی حذف می‌کند:

```bash
curl -fsS https://bartarindl.ir/v1/android/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"installationId":"server-ad-splash-check","packageName":"com.vpn.tornadovpn","versionCode":1000017,"versionName":"1000017"}' \
  | jq '{schemaVersion, normalServerCount:(.servers|length), adServers:[.adServers[]|del(.config)], splash:.ads.placements.splash}'
```

انتظار می‌رود `schemaVersion` همچنان ۱، `adServers` فقط شامل سرورهای فعال و
`splash.format` برابر `app_open` باشد. خالی بودن `adServers` باعث 503 نمی‌شود؛
کلاینت باید در این حالت fail-open وارد برنامه شود.

## سازگاری و بازگشت

- کلاینت‌های قدیمی فیلدهای جدید را نادیده می‌گیرند و همان `servers` قبلی را می‌گیرند.
- migration تکرارپذیر است و placement تکراری ایجاد نمی‌کند.
- downgrade جدول سرورهای تبلیغ و اطلاعات آن را حذف می‌کند؛ بدون backup اجرا نشود.
