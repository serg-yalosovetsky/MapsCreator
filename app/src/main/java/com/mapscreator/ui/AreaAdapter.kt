package com.mapscreator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mapscreator.R
import com.mapscreator.tiles.MapArea

class AreaAdapter(
    private val areas: List<MapArea>,
    private val onDownload: (MapArea) -> Unit,
    private val onExportGarmin: (MapArea) -> Unit,
    private val onDelete: (MapArea) -> Unit
) : RecyclerView.Adapter<AreaAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_area_name)
        val tvAge: TextView = view.findViewById(R.id.tv_area_age)
        val tvTiles: TextView = view.findViewById(R.id.tv_area_tiles)
        val btnDownload: ImageButton = view.findViewById(R.id.btn_download)
        val btnGarmin: ImageButton = view.findViewById(R.id.btn_garmin)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_area, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val area = areas[position]
        holder.tvName.text = area.name
        holder.tvAge.text = "Обновлено: ${formatAge(area.lastUpdated)}"
        holder.tvTiles.text = "${area.tileCount} тайлов · zoom ${area.zoomMin}–${area.zoomMax}"
        holder.btnDownload.setOnClickListener { onDownload(area) }
        holder.btnGarmin.setOnClickListener { onExportGarmin(area) }
        holder.btnDelete.setOnClickListener { onDelete(area) }
    }

    override fun getItemCount(): Int = areas.size
}
