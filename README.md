# Tornado VPN

مخزن یکپارچه سورس Tornado VPN برای نگهداری و توسعه بلندمدت.

## ساختار

- `client/` — کلاینت Android، نسخه پایه `1000021` با کانال کانفیگ امضاشده داخل تصویر
- `backend/` — بک‌اند و پنل مدیریت، نسخه پایه `2.1.0 IMAGE_CHANNEL`

کلاینت نسخه‌ای تغییر‌یافته از
[`2dust/v2rayNG`](https://github.com/2dust/v2rayNG) است و شرایط GPL-3.0 آن
در [`client/LICENSE`](client/LICENSE) و توضیحات انتساب در
[`client/NOTICE.md`](client/NOTICE.md) نگهداری شده است. این مجوز به‌صورت
خودکار برای بک‌اند مستقل اعمال نشده است.

## نکات امنیتی

فایل‌های اجرایی و اطلاعات محرمانه عمداً در Git نگهداری نمی‌شوند؛ از جمله `.env`، دیتابیس، کلید خصوصی امضای بک‌اند، Android keystore، رمزهای امضا، Firebase credentials و خروجی‌های APK/AAB.

برای راه‌اندازی بک‌اند، ابتدا فایل نمونه را کپی و مقادیر امن را روی همان سرور تولید کنید:

```bash
cd backend
cp .env.example .env
python3 scripts/generate_secrets.py --write-env .env
docker compose up -d --build
```

راهنمای کامل استقرار در [`backend/DEPLOY_IMAGE_BOOTSTRAP_FA.md`](backend/DEPLOY_IMAGE_BOOTSTRAP_FA.md) قرار دارد.

برای Build آزمایشی کلاینت:

```bash
cd client
./gradlew assembleDebug
```

فایل بزرگ `libv2ray.aar` به چند قطعه باینری نگهداری می‌شود. Gradle پیش از
Build آن را به‌طور خودکار بازسازی و SHA-256 آن را کنترل می‌کند؛ اقدام دستی
لازم نیست.

Build نسخه Release به چهار property با پیشوند `TORNADO_UPLOAD_` نیاز دارد. مقادیر و فایل keystore را فقط در محیط امن CI یا دستگاه سازنده نگه دارید و هرگز commit نکنید.

## نسخه مبنا

- Android package: `com.vpn.tornadovpn`
- Client version code: `1000021`
- Backend release: `2.1.0 IMAGE_CHANNEL`
