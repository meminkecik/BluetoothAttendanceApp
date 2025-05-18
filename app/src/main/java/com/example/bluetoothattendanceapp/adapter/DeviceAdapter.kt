package com.example.bluetoothattendanceapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
    private val devices = mutableListOf<BluetoothDevice>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val numberText: TextView = itemView.findViewById(R.id.tvNumber)
        private val nameText: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val addressText: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        private val timestampText: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr"))

        fun bind(device: BluetoothDevice) {
            numberText.text = "${adapterPosition + 1}."
            nameText.text = device.name
            addressText.text = device.address
            timestampText.text = dateFormat.format(device.timestamp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
    }

    override fun getItemCount(): Int = devices.size

    fun addDevice(device: BluetoothDevice) {
        devices.add(device)
        notifyItemInserted(devices.size - 1)
    }

    fun updateDevices(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    data class BluetoothDevice(
        val name: String,
        val address: String,
        val timestamp: Date
    )

    fun getStudentCount(): Int = devices.size
} 