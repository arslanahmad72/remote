package com.example.universalremote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.universalremote.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var rokuIp: String? = null
    private var irManager: ConsumerIrManager? = null
    private var useRoku = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        // Chip toggles
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            useRoku = checkedIds.contains(binding.chipRoku.id)
            Toast.makeText(this, if (useRoku) "Roku mode" else "Generic IR mode", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            rokuIp = binding.etRokuIp.text?.toString()?.trim()
            if (rokuIp.isNullOrEmpty()) {
                Toast.makeText(this, "Enter Roku IP", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Set Roku IP to $rokuIp", Toast.LENGTH_SHORT).show()
            }
        }

        // Wire buttons
        binding.btnPower.setOnClickListener { action("Power") }
        binding.btnHome.setOnClickListener { action("Home") }
        binding.btnBack.setOnClickListener { action("Back") }
        binding.btnUp.setOnClickListener { action("Up") }
        binding.btnDown.setOnClickListener { action("Down") }
        binding.btnLeft.setOnClickListener { action("Left") }
        binding.btnRight.setOnClickListener { action("Right") }
        binding.btnOk.setOnClickListener { action("Select") }
        binding.btnVolUp.setOnClickListener { action("VolumeUp") }
        binding.btnVolDown.setOnClickListener { action("VolumeDown") }
        binding.btnMute.setOnClickListener { action("VolumeMute") }
        binding.btnChanUp.setOnClickListener { action("ChannelUp") }
        binding.btnChanDown.setOnClickListener { action("ChannelDown") }
        binding.btnInfo.setOnClickListener { action("Info") }
    }

    private fun action(key: String) {
        if (useRoku) {
            sendRokuKeypress(key)
        } else {
            sendGenericIr(key)
        }
    }

    // ------- ROKU ECP (Wi‑Fi) -------
    private fun sendRokuKeypress(key: String) {
        val ip = rokuIp
        if (ip.isNullOrEmpty()) {
            Toast.makeText(this, "Set Roku IP first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$ip:8060/keypress/$key")
                    (url.openConnection() as HttpURLConnection).use { conn ->
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.doOutput = true
                        conn.outputStream.use { } // empty body
                        val code = conn.responseCode
                        code in 200..299
                    }
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@MainActivity, if (ok) "Sent $key" else "Failed $key", Toast.LENGTH_SHORT).show()
        }
    }

    // ------- Generic IR (sample NEC codes) -------
    private fun sendGenericIr(key: String) {
        val mgr = irManager
        if (mgr == null || !mgr.hasIrEmitter()) {
            Toast.makeText(this, "No IR emitter on this device", Toast.LENGTH_SHORT).show()
            return
        }

        // Example: 38kHz NEC-formatted patterns for a hypothetical TV address.
        val freq = 38000
        val pattern = when (key) {
            "Power" -> necPattern(0x10, 0x0C)     // device 0x10, cmd 0x0C (example)
            "VolumeUp" -> necPattern(0x10, 0x02)
            "VolumeDown" -> necPattern(0x10, 0x03)
            "ChannelUp" -> necPattern(0x10, 0x00)
            "ChannelDown" -> necPattern(0x10, 0x01)
            "Mute" -> necPattern(0x10, 0x0D)
            else -> null
        }

        if (pattern == null) {
            Toast.makeText(this, "IR not mapped for $key", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mgr.transmit(freq, pattern)
            Toast.makeText(this, "IR sent: $key", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "IR failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Create NEC IR pattern (microseconds burst pairs converted to carrier on/off counts)
    private fun necPattern(device: Int, command: Int): IntArray {
        // NEC format: header 9ms on, 4.5ms off, then 32 bits LSB-first: dev, ~dev, cmd, ~cmd
        fun bitToDurations(bit: Int): IntArray {
            // logical 0: 560us on, 560us off; logical 1: 560us on, 1690us off
            return if (bit == 0) intArrayOf(560, 560) else intArrayOf(560, 1690)
        }

        val bits = IntArray(32)
        var idx = 0
        fun writeByteLSB(b: Int) {
            for (i in 0 until 8) {
                bits[idx++] = (b shr i) and 1
            }
        }
        writeByteLSB(device and 0xFF)
        writeByteLSB(device.inv() and 0xFF)
        writeByteLSB(command and 0xFF)
        writeByteLSB(command.inv() and 0xFF)

        val bursts = mutableListOf<Int>()
        bursts += 9000; bursts += 4500
        for (i in 0 until 32) {
            val d = bitToDurations(bits[i])
            bursts += d[0]; bursts += d[1]
        }
        // final stop bit: 560us on
        bursts += 560

        // Convert microseconds to "on/off counts" required by ConsumerIrManager.transmit:
        // It actually takes microseconds directly as an int array (Android converts internally).
        return bursts.toIntArray()
    }
}