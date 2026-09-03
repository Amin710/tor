# HEV JNI binary provenance

The `libhev-socks5-tunnel.so` files in the ABI directories were extracted from
the official `2dust/v2rayNG` 2.2.6 GitHub release APKs dated 2026-07-05.

- Release: `https://github.com/2dust/v2rayNG/releases/tag/2.2.6`
- Upstream signing identity: `2dust <2dust-noreply@github.com>`
- GPG primary fingerprint: `76945E9F3E9A168F8070F195805D661C134DFAF68903C199463C31E5AE903AE0`
- arm64-v8a SHA-256: `b80d639ba57fb557e05e17a7ecde81eca791cd1af89679adeca705de920287a4`
- armeabi-v7a SHA-256: `b64e1be8085254e9925c02df64906df0248fd4e5e8d0c3458584c35afe11b6f1`

Both detached APK signatures were verified before extraction. The JNI library
registers against `com/v2ray/ang/service/TProxyService`, matching this project.
