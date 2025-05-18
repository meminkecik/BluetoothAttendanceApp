package com.example.bluetoothattendanceapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.databinding.ItemAttendanceRecordBinding
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceRecordsAdapter : ListAdapter<AttendanceRecord, AttendanceRecordsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemAttendanceRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr"))

        fun bind(record: AttendanceRecord) {
            binding.apply {
                txtStudentName.text = "${record.studentName} ${record.studentSurname}"
                txtStudentNumber.text = root.context.getString(R.string.student_number_format, record.studentNumber)
                txtTimestamp.text = dateFormat.format(record.timestamp)
                txtDeviceAddress.text = root.context.getString(R.string.device_address_format, record.deviceAddress)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AttendanceRecord>() {
        override fun areItemsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
            return oldItem == newItem
        }
    }
} 