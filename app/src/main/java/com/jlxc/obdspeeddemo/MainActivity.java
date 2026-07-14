package com.jlxc.obdspeeddemo;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_PERMISSIONS = 1001;
    private static final int HTTP_PORT = 47240;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object ioLock = new Object();

    private BluetoothAdapter bluetoothAdapter;
    private final List<BluetoothDevice> bondedDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private BluetoothDevice selectedDevice;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicBoolean ecuOnline = new AtomicBoolean(false);

    private Spinner deviceSpinner;
    private Button refreshButton;
    private Button connectButton;
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView speedText;
    private TextView rpmText;
    private TextView coolantText;
    private TextView voltageText;
    private TextView protocolText;
    private TextView httpText;
    private TextView logText;

    private volatile int speedKmh = -1;
    private volatile int rpm = -1;
    private volatile int coolantC = Integer.MIN_VALUE;
    private volatile String voltage = "";
    private volatile String protocol = "AUTO";
    private volatile String lastRaw = "";
    private volatile boolean pid0dSupported = false;
    private volatile boolean pid0cSupported = false;
    private volatile boolean pid05Supported = false;

    private Thread readerThread;
    private HttpServerThread httpThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        requestNeededPermissions();
        startHttpServer();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("OBD Speed Demo");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("先在系统蓝牙里配对：OBDII，配对码通常 1234 / 0000。然后回到这里选择已配对设备。");
        hint.setTextSize(14);
        hint.setTextColor(Color.rgb(90, 90, 90));
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < bondedDevices.size()) selectedDevice = bondedDevices.get(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { selectedDevice = null; }
        });
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, dp(8));
        refreshButton = makeButton("刷新设备");
        connectButton = makeButton("连接初始化");
        row1.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        row1.addView(connectButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        startButton = makeButton("开始读取");
        stopButton = makeButton("停止/断开");
        row2.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        row2.addView(stopButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(row2);

        statusText = makeInfoText("状态：未连接");
        root.addView(statusText);

        speedText = new TextView(this);
        speedText.setText("-- km/h");
        speedText.setTextSize(54);
        speedText.setGravity(Gravity.CENTER);
        speedText.setTextColor(Color.rgb(0, 0, 0));
        speedText.setPadding(0, dp(10), 0, dp(6));
        root.addView(speedText, new LinearLayout.LayoutParams(-1, -2));

        rpmText = makeInfoText("转速：-- rpm");
        coolantText = makeInfoText("水温：-- °C");
        voltageText = makeInfoText("电压：-- V");
        protocolText = makeInfoText("协议：--");
        httpText = makeInfoText("HTTP：启动中...");
        root.addView(rpmText);
        root.addView(coolantText);
        root.addView(voltageText);
        root.addView(protocolText);
        root.addView(httpText);

        TextView logTitle = makeInfoText("原始返回 / 日志：");
        logTitle.setPadding(0, dp(10), 0, 0);
        root.addView(logTitle);

        ScrollView scroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.rgb(45, 45, 45));
        logText.setText("等待操作...\n");
        logText.setTextIsSelectable(true);
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        refreshButton.setOnClickListener(v -> loadBondedDevices());
        connectButton.setOnClickListener(v -> connectSelectedDevice());
        startButton.setOnClickListener(v -> startReading());
        stopButton.setOnClickListener(v -> {
            stopReading();
            disconnect();
        });

        loadBondedDevices();
        updateHttpText();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private TextView makeInfoText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(Color.rgb(30, 30, 30));
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!permissions.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) loadBondedDevices();
    }

    private boolean hasBtConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadBondedDevices() {
        deviceAdapter.clear();
        bondedDevices.clear();
        selectedDevice = null;

        if (bluetoothAdapter == null) {
            appendLog("这台设备不支持蓝牙。\n");
            deviceAdapter.add("无蓝牙适配器");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            appendLog("系统蓝牙未开启，请先打开蓝牙。\n");
            deviceAdapter.add("请先打开蓝牙");
            return;
        }
        if (!hasBtConnectPermission()) {
            appendLog("缺少蓝牙连接权限，请授权后刷新。\n");
            deviceAdapter.add("缺少蓝牙权限");
            return;
        }

        try {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null) {
                for (BluetoothDevice d : paired) {
                    bondedDevices.add(d);
                    String name = safeName(d);
                    String addr = d.getAddress();
                    deviceAdapter.add(name + "  " + addr);
                }
            }
        } catch (SecurityException e) {
            appendLog("读取已配对设备失败：" + e.getMessage() + "\n");
        }

        if (bondedDevices.isEmpty()) {
            deviceAdapter.add("没有已配对设备：请去系统蓝牙配对 OBDII / 1234");
        } else {
            selectedDevice = bondedDevices.get(0);
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private String safeName(BluetoothDevice d) {
        try {
            String name = hasBtConnectPermission() ? d.getName() : null;
            return TextUtils.isEmpty(name) ? "未知设备" : name;
        } catch (SecurityException e) {
            return "未知设备";
        }
    }

    private void connectSelectedDevice() {
        if (selectedDevice == null) {
            appendLog("请先选择已配对的 OBD 蓝牙设备。\n");
            return;
        }
        if (connecting.get() || connected.get()) {
            appendLog("正在连接或已经连接。\n");
            return;
        }
        if (!hasBtConnectPermission()) {
            appendLog("缺少蓝牙权限，请先授权。\n");
            requestNeededPermissions();
            return;
        }

        connecting.set(true);
        status("状态：正在连接 " + safeName(selectedDevice) + " ...");
        appendLog("开始连接：" + safeName(selectedDevice) + "\n");

        new Thread(() -> {
            try {
                if (hasBtConnectPermission()) bluetoothAdapter.cancelDiscovery();
                BluetoothSocket s;
                try {
                    s = selectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                    s.connect();
                } catch (Exception first) {
                    appendLog("标准 SPP 连接失败，尝试备用通道 1：" + first.getMessage() + "\n");
                    try {
                        Method m = selectedDevice.getClass().getMethod("createRfcommSocket", int.class);
                        s = (BluetoothSocket) m.invoke(selectedDevice, 1);
                        s.connect();
                    } catch (Exception second) {
                        throw new IOException("备用连接也失败：" + second.getMessage(), second);
                    }
                }

                synchronized (ioLock) {
                    socket = s;
                    inputStream = s.getInputStream();
                    outputStream = s.getOutputStream();
                }
                connected.set(true);
                connecting.set(false);
                ecuOnline.set(false);
                status("状态：ELM 已连接，开始初始化...");
                appendLog("蓝牙串口已连接。\n");
                initElm();
                status("状态：ELM 已连接，可开始读取。ECU 状态会在读取后更新。");
                updateUiValues();
            } catch (Exception e) {
                connecting.set(false);
                connected.set(false);
                appendLog("连接失败：" + e.getMessage() + "\n");
                status("状态：连接失败");
                disconnect();
            }
        }, "obd-connect").start();
    }

    private void initElm() throws IOException, InterruptedException {
        appendLog("初始化 ELM327...\n");
        lastRaw = sendCommand("ATZ", 3500);
        Thread.sleep(1200);
        sendCommand("ATE0", 1500);  // echo off
        sendCommand("ATL0", 1500);  // linefeeds off
        sendCommand("ATS0", 1500);  // spaces off
        sendCommand("ATH0", 1500);  // headers off, easier parsing
        sendCommand("ATAT1", 1500); // adaptive timing auto 1
        sendCommand("ATSP0", 2500); // automatic protocol
        String dp = sendCommand("ATDP", 2500);
        protocol = cleanupElmText(dp);
        if (TextUtils.isEmpty(protocol)) protocol = "AUTO";
        appendLog("协议返回：" + protocol + "\n");

        String pids = sendCommand("0100", 2500);
        pid0dSupported = isPidSupported0100(pids, 0x0D);
        pid0cSupported = isPidSupported0100(pids, 0x0C);
        pid05Supported = isPidSupported0100(pids, 0x05);
        appendLog("PID 支持检测：车速010D=" + pid0dSupported + "，转速010C=" + pid0cSupported + "，水温0105=" + pid05Supported + "\n");
    }

    private void startReading() {
        if (!connected.get()) {
            appendLog("还没有连接 ELM，请先点“连接初始化”。\n");
            return;
        }
        if (reading.get()) {
            appendLog("已经在读取。\n");
            return;
        }
        reading.set(true);
        status("状态：正在读取标准 OBD2 数据...");
        readerThread = new Thread(() -> {
            while (reading.get() && connected.get()) {
                long loopStart = System.currentTimeMillis();
                try {
                    String speedResp = sendCommand("010D", 1200);
                    Integer spd = parsePidOneByte(speedResp, "0D");
                    if (spd != null) {
                        speedKmh = spd;
                        ecuOnline.set(true);
                    }

                    String rpmResp = sendCommand("010C", 1200);
                    Integer newRpm = parseRpm(rpmResp);
                    if (newRpm != null) {
                        rpm = newRpm;
                        ecuOnline.set(true);
                    }

                    String tempResp = sendCommand("0105", 1200);
                    Integer temp = parsePidOneByte(tempResp, "05");
                    if (temp != null) {
                        coolantC = temp - 40;
                        ecuOnline.set(true);
                    }

                    String rvResp = sendCommand("ATRV", 1200);
                    String v = parseVoltage(rvResp);
                    if (!TextUtils.isEmpty(v)) voltage = v;

                    updateUiValues();
                } catch (Exception e) {
                    appendLog("读取异常：" + e.getMessage() + "\n");
                    ecuOnline.set(false);
                    updateUiValues();
                    sleep(800);
                }

                long used = System.currentTimeMillis() - loopStart;
                long sleepMs = Math.max(150, 500 - used);
                sleep(sleepMs);
            }
            status(connected.get() ? "状态：已停止读取，ELM 仍连接" : "状态：已断开");
        }, "obd-reader");
        readerThread.start();
    }

    private void stopReading() {
        reading.set(false);
        Thread t = readerThread;
        if (t != null) t.interrupt();
        readerThread = null;
    }

    private void disconnect() {
        stopReading();
        connected.set(false);
        ecuOnline.set(false);
        connecting.set(false);
        synchronized (ioLock) {
            try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
            try { if (outputStream != null) outputStream.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            inputStream = null;
            outputStream = null;
            socket = null;
        }
        status("状态：已断开");
        updateUiValues();
    }

    private String sendCommand(String cmd, int timeoutMs) throws IOException {
        synchronized (ioLock) {
            if (socket == null || outputStream == null || inputStream == null) {
                throw new IOException("蓝牙串口未连接");
            }
            drainInput();
            String command = cmd.endsWith("\r") ? cmd : cmd + "\r";
            outputStream.write(command.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < deadline) {
                int available = inputStream.available();
                if (available > 0) {
                    int read = inputStream.read(buf, 0, Math.min(buf.length, available));
                    if (read > 0) {
                        String chunk = new String(buf, 0, read, StandardCharsets.US_ASCII);
                        sb.append(chunk);
                        if (sb.indexOf(">") >= 0) break;
                    }
                } else {
                    sleep(20);
                }
            }
            String resp = sb.toString();
            lastRaw = resp;
            appendLog("→ " + cmd + "\n" + cleanupForLog(resp) + "\n");
            return resp;
        }
    }

    private void drainInput() {
        try {
            byte[] buf = new byte[256];
            while (inputStream != null && inputStream.available() > 0) {
                int n = inputStream.read(buf, 0, Math.min(buf.length, inputStream.available()));
                if (n <= 0) break;
            }
        } catch (Exception ignored) {}
    }

    private static Integer parsePidOneByte(String response, String pidHex) {
        String hex = onlyHex(response);
        String marker = "41" + pidHex.toUpperCase(Locale.US);
        int searchFrom = 0;
        while (true) {
            int idx = hex.indexOf(marker, searchFrom);
            if (idx < 0) return null;
            int dataStart = idx + marker.length();
            if (hex.length() >= dataStart + 2) {
                try {
                    return Integer.parseInt(hex.substring(dataStart, dataStart + 2), 16);
                } catch (Exception ignored) {}
            }
            searchFrom = idx + 1;
        }
    }

    private static Integer parseRpm(String response) {
        String hex = onlyHex(response);
        String marker = "410C";
        int searchFrom = 0;
        while (true) {
            int idx = hex.indexOf(marker, searchFrom);
            if (idx < 0) return null;
            int dataStart = idx + marker.length();
            if (hex.length() >= dataStart + 4) {
                try {
                    int a = Integer.parseInt(hex.substring(dataStart, dataStart + 2), 16);
                    int b = Integer.parseInt(hex.substring(dataStart + 2, dataStart + 4), 16);
                    return ((a * 256) + b) / 4;
                } catch (Exception ignored) {}
            }
            searchFrom = idx + 1;
        }
    }

    private static String parseVoltage(String response) {
        Matcher m = Pattern.compile("([0-9]{1,2}(?:\\.[0-9]+)?)\\s*V", Pattern.CASE_INSENSITIVE).matcher(response);
        if (m.find()) return m.group(1) + " V";
        return "";
    }

    private static boolean isPidSupported0100(String response, int pid) {
        // Response example: 41 00 BE 1F A8 13. PIDs 01-20 are represented by a 32-bit bitmap.
        if (pid < 1 || pid > 0x20) return false;
        String hex = onlyHex(response);
        int idx = hex.indexOf("4100");
        if (idx < 0 || hex.length() < idx + 12) return false;
        try {
            long bitmap = Long.parseLong(hex.substring(idx + 4, idx + 12), 16);
            int bitIndex = 0x20 - pid;
            return ((bitmap >> bitIndex) & 1L) == 1L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String onlyHex(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
    }

    private static String cleanupElmText(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ").replace(">", " ").trim();
        t = t.replaceAll("\\s+", " ");
        // Remove common echoes when present.
        t = t.replace("ATDP", "").replace("ATZ", "").trim();
        return t;
    }

    private static String cleanupForLog(String s) {
        if (s == null || s.length() == 0) return "(无返回)";
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    private void updateUiValues() {
        mainHandler.post(() -> {
            speedText.setText(speedKmh >= 0 ? speedKmh + " km/h" : "-- km/h");
            rpmText.setText("转速：" + (rpm >= 0 ? rpm + " rpm" : "-- rpm") + supportSuffix(pid0cSupported));
            coolantText.setText("水温：" + (coolantC != Integer.MIN_VALUE ? coolantC + " °C" : "-- °C") + supportSuffix(pid05Supported));
            voltageText.setText("电压：" + (!TextUtils.isEmpty(voltage) ? voltage : "-- V"));
            protocolText.setText("协议：" + protocol + "    ECU：" + (ecuOnline.get() ? "在线" : "未确认") + supportSuffix(pid0dSupported));
            String state;
            if (connecting.get()) state = "正在连接";
            else if (connected.get()) state = "ELM已连接";
            else state = "未连接";
            statusText.setText("状态：" + state + " / ECU " + (ecuOnline.get() ? "已响应" : "未响应"));
        });
    }

    private String supportSuffix(boolean supported) {
        return supported ? "" : "  （未确认支持）";
    }

    private void status(String text) {
        mainHandler.post(() -> statusText.setText(text));
    }

    private void appendLog(String text) {
        mainHandler.post(() -> {
            String old = logText.getText().toString();
            String next = old + text;
            if (next.length() > 12000) next = next.substring(next.length() - 12000);
            logText.setText(next);
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void startHttpServer() {
        if (httpThread != null) return;
        httpThread = new HttpServerThread();
        httpThread.start();
        updateHttpText();
    }

    private void updateHttpText() {
        String ip = getLocalIpv4Address();
        String url = TextUtils.isEmpty(ip) ? ("http://本机IP:" + HTTP_PORT + "/data") : ("http://" + ip + ":" + HTTP_PORT + "/data");
        if (httpText != null) httpText.setText("HTTP接口：" + url + "  /speed  /status");
    }

    private String getLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (!host.startsWith("127.")) return host;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private class HttpServerThread extends Thread {
        private ServerSocket serverSocket;
        private final AtomicBoolean httpRunning = new AtomicBoolean(true);

        HttpServerThread() { super("obd-http-server"); }

        @Override public void run() {
            try {
                serverSocket = new ServerSocket(HTTP_PORT);
                appendLog("HTTP 服务已启动，端口 " + HTTP_PORT + "\n");
            } catch (IOException e) {
                appendLog("HTTP 服务启动失败：" + e.getMessage() + "\n");
                return;
            }
            while (httpRunning.get()) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (IOException e) {
                    if (httpRunning.get()) appendLog("HTTP 异常：" + e.getMessage() + "\n");
                }
            }
        }

        void shutdown() {
            httpRunning.set(false);
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleClient(Socket client) {
        try (Socket c = client;
             BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
             PrintWriter out = new PrintWriter(c.getOutputStream(), false)) {
            c.setSoTimeout(1500);
            String line = br.readLine();
            String path = "/";
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) path = parts[1];
            }
            while (line != null && line.length() > 0) line = br.readLine();

            String body;
            String contentType = "application/json; charset=utf-8";
            if (path.startsWith("/raw")) {
                body = "{\"raw\":" + jsonQuote(lastRaw) + "}";
            } else if (path.startsWith("/status") || path.startsWith("/speed") || path.startsWith("/data")) {
                body = buildJson();
            } else {
                contentType = "text/html; charset=utf-8";
                body = "<html><body><h3>OBD Speed Demo</h3><p>Use <a href='/data'>/data</a>, <a href='/speed'>/speed</a>, <a href='/status'>/status</a>, <a href='/raw'>/raw</a>.</p></body></html>";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            out.print("HTTP/1.1 200 OK\r\n");
            out.print("Content-Type: " + contentType + "\r\n");
            out.print("Access-Control-Allow-Origin: *\r\n");
            out.print("Cache-Control: no-store\r\n");
            out.print("Content-Length: " + bytes.length + "\r\n");
            out.print("Connection: close\r\n\r\n");
            out.flush();
            c.getOutputStream().write(bytes);
            c.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    private String buildJson() {
        return "{" +
                "\"speedKmh\":" + (speedKmh >= 0 ? speedKmh : "null") + "," +
                "\"rpm\":" + (rpm >= 0 ? rpm : "null") + "," +
                "\"coolantC\":" + (coolantC != Integer.MIN_VALUE ? coolantC : "null") + "," +
                "\"voltage\":" + jsonQuote(voltage) + "," +
                "\"elmConnected\":" + connected.get() + "," +
                "\"ecuConnected\":" + ecuOnline.get() + "," +
                "\"reading\":" + reading.get() + "," +
                "\"protocol\":" + jsonQuote(protocol) + "," +
                "\"pidSupport\":{\"speed010D\":" + pid0dSupported + ",\"rpm010C\":" + pid0cSupported + ",\"coolant0105\":" + pid05Supported + "}" +
                "}";
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 32) sb.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopReading();
        disconnect();
        if (httpThread != null) {
            httpThread.shutdown();
            httpThread = null;
        }
    }
}
