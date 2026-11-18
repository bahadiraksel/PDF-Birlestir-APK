package com.bahadir.pdfbirlestir;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PdfAdapter extends RecyclerView.Adapter<PdfAdapter.VH> {

    public static final class Item {
        public final String name;
        public final File file;
        public final long bytes;
        public final int pages;
        public final String password;
        public Item(String name, File file, long bytes, int pages, String password) {
            this.name = name; this.file = file; this.bytes = bytes; this.pages = pages; this.password = password;
        }
    }

    public interface Listener { void onListChanged(); }

    private final List<Item> items;
    private final Listener listener;
    private ItemTouchHelper touchHelper;
    private boolean locked;

    public PdfAdapter(List<Item> items, Listener listener) { this.items = items; this.listener = listener; }

    public void setTouchHelper(ItemTouchHelper h) { touchHelper = h; }
    @SuppressLint("NotifyDataSetChanged")
    public void setLocked(boolean l) { locked = l; notifyDataSetChanged(); }
    public boolean isLocked() { return locked; }

    public void move(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        Collections.swap(items, from, to);
        notifyItemMoved(from, to);
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
        listener.onListChanged();
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf, parent, false));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item it = items.get(position);
        h.index.setText(String.valueOf(position + 1));
        h.name.setText(it.name);
        h.info.setText(it.pages + " sayfa · " + size(it.bytes) + (it.password != null ? " · şifreli" : ""));
        h.up.setEnabled(!locked && position > 0);
        h.down.setEnabled(!locked && position < items.size() - 1);
        h.remove.setEnabled(!locked);
        h.up.setOnClickListener(v -> { int p = h.getBindingAdapterPosition(); move(p, p - 1); });
        h.down.setOnClickListener(v -> { int p = h.getBindingAdapterPosition(); move(p, p + 1); });
        h.remove.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p < 0) return;
            Item removed = items.remove(p);
            //noinspection ResultOfMethodCallIgnored
            removed.file.delete();
            notifyItemRemoved(p);
            notifyItemRangeChanged(p, items.size() - p);
            listener.onListChanged();
        });
        h.handle.setOnTouchListener((v, e) -> {
            if (!locked && e.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                touchHelper.startDrag(h);
            }
            return false;
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView handle, index, name, info;
        final Button up, down, remove;
        VH(View v) {
            super(v);
            handle = v.findViewById(R.id.handle);
            index = v.findViewById(R.id.index);
            name = v.findViewById(R.id.name);
            info = v.findViewById(R.id.info);
            up = v.findViewById(R.id.up);
            down = v.findViewById(R.id.down);
            remove = v.findViewById(R.id.remove);
        }
    }
}
