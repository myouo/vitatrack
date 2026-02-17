package com.example.healthanomaly.presentation.events

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.ItemEventBinding
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for anomaly events.
 */
class EventsAdapter(
    private val onItemClick: (AnomalyEvent) -> Unit
) : ListAdapter<AnomalyEvent, EventsAdapter.EventViewHolder>(EventDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)
        
        fun bind(event: AnomalyEvent) {
            binding.tvTimestamp.text = dateFormat.format(Date(event.timestampMs))
            binding.tvType.text = formatType(event.type)
            binding.tvDetails.text = event.details
            binding.tvSeverity.text = "Severity: ${event.severity}/10"
            
            // Set severity color
            val severityColor = when {
                event.severity >= 8 -> R.color.severity_high
                event.severity >= 5 -> R.color.severity_medium
                else -> R.color.severity_low
            }
            binding.tvSeverity.setTextColor(
                binding.root.context.getColor(severityColor)
            )
            
            // Acknowledged indicator
            binding.ivAcknowledged.visibility = if (event.acknowledged) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
            binding.root.setOnClickListener {
                onItemClick(event)
            }
        }
        
        private fun formatType(type: AnomalyType): String {
            return when (type) {
                AnomalyType.HEART_RATE_HIGH -> "High Heart Rate"
                AnomalyType.HEART_RATE_LOW -> "Low Heart Rate"
                AnomalyType.HEART_RATE_SUDDEN_CHANGE -> "HR Change"
                AnomalyType.STEP_FREQ_HIGH -> "High Step Freq"
                AnomalyType.STEP_FREQ_LOW -> "Low Step Freq"
                AnomalyType.GAIT_SUDDEN_CHANGE -> "Gait Change"
                AnomalyType.FALL_DETECTED -> "FALL!"
                AnomalyType.MOTION_INTENSITY_ANOMALY -> "Motion Alert"
            }
        }
    }
    
    private class EventDiffCallback : DiffUtil.ItemCallback<AnomalyEvent>() {
        override fun areItemsTheSame(oldItem: AnomalyEvent, newItem: AnomalyEvent): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: AnomalyEvent, newItem: AnomalyEvent): Boolean {
            return oldItem == newItem
        }
    }
}
