package com.example.healthanomaly.presentation.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthanomaly.domain.model.AnomalyEvent
import com.example.healthanomaly.domain.repository.AnomalyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Events screen.
 */
@HiltViewModel
class EventsViewModel @Inject constructor(
    private val anomalyRepository: AnomalyRepository
) : ViewModel() {
    
    // Events list
    val events: StateFlow<List<AnomalyEvent>> = anomalyRepository.anomalyEventsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * Acknowledge an event.
     */
    fun acknowledgeEvent(eventId: Long) {
        viewModelScope.launch {
            anomalyRepository.acknowledgeEvent(eventId)
        }
    }
    
    /**
     * Get event details.
     */
    fun getEventDetails(eventId: Long): AnomalyEvent? {
        return events.value.find { it.id == eventId }
    }
}
