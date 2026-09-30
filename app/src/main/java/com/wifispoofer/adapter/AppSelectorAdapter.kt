package com.wifispoofer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView
import com.wifispoofer.R

data class AppItem(
    val packageName: String,
    val label: String,
    var isSelected: Boolean = false
)

class AppSelectorAdapter(
    private val apps: MutableList<AppItem>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppSelectorAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cbApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.text = "${app.label}\n${app.packageName}"
        holder.checkBox.isChecked = app.isSelected
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            app.isSelected = isChecked
            onSelectionChanged(getSelectedPackages())
        }
    }

    override fun getItemCount() = apps.size

    fun getSelectedPackages(): Set<String> =
        apps.filter { it.isSelected }.map { it.packageName }.toSet()

    fun setSelectedPackages(packages: Set<String>) {
        apps.forEach { it.isSelected = it.packageName in packages }
        notifyDataSetChanged()
    }
}
