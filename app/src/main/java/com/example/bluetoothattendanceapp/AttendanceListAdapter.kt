package com.example.bluetoothattendanceapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.databinding.ItemAttendanceRecordBinding
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceListAdapter : RecyclerView.Adapter<AttendanceListAdapter.ViewHolder>() {
    private val records = mutableListOf<AttendanceRecord>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr"))

    fun updateRecords(newRecords: List<AttendanceRecord>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount() = records.size

    inner class ViewHolder(private val binding: ItemAttendanceRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: AttendanceRecord) {
            binding.apply {
                txtStudentName.text = "${record.studentName} ${record.studentSurname}"
                txtStudentNumber.text = root.context.getString(R.string.student_number_format, record.studentNumber)
                txtTimestamp.text = dateFormat.format(record.timestamp)
                txtDeviceAddress.text = root.context.getString(R.string.device_address_format, record.deviceAddress)
            }
        }
    }
} 