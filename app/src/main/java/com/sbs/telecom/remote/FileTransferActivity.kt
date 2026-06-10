package com.sbs.telecom.remote

import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sbs.telecom.remote.databinding.ActivityFileTransferBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileTransferActivity : AppCompatActivity(), FileTransferSession.MessageListener {

    private lateinit var binding: ActivityFileTransferBinding
    private var isClient = false // True = Phone이 '도움 주기' (Client), False = Phone이 '도움 받기' (Host)
    
    private val localFiles = mutableListOf<String>()
    private val remoteFiles = mutableListOf<String>()
    
    private lateinit var localAdapter: ArrayAdapter<String>
    private lateinit var remoteAdapter: ArrayAdapter<String>
    
    private var downloadsPath: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isClient = intent.getBooleanExtra("is_client", false)

        setupUI()
        setupDirectories()
        
        // Register listener
        FileTransferSession.activeListener = this
        
        // Notify other side we opened the UI
        FileTransferSession.sendCommand("FS_OPEN_UI")

        refreshAll()
    }

    private fun setupUI() {
        if (isClient) {
            // Local is 도움 주기 (아래), Remote is 도움 받기 (위)
            binding.txtLeftTitle.text = "도움 받기 (원격 PC)"
            binding.txtRightTitle.text = "도움 주기 (로컬 스마트폰)"
        } else {
            // Local is 도움 받기 (위), Remote is 도움 주기 (아래)
            binding.txtLeftTitle.text = "도움 받기 (로컬 스마트폰)"
            binding.txtRightTitle.text = "도움 주기 (원격 PC)"
        }

        // Initialize adapters
        localAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, localFiles)
        remoteAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, remoteFiles)

        if (isClient) {
            binding.listHelpReceive.adapter = remoteAdapter
            binding.listHelpGive.adapter = localAdapter
        } else {
            binding.listHelpReceive.adapter = localAdapter
            binding.listHelpGive.adapter = remoteAdapter
        }

        binding.btnRefresh.setOnClickListener { refreshAll() }

        binding.btnSendToHelpGive.setOnClickListener {
            if (isClient) {
                // Remote (위) -> Local (아래) : 다운로드 요청 (Pull)
                transferRemoteToLocal(binding.listHelpReceive)
            } else {
                // Local (위) -> Remote (아래) : 업로드 전송 (Push)
                transferLocalToRemote(binding.listHelpReceive)
            }
        }

        binding.btnSendToHelpReceive.setOnClickListener {
            if (isClient) {
                // Local (아래) -> Remote (위) : 업로드 전송 (Push)
                transferLocalToRemote(binding.listHelpGive)
            } else {
                // Remote (아래) -> Local (위) : 다운로드 요청 (Pull)
                transferRemoteToLocal(binding.listHelpGive)
            }
        }
    }

    private fun setupDirectories() {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsPath = if (publicDir != null && publicDir.exists()) {
            publicDir
        } else {
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        }
        if (downloadsPath != null && !downloadsPath!!.exists()) {
            downloadsPath!!.mkdirs()
        }
    }

    private fun refreshAll() {
        refreshLocalList()
        requestRemoteList()
    }

    private fun refreshLocalList() {
        localFiles.clear()
        val path = downloadsPath ?: return
        try {
            val list = path.listFiles()
            if (list != null) {
                for (file in list) {
                    if (file.isFile) {
                        val sizeKb = file.length() / 1024.0
                        localFiles.add(file.name + " (" + String.format("%.1f", sizeKb) + " KB)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "List local files failed: ${e.message}")
        }
        localAdapter.notifyDataSetChanged()
    }

    private fun requestRemoteList() {
        binding.txtStatus.text = "원격 기기 파일 목록 요청 중..."
        FileTransferSession.sendCommand("FS_LIST_REQ")
    }

    private fun transferLocalToRemote(listView: android.widget.ListView) {
        val position = listView.checkedItemPosition
        if (position == android.widget.ListView.INVALID_POSITION || position >= localFiles.size) {
            Toast.makeText(this, "전송할 로컬 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val itemText = localFiles[position]
        val filename = itemText.substringBefore(" (")
        val file = File(downloadsPath, filename)
        if (!file.exists()) {
            Toast.makeText(this, "파일이 존재하지 않습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (file.length() > 10 * 1024 * 1024) {
            Toast.makeText(this, "10MB 이상의 파일은 전송할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.txtStatus.text = "'$filename' 파일 전송 중..."
        binding.progressBar.visibility = View.VISIBLE

        // Read and send in background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = file.readBytes()
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                FileTransferSession.sendCommand("FS_FILE_SEND|$filename|$base64Data")
                
                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'$filename' 전송 완료"
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Send file failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileTransferActivity, "파일 전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.txtStatus.text = "파일 전송 실패"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun transferRemoteToLocal(listView: android.widget.ListView) {
        val position = listView.checkedItemPosition
        if (position == android.widget.ListView.INVALID_POSITION || position >= remoteFiles.size) {
            Toast.makeText(this, "다운로드할 원격 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val itemText = remoteFiles[position]
        val filename = itemText.substringBefore(" (")

        binding.txtStatus.text = "'$filename' 다운로드 요청 중..."
        FileTransferSession.sendCommand("FS_FILE_REQ|$filename")
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread {
            when {
                message == "FS_LIST_REQ" -> {
                    // Send local file list
                    sendLocalFileList()
                }
                message.startsWith("FS_LIST_RESP|") -> {
                    val jsonStr = message.substringAfter("FS_LIST_RESP|")
                    updateRemoteList(jsonStr)
                }
                message.startsWith("FS_FILE_REQ|") -> {
                    val filename = message.substringAfter("FS_FILE_REQ|")
                    sendLocalFile(filename)
                }
                message.startsWith("FS_FILE_SEND|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val filename = parts[1]
                        val base64Data = parts[2]
                        saveRemoteFile(filename, base64Data)
                    }
                }
                message == "FS_CLOSE_UI" -> {
                    Toast.makeText(this, "상대방이 파일 전송을 종료했습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun sendLocalFileList() {
        val path = downloadsPath ?: return
        val jsonArray = JSONArray()
        try {
            val list = path.listFiles()
            if (list != null) {
                for (file in list) {
                    if (file.isFile) {
                        val jsonObject = JSONObject().apply {
                            put("name", file.name)
                            put("size", file.length())
                        }
                        jsonArray.put(jsonObject)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Failed to compile file list: ${e.message}")
        }
        FileTransferSession.sendCommand("FS_LIST_RESP|$jsonArray")
    }

    private fun updateRemoteList(jsonStr: String) {
        remoteFiles.clear()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val size = obj.getLong("size")
                val sizeKb = size / 1024.0
                remoteFiles.add(name + " (" + String.format("%.1f", sizeKb) + " KB)")
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Parse remote file list failed: ${e.message}")
        }
        remoteAdapter.notifyDataSetChanged()
        binding.txtStatus.text = "원격 파일 목록 동기화 완료"
    }

    private fun sendLocalFile(filename: String) {
        val file = File(downloadsPath, filename)
        if (!file.exists()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = file.readBytes()
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                FileTransferSession.sendCommand("FS_FILE_SEND|$filename|$base64Data")
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Failed to read requested file: ${e.message}")
            }
        }
    }

    private fun saveRemoteFile(filename: String, base64Data: String) {
        binding.txtStatus.text = "'$filename' 파일 저장 중..."
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                val file = File(downloadsPath, filename)
                file.writeBytes(bytes)

                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'$filename' 다운로드 완료"
                    binding.progressBar.visibility = View.GONE
                    refreshLocalList()
                }
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Failed to save remote file: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileTransferActivity, "파일 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.txtStatus.text = "파일 저장 실패"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileTransferSession.activeListener = null
        FileTransferSession.sendCommand("FS_CLOSE_UI")
    }
}
