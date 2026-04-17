package com.example.healthanomaly.presentation.results

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.healthanomaly.R
import com.example.healthanomaly.data.dataset.BATCH_NONE_LABEL
import com.example.healthanomaly.data.dataset.BatchSampleResult
import com.example.healthanomaly.databinding.ItemBatchResultBinding

class BatchResultsAdapter :
    ListAdapter<BatchSampleResult, BatchResultsAdapter.ResultViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemBatchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(
        private val binding: ItemBatchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: BatchSampleResult) {
            val context = binding.root.context
            val badgeColor = if (result.matched) {
                R.color.status_success
            } else {
                R.color.status_error
            }

            binding.tvSampleId.text = result.sampleId
            binding.tvMatchStatus.text = context.getString(
                if (result.matched) R.string.batch_result_pass else R.string.batch_result_fail
            )
            binding.tvMatchStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, badgeColor)
            )
            binding.tvExpected.text = context.getString(
                R.string.batch_result_expected,
                result.expectedLabel
            )
            binding.tvPredicted.text = context.getString(
                R.string.batch_result_predicted,
                result.predictedLabel
            )
            binding.tvDetected.text = context.getString(
                R.string.batch_result_detected,
                result.detectedTypes.joinToString().ifBlank { BATCH_NONE_LABEL }
            )
            binding.tvElapsed.text = context.getString(
                R.string.batch_result_elapsed,
                result.elapsedMs
            )
            if (result.error.isNullOrBlank()) {
                binding.tvError.visibility = android.view.View.GONE
            } else {
                binding.tvError.visibility = android.view.View.VISIBLE
                binding.tvError.text = context.getString(
                    R.string.batch_result_error,
                    result.error
                )
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<BatchSampleResult>() {
        override fun areItemsTheSame(oldItem: BatchSampleResult, newItem: BatchSampleResult): Boolean {
            return oldItem.sampleId == newItem.sampleId
        }

        override fun areContentsTheSame(oldItem: BatchSampleResult, newItem: BatchSampleResult): Boolean {
            return oldItem == newItem
        }
    }
}
