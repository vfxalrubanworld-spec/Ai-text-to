package com.q2.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(message: ChatMessage) {
            text1.text = message.text
            text1.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (message.isUser) android.R.color.holo_blue_light else android.R.color.white
                )
            )
            text1.textSize = 16f

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeString = sdf.format(Date(message.timestamp))
            text2.text = if (message.isUser) "You · $timeString" else "Q2 Assistant · $timeString"
            text2.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
            text2.textSize = 12f

            // Alignments
            val params = itemView.layoutParams as ViewGroup.MarginLayoutParams
            if (message.isUser) {
                params.leftMargin = 100
                params.rightMargin = 20
                text1.gravity = Gravity.END
                text2.gravity = Gravity.END
            } else {
                params.leftMargin = 20
                params.rightMargin = 100
                text1.gravity = Gravity.START
                text2.gravity = Gravity.START
            }
            itemView.layoutParams = params
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
