# Evakuatsiya yordamchisi — Android APK

Bu papka web-demo'dan alohida native Kotlin Android loyihasidir.

## Android Studio'da ochish

1. Android Studio'ni oching.
2. `Open` ni bosing.
3. Shu loyiha ichidagi `android-app` papkasini tanlang.
4. Gradle sync tugagach, telefonni USB debugging bilan ulang yoki emulator tanlang.
5. `Run` orqali tekshiring. APK uchun `Build > Build APK(s)` ni bosing.

Ilova ichiga hozirgi `assets/evakuatsiya-xaritasi.jpg` va `assets/sirena.mp3` demo fayllari qo‘shilgan.

## Hozirgi bosqich

- xarita rasmini tanlash;
- sirena audiosini tanlash;
- mikrofon, lokatsiya va bildirishnoma ruxsatlarini so‘rash;
- Android foreground service orqali sirenani kuzatish;
- sirena aniqlansa telefon ogohlantirishi va audio chalishi;
- GPS koordinatasini ko‘rsatish.
- plan ustida ko‘k foydalanuvchi nuqtasi va EXIT nuqtasi;
- xarita ustida yo‘lak tugunlari bo‘yicha chiqish marshruti;
- "Meni xaritada belgilash" orqali boshlang‘ich kalibratsiya.
- QR orqali belgilangan plan nuqtasini aniq topish va marshrutni qayta hisoblash.

Hozirgi GPS kalibratsiyasi tashqarida yoki GPS yaxshi ko‘ringan joyda taxminiy ishlaydi. Bino ichida haqiqiy 1:1 joylashuv uchun QR/BLE anchorlar kerak; Android fon cheklovlari sabab kuzatuv foydalanuvchi tugma orqali yoqqan foreground service sifatida ishlaydi.

QR matn formati va joylashtirish ko‘rsatmasi [QR_FORMAT.md](QR_FORMAT.md) faylida.
