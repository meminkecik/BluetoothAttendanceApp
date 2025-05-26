package com.example.bluetoothattendanceapp.ui.common

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.model.AttendanceRecord
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceRecordsAdapter : ListAdapter<AttendanceRecord, AttendanceRecordsAdapter.ViewHolder>(
    DiffCallback()
) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val numberText: TextView = view.findViewById(R.id.tvNumber)
        private val nameText: TextView = view.findViewById(R.id.tvDeviceName)
        private val studentNumberText: TextView = view.findViewById(R.id.tvDeviceAddress)
        private val timestampText: TextView = view.findViewById(R.id.tvTimestamp)
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr"))

        @SuppressLint("SetTextI18n")
        fun bind(record: AttendanceRecord, position: Int) {
            numberText.text = "${position + 1}."
            nameText.text = "${record.studentName} ${record.studentSurname}"
            studentNumberText.text = "Öğrenci No: ${record.studentNumber}"
            timestampText.text = dateFormat.format(record.timestamp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    private class DiffCallback : DiffUtil.ItemCallback<AttendanceRecord>() {
        override fun areItemsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
            return oldItem.courseId == newItem.courseId && 
                   oldItem.deviceAddress == newItem.deviceAddress &&
                   oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
            return oldItem == newItem
        }
    }
} 