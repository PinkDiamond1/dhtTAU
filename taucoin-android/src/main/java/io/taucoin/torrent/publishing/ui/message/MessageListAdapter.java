package io.taucoin.torrent.publishing.ui.message;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import io.taucoin.torrent.publishing.R;
import io.taucoin.torrent.publishing.databinding.ItemMessageListBinding;
import io.taucoin.torrent.publishing.ui.Selectable;

public class MessageListAdapter extends ListAdapter<MessageListItem, MessageListAdapter.ViewHolder>
    implements Selectable<MessageListItem> {
    private ClickListener listener;

    MessageListAdapter(ClickListener listener) {
        super(diffCallback);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ItemMessageListBinding binding = DataBindingUtil.inflate(inflater,
                R.layout.item_message_list,
                parent,
                false);

        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(holder, position);
    }

    @Override
    public int getItemCount() {
        return 10;
    }

    @Override
    public MessageListItem getItemKey(int position) {
        if (position < 0 || position >= getCurrentList().size())
            return null;

        return null;
    }

    @Override
    public int getItemPosition(MessageListItem key) {
        return getCurrentList().indexOf(key);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private ItemMessageListBinding binding;
        /* For selection support */
        private MessageListItem selectionKey;
        private boolean isSelected;

        ViewHolder(ItemMessageListBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ViewHolder holder, int position) {
            holder.binding.tvHash.setText("Transaction hash/123122222222222222222222222222222222");
            holder.binding.tvName.setText("Star War");
            holder.binding.tvTime.setText("17/06/2020 11:42");
        }
    }

    public interface ClickListener {
        void onItemClicked(@NonNull MessageListItem item);

        void onItemPauseClicked(@NonNull MessageListItem item);
    }

    private static final DiffUtil.ItemCallback<MessageListItem> diffCallback = new DiffUtil.ItemCallback<MessageListItem>() {
        @Override
        public boolean areContentsTheSame(@NonNull MessageListItem oldItem, @NonNull MessageListItem newItem) {
            return oldItem.equalsContent(newItem);
        }

        @Override
        public boolean areItemsTheSame(@NonNull MessageListItem oldItem, @NonNull MessageListItem newItem) {
            return oldItem.equals(newItem);
        }
    };
}
