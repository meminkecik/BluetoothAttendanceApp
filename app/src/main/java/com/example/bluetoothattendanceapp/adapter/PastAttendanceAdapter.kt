package com.example.bluetoothattendanceapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.PastAttendanceItem
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.Course
import com.example.bluetoothattendanceapp.databinding.ItemPastAttendanceBinding
import java.text.SimpleDateFormat
import java.util.Locale

class PastAttendanceAdapter(
    private val onItemClick: (PastAttendanceItem) -> Unit
) : ListAdapter<PastAttendanceItem, PastAttendanceAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPastAttendanceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemPastAttendanceBinding,
        private val onItemClick: (PastAttendanceItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun bind(item: PastAttendanceItem) {
            binding.apply {
                tvCourseName.text = item.course.name
                tvDate.text = dateFormat.format(item.course.createdAt)
                tvStudentCount.text = "${item.studentCount} Öğrenci"
                tvStatus.text = if (item.isActive) "Aktif" else "Tamamlandı"
                tvStatus.setBackgroundResource(
                    if (item.isActive) com.example.bluetoothattendanceapp.R.color.colorSuccess
                    else com.example.bluetoothattendanceapp.R.color.colorGray
                )

                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PastAttendanceItem>() {
            override fun areItemsTheSame(oldItem: PastAttendanceItem, newItem: PastAttendanceItem) =
                oldItem.sessionId == newItem.sessionId

            override fun areContentsTheSame(oldItem: PastAttendanceItem, newItem: PastAttendanceItem) =
                oldItem == newItem
        }
    }
} 