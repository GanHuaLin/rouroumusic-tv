package cc.ganhualin.xiaomusictv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import com.google.gson.JsonObject;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.ViewHolder> {

    private List<String> songs = new ArrayList<>();
    private Set<String> favorites = new HashSet<>(); // Added
    private OnItemClickListener listener;
    private OnActionClickListener actionListener;
    private int playingIndex = -1;
    private boolean isPlayerPlaying = false; // Track player state
    private Context context;
    private ApiService apiService;
    private String baseUrl;

    private Map<String, String> coverUrlCache = new HashMap<>(); // Cache for cover URLs
    private Map<String, String> artistCache = new HashMap<>(); // Cache for artist names

    public SongAdapter(Context context) {
        this.context = context;
        this.apiService = RetrofitClient.getClient(context).create(ApiService.class);
        this.baseUrl = RetrofitClient.getClient(context).baseUrl().toString();
    }

    public interface OnItemClickListener {
        void onItemClick(String song, int position);
    }
    
    public interface OnActionClickListener {
        void onPlay(String song, int position);
        void onFullscreen(String song, int position);
        void onFav(String song, int position);
        void onDelete(String song, int position);
    }

    public void setSongs(List<String> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    // Added method to update favorites
    public void setFavorites(List<String> favList, boolean notify) {
        this.favorites.clear();
        if (favList != null) {
            this.favorites.addAll(favList);
        }
        if (notify) notifyDataSetChanged();
    }
    
    // Overload for backward compatibility if needed, or just replace usage
    public void setFavorites(List<String> favList) {
        setFavorites(favList, true);
    }
    
    public void updateItem(int position) {
        notifyItemChanged(position);
    }

    public void setPlayingIndex(int index) {
        int oldIndex = this.playingIndex;
        this.playingIndex = index;
        if (oldIndex != -1) notifyItemChanged(oldIndex);
        if (index != -1) notifyItemChanged(index);
    }

    public int getPlayingIndex() {
        return playingIndex;
    }

    public void setPlayerPlaying(boolean isPlaying) {
        if (this.isPlayerPlaying == isPlaying) return;
        this.isPlayerPlaying = isPlaying;
        if (playingIndex != -1) notifyItemChanged(playingIndex);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }
    
    public void setOnActionClickListener(OnActionClickListener listener) {
        this.actionListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String song = songs.get(position);
        holder.tvIndex.setText(String.valueOf(position + 1));
        holder.tvSongName.setText(song);
        holder.tvArtist.setText(artistCache.getOrDefault(song, "加载中..."));

        // Highlight playing song
        if (position == playingIndex) {
            holder.tvSongName.setTextColor(0xFFFFFFFF); // White (playing)
            
            // Show equalizer instead of number
            holder.tvIndex.setVisibility(View.GONE);
            holder.ivEqualizer.setVisibility(View.VISIBLE);
            
            // Set animation
            holder.ivEqualizer.setImageResource(R.drawable.anim_equalizer);
            if (holder.ivEqualizer.getDrawable() instanceof AnimationDrawable) {
                AnimationDrawable anim = (AnimationDrawable) holder.ivEqualizer.getDrawable();
                if (isPlayerPlaying) {
                     holder.btnItemPlay.setImageResource(R.drawable.ic_pause);
                     anim.start();
                } else {
                     holder.btnItemPlay.setImageResource(R.drawable.ic_play);
                     anim.stop();
                     if (anim.getNumberOfFrames() > 0) anim.selectDrawable(0);
                }
            } else {
                // Fallback for static drawable if used
                 if (isPlayerPlaying) holder.btnItemPlay.setImageResource(R.drawable.ic_pause);
                 else holder.btnItemPlay.setImageResource(R.drawable.ic_play);
            }
            
        } else {
            holder.tvSongName.setTextColor(0xFFF3F4F6); // White
            holder.btnItemPlay.setImageResource(R.drawable.ic_play);
            
            // Show number
            holder.tvIndex.setVisibility(View.VISIBLE);
            holder.ivEqualizer.setVisibility(View.GONE);
        }

        // Set Favorite Icon State
        if (favorites.contains(song)) {
            holder.btnItemFav.setImageResource(R.drawable.ic_favorite);
            holder.btnItemFav.setColorFilter(Color.RED);
        } else {
            holder.btnItemFav.setImageResource(R.drawable.ic_favorite_border);
            holder.btnItemFav.setColorFilter(Color.parseColor("#E5E7EB"));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(song, position);
        });
        
        // Action Buttons
        holder.btnItemPlay.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onPlay(song, position);
        });
        holder.btnItemFullscreen.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onFullscreen(song, position);
        });
        holder.btnItemFav.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onFav(song, position);
        });
        holder.btnItemDelete.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onDelete(song, position);
        });
        
        // Load Cover Image: always reset first to avoid recycled ViewHolder showing stale cover
        Glide.with(context).clear(holder.ivCover);
        holder.ivCover.setImageResource(R.drawable.ic_cover_placeholder);

        if (coverUrlCache.containsKey(song)) {
            String cachedUrl = coverUrlCache.get(song);
            if (cachedUrl != null && !cachedUrl.isEmpty()) {
                loadCover(cachedUrl, holder.ivCover);
            }
            // null/empty means we queried and found no cover — keep placeholder
        } else {
            // Tag the view with the song name to avoid delayed response updating wrong item
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
                                            // Only update if this ViewHolder still shows the same song
                                            if (song.equals(holder.ivCover.getTag())) {
                                                loadCover(finalUrl, holder.ivCover);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    // No info found - ensure we don't show "Loading..." forever
                    if (!artistCache.containsKey(song)) artistCache.put(song, "未知歌手");
                    if (!coverUrlCache.containsKey(song)) coverUrlCache.put(song, "");
                }

                @Override
                public void onFailure(Call<JsonObject> call, Throwable t) {
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
            .centerCrop()
            .into(target);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIndex;
        ImageView ivEqualizer; 
        TextView tvSongName;
        TextView tvArtist;
        ImageView ivCover;
        ImageButton btnItemPlay, btnItemFullscreen, btnItemFav, btnItemDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tvIndex);
            tvIndex.setVisibility(View.VISIBLE); // Default
            ivEqualizer = itemView.findViewById(R.id.ivEqualizer); 
            tvSongName = itemView.findViewById(R.id.tvSongName);
            tvArtist = itemView.findViewById(R.id.tvArtist);
            ivCover = itemView.findViewById(R.id.ivCover);
            btnItemPlay = itemView.findViewById(R.id.btnItemPlay);
            btnItemFullscreen = itemView.findViewById(R.id.btnItemFullscreen);
            btnItemFav = itemView.findViewById(R.id.btnItemFav);
            btnItemDelete = itemView.findViewById(R.id.btnItemDelete);
        }
    }
}
