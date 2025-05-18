package com.example.bluetoothattendanceapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.AttendeeInfo
import com.example.bluetoothattendanceapp.databinding.ItemAttendeeBinding

class AttendanceDetailsAdapter : ListAdapter<AttendeeInfo, AttendanceDetailsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendeeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemAttendeeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(attendee: AttendeeInfo) {
            binding.apply {
                tvStudentName.text = "${attendee.studentName} ${attendee.studentSurname}"
                tvStudentNumber.text = attendee.studentNumber
                tvAttendanceTime.text = attendee.timestamp
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AttendeeInfo>() {
            override fun areItemsTheSame(oldItem: AttendeeInfo, newItem: AttendeeInfo): Boolean {
                return oldItem.studentNumber == newItem.studentNumber
            }

            override fun areContentsTheSame(oldItem: AttendeeInfo, newItem: AttendeeInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
} 