package com.internalpositioning.find3.find3app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.util.Linkify;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    // logging
    private final String TAG = "MainActivity";


    // background manager
    private PendingIntent recurringLl24 = null;
    WebSocketClient mWebSocketClient = null;
    private RemindTask oneSecondTimer = null;
    Timer timer = null;

    private String[] autocompleteLocations = new String[] {"bedroom","living room","kitchen","bathroom", "office"};

    @Override
    protected void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy()");
        if (timer != null) timer.cancel();
        if (mWebSocketClient != null) {
            mWebSocketClient.close();
        }
        super.onDestroy();
    }

    class RemindTask extends TimerTask {
        private Integer counter = 0;

        public void resetCounter() {
            counter = 0;
        }
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    counter++;
                    if (mWebSocketClient != null) {
                        if (mWebSocketClient.isClosed()) {
                            connectWebSocket();
                        }
                    }
                    TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                    String currentText = rssi_msg.getText().toString();
                    if (currentText.contains("ago: ")) {
                        String[] currentTexts = currentText.split("ago: ");
                        currentText = currentTexts[1];
                    }
                    rssi_msg.setText(counter + " seconds ago: " + currentText);
                }
            });
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // check permissions
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.INTERNET, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_WIFI_STATE}, 1);
        }

        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
        rssi_msg.setText("not running");

        if (timer != null) timer.cancel();

        // check to see if there are preferences
        SharedPreferences sharedPref = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
        EditText familyNameEdit = (EditText) findViewById(R.id.familyName);
        familyNameEdit.setText(sharedPref.getString("familyName", ""));
        EditText deviceNameEdit = (EditText) findViewById(R.id.deviceName);
        deviceNameEdit.setText(sharedPref.getString("deviceName", ""));
        EditText serverAddressEdit = (EditText) findViewById(R.id.serverAddress);
        serverAddressEdit.setText(sharedPref.getString("serverAddress", ((EditText) findViewById(R.id.serverAddress)).getText().toString()));
        CheckBox checkBoxAllowGPS = (CheckBox) findViewById(R.id.allowGPS);
        checkBoxAllowGPS.setChecked(sharedPref.getBoolean("allowGPS",false));


        AutoCompleteTextView textView = (AutoCompleteTextView) findViewById(R.id.locationName);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, autocompleteLocations);
        textView.setAdapter(adapter);


        ToggleButton toggleButtonTracking = (ToggleButton) findViewById(R.id.toggleScanType);

        toggleButtonTracking.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                rssi_msg.setText("not running");
                Log.d(TAG, "toggle set to false");

                CompoundButton scanButton = (CompoundButton) findViewById(R.id.toggleButton);
                scanButton.setChecked(false);
            }
        });

        ToggleButton toggleButton = (ToggleButton) findViewById(R.id.toggleButton);
        toggleButton.setChecked(ScanService.IS_SERVICE_RUNNING);
        scanIsRunning(ScanService.IS_SERVICE_RUNNING);

        toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                                    @Override
                                                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                                        if (isChecked) {
                                                            TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                                                            String familyName = ((EditText) findViewById(R.id.familyName)).getText().toString().toLowerCase();
                                                            if (familyName.equals("")) {
                                                                rssi_msg.setText("family name cannot be empty");
                                                                buttonView.toggle();
                                                                return;
                                                            }

                                                            String serverAddress = ((EditText) findViewById(R.id.serverAddress)).getText().toString().toLowerCase();
                                                            if (serverAddress.equals("")) {
                                                                rssi_msg.setText("server address cannot be empty");
                                                                buttonView.toggle();
                                                                return;
                                                            }
                                                            if (serverAddress.contains("http") != true) {
                                                                rssi_msg.setText("must include http or https in server name");
                                                                buttonView.toggle();
                                                                return;
                                                            }
                                                            String deviceName = ((EditText) findViewById(R.id.deviceName)).getText().toString().toLowerCase();
                                                            if (deviceName.equals("")) {
                                                                rssi_msg.setText("device name cannot be empty");
                                                                buttonView.toggle();
                                                                return;
                                                            }
                                                            boolean allowGPS = ((CheckBox) findViewById(R.id.allowGPS)).isChecked();
                                                            Log.d(TAG, "allowGPS is checked: " + allowGPS);
                                                            String locationName = ((EditText) findViewById(R.id.locationName)).getText().toString().toLowerCase();

                                                            CompoundButton trackingButton = (CompoundButton) findViewById(R.id.toggleScanType);
                                                            if (trackingButton.isChecked() == false) {
                                                                locationName = "";
                                                            } else {
                                                                if (locationName.equals("")) {
                                                                    rssi_msg.setText("location name cannot be empty when learning");
                                                                    buttonView.toggle();
                                                                    return;
                                                                }
                                                            }

                                                            scanIsRunning(true);

                                                            Intent startScanService = new Intent(MainActivity.this, ScanService.class);
                                                            startScanService.setAction("start");
                                                            startScanService.putExtra("familyName", familyName);
                                                            startScanService.putExtra("deviceName", deviceName);
                                                            startScanService.putExtra("locationName", locationName);
                                                            startScanService.putExtra("serverAddress", serverAddress);
                                                            startScanService.putExtra("allowGPS", allowGPS);
                                                            try {
                                                                startForegroundService(startScanService);
                                                            } catch (Exception e) {
                                                                Log.w(TAG, e.toString());
                                                            }


                                                        } else {
                                                            Log.d(TAG, "toggle set to false");
                                                            scanIsRunning(false);
                                                            Intent stopIntent = new Intent(MainActivity.this, ScanService.class);
                                                            stopIntent.setAction("stop");
                                                            startService(stopIntent);
                                                            timer.cancel();
                                                        }
                                                    }
                                                }
        );


    }

    private void scanIsRunning(boolean isRunning){
        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
        if(isRunning) {

            String familyName = ((EditText) findViewById(R.id.familyName)).getText().toString().toLowerCase();

            String serverAddress = ((EditText) findViewById(R.id.serverAddress)).getText().toString().toLowerCase();
            String deviceName = ((EditText) findViewById(R.id.deviceName)).getText().toString().toLowerCase();
            String locationName = ((EditText) findViewById(R.id.locationName)).getText().toString().toLowerCase();
            boolean allowGPS = ((CheckBox) findViewById(R.id.allowGPS)).isChecked();

            SharedPreferences sharedPref = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString("familyName", familyName);
            editor.putString("deviceName", deviceName);
            editor.putString("serverAddress", serverAddress);
            editor.putString("locationName", locationName);
            editor.putBoolean("allowGPS", allowGPS);
            editor.commit();


            rssi_msg.setText("running");
            timer = new Timer();
            oneSecondTimer = new RemindTask();
            timer.scheduleAtFixedRate(oneSecondTimer, 1000, 1000);

            final TextView myClickableUrl = (TextView) findViewById(R.id.textInstructions);
            myClickableUrl.setText("See your results in realtime: " + serverAddress + "/view/location/" + familyName + "/" + deviceName);
            Linkify.addLinks(myClickableUrl, Linkify.WEB_URLS);

            connectWebSocket();
        } else {
            rssi_msg.setText("not running");
        }
    }

    private void connectWebSocket() {
        URI uri;
        try {
            String serverAddress = ((EditText) findViewById(R.id.serverAddress)).getText().toString();
            String familyName = ((EditText) findViewById(R.id.familyName)).getText().toString();
            String deviceName = ((EditText) findViewById(R.id.deviceName)).getText().toString();
            serverAddress = serverAddress.replace("http", "ws");
            uri = new URI(serverAddress + "/ws?family=" + familyName + "&device=" + deviceName);
            Log.d("Websocket", "connect to websocket at " + uri.toString());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i("Websocket", "Opened");
                mWebSocketClient.send("Hello");
            }

            @Override
            public void onMessage(String s) {
                final String message = s;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d("Websocket", "message: " + message);
                        JSONObject json = null;
                        JSONObject fingerprint = null;
                        JSONObject sensors = null;
                        JSONObject bluetooth = null;
                        JSONObject wifi = null;
                        String deviceName = "";
                        String locationName = "";
                        String familyName = "";
                        try {
                            json = new JSONObject(message);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                            return;
                        }
                        try {
                            fingerprint = new JSONObject(json.get("sensors").toString());
                            Log.d("Websocket", "fingerprint: " + fingerprint);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            sensors = new JSONObject(fingerprint.get("s").toString());
                            deviceName = fingerprint.get("d").toString();
                            familyName = fingerprint.get("f").toString();
                            locationName = fingerprint.get("l").toString();
                            Log.d("Websocket", "sensors: " + sensors);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            wifi = new JSONObject(sensors.get("wifi").toString());
                            Log.d("Websocket", "wifi: " + wifi);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            bluetooth = new JSONObject(sensors.get("bluetooth").toString());
                            Log.d("Websocket", "bluetooth: " + bluetooth);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        Log.d("Websocket", bluetooth.toString());
                        Integer bluetoothPoints = bluetooth.length();
                        Integer wifiPoints = wifi.length();
                        Long secondsAgo = null;
                        try {
                            secondsAgo = fingerprint.getLong("t");
                        } catch (Exception e) {
                            Log.w("Websocket", e);
                        }

                        if ((System.currentTimeMillis() - secondsAgo)/1000 > 3) {
                            return;
                        }
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss");
                        Date resultdate = new Date(secondsAgo);
//                        String message = sdf.format(resultdate) + ": " + bluetoothPoints.toString() + " bluetooth and " + wifiPoints.toString() + " wifi points inserted for " + familyName + "/" + deviceName;
                        String message = "1 second ago: added " + bluetoothPoints.toString() + " bluetooth and " + wifiPoints.toString() + " wifi points for " + familyName + "/" + deviceName;
                        oneSecondTimer.resetCounter();
                        if (locationName.equals("") == false) {
                            message += " at " + locationName;
                        }
                        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                        Log.d("Websocket", message);
                        rssi_msg.setText(message);

                    }
                });
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("Websocket", "Closed " + s);
            }

            @Override
            public void onError(Exception e) {
                Log.i("Websocket", "Error " + e.getMessage());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                        rssi_msg.setText("cannot connect to server, fingerprints will not be uploaded");
                    }
                });
            }
        };
        mWebSocketClient.connect();
    }




}
