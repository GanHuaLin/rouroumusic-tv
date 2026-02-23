package cc.ganhualin.xiaomusictv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

    private List<String> playlists = new ArrayList<>();
    private Map<String, List<String>> data;
    private OnItemClickListener listener;
    private int selectedPosition = 0;

    public interface OnItemClickListener {
        void onItemClick(String playlistName);
    }

    public void setData(Map<String, List<String>> data) {
        this.data = data;
        this.playlists = new ArrayList<>(data.keySet());
        notifyDataSetChanged();
    }
    
    public void setSelection(int position) {
        int oldPos = selectedPosition;
        selectedPosition = position;
        notifyItemChanged(oldPos);
        notifyItemChanged(selectedPosition);
    }

    public void notifyPlaylistUpdated(String playlistName, List<String> newSongs) {
        if (data != null) {
            data.put(playlistName, newSongs);
            int index = playlists.indexOf(playlistName);
            if (index != -1) {
                notifyItemChanged(index);
            } else {
                // New playlist (e.g. first favorite), add to top or bottom? 
                // Let's add to top for visibility or bottom? 
                // "我的收藏" usually important. Let's add to index 0 or 1?
                // For simplicity, add to end.
                playlists.add(playlistName);
                notifyItemInserted(playlists.size() - 1);
            }
        }
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String name = playlists.get(position);
        List<String> songs = data.get(name);
        holder.tvName.setText(name.equals("All Songs") ? "所有歌曲" : name);
        holder.tvCount.setText(songs != null ? String.valueOf(songs.size()) : "0");

        holder.itemView.setOnClickListener(v -> {
            int oldPos = selectedPosition;
            int newPos = holder.getAdapterPosition();
            if (newPos == RecyclerView.NO_POSITION) return;
            
            selectedPosition = newPos;
            if (oldPos != selectedPosition) {
                notifyItemChanged(oldPos);
                notifyItemChanged(selectedPosition);
            }
            if (listener != null) listener.onItemClick(name);
        });
        
        // Simple selection visual
        holder.itemView.setSelected(selectedPosition == position);
        if (selectedPosition == position) {
            holder.tvName.setTextColor(0xFFFFFFFF); // White
        } else {
            holder.tvName.setTextColor(0xFF9CA3AF); // Gray
        }
        
        // Strict Focus Trapping for TV Remote
        if (position == playlists.size() - 1) {
            holder.itemView.setNextFocusDownId(R.id.btnOpenPlayer);
        } else {
            holder.itemView.setNextFocusDownId(View.NO_ID); // Default Recycler behavior
        }
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvCount;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvCount = itemView.findViewById(R.id.tvCount);
        }
    }
}
