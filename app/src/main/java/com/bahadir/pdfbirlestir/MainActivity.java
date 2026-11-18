package com.bahadir.pdfbirlestir;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final List<PdfAdapter.Item> items = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Deque<Pending> needPassword = new ArrayDeque<>();

    private PdfAdapter adapter;
    private TextView summary, empty, progressText;
    private View progressBox;
    private Button btnAdd, btnMerge;
    private boolean busy;

    /** Sifre bekleyen dosya */
    private static final class Pending {
        final String name; final File file; final long bytes;
        Pending(String n, File f, long b) { name = n; file = f; bytes = b; }
    }

    private final ActivityResultLauncher<String[]> pickPdfs =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) addUris(uris);
            });

    private final ActivityResultLauncher<String> createOutput =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
                if (uri != null) startMerge(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) deleteRecursive(inputsDir());

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.getMenu().add("Ada göre sırala").setOnMenuItemClickListener(m -> { sortByName(); return true; });
        toolbar.getMenu().add("Listeyi temizle").setOnMenuItemClickListener(m -> { clearAll(); return true; });

        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);
        progressBox = findViewById(R.id.progressBox);
        progressText = findViewById(R.id.progressText);
        btnAdd = findViewById(R.id.btnAdd);
        btnMerge = findViewById(R.id.btnMerge);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PdfAdapter(items, this::refresh);
        list.setAdapter(adapter);
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return !adapter.isLocked(); }
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a,
                                            @NonNull RecyclerView.ViewHolder b) {
                adapter.move(a.getBindingAdapterPosition(), b.getBindingAdapterPosition());
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
        });
        helper.attachToRecyclerView(list);
        adapter.setTouchHelper(helper);

        btnAdd.setOnClickListener(v -> pickPdfs.launch(new String[]{"application/pdf"}));
        btnMerge.setOnClickListener(v -> {
            if (items.size() < 2) {
                Toast.makeText(this, "Birleştirmek için en az 2 PDF ekle.", Toast.LENGTH_SHORT).show();
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date());
            createOutput.launch("birlesik_" + stamp + ".pdf");
        });

        refresh();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    /** Baska uygulamadan "Paylas" ile gelen PDF'ler */
    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        intent.setAction(null);
        if (!uris.isEmpty()) addUris(uris);
    }

    // ---------- Dosya ekleme ----------

    private File inputsDir() {
        File d = new File(getCacheDir(), "inputs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File tmpDir() {
        File d = new File(getCacheDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private void addUris(List<Uri> uris) {
        setBusy(true, "Dosyalar okunuyor…");
        worker.execute(() -> {
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                String name = displayName(uri);
                int idx = i + 1;
                runOnUiThread(() -> progressText.setText("Okunuyor " + idx + "/" + uris.size() + ": " + name));
                File copy = new File(inputsDir(), UUID.randomUUID() + ".pdf");
                try {
                    // Dosyanin bayt bayt birebir kopyasi
                    long bytes = copyUriToFile(uri, copy);
                    try {
                        int pages = PdfMerger.countPages(copy, null, tmpDir());
                        PdfAdapter.Item item = new PdfAdapter.Item(name, copy, bytes, pages, null);
                        runOnUiThread(() -> { items.add(item); adapter.notifyItemInserted(items.size() - 1); refresh(); });
                    } catch (InvalidPasswordException pe) {
                        synchronized (needPassword) { needPassword.add(new Pending(name, copy, bytes)); }
                    }
                } catch (Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                    errors.add(name + ": " + friendly(t));
                }
            }
            runOnUiThread(() -> {
                setBusy(false, null);
                if (!errors.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Bazı dosyalar eklenemedi")
                            .setMessage(android.text.TextUtils.join("\n\n", errors))
                            .setPositiveButton("Tamam", null).show();
                }
                askNextPassword(null);
            });
        });
    }

    private void askNextPassword(String error) {
        Pending p;
        synchronized (needPassword) { p = needPassword.peek(); }
        if (p == null) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("PDF şifresi");
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Şifreli PDF")
                .setMessage((error != null ? error + "\n\n" : "") + "“" + p.name + "” şifreyle korunuyor. Açmak için şifreyi gir.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Aç", (d, w) -> {
                    String pw = input.getText().toString();
                    worker.execute(() -> {
                        try {
                            int pages = PdfMerger.countPages(p.file, pw, tmpDir());
                            synchronized (needPassword) { needPassword.poll(); }
                            PdfAdapter.Item item = new PdfAdapter.Item(p.name, p.file, p.bytes, pages, pw);
                            runOnUiThread(() -> {
                                items.add(item); adapter.notifyItemInserted(items.size() - 1); refresh();
                                askNextPassword(null);
                            });
                        } catch (InvalidPasswordException e) {
                            runOnUiThread(() -> askNextPassword("Şifre yanlış."));
                        } catch (Throwable t) {
                            synchronized (needPassword) { needPassword.poll(); }
                            //noinspection ResultOfMethodCallIgnored
                            p.file.delete();
                            runOnUiThread(() -> {
                                Toast.makeText(this, p.name + ": " + friendly(t), Toast.LENGTH_LONG).show();
                                askNextPassword(null);
                            });
                        }
                    });
                })
                .setNegativeButton("Atla", (d, w) -> {
                    synchronized (needPassword) { needPassword.poll(); }
                    //noinspection ResultOfMethodCallIgnored
                    p.file.delete();
                    askNextPassword(null);
                })
                .show();
    }

    // ---------- Birlestirme ----------

    private void startMerge(Uri target) {
        List<PdfMerger.Input> inputs = new ArrayList<>();
        int expectedPages = 0;
        for (PdfAdapter.Item it : items) {
            inputs.add(new PdfMerger.Input(it.name, it.file, it.password));
            expectedPages += it.pages;
        }
        final int total = expectedPages;
        setBusy(true, "Birleştiriliyor…");
        worker.execute(() -> {
            File out = new File(getCacheDir(), "output.pdf");
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            try {
                PdfMerger.Result r = PdfMerger.merge(inputs, out, tmpDir(),
                        msg -> runOnUiThread(() -> progressText.setText(msg)));
                if (r.pageCount != total) {
                    throw new IOException("Sayfa sayısı tutmuyor (" + r.pageCount + " / " + total + ").");
                }
                runOnUiThread(() -> progressText.setText("Seçtiğin konuma yazılıyor…"));
                writeAndVerify(out, target);
                runOnUiThread(() -> { setBusy(false, null); showSuccess(target, r, inputs.size()); });
            } catch (Throwable t) {
                tryDelete(target);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Birleştirme yapılmadı")
                            .setMessage(friendly(t) + "\n\nKaynak dosyaların hiçbiri değiştirilmedi.")
                            .setPositiveButton("Tamam", null).show();
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                deleteRecursive(tmpDir());
            }
        });
    }

    /** Dosyayi hedefe yazar, sonra geri okuyup SHA-256 ile birebir ayni oldugunu kontrol eder. */
    private void writeAndVerify(File src, Uri target) throws IOException {
        OutputStream os;
        try { os = getContentResolver().openOutputStream(target, "wt"); }
        catch (Exception e) { os = getContentResolver().openOutputStream(target, "w"); }
        if (os == null) throw new IOException("Hedef dosya açılamadı.");
        try (InputStream in = new FileInputStream(src); OutputStream o = os) {
            copy(in, o);
            o.flush();
        }
        String want = PdfMerger.sha256(src);
        try (InputStream back = getContentResolver().openInputStream(target)) {
            if (back == null) throw new IOException("Kaydedilen dosya geri okunamadı.");
            String got = PdfMerger.sha256(back);
            if (!want.equals(got)) {
                throw new IOException("Kaydedilen dosya doğrulanamadı (yazma sırasında bozulma). Lütfen tekrar dene.");
            }
        }
    }

    private void showSuccess(Uri target, PdfMerger.Result r, int fileCount) {
        String name = displayName(target);
        new MaterialAlertDialogBuilder(this)
                .setTitle("✓ Birleştirildi")
                .setMessage(fileCount + " dosya, toplam " + r.pageCount + " sayfa birleştirildi.\n\n"
                        + "Dosya: " + name + " (" + PdfAdapter.size(r.outputBytes) + ")\n\n"
                        + "Doğrulandı: her sayfanın içeriği, resimleri ve boyutu kaynak dosyalarla birebir aynı. "
                        + "Kaydedilen dosya da bayt bayt kontrol edildi.")
                .setPositiveButton("Aç", (d, w) -> openPdf(target))
                .setNeutralButton("Paylaş", (d, w) -> sharePdf(target))
                .setNegativeButton("Kapat", null)
                .show();
    }

    private void openPdf(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "PDF'i aç")); }
        catch (Exception e) { Toast.makeText(this, "PDF açacak uygulama bulunamadı.", Toast.LENGTH_LONG).show(); }
    }

    private void sharePdf(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Paylaş"));
    }

    // ---------- Arayuz ----------

    private void refresh() {
        int pages = 0; long bytes = 0;
        for (PdfAdapter.Item it : items) { pages += it.pages; bytes += it.bytes; }
        summary.setText(items.isEmpty() ? "Dosya eklenmedi"
                : items.size() + " dosya · " + pages + " sayfa · " + PdfAdapter.size(bytes));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        btnMerge.setEnabled(!busy && items.size() >= 2);
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        btnAdd.setEnabled(!b);
        adapter.setLocked(b);
        progressBox.setVisibility(b ? View.VISIBLE : View.GONE);
        if (msg != null) progressText.setText(msg);
        if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh();
    }

    private void sortByName() {
        if (busy) return;
        items.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        adapter.notifyItemRangeChanged(0, items.size());
        refresh();
    }

    private void clearAll() {
        if (busy || items.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage("Listedeki tüm dosyalar çıkarılsın mı? (Telefonundaki asıl dosyalar silinmez.)")
                .setPositiveButton("Temizle", (d, w) -> {
                    int n = items.size();
                    items.clear();
                    adapter.notifyItemRangeRemoved(0, n);
                    deleteRecursive(inputsDir());
                    refresh();
                })
                .setNegativeButton("Vazgeç", null).show();
    }

    // ---------- Yardimcilar ----------

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "belge.pdf" : name;
    }

    private long copyUriToFile(Uri uri, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Dosya açılamadı.");
            return copy(in, out);
        }
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        long total = 0; int r;
        while ((r = in.read(buf)) != -1) { out.write(buf, 0, r); total += r; }
        return total;
    }

    private void tryDelete(Uri uri) {
        try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); }
        catch (Exception ignored) {}
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteRecursive(k);
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }

    private static String friendly(Throwable t) {
        if (t instanceof OutOfMemoryError) return "Telefonun belleği bu dosyalar için yetmedi.";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        if (m.contains("Header doesn't contain versioninfo") || m.contains("Missing root object"))
            return "Bu dosya geçerli bir PDF değil ya da bozuk.";
        return m;
    }
}
