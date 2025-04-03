package com.example.bluetoothattendanceapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.Course
import java.text.SimpleDateFormat
import java.util.Locale

class CourseAdapter(
    private val onCourseClick: (Course) -> Unit
) : ListAdapter<Course, CourseAdapter.CourseViewHolder>(CourseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view, onCourseClick)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CourseViewHolder(
        itemView: View,
        private val onCourseClick: (Course) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.courseName)
        private val timeTextView: TextView = itemView.findViewById(R.id.courseTime)
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun bind(course: Course) {
            nameTextView.text = course.name
            timeTextView.text = dateFormat.format(course.createdAt)
            itemView.setOnClickListener { onCourseClick(course) }
        }
    }

    private class CourseDiffCallback : DiffUtil.ItemCallback<Course>() {
        override fun areItemsTheSame(oldItem: Course, newItem: Course): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Course, newItem: Course): Boolean {
            return oldItem == newItem
        }
    }
} 