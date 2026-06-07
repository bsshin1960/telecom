package com.sbs.telecom.remote

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sbs.telecom.remote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHostMode.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }

        binding.btnClientMode.setOnClickListener {
            if (binding.layoutIpInput.visibility == View.VISIBLE) {
                binding.layoutIpInput.visibility = View.GONE
            } else {
                binding.layoutIpInput.visibility = View.VISIBLE
            }
        }

        binding.btnConnect.setOnClickListener {
            val ip = binding.edtIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "IP 주소를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, ClientActivity::class.java).apply {
                    putExtra("EXTRA_HOST_IP", ip)
                }
                startActivity(intent)
            }
        }
    }
}
