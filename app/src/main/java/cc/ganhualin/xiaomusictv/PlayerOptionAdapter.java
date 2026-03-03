package cc.ganhualin.xiaomusictv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PlayerOptionAdapter extends RecyclerView.Adapter<PlayerOptionAdapter.ViewHolder> {

    private final List<String> options;
    private final java.util.Set<String> disabledOptions = new java.util.HashSet<>();
    private final OnOptionInteractionListener listener;

    public interface OnOptionInteractionListener {
        void onOptionClick(String option, int position);
        boolean onOptionLeftRight(String option, int direction, int position);
        String getOptionText(String option, boolean hasFocus);
    }

    public PlayerOptionAdapter(List<String> options, OnOptionInteractionListener listener) {
        this.options = options;
        this.listener = listener;
    }

    public void setOptionDisabled(String option, boolean disabled) {
        setOptionDisabledNoNotify(option, disabled);
        notifyDataSetChanged();
    }

    public void setOptionDisabledNoNotify(String option, boolean disabled) {
        if (disabled) {
            disabledOptions.add(option);
        } else {
            disabledOptions.remove(option);
        }
    }

    public void notifyOptionChanged(String option) {
        int index = options.indexOf(option);
        if (index != -1) {
            notifyItemChanged(index);
        }
    }

    public boolean isOptionDisabled(String option) {
        return disabledOptions.contains(option);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_player_option, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String option = options.get(position);
        boolean isDisabled = disabledOptions.contains(option);
        
        holder.tvOptionName.setText(listener.getOptionText(option, holder.itemView.hasFocus()));
        holder.itemView.setEnabled(!isDisabled);
        holder.itemView.setAlpha(isDisabled ? 0.3f : 1.0f);
        holder.itemView.setFocusable(!isDisabled);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null && !isDisabled) {
                listener.onOptionClick(option, position);
            }
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (listener != null) {
                holder.tvOptionName.setText(listener.getOptionText(option, hasFocus));
            }
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && listener != null) {
                    if (listener.onOptionLeftRight(option, -1, position)) {
                        holder.tvOptionName.setText(listener.getOptionText(option, true));
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && listener != null) {
                    if (listener.onOptionLeftRight(option, 1, position)) {
                        holder.tvOptionName.setText(listener.getOptionText(option, true));
                        return true;
                    }
                }
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return options.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvOptionName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOptionName = itemView.findViewById(R.id.tvOptionName);
        }
    }
}
