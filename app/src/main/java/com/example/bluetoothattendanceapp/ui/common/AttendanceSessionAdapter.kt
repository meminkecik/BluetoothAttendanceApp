package com.example.bluetoothattendanceapp.ui.common

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.data.model.AttendanceSession
import com.example.bluetoothattendanceapp.databinding.ItemAttendanceSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AttendanceSessionAdapter : 
    ListAdapter<AttendanceSession, AttendanceSessionAdapter.ViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemAttendanceSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        @SuppressLint("SetTextI18n")
        fun bind(session: AttendanceSession) {
            binding.apply {
                tvCourseName.text = session.courseName
                tvDate.text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(Date(session.date))
                tvAttendeeCount.text = "${session.attendees.size} Öğrenci"
                tvStatus.text = if(session.isActive) "Aktif" else "Tamamlandı"
            }
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<AttendanceSession>() {
        override fun areItemsTheSame(oldItem: AttendanceSession, newItem: AttendanceSession) =
            oldItem.id == newItem.id
        
        override fun areContentsTheSame(oldItem: AttendanceSession, newItem: AttendanceSession) =
            oldItem == newItem
    }
} 