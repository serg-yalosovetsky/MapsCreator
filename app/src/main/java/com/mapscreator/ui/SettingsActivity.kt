package com.mapscreator.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mapscreator.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDays = prefs.getInt(KEY_AUTO_UPDATE_DAYS, DEFAULT_AUTO_UPDATE_DAYS)
        binding.sliderAutoUpdateDays.value = savedDays.toFloat()
        binding.tvAutoUpdateDays.text = "$savedDays дней"

        binding.sliderAutoUpdateDays.addOnChangeListener { _, value, _ ->
            val days = value.toInt()
            binding.tvAutoUpdateDays.text = "$days дней"
            prefs.edit().putInt(KEY_AUTO_UPDATE_DAYS, days).apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val PREFS_NAME = "mapscreator_prefs"
        const val KEY_AUTO_UPDATE_DAYS = "auto_update_days"
        const val DEFAULT_AUTO_UPDATE_DAYS = 365
    }
}
