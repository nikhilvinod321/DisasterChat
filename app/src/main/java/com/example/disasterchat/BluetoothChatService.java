package com.example.disasterchat;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.*;
import java.util.UUID;
import java.util.function.Consumer;

public class BluetoothChatService {
    private static final String TAG = "BluetoothChatService";
    private static final String SERVICE_NAME = "DisasterChat";
    private static final UUID SERVICE_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler;
    private final Consumer<String> onMessageReceived;
    private final Consumer<BluetoothDevice> onDeviceConnected;
    private final Runnable onDeviceDisconnected;

    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;

    private int state;
    private static final int STATE_NONE = 0;
    private static final int STATE_LISTEN = 1;
    private static final int STATE_CONNECTING = 2;
    private static final int STATE_CONNECTED = 3;

    public BluetoothChatService(Context context, Handler handler,
                                Consumer<String> onMessageReceived,
                                Consumer<BluetoothDevice> onDeviceConnected,
                                Runnable onDeviceDisconnected) {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.handler = handler;
        this.onMessageReceived = onMessageReceived;
        this.onDeviceConnected = onDeviceConnected;
        this.onDeviceDisconnected = onDeviceDisconnected;
        this.state = STATE_NONE;
    }

    public synchronized void start() {
        Log.d(TAG, "start");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(STATE_LISTEN);

        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    public synchronized void connect(BluetoothDevice device) {
        Log.d(TAG, "connect to: " + device);

        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "connected");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        onDeviceConnected.accept(device);
        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        setState(STATE_NONE);
    }

    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED) return;
            r = connectedThread;
        }
        r.write(out);
    }

    private void connectionFailed() {
        setState(STATE_LISTEN);
        onDeviceDisconnected.run();
        BluetoothChatService.this.start();
    }

    private void connectionLost() {
        setState(STATE_LISTEN);
        onDeviceDisconnected.run();
        BluetoothChatService.this.start();
    }

    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + this.state + " -> " + state);
        this.state = state;
    }

    public synchronized boolean isConnected() {
        return state == STATE_CONNECTED;
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: listen() failed", e);
            }
            serverSocket = tmp;
        }

        public void run() {
            Log.d(TAG, "Socket Type: BEGIN acceptThread" + this);
            setName("AcceptThread");

            BluetoothSocket socket;

            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: accept() failed", e);
                    break;
                }

                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (state) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "END acceptThread");
        }

        public void cancel() {
            Log.d(TAG, "Socket Type: cancel " + this);
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: close() of server failed", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;

            try {
                tmp = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: create() failed", e);
            }
            socket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN connectThread");
            setName("ConnectThread");

            bluetoothAdapter.cancelDiscovery();

            try {
                socket.connect();
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            synchronized (BluetoothChatService.this) {
                connectThread = null;
            }

            connected(socket, device);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inStream;
        private final OutputStream outStream;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            inStream = tmpIn;
            outStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN connectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = inStream.read(buffer);
                    String receivedMessage = new String(buffer, 0, bytes);
                    onMessageReceived.accept(receivedMessage);
                } catch (IOException e) {
                    Log.d(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                outStream.write(buffer);
                outStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}