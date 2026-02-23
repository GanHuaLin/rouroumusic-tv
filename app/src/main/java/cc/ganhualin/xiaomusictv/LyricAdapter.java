package cc.ganhualin.xiaomusictv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class LyricAdapter extends RecyclerView.Adapter<LyricAdapter.LyricViewHolder> {

    public static class LyricLine {
        public long timeMs;
        public String text;
        public String rawLine; // 保存原始行内容用于写回

        public LyricLine(long timeMs, String text) {
            this(timeMs, text, "");
        }

        public LyricLine(long timeMs, String text, String rawLine) {
            this.timeMs = timeMs;
            this.text = text;
            this.rawLine = rawLine;
        }
    }

    private List<LyricLine> lyrics = new ArrayList<>();
    private int currentIndex = -1;

    public void setLyrics(List<LyricLine> lyrics) {
        List<LyricLine> padded = new ArrayList<>();
        if (lyrics != null && !lyrics.isEmpty()) {
            padded.add(new LyricLine(-1, ""));
            padded.addAll(lyrics);
            padded.add(new LyricLine(Long.MAX_VALUE, ""));
        }
        this.lyrics = padded;
        notifyDataSetChanged();
    }

    public void setCurrentIndex(int index) {
        if (this.currentIndex != index) {
            int old = this.currentIndex;
            this.currentIndex = index;
            // Notify a wide range around both the old and new index so all
            // visible rows recalculate their distance-based alpha correctly.
            int rangeStart = Math.max(0, Math.min(old, index) - 3);
            int rangeEnd   = Math.min(lyrics.size() - 1, Math.max(old, index) + 3);
            notifyItemRangeChanged(rangeStart, rangeEnd - rangeStart + 1);
        }
    }

    public List<LyricLine> getLyrics() {
        return lyrics;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lyric, parent, false);
        return new LyricViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        LyricLine line = lyrics.get(position);
        holder.tvLyric.setText(line.text);

        if (position == currentIndex) {
            // 当前行：最亮，粗体大字
            holder.tvLyric.setTextColor(0xFFFFFFFF);
            holder.tvLyric.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f);
            holder.tvLyric.setTypeface(null, android.graphics.Typeface.BOLD);
            holder.tvLyric.setShadowLayer(8, 0, 4, 0x80000000);
            holder.itemView.setAlpha(1.0f);
        } else {
            // 距离越远透明度越低，自然渐出
            int distance = (currentIndex >= 0) ? Math.abs(position - currentIndex) : position + 1;
            float alpha = Math.max(0.08f, 1.0f - distance * 0.35f);
            holder.tvLyric.setTextColor(0xFFFFFFFF);
            holder.tvLyric.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f);
            holder.tvLyric.setTypeface(null, android.graphics.Typeface.NORMAL);
            holder.tvLyric.setShadowLayer(0, 0, 0, 0);
            holder.itemView.setAlpha(alpha);
        }
    }

    @Override
    public int getItemCount() {
        return lyrics.size();
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        TextView tvLyric;

        public LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLyric = itemView.findViewById(R.id.tvLyric);
        }
    }
}
