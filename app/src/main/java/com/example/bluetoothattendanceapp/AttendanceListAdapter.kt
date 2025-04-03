package com.example.bluetoothattendanceapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceListAdapter : ListAdapter<AttendanceRecord, AttendanceListAdapter.ViewHolder>(AttendanceDiffCallback()) {

    class ViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("tr"))

        fun bind(record: AttendanceRecord) {
            view.apply {
                findViewById<TextView>(R.id.txtStudentName).text = 
                    context.getString(R.string.student_name_format, record.studentName, record.studentSurname)
                
                findViewById<TextView>(R.id.txtStudentInfo).text = 
                    record.studentNumber?.let { 
                        context.getString(R.string.student_number_format, it)
                    } ?: ""
                
                findViewById<TextView>(R.id.txtDeviceInfo).text = 
                    context.getString(R.string.device_address_format, record.deviceAddress)
                
                findViewById<TextView>(R.id.txtTimestamp).text = 
                    context.getString(R.string.timestamp_format, dateFormat.format(record.timestamp))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class AttendanceDiffCallback : DiffUtil.ItemCallback<AttendanceRecord>() {
    override fun areItemsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
        return oldItem == newItem
    }
} 