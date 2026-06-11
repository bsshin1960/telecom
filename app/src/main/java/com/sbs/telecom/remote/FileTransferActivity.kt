package com.sbs.telecom.remote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var isCancelled = false
    private class ActiveReceiver(
        val targetPath: String,
        val totalChunks: Int,
        val tmpFile: File
    )
    private val activeReceivers = mutableMapOf<String, ActiveReceiver>()
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

        // Android 11+ 전체 파일 접근 권한 확인 및 요청
        checkStoragePermission()
        
        // 1. 상대방에게 나의 화면 열림 알림 및 나의 초기 경로 전송
        FileTransferSession.sendCommand("FS_OPEN_UI|$localCurrentPath")
        
        // 2. 나의 초기 목록 푸시 및 상대방 목록 즉시 요청 (싱크 이슈 방지)
        refreshLocalList()
        sendLocalFileList(localCurrentPath)
        requestRemoteList()
    }

    override fun onResume() {
        super.onResume()
        // 권한 설정에서 돌아온 경우 목록 새로고침
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                refreshLocalList()
                sendLocalFileList(localCurrentPath)
            }
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 이상: MANAGE_EXTERNAL_STORAGE 권한 필요
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("📁 파일 접근 권한 필요")
                    .setMessage("스마트폰 내부 저장소 전체를 탐색하려면 '모든 파일 접근' 권한이 필요합니다.\n\n설정 화면으로 이동하여 'TeleControl' 앱의 '모든 파일 접근 허용'을 켜주세요.")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${packageName}")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            // 일부 기기에서 직접 URI 방식이 안될 경우 일반 설정 화면으로
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("나중에") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(this, "권한이 없으면 일부 폴더가 표시되지 않을 수 있습니다.", Toast.LENGTH_LONG).show()
                    }
                    .show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6~10: READ_EXTERNAL_STORAGE 런타임 권한 요청
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                refreshLocalList()
                sendLocalFileList(localCurrentPath)
            } else {
                Toast.makeText(this, "저장소 권한이 거부되었습니다. 일부 폴더가 보이지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
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

        binding.btnCancelTransfer.setOnClickListener {
            cancelTransfer()
        }
    }

    private fun cancelTransfer() {
        isCancelled = true
        for ((filename, receiver) in activeReceivers) {
            FileTransferSession.sendCommand("FS_FILE_CANCEL|$filename")
            try {
                if (receiver.tmpFile.exists()) {
                    receiver.tmpFile.delete()
                }
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Failed to delete tmp file: ${e.message}")
            }
        }
        activeReceivers.clear()
        
        binding.txtStatus.text = "전송 취소됨"
        binding.progressBar.visibility = View.GONE
        binding.btnCancelTransfer.visibility = View.GONE
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

        if (file.length() > 50 * 1024 * 1024) {
            Toast.makeText(this, "50MB 이상의 파일은 전송할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (file.length() >= 10 * 1024 * 1024) {
            Toast.makeText(this, "비용 발생 주의!", Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, "비용 발생 주의!", Toast.LENGTH_SHORT).show()
            }, 2500)
        }

        isCancelled = false
        binding.txtStatus.text = "'$filename' 파일 전송 중..."
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.btnCancelTransfer.visibility = View.VISIBLE

        val fileSize = file.length()
        val chunkSize = 512 * 1024 // 512KB
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        
        binding.progressBar.max = totalChunks
        binding.progressBar.progress = 0

        var success = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val targetPath = fslashJoin(remoteCurrentPath, filename)
                FileTransferSession.sendCommand("FS_FILE_START|$targetPath|$totalChunks")
                
                file.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    var chunkIdx = 0
                    var bytesRead = input.read(buffer)
                    
                    while (bytesRead != -1) {
                        if (isCancelled) {
                            FileTransferSession.sendCommand("FS_FILE_CANCEL|$filename")
                            withContext(Dispatchers.Main) {
                                binding.txtStatus.text = "전송 취소됨"
                                binding.progressBar.visibility = View.GONE
                                binding.btnCancelTransfer.visibility = View.GONE
                            }
                            return@launch
                        }
                        
                        val chunkData = if (bytesRead < chunkSize) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer
                        }
                        
                        val base64Chunk = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                        FileTransferSession.sendCommand("FS_FILE_CHUNK|$filename|$chunkIdx|$base64Chunk")
                        
                        chunkIdx++
                        withContext(Dispatchers.Main) {
                            binding.txtStatus.text = "'$filename' 파일 전송 중..."
                        }
                        
                        bytesRead = input.read(buffer)
                        kotlinx.coroutines.delay(10)
                    }
                }
                
                FileTransferSession.sendCommand("FS_FILE_END|$filename")
                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'$filename' 전송 완료 대기 중..."
                }
                success = true
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Send file failed: ${e.message}")
                FileTransferSession.sendCommand("FS_FILE_SEND_ERR|$filename|${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileTransferActivity, "파일 전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.txtStatus.text = "파일 전송 실패"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!success) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
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
                    val targetPath = if (requestedPath.isNotEmpty()) requestedPath else localCurrentPath
                    localCurrentPath = targetPath
                    sendLocalFileList(targetPath)
                    refreshLocalList()
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
                message.startsWith("FS_FILE_START|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val targetPath = parts[1]
                        val totalChunksStr = parts[2]
                        handleFileStart(targetPath, totalChunksStr)
                    }
                }
                message.startsWith("FS_FILE_CHUNK|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 4) {
                        val filename = parts[1]
                        val chunkIdxStr = parts[2]
                        val base64Chunk = parts[3]
                        handleFileChunk(filename, chunkIdxStr, base64Chunk)
                    }
                }
                message.startsWith("FS_FILE_END|") -> {
                    val filename = message.substringAfter("FS_FILE_END|")
                    handleFileEnd(filename)
                }
                message.startsWith("FS_FILE_CANCEL|") -> {
                    val filename = message.substringAfter("FS_FILE_CANCEL|")
                    handleFileCancel(filename)
                }
                message.startsWith("FS_FILE_PROGRESS|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val filename = parts[1]
                        val chunkIdxStr = parts[2]
                        try {
                            val chunkIdx = chunkIdxStr.toInt()
                            binding.progressBar.progress = chunkIdx + 1
                            binding.txtStatus.text = "'$filename' 파일 전송 중 (${chunkIdx + 1}/${binding.progressBar.max})..."
                        } catch (e: Exception) {}
                    }
                }
                message.startsWith("FS_FILE_SEND_OK|") -> {
                    binding.txtStatus.text = "파일 전송 완료"
                    binding.progressBar.visibility = View.GONE
                    binding.btnCancelTransfer.visibility = View.GONE
                }
                message.startsWith("FS_FILE_SEND_ERR|") -> {
                    val parts = message.split("|", limit = 3)
                    if (parts.size >= 3) {
                        val filename = parts[1]
                        val err = parts[2]
                        binding.txtStatus.text = "'$filename' 전송 실패: $err"
                        binding.progressBar.visibility = View.GONE
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
                }
                message.startsWith("FS_FILE_EXISTS|") -> {
                    binding.txtStatus.text = "파일이 있습니다."
                    binding.progressBar.visibility = View.GONE
                    binding.btnCancelTransfer.visibility = View.GONE
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
        val currentStatus = binding.txtStatus.text.toString()
        if (currentStatus != "파일 전송 완료" && currentStatus != "파일 수신 완료" && currentStatus != "파일이 있습니다.") {
            binding.txtStatus.text = ""
        }
    }

    private fun sendLocalFile(requestedPath: String) {
        val file = File(requestedPath)
        if (!file.exists()) return
        val filename = file.name

        isCancelled = false
        binding.txtStatus.text = "'$filename' 파일 전송 중..."
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.btnCancelTransfer.visibility = View.VISIBLE

        val fileSize = file.length()
        val chunkSize = 512 * 1024 // 512KB
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        
        binding.progressBar.max = totalChunks
        binding.progressBar.progress = 0

        var success = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FileTransferSession.sendCommand("FS_FILE_START|$requestedPath|$totalChunks")
                
                file.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    var chunkIdx = 0
                    var bytesRead = input.read(buffer)
                    
                    while (bytesRead != -1) {
                        if (isCancelled) {
                            FileTransferSession.sendCommand("FS_FILE_CANCEL|$filename")
                            withContext(Dispatchers.Main) {
                                binding.txtStatus.text = "전송 취소됨"
                                binding.progressBar.visibility = View.GONE
                                binding.btnCancelTransfer.visibility = View.GONE
                            }
                            return@launch
                        }
                        
                        val chunkData = if (bytesRead < chunkSize) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer
                        }
                        
                        val base64Chunk = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                        FileTransferSession.sendCommand("FS_FILE_CHUNK|$filename|$chunkIdx|$base64Chunk")
                        
                        chunkIdx++
                        withContext(Dispatchers.Main) {
                            binding.txtStatus.text = "'$filename' 파일 전송 중..."
                        }
                        
                        bytesRead = input.read(buffer)
                        kotlinx.coroutines.delay(10)
                    }
                }
                
                FileTransferSession.sendCommand("FS_FILE_END|$filename")
                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'$filename' 전송 완료 대기 중..."
                }
                success = true
            } catch (e: Exception) {
                Log.e("FileTransferActivity", "Send requested file failed: ${e.message}")
                FileTransferSession.sendCommand("FS_FILE_SEND_ERR|$filename|${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileTransferActivity, "파일 전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.txtStatus.text = "파일 전송 실패"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!success) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun handleFileStart(targetPath: String, totalChunksStr: String) {
        try {
            val totalChunks = totalChunksStr.toInt()
            val filename = targetPath.substringAfterLast("/").substringAfterLast("\\")
            val localFile = File(localCurrentPath, filename)
            
            if (localFile.exists()) {
                binding.txtStatus.text = "파일이 있습니다."
                binding.progressBar.visibility = View.GONE
                binding.btnCancelTransfer.visibility = View.GONE
                FileTransferSession.sendCommand("FS_FILE_EXISTS|$filename")
                return
            }
            
            binding.txtStatus.text = "'$filename' 수신 중..."
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = totalChunks
            binding.progressBar.progress = 0
            binding.btnCancelTransfer.visibility = View.VISIBLE
            
            val tmpFile = File(localCurrentPath, "$filename.tmp")
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            
            val receiver = ActiveReceiver(
                targetPath = localFile.absolutePath,
                totalChunks = totalChunks,
                tmpFile = tmpFile
            )
            activeReceivers[filename] = receiver
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Error handling file start: ${e.message}")
        }
    }

    private fun handleFileChunk(filename: String, chunkIdxStr: String, base64Chunk: String) {
        try {
            val chunkIdx = chunkIdxStr.toInt()
            val receiver = activeReceivers[filename] ?: return
            
            val bytes = Base64.decode(base64Chunk, Base64.NO_WRAP)
            receiver.tmpFile.appendBytes(bytes)
            
            binding.progressBar.progress = chunkIdx + 1
            binding.txtStatus.text = "'$filename' 파일 수신 중 (${chunkIdx + 1}/${receiver.totalChunks})..."
            FileTransferSession.sendCommand("FS_FILE_PROGRESS|$filename|$chunkIdx")
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Error handling file chunk: ${e.message}")
        }
    }

    private fun handleFileEnd(filename: String) {
        try {
            val receiver = activeReceivers.remove(filename) ?: return
            val finalFile = File(receiver.targetPath)
            
            if (receiver.tmpFile.exists()) {
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                receiver.tmpFile.renameTo(finalFile)
            }
            
            binding.txtStatus.text = "파일 수신 완료"
            binding.progressBar.visibility = View.GONE
            binding.btnCancelTransfer.visibility = View.GONE
            
            refreshLocalList()
            sendLocalFileList(localCurrentPath)
            
            FileTransferSession.sendCommand("FS_FILE_SEND_OK|$filename|${finalFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Error handling file end: ${e.message}")
            binding.txtStatus.text = "파일 수신 완료 처리 실패"
            binding.progressBar.visibility = View.GONE
            binding.btnCancelTransfer.visibility = View.GONE
        }
    }

    private fun handleFileCancel(filename: String) {
        try {
            val receiver = activeReceivers.remove(filename)
            if (receiver != null) {
                if (receiver.tmpFile.exists()) {
                    receiver.tmpFile.delete()
                }
            }
            binding.txtStatus.text = "전송 취소됨"
            binding.progressBar.visibility = View.GONE
            binding.btnCancelTransfer.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("FileTransferActivity", "Error handling file cancel: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTransfer()
        FileTransferSession.activeListener = null
        FileTransferSession.sendCommand("FS_CLOSE_UI")
    }
}
