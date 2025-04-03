package com.example.bluetoothattendanceapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    data class BluetoothDevice(
        val name: String?,
        val address: String,
        val timestamp: Date? = null
    )

    private val devices = mutableListOf<BluetoothDevice>()

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.deviceName)
        val deviceAddress: TextView = view.findViewById(R.id.deviceAddress)
        val deviceTimestamp: TextView = view.findViewById(R.id.deviceTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.deviceName.apply {
            text = device.name ?: "İsimsiz Cihaz"
            setTextColor(context.getColor(R.color.bluetooth_enabled))
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0)
        }
        holder.deviceAddress.text = device.address
        holder.deviceTimestamp.text = device.timestamp?.let { 
            SimpleDateFormat("HH:mm:ss", Locale("tr")).format(it)
        } ?: ""
    }

    override fun getItemCount() = devices.size

    fun addDevice(device: BluetoothDevice) {
        val existingIndex = devices.indexOfFirst { it.address == device.address }
        if (existingIndex == -1) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        } else {
            devices[existingIndex] = device
            notifyItemChanged(existingIndex)
        }
    }

    fun clearDevices() {
        val size = devices.size
        devices.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun updateDevices(newDevices: List<BluetoothDevice>) {
        val diffCallback = DeviceDiffCallback(devices, newDevices)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        devices.clear()
        devices.addAll(newDevices)
        diffResult.dispatchUpdatesTo(this)
    }

    class DeviceDiffCallback(
        private val oldList: List<BluetoothDevice>,
        private val newList: List<BluetoothDevice>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].address == newList[newItemPosition].address
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition] == newList[newItemPosition]
    }
} 