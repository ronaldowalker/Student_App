package dev.kwasi.echoservercomplete

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.kwasi.echoservercomplete.chatlist.ChatListAdapter
import dev.kwasi.echoservercomplete.models.ContentModel
import dev.kwasi.echoservercomplete.network.Client
import dev.kwasi.echoservercomplete.network.NetworkMessageInterface
import dev.kwasi.echoservercomplete.network.Server
import dev.kwasi.echoservercomplete.peerlist.PeerListAdapter
import dev.kwasi.echoservercomplete.peerlist.PeerListAdapterInterface
import dev.kwasi.echoservercomplete.wifidirect.WifiDirectInterface
import dev.kwasi.echoservercomplete.wifidirect.WifiDirectManager

// Activity for managing communication using Wi-Fi Direct
class CommunicationActivity : AppCompatActivity(), WifiDirectInterface, PeerListAdapterInterface, NetworkMessageInterface {
    private var wifiDirectManager: WifiDirectManager? = null

    // IntentFilter to capture Wi-Fi Direct actions
    private val wifiDirectIntentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var peerAdapter: PeerListAdapter? = null
    private var chatAdapter: ChatListAdapter? = null

    private var isWifiDirectEnabled = false // Wi-Fi Direct adapter status
    private var isConnectedToPeer = false // Connection status
    private var hasAvailablePeers = false // Availability of peers
    private var serverInstance: Server? = null // Server instance
    private var clientInstance: Client? = null // Client instance
    private var localDeviceIp: String = "" // Device IP address
    private var isStudentIDValid = false // Student ID validation status
    private var studentIdSeed: String? = " " // Seed for encryption

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enable edge-to-edge layout
        setContentView(R.layout.activity_communication)

        // Set window insets (padding) for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize Wi-Fi Direct manager
        val wifiP2pManager: WifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val p2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
        wifiDirectManager = WifiDirectManager(wifiP2pManager, p2pChannel, this)

        // Setup peer list RecyclerView
        peerAdapter = PeerListAdapter(this)
        val peerRecyclerView: RecyclerView = findViewById(R.id.peerRecyclerView)
        peerRecyclerView.adapter = peerAdapter
        peerRecyclerView.layoutManager = LinearLayoutManager(this)

        // Setup chat list RecyclerView
        chatAdapter = ChatListAdapter()
        val chatRecyclerView: RecyclerView = findViewById(R.id.chatRecyclerView)
        chatRecyclerView.adapter = chatAdapter
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    // Register Wi-Fi Direct broadcast receiver when activity resumes
    override fun onResume() {
        super.onResume()
        wifiDirectManager?.also {
            registerReceiver(it, wifiDirectIntentFilter)
        }
    }

    // Unregister Wi-Fi Direct broadcast receiver when activity pauses
    override fun onPause() {
        super.onPause()
        wifiDirectManager?.also {
            unregisterReceiver(it)
        }
    }

    // Discover nearby Wi-Fi Direct peers
    fun discoverNearbyPeers(view: View) {
        validateStudentID() // Validate student ID
        if (isStudentIDValid) {
            hideKeyboard()
            wifiDirectManager?.discoverPeers() // Start peer discovery
            val studentIdInput: EditText = findViewById(R.id.studentIdEditText)
            studentIdSeed = studentIdInput.text.toString() // Store student ID
        } else {
            updateUI() // Update the UI in case of invalid ID
        }
    }

    // Update the UI based on Wi-Fi Direct state and connection status
    private fun updateUI() {
        val wifiDirectDisabledView: ConstraintLayout = findViewById(R.id.wifiDirectDisabledView)
        wifiDirectDisabledView.visibility = if (!isWifiDirectEnabled) View.VISIBLE else View.GONE

        val noConnectionView: ConstraintLayout = findViewById(R.id.noConnectionView)
        noConnectionView.visibility = if (isWifiDirectEnabled && !isConnectedToPeer) View.VISIBLE else View.GONE

        val peerRecyclerView: RecyclerView = findViewById(R.id.peerRecyclerView)
        peerRecyclerView.visibility = if (isWifiDirectEnabled && !isConnectedToPeer && hasAvailablePeers && isStudentIDValid) View.VISIBLE else View.GONE

        val connectedView: ConstraintLayout = findViewById(R.id.connectedView)
        connectedView.visibility = if (isConnectedToPeer) View.VISIBLE else View.GONE
    }

