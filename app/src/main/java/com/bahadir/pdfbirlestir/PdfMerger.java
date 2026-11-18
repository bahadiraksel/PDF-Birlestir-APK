package com.bahadir.pdfbirlestir;

import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kayipsiz PDF birlestirme.
 *
 * Sayfalar render edilmez (resme cevrilmez). PDFBox, her sayfanin nesnelerini
 * (icerik akisi, resimler, fontlar, notlar, form alanlari) kaynak dosyadaki
 * sikistirilmis halleriyle hedef dosyaya kopyalar. Hicbir resim yeniden sikistirilmaz.
 *
 * Ustune bir de dogrulama yapilir: birlesik dosya yeniden acilir ve her sayfanin
 * parmak izi (icerik akisi, resimlerin ham baytlari, sayfa boyutu, donus, not sayisi)
 * kaynak sayfalarla tek tek karsilastirilir. Tek bir fark bile varsa islem hata verir.
 */
public final class PdfMerger {

    public interface Progress { void update(String message); }

    public static final class Input {
        public final String name;
        public final File file;
        public final String password;
        public Input(String name, File file, String password) {
            this.name = name; this.file = file; this.password = password;
        }
    }

    public static final class Result {
        public final int pageCount;
        public final long outputBytes;
        Result(int pageCount, long outputBytes) { this.pageCount = pageCount; this.outputBytes = outputBytes; }
    }

    private PdfMerger() {}

    private static MemoryUsageSetting mem(File tmpDir) {
        // Buyuk dosyalarda bellek tasmasin diye gecici dosya kullan
        MemoryUsageSetting s = MemoryUsageSetting.setupTempFileOnly();
        s.setTempDir(tmpDir);
        return s;
    }

    /** Dosyayi acip sayfa sayisini dondurur. Sifre yanlissa InvalidPasswordException firlatir. */
    public static int countPages(File file, String password, File tmpDir) throws IOException {
        PDDocument doc = PDDocument.load(file, password == null ? "" : password, mem(tmpDir));
        try { return doc.getNumberOfPages(); } finally { doc.close(); }
    }

    public static Result merge(List<Input> inputs, File out, File tmpDir, Progress progress) throws Exception {
        List<PDDocument> opened = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        PDDocument dest = new PDDocument(mem(tmpDir));
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (int i = 0; i < inputs.size(); i++) {
                Input in = inputs.get(i);
                progress.update(String.format(Locale.ROOT, "Ekleniyor %d/%d: %s", i + 1, inputs.size(), in.name));
                PDDocument src = PDDocument.load(in.file, in.password == null ? "" : in.password, mem(tmpDir));
                opened.add(src);
                if (src.isEncrypted()) {
                    // Icerik zaten cozuldu; birlesik dosyaya sifre tasinmaz. Icerik degismez.
                    src.setAllSecurityToBeRemoved(true);
                }
                for (PDPage p : src.getPages()) expected.add(fingerprint(p));
                merger.appendDocument(dest, src);
            }
            progress.update("Kaydediliyor…");
            dest.save(out);
        } finally {
            try { dest.close(); } catch (IOException ignored) {}
            for (PDDocument d : opened) { try { d.close(); } catch (IOException ignored) {} }
        }

        progress.update("Doğrulanıyor: sayfalar tek tek karşılaştırılıyor…");
        PDDocument check = PDDocument.load(out, mem(tmpDir));
        try {
            int n = check.getNumberOfPages();
            if (n != expected.size()) {
                throw new IOException("Doğrulama başarısız: " + expected.size()
                        + " sayfa bekleniyordu, birleşik dosyada " + n + " sayfa var.");
            }
            int i = 0;
            for (PDPage p : check.getPages()) {
                String fp = fingerprint(p);
                if (!fp.equals(expected.get(i))) {
                    throw new IOException("Doğrulama başarısız: " + (i + 1)
                            + ". sayfa kaynak dosyadakiyle birebir aynı değil. Dosya kaydedilmedi.");
                }
                i++;
                if (i % 25 == 0) progress.update("Doğrulanıyor: " + i + "/" + n + " sayfa");
            }
            return new Result(n, out.length());
        } finally {
            check.close();
        }
    }

    /** Bir sayfanin icerigini temsil eden parmak izi. */
    static String fingerprint(PDPage page) throws IOException {
        MessageDigest md = sha256();
        PDRectangle mb = page.getMediaBox();
        PDRectangle cb = page.getCropBox();
        md.update(String.format(Locale.ROOT, "mb%.3f,%.3f,%.3f,%.3f|cb%.3f,%.3f,%.3f,%.3f|rot%d|",
                mb.getLowerLeftX(), mb.getLowerLeftY(), mb.getUpperRightX(), mb.getUpperRightY(),
                cb.getLowerLeftX(), cb.getLowerLeftY(), cb.getUpperRightX(), cb.getUpperRightY(),
                page.getRotation()).getBytes());
        // Sayfa icerik akisi (yazilar, cizimler)
        InputStream c = page.getContents();
        if (c != null) { try { digest(md, c); } finally { c.close(); } }
        // Notlar / baglantilar / form alanlari
        md.update(("annots" + page.getAnnotations().size() + "|").getBytes());
        // Resimler ve form nesneleri: sikistirilmis ham baytlar birebir ayni olmali
        PDResources res = page.getResources();
        if (res != null) {
            COSBase xo = res.getCOSObject().getDictionaryObject(COSName.XOBJECT);
            if (xo instanceof COSDictionary) {
                COSDictionary xd = (COSDictionary) xo;
                List<COSName> names = new ArrayList<>(xd.keySet());
                names.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (COSName name : names) {
                    md.update(("x:" + name.getName() + "|").getBytes());
                    COSBase obj = xd.getDictionaryObject(name);
                    if (obj instanceof COSStream) {
                        InputStream raw = ((COSStream) obj).createRawInputStream();
                        try { digest(md, raw); } finally { raw.close(); }
                    }
                }
            }
            COSBase fonts = res.getCOSObject().getDictionaryObject(COSName.FONT);
            if (fonts instanceof COSDictionary) {
                List<COSName> names = new ArrayList<>(((COSDictionary) fonts).keySet());
                names.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (COSName name : names) md.update(("f:" + name.getName() + "|").getBytes());
            }
        }
        return hex(md.digest());
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = new FileInputStream(f)) { digest(md, in); }
        return hex(md.digest());
    }

    public static String sha256(InputStream in) throws IOException {
        MessageDigest md = sha256();
        digest(md, in);
        return hex(md.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void digest(MessageDigest md, InputStream in) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
