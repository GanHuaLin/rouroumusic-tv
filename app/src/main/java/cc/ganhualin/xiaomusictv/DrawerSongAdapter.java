package cc.ganhualin.xiaomusictv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import com.google.gson.JsonObject;

public class DrawerSongAdapter extends RecyclerView.Adapter<DrawerSongAdapter.ViewHolder> {

    private List<String> songs = new ArrayList<>();
    private OnItemClickListener listener;
    private int playingIndex = -1;
    private boolean isPlayerPlaying = false;
    private Context context;
    private ApiService apiService;
    private String baseUrl;

    private Map<String, String> coverUrlCache = new HashMap<>();
    private Map<String, String> artistCache = new HashMap<>();

    public DrawerSongAdapter(Context context) {
        this.context = context;
        this.apiService = RetrofitClient.getClient(context).create(ApiService.class);
        this.baseUrl = RetrofitClient.getClient(context).baseUrl().toString();
    }

    public interface OnItemClickListener {
        void onItemClick(String song, int position);
    }

    public void setSongs(List<String> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    public void setPlayingIndex(int index) {
        int oldIndex = this.playingIndex;
        this.playingIndex = index;
        if (oldIndex != -1) notifyItemChanged(oldIndex);
        if (index != -1) notifyItemChanged(index);
    }

    public void setPlayerPlaying(boolean isPlaying) {
        if (this.isPlayerPlaying == isPlaying) return;
        this.isPlayerPlaying = isPlaying;
        if (playingIndex != -1) notifyItemChanged(playingIndex);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_drawer_song, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String song = songs.get(position);
        holder.tvTitle.setText(song);
         holder.tvArtist.setText(artistCache.containsKey(song) ? artistCache.get(song) : "加载中...");

        boolean isPlaying = (position == playingIndex);
        
        // Explicitly handle focus visual changes with animation
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                holder.tvTitle.setTextColor(Color.BLACK);
                holder.tvArtist.setTextColor(Color.parseColor("#666666"));
                holder.ivArrow.setAlpha(1.0f);
                v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(200).start();
                v.setElevation(20f);
            } else {
                if (isPlaying) {
                    holder.tvTitle.setTextColor(Color.parseColor("#26a2ff"));
                } else {
                    holder.tvTitle.setTextColor(Color.WHITE);
                }
                holder.tvArtist.setTextColor(Color.parseColor("#B3FFFFFF"));
                holder.ivArrow.setAlpha(0f);
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
                v.setElevation(4f);
            }
        });

        // Initial state sync
        if (holder.itemView.isFocused()) {
            holder.tvTitle.setTextColor(Color.BLACK);
            holder.tvArtist.setTextColor(Color.parseColor("#666666"));
            holder.ivArrow.setAlpha(1.0f);
            holder.itemView.setScaleX(1.05f);
            holder.itemView.setScaleY(1.05f);
        } else {
            if (isPlaying) {
                holder.tvTitle.setTextColor(Color.parseColor("#26a2ff"));
            } else {
                holder.tvTitle.setTextColor(Color.WHITE);
            }
            holder.tvArtist.setTextColor(Color.parseColor("#B3FFFFFF"));
            holder.ivArrow.setAlpha(0f);
            holder.itemView.setScaleX(1.0f);
            holder.itemView.setScaleY(1.0f);
        }

        // Show equalizer if playing
        if (isPlaying) {
            holder.ivEqualizer.setVisibility(View.VISIBLE);
            if (holder.viewPlayingOverlay != null) holder.viewPlayingOverlay.setVisibility(View.VISIBLE);
            if (isPlayerPlaying) {
                holder.ivEqualizer.setImageResource(R.drawable.anim_equalizer);
                if (holder.ivEqualizer.getDrawable() instanceof AnimationDrawable) {
                    ((AnimationDrawable) holder.ivEqualizer.getDrawable()).start();
                }
            } else {
                holder.ivEqualizer.setImageResource(R.drawable.ic_equalizer);
            }
        } else {
            holder.ivEqualizer.setVisibility(View.GONE);
            if (holder.viewPlayingOverlay != null) holder.viewPlayingOverlay.setVisibility(View.GONE);
        }

        holder.itemView.setFocusable(true);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(song, position);
        });

        // Load cover: always reset first to avoid recycled ViewHolder showing stale cover
        Glide.with(context).clear(holder.ivCover);
        holder.ivCover.setImageResource(R.drawable.ic_cover_placeholder);

        if (coverUrlCache.containsKey(song)) {
            String cachedUrl = coverUrlCache.get(song);
            if (cachedUrl != null && !cachedUrl.isEmpty()) {
                loadCover(cachedUrl, holder.ivCover);
            }
        } else {
            holder.ivCover.setTag(song);
            apiService.getMusicInfo(song, true).enqueue(new Callback<JsonObject>() {
                @Override
                public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JsonObject json = response.body();
                            if (json.has("tags")) {
                                JsonObject tags = json.getAsJsonObject("tags");
                                if (tags != null) {
                                    String tagArtist = tags.has("artist") && !tags.get("artist").isJsonNull() ? tags.get("artist").getAsString() : null;
                                    String lyrics = tags.has("lyrics") && !tags.get("lyrics").isJsonNull() ? tags.get("lyrics").getAsString() : null;
                                    
                                    String finalArtist = tagArtist;
                                    String lyricArtist = extractArtistFromLyrics(lyrics);
                                    if (lyricArtist != null && !lyricArtist.isEmpty()) {
                                        finalArtist = lyricArtist;
                                    } else if (finalArtist == null || finalArtist.isEmpty() || finalArtist.equals("Various Artists")) {
                                        finalArtist = "未知歌手";
                                    }

                                    artistCache.put(song, finalArtist);
                                    if (song.equals(holder.ivCover.getTag())) {
                                        holder.tvArtist.setText(finalArtist);
                                    }

                                    if (tags.has("picture")) {
                                        String pic = tags.get("picture").getAsString();
                                        if (pic != null && !pic.isEmpty()) {
                                            String finalUrl = pic;
                                            if (!pic.startsWith("http")) {
                                                finalUrl = baseUrl + (pic.startsWith("/") ? pic.substring(1) : pic);
                                            }
                                            coverUrlCache.put(song, finalUrl);
                                            if (song.equals(holder.ivCover.getTag())) {
                                                loadCover(finalUrl, holder.ivCover);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                    if (!artistCache.containsKey(song)) artistCache.put(song, "未知歌手");
                    coverUrlCache.put(song, "");
                }
                @Override public void onFailure(Call<JsonObject> call, Throwable t) {
                    if (song.equals(holder.ivCover.getTag())) {
                        holder.tvArtist.setText("加载失败");
                    }
                }
            });
        }
    }

    private String extractArtistFromLyrics(String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return null;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(lyrics))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase().startsWith("[ar:")) {
                    int closing = line.indexOf(']');
                    if (closing > 4) {
                        return line.substring(4, closing).trim();
                    }
                }
            }
        } catch (java.io.IOException e) {
            // ignore
        }
        return null;
    }

    private void loadCover(String url, ImageView target) {
        Glide.with(context)
            .load(url)
            .placeholder(R.drawable.ic_cover_placeholder)
            .error(R.drawable.ic_cover_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transform(new com.bumptech.glide.load.resource.bitmap.RoundedCorners(8))
            .into(target);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvTitle;
        TextView tvArtist;
        ImageView ivEqualizer;
        ImageView ivArrow;
        View viewPlayingOverlay;

        ViewHolder(View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.id_cover_under_overlay);
            tvTitle = itemView.findViewById(R.id.tvDrawerItemTitle);
            tvArtist = itemView.findViewById(R.id.tvDrawerItemArtist);
            ivEqualizer = itemView.findViewById(R.id.ivDrawerPlaying);
            ivArrow = itemView.findViewById(R.id.ivDrawerArrow);
            viewPlayingOverlay = itemView.findViewById(R.id.viewDrawerPlayingOverlay);
        }
    }
}
