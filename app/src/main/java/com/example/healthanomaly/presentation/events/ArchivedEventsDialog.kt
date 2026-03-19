package com.example.healthanomaly.presentation.events

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.healthanomaly.R
import com.example.healthanomaly.databinding.DialogArchivedEventsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dialog showing archived anomaly events with swipe-to-unarchive.
 */
class ArchivedEventsDialog : DialogFragment() {
    
    private var _binding: DialogArchivedEventsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: EventsViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private lateinit var adapter: ArchivedEventsAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogArchivedEventsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupSwipeToUnarchive()
        observeArchivedEvents()
        
        binding.btnClose.setOnClickListener { dismiss() }
    }
    
    private fun setupRecyclerView() {
        adapter = ArchivedEventsAdapter()
        
        binding.recyclerViewArchived.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ArchivedEventsDialog.adapter
        }
    }
    
    private fun setupSwipeToUnarchive() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false
            
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val event = adapter.currentList[position]
                viewModel.unarchiveEvent(event.id)
                
                Snackbar.make(binding.root, R.string.event_unarchived, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        viewModel.archiveEvent(event.id)
                    }
                    .show()
            }
            
            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val paint = Paint().apply {
                        color = ContextCompat.getColor(requireContext(), R.color.status_success)
                    }
                    val cornerRadius = 12f * resources.displayMetrics.density
                    
                    if (dX > 0) {
                        val rect = RectF(
                            itemView.left.toFloat(),
                            itemView.top.toFloat(),
                            itemView.left + dX,
                            itemView.bottom.toFloat()
                        )
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
                    } else if (dX < 0) {
                        val rect = RectF(
                            itemView.right + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat()
                        )
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
                    }
                    
                    val alpha = 1.0f - Math.abs(dX) / itemView.width.toFloat()
                    itemView.alpha = alpha
                    itemView.translationX = dX
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
        }
        
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerViewArchived)
    }
    
    private fun observeArchivedEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.archivedEvents.collectLatest { events ->
                    adapter.submitList(events)
                    
                    binding.tvEmptyArchived.visibility = if (events.isEmpty()) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
