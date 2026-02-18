package com.example.healthanomaly.presentation.events

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.DialogEventDetailBinding
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.model.AnomalyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog showing detailed information about an anomaly event.
 */
class EventDetailDialog : DialogFragment() {
    
    private var _binding: DialogEventDetailBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: EventsViewModel by viewModels(ownerProducer = { requireParentFragment() })
    
    private var eventId: Long = -1
    
    companion object {
        private const val ARG_EVENT_ID = "event_id"
        
        fun newInstance(event: AnomalyEvent): EventDetailDialog {
            return EventDetailDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EVENT_ID, event.id)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventId = arguments?.getLong(ARG_EVENT_ID) ?: -1
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEventDetailBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val event = viewModel.getEventDetails(eventId)
        event?.let { displayEvent(it) }
        
        binding.btnAcknowledge.setOnClickListener {
            viewModel.acknowledgeEvent(eventId)
            dismiss()
        }
        
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }
    
    private fun displayEvent(event: AnomalyEvent) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        
        binding.tvTimestamp.text = dateFormat.format(Date(event.timestampMs))
        binding.tvType.text = formatType(event.type)
        binding.tvSeverity.text = "Severity: ${event.severity}/10"
        binding.tvDetails.text = event.details
        binding.tvAcknowledged.text = if (event.acknowledged) "Yes" else "No"
    }
    
    private fun formatType(type: AnomalyType): String {
        return when (type) {
            AnomalyType.HEART_RATE_HIGH -> "High Heart Rate"
            AnomalyType.HEART_RATE_LOW -> "Low Heart Rate"
            AnomalyType.HEART_RATE_SUDDEN_CHANGE -> "Sudden Heart Rate Change"
            AnomalyType.STEP_FREQ_HIGH -> "High Step Frequency"
            AnomalyType.STEP_FREQ_LOW -> "Low Step Frequency"
            AnomalyType.FALL_DETECTED -> "Fall Detected"
            AnomalyType.ACTIVITY_CHANGE -> "Activity Change"
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
