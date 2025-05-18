package com.example.bluetoothattendanceapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    private val devices = mutableListOf<BluetoothDevice>()

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val numberText: TextView = view.findViewById(R.id.tvNumber)
        private val nameText: TextView = view.findViewById(R.id.tvDeviceName)
        private val addressText: TextView = view.findViewById(R.id.tvDeviceAddress)
        private val timestampText: TextView = view.findViewById(R.id.tvTimestamp)
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr"))

        fun bind(device: BluetoothDevice, position: Int) {
            numberText.text = "${position + 1}."
            nameText.text = device.name
            addressText.text = device.address
            timestampText.text = dateFormat.format(device.timestamp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device, position)
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

    fun getStudentCount(): Int = devices.size

    data class BluetoothDevice(
        val name: String,
        val address: String,
        val timestamp: Date
    )
} 