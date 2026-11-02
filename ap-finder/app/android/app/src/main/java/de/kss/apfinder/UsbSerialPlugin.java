package de.kss.apfinder;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serielle USB-Verbindung (CDC-ACM) zum ESP32-C5 über den Android-USB-Host.
 * Der native USB-Port des ESP32-C5 (USB-Serial/JTAG, VID 0x303A) ist ein
 * CDC-ACM-Gerät; ein Treiber ist nicht nötig.
 *
 * JS:  Capacitor.Plugins.UsbSerial
 *   list()                     → { devices: [{ vendorId, productId, name }] }
 *   connect({ vendorId? })     → { vendorId, productId, name }
 *   write({ data })            → sendet Text (UTF-8)
 *   disconnect()
 * Ereignisse:
 *   "data"  { lines: [ … ] }   vollständige Textzeilen
 *   "state" { state: "connected" | "disconnected" | "attached" }
 */
@CapacitorPlugin(name = "UsbSerial")
public class UsbSerialPlugin extends Plugin {

    private static final String ACTION_USB_PERMISSION = "de.kss.apfinder.USB_PERMISSION";
    private static final int ESPRESSIF_VID = 0x303A;
    // CDC-Klassenanfragen
    private static final int REQTYPE_HOST_TO_DEVICE_CLASS = 0x21;
    private static final int SET_LINE_CODING = 0x20;
    private static final int SET_CONTROL_LINE_STATE = 0x22;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface commInterface;
    private UsbInterface dataInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private Thread readThread;
    private volatile boolean running;
    private PluginCall pendingConnect;
    private UsbDevice pendingDevice;
    private final Object writeLock = new Object();
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                PluginCall call = pendingConnect;
                UsbDevice dev = pendingDevice;
                pendingConnect = null;
                pendingDevice = null;
                if (call == null || dev == null) return;
                if (usbManager.hasPermission(dev)) {
                    openDevice(dev, call);
                } else {
                    call.reject("USB-Zugriff wurde nicht erlaubt.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = getUsbDevice(intent);
                if (dev != null && device != null && dev.getDeviceName().equals(device.getDeviceName())) {
                    close(true);
                }
            }
        }
    };

    @Override
    public void load() {
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(getContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            JSObject ev = new JSObject();
            ev.put("state", "attached");
            notifyListeners("state", ev);
        }
    }

    @Override
    protected void handleOnDestroy() {
        close(false);
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // war nicht registriert
            }
            receiverRegistered = false;
        }
        super.handleOnDestroy();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private UsbDevice findDevice(int vendorId) {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (vendorId < 0 || d.getVendorId() == vendorId) return d;
        }
        return null;
    }

    private static String deviceName(UsbDevice d) {
        String n = d.getProductName();
        return n != null ? n : d.getDeviceName();
    }

    @PluginMethod
    public void list(PluginCall call) {
        JSArray arr = new JSArray();
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            JSObject o = new JSObject();
            o.put("vendorId", d.getVendorId());
            o.put("productId", d.getProductId());
            o.put("name", deviceName(d));
            arr.put(o);
        }
        JSObject ret = new JSObject();
        ret.put("devices", arr);
        ret.put("connected", connection != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (connection != null) {
            resolveConnected(call);
            return;
        }
        int vid = call.getInt("vendorId", ESPRESSIF_VID);
        UsbDevice d = findDevice(vid);
        if (d == null) {
            call.reject("Kein ESP32 gefunden – Messkopf per USB-C (OTG) anstecken.");
            return;
        }
        if (usbManager.hasPermission(d)) {
            openDevice(d, call);
            return;
        }
        if (pendingConnect != null) {
            pendingConnect.reject("abgelöst");
        }
        pendingConnect = call;
        pendingDevice = d;
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getContext().getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(getContext(), 0, intent, flags);
        usbManager.requestPermission(d, pi);
    }

    private void resolveConnected(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("vendorId", device.getVendorId());
        ret.put("productId", device.getProductId());
        ret.put("name", deviceName(device));
        call.resolve(ret);
    }

    private void openDevice(UsbDevice d, PluginCall call) {
        close(false);
        UsbDeviceConnection c = usbManager.openDevice(d);
        if (c == null) {
            call.reject("USB-Gerät lässt sich nicht öffnen.");
            return;
        }
        UsbInterface comm = null;
        UsbInterface data = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && comm == null) {
                comm = itf;
            } else if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA && data == null) {
                UsbEndpoint ei = null;
                UsbEndpoint eo = null;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep;
                    else eo = ep;
                }
                if (ei != null && eo != null) {
                    data = itf;
                    in = ei;
                    out = eo;
                }
            }
        }
        if (data == null) {
            c.close();
            call.reject("Keine serielle Schnittstelle (CDC) am Gerät gefunden.");
            return;
        }
        if (comm != null) c.claimInterface(comm, true);
        if (!c.claimInterface(data, true)) {
            c.close();
            call.reject("Serielle Schnittstelle ist belegt.");
            return;
        }
        int ifIndex = comm != null ? comm.getId() : 0;
        // 115200 8N1 (für USB-Serial/JTAG ohne Bedeutung, schadet aber nicht)
        byte[] lineCoding = {(byte) 0x00, (byte) 0xC2, (byte) 0x01, (byte) 0x00, 0, 0, 8};
        c.controlTransfer(REQTYPE_HOST_TO_DEVICE_CLASS, SET_LINE_CODING, 0, ifIndex, lineCoding, lineCoding.length, 500);
        // DTR = 1, RTS = 0: RTS ohne DTR würde den ESP32 zurücksetzen
        c.controlTransfer(REQTYPE_HOST_TO_DEVICE_CLASS, SET_CONTROL_LINE_STATE, 0x01, ifIndex, null, 0, 500);

        device = d;
        connection = c;
        commInterface = comm;
        dataInterface = data;
        endpointIn = in;
        endpointOut = out;
        startReader();
        resolveConnected(call);
        JSObject ev = new JSObject();
        ev.put("state", "connected");
        notifyListeners("state", ev);
    }

    private void startReader() {
        running = true;
        final UsbDeviceConnection c = connection;
        final UsbEndpoint in = endpointIn;
        readThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            List<String> batch = new ArrayList<>();
            long lastFlush = System.currentTimeMillis();
            int failures = 0;
            while (running) {
                int n = c.bulkTransfer(in, buf, buf.length, 100);
                if (n > 0) {
                    failures = 0;
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == '\n') {
                            String s = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
                            line.reset();
                            if (!s.isEmpty()) batch.add(s);
                        } else if (line.size() < 8192) {
                            line.write(b);
                        }
                    }
                } else if (n < 0) {
                    // -1 = Zeitüberschreitung oder Fehler; gelegentlich prüfen, ob das Gerät noch da ist
                    if (++failures % 50 == 0 && device != null && !usbManager.getDeviceList().containsKey(device.getDeviceName())) {
                        break;
                    }
                }
                long now = System.currentTimeMillis();
                if (!batch.isEmpty() && (batch.size() >= 25 || now - lastFlush >= 40)) {
                    JSObject ev = new JSObject();
                    ev.put("lines", new JSArray(batch));
                    notifyListeners("data", ev);
                    batch = new ArrayList<>();
                    lastFlush = now;
                }
            }
            if (running) {
                getBridge().executeOnMainThread(() -> close(true));
            }
        }, "apfinder-usb-read");
        readThread.setDaemon(true);
        readThread.start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data", "");
        UsbDeviceConnection c = connection;
        UsbEndpoint out = endpointOut;
        if (c == null || out == null) {
            call.reject("Nicht verbunden.");
            return;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            int off = 0;
            while (off < bytes.length) {
                int len = Math.min(bytes.length - off, 512);
                int n = c.bulkTransfer(out, bytes, off, len, 1000);
                if (n <= 0) {
                    call.reject("Schreibfehler auf USB.");
                    return;
                }
                off += n;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        close(true);
        call.resolve();
    }

    private synchronized void close(boolean notify) {
        boolean wasOpen = connection != null;
        running = false;
        Thread t = readThread;
        readThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (connection != null) {
            try {
                connection.controlTransfer(REQTYPE_HOST_TO_DEVICE_CLASS, SET_CONTROL_LINE_STATE, 0x00,
                        commInterface != null ? commInterface.getId() : 0, null, 0, 200);
            } catch (Exception ignored) {
                // Gerät evtl. schon weg
            }
            if (dataInterface != null) connection.releaseInterface(dataInterface);
            if (commInterface != null) connection.releaseInterface(commInterface);
            connection.close();
        }
        connection = null;
        device = null;
        commInterface = null;
        dataInterface = null;
        endpointIn = null;
        endpointOut = null;
        if (notify && wasOpen) {
            JSObject ev = new JSObject();
            ev.put("state", "disconnected");
            notifyListeners("state", ev);
        }
    }
}
