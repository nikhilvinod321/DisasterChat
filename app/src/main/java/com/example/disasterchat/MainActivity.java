package com.example.disasterchat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // Request codes
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_DISCOVERABLE = 2;
    private static final int REQUEST_PERMISSIONS = 3;

    // UI Components
    private ListView messagesList;
    private EditText messageInput;
    private Button sendButton, discoverButton, sosButton;
    private TextView statusText, infoText;

    // Bluetooth Components
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothChatService chatService;
    private BluetoothDevice connectedDevice;

    // Data
    private ArrayAdapter<String> messagesAdapter;
    private ArrayList<String> messages = new ArrayList<>();
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        initializeBluetooth();
        setupEventListeners();
        requestAllPermissions();
    }

    private void initializeUI() {
        // Find UI components
        messagesList = findViewById(R.id.messages_list);
        messageInput = findViewById(R.id.message_input);
        sendButton = findViewById(R.id.send_button);
        discoverButton = findViewById(R.id.discover_button);
        sosButton = findViewById(R.id.sos_button);
        statusText = findViewById(R.id.status_text);
        infoText = findViewById(R.id.info_text);

        // Setup messages list
        messagesAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, messages) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = (TextView) view;
                textView.setTextColor(ContextCompat.getColor(getContext(), R.color.white));
                textView.setPadding(16, 8, 16, 8);

                String message = getItem(position);
                if (message.startsWith("SOS") || message.startsWith("🚨")) {
                    textView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.sos_button));
                } else if (message.startsWith("Sent:")) {
                    textView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.send_button));
                } else if (message.startsWith("Received:")) {
                    textView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.discover_button));
                } else {
                    textView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.info_background));
                }

                return view;
            }
        };
        messagesList.setAdapter(messagesAdapter);

        // Add welcome message
        addMessage("System: 🚨 Disaster Chat initialized");
        addMessage("System: This app works WITHOUT internet or cellular service");
        addMessage("System: Tap 'Find Devices' to connect with others nearby");
    }

    private void initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            addMessage("ERROR: This device doesn't support Bluetooth!");
            Toast.makeText(this, "Bluetooth not supported!", Toast.LENGTH_LONG).show();
            return;
        }

        // Make device discoverable
        if (bluetoothAdapter.isEnabled()) {
            makeDeviceDiscoverable();
            initializeChatService();
        } else {
            requestEnableBluetooth();
        }
    }

    private void requestEnableBluetooth() {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }

    private void makeDeviceDiscoverable() {
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE);
    }

    private void initializeChatService() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            chatService = new BluetoothChatService(this, handler,
                    (message) -> runOnUiThread(() -> {
                        addMessage("Received: " + message);
                        updateConnectionStatus(true);

                        // Auto-respond to SOS messages
                        if (message.contains("SOS") || message.contains("🚨")) {
                            playSOSAlert();
                        }
                    }),
                    (device) -> runOnUiThread(() -> {
                        connectedDevice = device;
                        addMessage("Connected to: " + device.getName());
                        updateConnectionStatus(true);
                        updateInfoText("Connected! You can now send messages.");
                    }),
                    () -> runOnUiThread(() -> {
                        connectedDevice = null;
                        updateConnectionStatus(false);
                        updateInfoText("Connection lost. Tap 'Find Devices' to reconnect.");
                    })
            );
            addMessage("System: Bluetooth service ready - waiting for connections");
            updateInfoText("Ready! Tap 'Find Devices' to connect with others.");
        }
    }

    private void setupEventListeners() {
        // Send message button
        sendButton.setOnClickListener(v -> sendMessage());

        // Enter key for sending
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        // Discover devices button
        discoverButton.setOnClickListener(v -> {
            if (hasBluetoothPermissions()) {
                discoverDevices();
            } else {
                requestAllPermissions();
            }
        });

        // SOS button
        sosButton.setOnClickListener(v -> sendSOSMessage());

        // Register for Bluetooth device discovery
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(deviceDiscoveryReceiver, filter);
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };

            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        if (chatService != null && chatService.isConnected()) {
            chatService.write(message.getBytes());
            addMessage("Sent: " + message);
            messageInput.setText("");
            scrollToBottom();
        } else {
            addMessage("System: Not connected! Tap 'Find Devices' first.");
            Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendSOSMessage() {
        String sosMessage = getString(R.string.sos_message);

        if (chatService != null && chatService.isConnected()) {
            chatService.write(sosMessage.getBytes());
            addMessage("🚨 SOS SENT: " + sosMessage);
            Toast.makeText(this, "SOS message broadcasted!", Toast.LENGTH_SHORT).show();
        } else {
            addMessage("System: SOS failed - no connection! Find devices first.");
            Toast.makeText(this, "No connection! Find devices first.", Toast.LENGTH_LONG).show();
        }
        scrollToBottom();
    }

    private void discoverDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_SHORT).show();
            return;
        }

        addMessage("System: 🔍 Searching for nearby devices...");
        updateInfoText("Searching for devices... Make sure other devices are also searching.");

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Also check paired devices
        checkPairedDevices();

        // Start discovery
        boolean started = bluetoothAdapter.startDiscovery();
        if (!started) {
            addMessage("System: Failed to start device discovery");
        }
    }

    private void checkPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {

            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                addMessage("System: Found " + pairedDevices.size() + " paired devices");
                for (BluetoothDevice device : pairedDevices) {
                    addMessage("Paired: " + device.getName() + " - " + device.getAddress());
                }
            }
        }
    }

    private final BroadcastReceiver deviceDiscoveryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = device.getName();
                    if (deviceName == null) deviceName = "Unknown Device";

                    addMessage("Found: " + deviceName + " (" + device.getAddress() + ")");

                    // Auto-connect to first disaster chat device found
                    if (deviceName.contains("Disaster") || connectedDevice == null) {
                        bluetoothAdapter.cancelDiscovery();
                        connectToDevice(device);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                addMessage("System: Device search completed");
                updateInfoText("Search complete. If no devices found, make sure others are also searching.");
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        addMessage("System: Connecting to " + device.getName() + "...");
        updateInfoText("Connecting to " + device.getName() + "...");

        if (chatService != null) {
            chatService.connect(device);
        }
    }

    private void addMessage(String message) {
        messages.add(message);
        messagesAdapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void scrollToBottom() {
        messagesList.post(() -> {
            if (messages.size() > 0) {
                messagesList.setSelection(messages.size() - 1);
            }
        });
    }

    private void updateConnectionStatus(boolean connected) {
        statusText.setText(connected ? "Connected" : "Disconnected");
        statusText.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.status_connected : R.color.status_disconnected));
    }

    private void updateInfoText(String text) {
        infoText.setText(text);
    }

    private void playSOSAlert() {
        // Simple vibration for SOS alert
        Toast.makeText(this, "🚨 EMERGENCY SOS RECEIVED! 🚨", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                addMessage("System: Bluetooth enabled");
                makeDeviceDiscoverable();
                initializeChatService();
            } else {
                addMessage("System: Bluetooth is required for this app");
                Toast.makeText(this, "Bluetooth is required!", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode > 0) {
                addMessage("System: Device is discoverable for " + resultCode + " seconds");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                addMessage("System: All permissions granted - ready to discover devices");
                updateInfoText("Permissions granted! Tap 'Find Devices' to connect.");
            } else {
                addMessage("System: Permissions needed for device discovery");
                updateInfoText("Grant permissions to discover nearby devices.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(deviceDiscoveryReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }

        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        if (chatService != null) {
            chatService.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chatService != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            if (!chatService.isConnected()) {
                chatService.start();
            }
        }
    }
}