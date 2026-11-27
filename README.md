# Kıvrık Yılan — Native Android (Kotlin)

WebView yok. Tüm oyun saf Android framework ile yazıldı: Kotlin + Canvas (çizim), AudioTrack (ses), Vibrator (titreşim), SharedPreferences (kayıt). Harici bağımlılık yok.

## Çalıştırma
1. Android Studio (Koala veya yeni) ile bu klasörü aç (`File > Open`).
2. Gradle senkronizasyonu bitince bir telefon/emülatör seçip Run.
3. APK için: `Build > Build APK(s)`.

Gereksinimler: JDK 17, Android SDK 34, minSdk 24 (Android 7.0+). Ekran dikey sabit.

## Dosyalar
- `Skills.kt` — yetenek ağacı verisi ve yerleşimi
- `Game.kt` — oyun motoru (tüm kurallar)
- `Board.kt` — tahta, elma, yılan çizimi
- `GameView.kt` — arayüz, yetenek ağacı ekranı, dokunma
- `Glyphs.kt` / `Ink.kt` — çizgi film ikonları ve çizim yardımcıları
- `Sfx.kt` — sentezlenen ses efektleri
- `State.kt` — kayıt/yükleme
