package com.violet.wifidogauthenticator.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.violet.wifidogauthenticator.R;
import com.violet.wifidogauthenticator.models.LogModel;

import java.util.ArrayList;
import java.util.List;

/**
 * FIX: All public mutating methods (addLog, setLogs, clear) are now
 * synchronized so concurrent calls from the Logger's mainHandler.post()
 * and UI button clicks cannot leave the adapter in an inconsistent state,
 * which would cause "inconsistency detected" RecyclerView crashes.
 *
 * FIX: onBindViewHolder now guards against null timestamp / message in
 * LogModel so a partially-constructed model cannot cause an NPE.
 *
 * FIX: getItemCount is also synchronized to return a consistent size.
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {

    private final List<LogModel> logs = new ArrayList<>();
    private final Context        context;

    public LogAdapter(Context context) {
        this.context = context;
    }

    /** Called on the main thread from Logger's mainHandler.post – safe. */
    public synchronized void addLog(LogModel log) {
        if (log == null) return;  // FIX: null guard
        logs.add(log);
        notifyItemInserted(logs.size() - 1);
    }

    public synchronized void setLogs(List<LogModel> newLogs) {
        logs.clear();
        if (newLogs != null) logs.addAll(newLogs);  // FIX: null guard
        notifyDataSetChanged();
    }

    public synchronized void clear() {
        logs.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(v);
    }

    @Override
    public synchronized void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        // FIX: Bounds check – RecyclerView can call bind with a stale position
        // after a concurrent clear(), which would throw IndexOutOfBoundsException.
        if (position < 0 || position >= logs.size()) return;

        LogModel log = logs.get(position);

        // FIX: Guard null fields in LogModel before calling setText
        holder.tvTime.setText(log.getTimestamp() != null ? log.getTimestamp() : "");
        holder.tvMessage.setText(log.getMessage() != null ? log.getMessage() : "");

        int color;
        switch (log.getType()) {
            case LogModel.TYPE_SUCCESS:
                color = 0xFF4CAF50; // green
                break;
            case LogModel.TYPE_ERROR:
                color = 0xFFF44336; // red
                break;
            case LogModel.TYPE_WARN:
                color = 0xFFFF9800; // orange
                break;
            default:
                color = 0xFFB0BEC5; // grey-blue
        }
        holder.tvMessage.setTextColor(color);
        holder.tvTime.setTextColor(0xFF607D8B);
    }

    @Override
    public synchronized int getItemCount() { return logs.size(); }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView tvTime, tvMessage;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime    = itemView.findViewById(R.id.tv_log_time);
            tvMessage = itemView.findViewById(R.id.tv_log_message);
        }
    }
}
