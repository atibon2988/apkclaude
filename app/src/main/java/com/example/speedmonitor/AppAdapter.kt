package com.example.speedmonitor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val apps: List<AppInfo>,
    private var selectedPackage: String?,
    private val onSelect: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val label: TextView = view.findViewById(R.id.txtLabel)
        val pkg: TextView = view.findViewById(R.id.txtPackage)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.pkg.text = app.packageName
        holder.root.isSelected = app.packageName == selectedPackage
        holder.root.setBackgroundColor(
            if (app.packageName == selectedPackage) 0xFFDDEEFF.toInt() else 0x00000000
        )
        holder.root.setOnClickListener {
            val previous = selectedPackage
            selectedPackage = app.packageName
            onSelect(app)
            notifyItemChanged(apps.indexOfFirst { it.packageName == previous })
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = apps.size
}
