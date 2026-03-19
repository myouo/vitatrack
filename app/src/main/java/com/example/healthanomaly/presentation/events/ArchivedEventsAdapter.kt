package com.example.healthanomaly.presentation.events

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.ItemEventCompactBinding
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact RecyclerView adapter for archived anomaly events.
 * Shows abbreviated information without click-to-detail.
 */
class ArchivedEventsAdapter :
    ListAdapter<AnomalyEvent, ArchivedEventsAdapter.CompactViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompactViewHolder {
        val binding = ItemEventCompactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CompactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CompactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CompactViewHolder(
        private val binding: ItemEventCompactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.US)

        fun bind(event: AnomalyEvent) {
            binding.tvTimestamp.text = dateFormat.format(Date(event.timestampMs))
            binding.tvType.text = formatType(event.type)
            binding.tvDetails.text = event.details

            val (label, color) = when {
                event.severity >= 8 -> "高" to R.color.severity_high
                event.severity >= 5 -> "中" to R.color.severity_medium
                else -> "低" to R.color.severity_low
            }
            binding.tvSeverity.text = label
            binding.tvSeverity.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.white)
            )
            binding.tvSeverity.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(binding.root.context, color)
            )
        }

        private fun formatType(type: AnomalyType): String {
            val ctx = binding.root.context
            return when (type) {
                AnomalyType.HEART_RATE_HIGH -> ctx.getString(R.string.anomaly_hr_high)
                AnomalyType.HEART_RATE_LOW -> ctx.getString(R.string.anomaly_hr_low)
                AnomalyType.HEART_RATE_SUDDEN_CHANGE -> ctx.getString(R.string.anomaly_hr_change)
                AnomalyType.STEP_FREQ_HIGH -> ctx.getString(R.string.anomaly_step_high)
                AnomalyType.STEP_FREQ_LOW -> ctx.getString(R.string.anomaly_step_low)
                AnomalyType.GAIT_SUDDEN_CHANGE -> ctx.getString(R.string.anomaly_gait_change)
                AnomalyType.FALL_DETECTED -> ctx.getString(R.string.anomaly_fall)
                AnomalyType.MOTION_INTENSITY_ANOMALY -> ctx.getString(R.string.anomaly_motion)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AnomalyEvent>() {
        override fun areItemsTheSame(a: AnomalyEvent, b: AnomalyEvent) = a.id == b.id
        override fun areContentsTheSame(a: AnomalyEvent, b: AnomalyEvent) = a == b
    }
}