    // Send a message to a connected peer
    fun sendMessage(view: View) {
        val messageInput: EditText = findViewById(R.id.messageEditText)
        val messageText = messageInput.text.toString()
        val outgoingMessage = ContentModel(messageText, localDeviceIp)
        val tempMessage = ContentModel(messageText, localDeviceIp)
        messageInput.text.clear() // Clear input field after sending
        clientInstance?.sendMessage(outgoingMessage) // Send message through client
        chatAdapter?.addItemToEnd(tempMessage) // Update chat list
    }

    // Validate the student ID input by the user
    private fun validateStudentID() {
        val studentIdInput: EditText = findViewById(R.id.studentIdEditText)
        val studentIdValue = studentIdInput.text.toString().toIntOrNull()
        val validationMessage = if (studentIdValue != null && studentIdValue in 810000000 until 900000000) {
            isStudentIDValid = true
            "Updating Listings..."
        } else {
            isStudentIDValid = false
            "ID is invalid"
        }
        Toast.makeText(this, validationMessage, Toast.LENGTH_SHORT).show()
    }

    // Callback when Wi-Fi Direct adapter state changes (enabled/disabled)
    override fun onWiFiDirectStateChanged(isEnabled: Boolean) {
        isWifiDirectEnabled = isEnabled
        val stateMessage = if (isEnabled) {
            "WiFi Direct is enabled!"
        } else {
            "WiFi Direct is disabled! Please enable the WiFi adapter."
        }
        Toast.makeText(this, stateMessage, Toast.LENGTH_SHORT).show()
        updateUI() // Update the UI based on adapter state
    }

    // Callback when the peer list is updated
    override fun onPeerListUpdated(peerDevices: Collection<WifiP2pDevice>) {
        Toast.makeText(this, "Updated listing of nearby WiFi Direct devices", Toast.LENGTH_SHORT).show()
        hasAvailablePeers = peerDevices.isNotEmpty() // Check if there are any peers
        peerAdapter?.updateList(peerDevices) // Update the peer list adapter
        updateUI() // Refresh the UI
    }

    // Callback when the group status changes (group formation)
    override fun onGroupStatusChanged(groupInfo: WifiP2pGroup?) {
        val groupStatusMessage = if (groupInfo == null) {
            "Group is not formed"
        } else {
            "Group has been formed"
        }
        Toast.makeText(this, groupStatusMessage, Toast.LENGTH_SHORT).show()
        isConnectedToPeer = groupInfo != null // Update connection status

        // Manage server and client based on group ownership
        if (groupInfo == null) {
            serverInstance?.close()
            clientInstance?.close()
        } else if (groupInfo.isGroupOwner && serverInstance == null) {
            serverInstance = Server(this)
            localDeviceIp = "192.168.49.1" // Set default IP for the group owner
        } else if (!groupInfo.isGroupOwner && clientInstance == null) {
            clientInstance = Client(this, studentIdSeed.toString())
            localDeviceIp = clientInstance!!.ip // Get client IP address
        }
    }

    // Callback when the device status changes
    override fun onDeviceStatusChanged(device: WifiP2pDevice) {
        Toast.makeText(this, "Device status has been updated", Toast.LENGTH_SHORT).show()
    }

    // Callback for handling peer selection (when a user taps on a peer)
    override fun onPeerClicked(peerDevice: WifiP2pDevice) {
        wifiDirectManager?.connectToPeer(peerDevice) // Initiate connection to the selected peer
    }

    // Callback for handling received content (messages from peers)
    override fun onContent(contentMessage: ContentModel) {
        runOnUiThread {
            chatAdapter?.addItemToEnd(contentMessage) // Update chat list on UI thread
        }
    }

    // Hide the soft keyboard
    private fun hideKeyboard() {
        val currentFocusView = this.currentFocus
        if (currentFocusView != null) {
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(currentFocusView.windowToken, 0) // Hide the keyboard
        }
    }

    // Handle failed connection attempts
    override fun failedConnection() {
        runOnUiThread {
            isConnectedToPeer = false
            updateUI() // Update UI on failed connection
        }
    }
}
