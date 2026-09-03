# Tornado Bootstrap Security Protocol v2

این فایل قرارداد byte-level مشترک کلاینت Android و بک‌اند است. همه رشته‌ها UTF-8،
جداکننده فقط LF و انتهای ورودی امضا بدون LF است. همه Base64ها standard و padded
هستند؛ `sessionId` تنها استثنا و URL-safe بدون padding است.

## مسیرها

- `POST /v1/android/session`
- `POST /v1/android/bootstrap/secure`
- `POST /v1/android/bootstrap` فقط هنگام `LEGACY_BOOTSTRAP_ENABLED=true`

## درخواست نشست

```json
{
  "protocolVersion": 2,
  "installationId": "...",
  "packageName": "com.vpn.tornadovpn",
  "versionCode": 1000020,
  "versionName": "1000020",
  "deviceCredential": "",
  "clientEncryptionPublicKey": "base64 DER RSA-2048 SPKI",
  "clientSigningPublicKey": "base64 DER P-256 SPKI"
}
```

ورودی SHA-256 با خروجی lowercase hex:

```text
TORNADO-SESSION-REQUEST-V2
2
installationId
packageName
versionCode
versionName
deviceCredential
clientEncryptionPublicKey
clientSigningPublicKey
```

## پاسخ نشست

```json
{
  "protocolVersion": 2,
  "sessionId": "32 random bytes as URL-safe token",
  "challengeNonce": "32 random bytes as Base64",
  "requestHash": "lowercase sha256 hex",
  "clientEncryptionKeySha256": "sha256 hex of RSA DER",
  "clientSigningKeySha256": "sha256 hex of EC DER",
  "issuedAtEpochSeconds": 0,
  "expiresAtEpochSeconds": 0,
  "attestationRequired": true,
  "keyId": "tornado-signing-2026-01",
  "signature": "Base64 DER ECDSA P-256/SHA-256"
}
```

ورودی امضای بک‌اند:

```text
TORNADO-SESSION-RESPONSE-V2
2
sessionId
challengeNonce
requestHash
clientEncryptionKeySha256
clientSigningKeySha256
issuedAtEpochSeconds
expiresAtEpochSeconds
1-or-0
keyId
```

## درخواست Bootstrap امن

```json
{
  "protocolVersion": 2,
  "sessionId": "...",
  "clientTimestampEpochSeconds": 0,
  "requestSignature": "Base64 DER ECDSA signature"
}
```

ورودی امضای کلید P-256 داخل Android Keystore:

```text
TORNADO-BOOTSTRAP-REQUEST-V2
2
sessionId
challengeNonce
requestHash
clientTimestampEpochSeconds
```

اگر `attestationRequired=true` باشد کلاینت limited-use token را در هدر
`X-Firebase-AppCheck` می‌فرستد. اگر دریافت token شکست خورد، درخواست امضاشده بدون
هدر ارسال می‌شود تا سرور grace دستگاه قبلاً تأییدشده را بررسی و علت واقعی را ثبت
کند. دستگاه جدید بدون token معتبر fail-closed است.

## Enrollment

- PostgreSQL فقط HMAC credential، کلید عمومی امضای دستگاه و زمان‌های attestation
  را نگهداری می‌کند.
- Redis نیز credential خام نگه نمی‌دارد؛ فقط HMAC و ciphertext کوتاه‌عمر AES-GCM
  با AAD متصل به `sessionId` و `installationHash` در session قرار می‌گیرد.
- نصب جدید، credential نامعتبر، تغییر کلید، revoke یا پایان TTL نیازمند App Check است.
- TTL پیش‌فرض ۳۰ روز و grace فقط برای همان credential/key و حداکثر ۷۲ ساعت است.
- App ID باید دقیقاً `1:596541536411:android:dac16d9e842a9a99f82e3e` باشد.
- hash هر limited-use token با Redis `SET NX EX` و هر session با `GETDEL` فقط یک
  بار مصرف می‌شود.

## Secure envelope

کلید content تصادفی AES-256 با RSA-OAEP SHA-256 و **MGF1-SHA1** wrap می‌شود.
payload با AES-256-GCM و IV دوازده‌بایتی رمز می‌شود.

```json
{
  "version": 2,
  "keyId": "...",
  "sessionId": "...",
  "requestHash": "...",
  "issuedAtEpochSeconds": 0,
  "expiresAtEpochSeconds": 0,
  "requestNonce": "challengeNonce",
  "wrappedKey": "base64",
  "iv": "base64",
  "ciphertext": "base64 ciphertext+GCM tag",
  "signature": "base64 DER ECDSA signature"
}
```

هدر GCM/AAD:

```text
TORNADO-BOOTSTRAP-ENVELOPE-V2
2
keyId
sessionId
requestHash
issuedAtEpochSeconds
expiresAtEpochSeconds
requestNonce
wrappedKey
iv
```

ورودی امضای پاسخ دقیقاً `headerBytes + LF + ciphertextBase64` است. payload داخلی
فیلد زیر را اضافه می‌کند:

```json
{
  "security": {
    "protocolVersion": 2,
    "deviceCredential": "opaque URL-safe credential",
    "attestationExpiresAtEpochSeconds": 0
  }
}
```

credential فقط بعد از verify امضای بک‌اند، decrypt موفق GCM و اعتبارسنجی کامل
payload ذخیره می‌شود. TLS استاندارد CA-valid است و leaf pin استفاده نمی‌شود.

این طرح harvesting ساده، replay، credential کپی‌شده بدون Keystore private key و
جعل پاسخ را متوقف می‌کند؛ روی دستگاه root/hooked پس از decrypt حفاظت مطلق ممکن نیست.
