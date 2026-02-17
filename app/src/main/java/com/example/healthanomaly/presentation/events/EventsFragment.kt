package com.example.healthanomaly.presentation.events

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.healthanomaly.databinding.FragmentEventsBinding
import com.example.healthanomaly.domain.model.AnomalyEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Events fragment showing anomaly event list.
 */
@AndroidEntryPoint
class EventsFragment : Fragment() {
    
    private var _binding: FragmentEventsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: EventsViewModel by viewModels()
    private lateinit var adapter: EventsAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEventsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        observeEvents()
    }
    
    /**
     * Set up RecyclerView.
     */
    private fun setupRecyclerView() {
        adapter = EventsAdapter { event ->
            showEventDetail(event)
        }
        
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@EventsFragment.adapter
        }
    }
    
    /**
     * Observe events.
     */
    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collectLatest { events ->
                    adapter.submitList(events)
                    
                    // Show empty state if no events
                    binding.tvEmptyState.visibility = if (events.isEmpty()) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
            }
        }
    }
    
    /**
     * Show event detail dialog.
     */
    private fun showEventDetail(event: AnomalyEvent) {
        EventDetailDialog.newInstance(event).show(
            childFragmentManager,
            "event_detail"
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
