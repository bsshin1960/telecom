package com.sbs.telecom.remote

import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
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
    
    // 절대경로 기반으로 디스크/스토리지 탐색
    private var storageRoot = ""
    private var localCurrentPath = ""
    private var remoteCurrentPath = "Pending..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isClient = intent.getBooleanExtra("is_client", false)
        
        // 안드로이드 기본 스토리지 최상위 설정 (/storage/emulated/0)
        storageRoot = Environment.getExternalStorageDirectory().absolutePath
        localCurrentPath = storageRoot

        // 인텐트로 초기 원격(PC) 경로가 전달되었으면 바인딩
        val initialRemote = intent.getStringExtra("initial_remote_path")
        if (initialRemote != null && initialRemote.isNotEmpty()) {
            remoteCurrentPath = initialRemote
        }

        setupUI()
        
        // Register listener
        FileTransferSession.activeListener = this
        
        // 1. 상대방에게 나의 화면 열림 알림 및 나의 초기 경로 전송
        FileTransferSession.sendCommand("FS_OPEN_UI|$localCurrentPath")
        
        // 2. 나의 초기 목록 푸시 및 상대방 목록 즉시 요청 (싱크 이슈 방지)
        refreshLocalList()
        sendLocalFileList(localCurrentPath)
        requestRemoteList()
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

        updatePathLabels()

        // Initialize adapters with custom light-theme item layout
        localAdapter = ArrayAdapter(this, R.layout.list_item_file, localFiles)
        remoteAdapter = ArrayAdapter(this, R.layout.list_item_file, remoteFiles)

        if (isClient) {
            binding.listHelpReceive.adapter = remoteAdapter
            binding.listHelpGive.adapter = localAdapter
        } else {
            binding.listHelpReceive.adapter = localAdapter
            binding.listHelpGive.adapter = remoteAdapter
        }

        // List item click listeners for directory navigation
        binding.listHelpReceive.setOnItemClickListener { _, _, position, _ ->
            val isLocal = !isClient
            val listView = binding.listHelpReceive
            handleItemClick(position, isLocal, listView)
        }

        binding.listHelpGive.setOnItemClickListener { _, _, position, _ ->
            val isLocal = isClient
            val listView = binding.listHelpGive
            handleItemClick(position, isLocal, listView)
        }

        binding.btnSendToHelpGive.setOnClickListener {
            if (isClient) {
                // Remote (위) -> Local (아래) : 다운로드 요청 (Pull)
                transferRemoteToLocal(binding.listHelpReceive, remoteFiles)
            } else {
                // Local (위) -> Remote (아래) : 업로드 전송 (Push)
                transferLocalToRemote(binding.listHelpReceive, localFiles)
            }
        }

        binding.btnSendToHelpReceive.setOnClickListener {
            if (isClient) {
                // Local (아래) -> Remote (위) : 업로드 전송 (Push)
                transferLocalToRemote(binding.listHelpGive, localFiles)
            } else {
                // Remote (아래) -> Local (위) : 다운로드 요청 (Pull)
                transferRemoteToLocal(binding.listHelpGive, remoteFiles)
            }
        }
    }

    private fun updatePathLabels() {
        val localLabel = if (isClient) binding.txtRightPath else binding.txtLeftPath
        val remoteLabel = if (isClient) binding.txtLeftPath else binding.txtRightPath
        
        localLabel.text = "경로: $localCurrentPath"
        remoteLabel.text = "경로: $remoteCurrentPath"
    }

    private fun refreshAll() {
        refreshLocalList()
        requestRemoteList()
    }

    private fun refreshLocalList() {
        localFiles.clear()
        val currentDir = File(localCurrentPath)
        
        updatePathLabels()

        // 상위 폴더 추가 (기본 내부 스토리지 루트보다 위로 갈 수 있음)
        if (localCurrentPath != "/") {
            localFiles.add("📁 .. (상위 폴더)")
        }

        try {
            val list = currentDir.listFiles()
            if (list != null) {
                val dirs = mutableListOf<String>()
                val files = mutableListOf<String>()
                
                for (file in list) {
                    try {
                        if (file.isDirectory) {
                            dirs.add(file.name)
                        } else if (file.isFile) {
                            val sizeKb = file.length() / 1024.0
                            files.add(file.name + " (" + String.format("%.1f", sizeKb) + " KB)")
                        }
                    } catch (e: Exception) {
                        // 권한 제한 파일 스킵
                    }
                }
                
                dirs.sort()
                files.sort()
                
                for (d in dirs) {
                    localFiles.add("📁 $d")
                }
                for (f in files) {
                    localFiles.add("📄 $f")
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "List local files failed: ${e.message}")
        }
        localAdapter.notifyDataSetChanged()
    }

    private fun requestRemoteList() {
        val reqPath = if (remoteCurrentPath != "Pending...") remoteCurrentPath else ""
        binding.txtStatus.text = "원격 기기 파일 목록 요청 중..."
        FileTransferSession.sendCommand("FS_LIST_REQ|$reqPath")
    }

    private fun handleItemClick(position: Int, isLocal: Boolean, listView: ListView) {
        val fileList = if (isLocal) localFiles else remoteFiles
        if (position < 0 || position >= fileList.size) return
        
        val itemText = fileList[position]
        if (itemText.startsWith("📁 ")) {
            val folderName = if (itemText == "📁 .. (상위 폴더)") {
                ".."
            } else {
                itemText.substring(2)
            }
            
            if (isLocal) {
                if (folderName == "..") {
                    val parent = File(localCurrentPath).parent
                    localCurrentPath = parent ?: "/"
                } else {
                    localCurrentPath = File(localCurrentPath, folderName).absolutePath
                }
                // 목록 갱신 및 내 상태를 원격에 즉시 동보
                refreshLocalList()
                sendLocalFileList(localCurrentPath)
            } else {
                if (remoteCurrentPath == "Pending...") return
                
                if (folderName == "..") {
                    val parts = remoteCurrentPath.replace("\\", "/").rstrip("/").split("/")
                    remoteCurrentPath = if (parts.size > 1) {
                        parts.subList(0, parts.size - 1).joinToString("/")
                    } else {
                        "/"
                    }
                } else {
                    remoteCurrentPath = fslashJoin(remoteCurrentPath, folderName)
                }
                requestRemoteList()
            }
            
            listView.clearChoices()
            listView.requestLayout()
        }
    }

    private fun fslashJoin(p1: String, p2: String): String {
        val path = "$p1/$p2"
        return path.replace("//", "/")
    }

    private fun String.rstrip(char: String): String {
        return if (this.endsWith(char)) this.substring(0, this.length - char.length) else this
    }

    private fun transferLocalToRemote(listView: ListView, fileList: List<String>) {
        val position = listView.checkedItemPosition
        if (position == ListView.INVALID_POSITION || position >= fileList.size) {
            Toast.makeText(this, "전송할 로컬 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val itemText = fileList[position]
        if (itemText.startsWith("📁 ")) {
            Toast.makeText(this, "폴더 전송은 지원하지 않습니다. 파일만 전송 가능합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val filename = itemText.substring(2).substringBefore(" (")
        val file = File(localCurrentPath, filename)
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = file.readBytes()
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                
                val targetPath = fslashJoin(remoteCurrentPath, filename)
                FileTransferSession.sendCommand("FS_FILE_SEND|$targetPath|$base64Data")
                
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

    private fun transferRemoteToLocal(listView: ListView, fileList: List<String>) {
        val position = listView.checkedItemPosition
        if (position == ListView.INVALID_POSITION || position >= fileList.size) {
            Toast.makeText(this, "다운로드할 원격 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val itemText = fileList[position]
        if (itemText.startsWith("📁 ")) {
            Toast.makeText(this, "폴더 전송은 지원하지 않습니다. 파일만 전송 가능합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val filename = itemText.substring(2).substringBefore(" (")
        val srcPath = fslashJoin(remoteCurrentPath, filename)

        binding.txtStatus.text = "'$filename' 다운로드 요청 중..."
        FileTransferSession.sendCommand("FS_FILE_REQ|$srcPath")
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread {
            when {
                message.startsWith("FS_OPEN_UI") -> {
                    val path = message.substringAfter("FS_OPEN_UI|", "Pending...")
                    if (path != "Pending..." && path.isNotEmpty()) {
                        remoteCurrentPath = path
                        refreshAll()
                    }
                }
                message.startsWith("FS_LIST_REQ") -> {
                    val requestedPath = message.substringAfter("FS_LIST_REQ|", "")
                    sendLocalFileList(if (requestedPath.isNotEmpty()) requestedPath else localCurrentPath)
                }
                message.startsWith("FS_LIST_RESP|") -> {
                    val parts = message.split("|", limit = 3)
                    if (parts.size >= 3) {
                        val path = parts[1]
                        val jsonStr = parts[2]
                        remoteCurrentPath = path
                        updateRemoteList(jsonStr)
                    }
                }
                message.startsWith("FS_FILE_REQ|") -> {
                    val requestedPath = message.substringAfter("FS_FILE_REQ|")
                    sendLocalFile(requestedPath)
                }
                message.startsWith("FS_FILE_SEND|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val targetPath = parts[1]
                        val base64Data = parts[2]
                        saveRemoteFile(targetPath, base64Data)
                    }
                }
                message == "FS_CLOSE_UI" -> {
                    Toast.makeText(this, "상대방이 파일 전송을 종료했습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun sendLocalFileList(requestedPath: String) {
        val currentDir = File(requestedPath)
        val jsonArray = JSONArray()
        try {
            val list = currentDir.listFiles()
            if (list != null) {
                val dirs = mutableListOf<File>()
                val files = mutableListOf<File>()
                
                for (file in list) {
                    try {
                        if (file.isDirectory) {
                            dirs.add(file)
                        } else if (file.isFile) {
                            files.add(file)
                        }
                    } catch (e: Exception) {}
                }
                dirs.sortBy { it.name }
                files.sortBy { it.name }

                for (d in dirs) {
                    val jsonObject = JSONObject().apply {
                        put("name", d.name)
                        put("is_dir", true)
                        put("size", 0)
                    }
                    jsonArray.put(jsonObject)
                }
                for (f in files) {
                    val jsonObject = JSONObject().apply {
                        put("name", f.name)
                        put("is_dir", false)
                        put("size", f.length())
                    }
                    jsonArray.put(jsonObject)
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Failed to compile file list: ${e.message}")
        }
        FileTransferSession.sendCommand("FS_LIST_RESP|$requestedPath|$jsonArray")
    }

    private fun updateRemoteList(jsonStr: String) {
        remoteFiles.clear()
        
        // 상위 폴더 추가
        if (remoteCurrentPath != "/") {
            remoteFiles.add("📁 .. (상위 폴더)")
        }

        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val isDir = obj.getBoolean("is_dir")
                if (isDir) {
                    remoteFiles.add("📁 $name")
                } else {
                    val size = obj.getLong("size")
                    val sizeKb = size / 1024.0
                    remoteFiles.add("📄 $name (" + String.format("%.1f", sizeKb) + " KB)")
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Parse remote file list failed: ${e.message}")
        }
        remoteAdapter.notifyDataSetChanged()
        updatePathLabels()
        binding.txtStatus.text = "원격 파일 목록 동기화 완료"
    }

    private fun sendLocalFile(requestedPath: String) {
        val file = File(requestedPath)
        if (!file.exists()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = file.readBytes()
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                FileTransferSession.sendCommand("FS_FILE_SEND|$requestedPath|$base64Data")
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Failed to read requested file: ${e.message}")
            }
        }
    }

    private fun saveRemoteFile(targetPath: String, base64Data: String) {
        val file = File(targetPath)
        
        binding.txtStatus.text = "'${file.name}' 파일 저장 중..."
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                file.parentFile?.mkdirs()
                
                val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                file.writeBytes(bytes)

                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'${file.name}' 다운로드 완료"
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
