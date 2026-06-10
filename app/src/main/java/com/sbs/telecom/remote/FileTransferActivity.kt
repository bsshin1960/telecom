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
    
    private var downloadsPath: File? = null
    
    // 상대 경로 관리 (Downloads 폴더 기준)
    private var localRelativePath = ""
    private var remoteRelativePath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isClient = intent.getBooleanExtra("is_client", false)

        setupUI()
        setupDirectories()
        
        // Register listener
        FileTransferSession.activeListener = this
        
        // Notify other side we opened the UI and request initial remote list
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
            handleItemClick(position, isLocal, if (isClient) binding.listHelpReceive else binding.listHelpReceive)
        }

        binding.listHelpGive.setOnItemClickListener { _, _, position, _ ->
            val isLocal = isClient
            handleItemClick(position, isLocal, if (isClient) binding.listHelpGive else binding.listHelpGive)
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
        
        localLabel.text = if (localRelativePath.isNotEmpty()) "경로: Downloads/$localRelativePath" else "경로: Downloads"
        remoteLabel.text = if (remoteRelativePath.isNotEmpty()) "경로: Downloads/$remoteRelativePath" else "경로: Downloads"
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
        val rootPath = downloadsPath ?: return
        
        val currentDir = File(rootPath, localRelativePath)
        // 보안 검증 (상위 디렉토리 탈출 방지)
        try {
            if (!currentDir.canonicalPath.startsWith(rootPath.canonicalPath)) {
                localRelativePath = ""
                refreshLocalList()
                return
            }
        } catch (e: Exception) {
            localRelativePath = ""
            return
        }

        updatePathLabels()

        // 상위 폴더 추가
        if (localRelativePath.isNotEmpty()) {
            localFiles.add("📁 .. (상위 폴더)")
        }

        try {
            val list = currentDir.listFiles()
            if (list != null) {
                val dirs = mutableListOf<String>()
                val files = mutableListOf<String>()
                
                for (file in list) {
                    if (file.isDirectory) {
                        dirs.add(file.name)
                    } else if (file.isFile) {
                        val sizeKb = file.length() / 1024.0
                        files.add(file.name + " (" + String.format("%.1f", sizeKb) + " KB)")
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
        binding.txtStatus.text = "원격 기기 파일 목록 요청 중..."
        FileTransferSession.sendCommand("FS_LIST_REQ|$remoteRelativePath")
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
                    val parts = localRelativePath.split("/")
                    localRelativePath = if (parts.size > 1) {
                        parts.subList(0, parts.size - 1).joinToString("/")
                    } else {
                        ""
                    }
                } else {
                    localRelativePath = if (localRelativePath.isNotEmpty()) "$localRelativePath/$folderName" else folderName
                }
                refreshLocalList()
            } else {
                if (folderName == "..") {
                    val parts = remoteRelativePath.split("/")
                    remoteRelativePath = if (parts.size > 1) {
                        parts.subList(0, parts.size - 1).joinToString("/")
                    } else {
                        ""
                    }
                } else {
                    remoteRelativePath = if (remoteRelativePath.isNotEmpty()) "$remoteRelativePath/$folderName" else folderName
                }
                requestRemoteList()
            }
            
            listView.clearChoices()
            listView.requestLayout()
        }
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
        val file = File(File(downloadsPath, localRelativePath), filename)
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
                
                // 상대방의 현재 상대 경로 아래에 저장되도록 경로 구성
                val targetPath = if (remoteRelativePath.isNotEmpty()) "$remoteRelativePath/$filename" else filename
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
        val srcPath = if (remoteRelativePath.isNotEmpty()) "$remoteRelativePath/$filename" else filename

        binding.txtStatus.text = "'$filename' 다운로드 요청 중..."
        FileTransferSession.sendCommand("FS_FILE_REQ|$srcPath")
    }

    override fun onMessageReceived(message: String) {
        runOnUiThread {
            when {
                message.startsWith("FS_LIST_REQ") -> {
                    // 원격지에서 경로 목록 요청 시 응답
                    val requestedPath = message.substringAfter("FS_LIST_REQ|", "")
                    sendLocalFileList(requestedPath)
                }
                message.startsWith("FS_LIST_RESP|") -> {
                    val parts = message.split("|", limit = 3)
                    if (parts.size >= 3) {
                        val path = parts[1]
                        val jsonStr = parts[2]
                        remoteRelativePath = path
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
        val rootPath = downloadsPath ?: return
        val currentDir = File(rootPath, requestedPath)
        
        // 보안 검증 (상위 디렉토리 탈출 방지)
        try {
            if (!currentDir.canonicalPath.startsWith(rootPath.canonicalPath)) {
                return
            }
        } catch (e: Exception) {
            return
        }

        val jsonArray = JSONArray()
        try {
            val list = currentDir.listFiles()
            if (list != null) {
                // 폴더와 파일 분리 정렬
                val dirs = mutableListOf<File>()
                val files = mutableListOf<File>()
                
                for (file in list) {
                    if (file.isDirectory) {
                        dirs.add(file)
                    } else if (file.isFile) {
                        files.add(file)
                    }
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
        if (remoteRelativePath.isNotEmpty()) {
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
        val rootPath = downloadsPath ?: return
        val file = File(rootPath, requestedPath)
        
        // 보안 검증
        try {
            if (!file.canonicalPath.startsWith(rootPath.canonicalPath) || !file.exists()) {
                return
            }
        } catch (e: Exception) {
            return
        }

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
        val rootPath = downloadsPath ?: return
        val filename = targetPath.substringAfterLast("/")
        
        // 보안 경로 구성
        val file = File(File(rootPath, localRelativePath), filename)
        try {
            if (!file.canonicalPath.startsWith(rootPath.canonicalPath)) {
                Log.e("FileTransferActivity", "Path traversal attack blocked on receive!")
                return
            }
        } catch (e: Exception) {
            return
        }

        binding.txtStatus.text = "'$filename' 파일 저장 중..."
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure parent directory exists
                file.parentFile?.mkdirs()
                
                val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                file.writeBytes(bytes)

                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "'$filename' 다운로드 완료"
                    binding.progressBar.visibility = View.GONE
                    refreshLocalList() // 수신 후 자동 새로고침
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
