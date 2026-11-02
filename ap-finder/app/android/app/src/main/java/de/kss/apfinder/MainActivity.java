package de.kss.apfinder;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Eigenes Plugin vor super.onCreate registrieren
        registerPlugin(UsbSerialPlugin.class);
        super.onCreate(savedInstanceState);
        // Messgerät: Bildschirm bleibt an, solange die App im Vordergrund ist
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
