# PDF Birleştir (Android)

Birden fazla PDF'i **kayıpsız** birleştirir: sayfalar resme çevrilmez, resimler ve yazılar yeniden sıkıştırılmaz.
Her birleştirmeden sonra uygulama sonucu doğrular (her sayfanın içeriği, resimlerinin ham baytları, boyutu ve dönüşü
kaynakla birebir karşılaştırılır; kaydedilen dosya SHA-256 ile geri okunur). Tek bir fark çıkarsa dosya kaydedilmez.

## APK nasıl alınır (GitHub Actions)
1. GitHub'da yeni bir **private** repo aç (ör. `pdf-birlestir`).
2. Bu klasörün içeriğini (zip'ten çıkarıp) repoya yükle. `.github/workflows/build.yml` de gitmeli.
3. Actions sekmesinde "APK Derle" kendiliğinden çalışır (~5 dk).
4. Bitince repo'nun **Releases** bölümünde `PdfBirlestir.apk` çıkar; telefona indirip kur.

İmza anahtarı `app/pdf-release.jks` repoda durur; güncellemelerde aynı imza kullanılır, kaldır-kur gerekmez.

## Teknik
- Java, minSdk 24, targetSdk 34, AGP 8.5.2, Gradle 8.9
- PDF motoru: PdfBox-Android 2.0.27 (Apache 2.0) — `PDFMergerUtility`
- Büyük dosyalar için RAM yerine geçici dosya kullanılır.
