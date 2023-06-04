package com.internalpositioning.find3.find3app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Created by zacks on 3/2/2018.
 */

public class ScanService extends Service {

    public static boolean IS_SERVICE_RUNNING = false;
    // logging
    private final String TAG = "ScanService";
    private final int NOTIFICATION_ID = 1001;
    private final String NOTIFICATION_CHANNELID = "find3";

    boolean mAllowRebind; // indicates whether onRebind should be used

    boolean isScanning = false;
    private final Object lock = new Object();

    // wifi scanning
    private WifiManager wifiManager;

    // bluetooth scanning
    private BluetoothAdapter BTAdapter = BluetoothAdapter.getDefaultAdapter();
    BluetoothBroadcastReceiver receiver = null;

    // post data request queue
    RequestQueue queue;
    private JSONObject jsonBody = new JSONObject();
    private JSONObject bluetoothResults = new JSONObject();
    private JSONObject wifiResults = new JSONObject();

    private String familyName = "";
    private String locationName = "";
    private String deviceName = "";
    private String serverAddress = "";
    private boolean allowGPS = false;
    private Thread mThread;
    private boolean mServiceStopped = false;
    private long mLastSuccessfulScan = 0l;
    private long mWifiTimeout = 24000l;
    private int mScansLastMinute = 0;
    private long mLastSuccessfulUpload = 0l;
    private int mUploadsLastMinute = 0;

    @Override
    public void onCreate() {
        // The service is being created
        Log.d(TAG, "creating new scan service");
        queue = Volley.newRequestQueue(this);
        // setup wifi
        wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager.isWifiEnabled() == false) {
            wifiManager.setWifiEnabled(true);
        }
        registerReceiver(mWifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));


        try {
            // setup bluetooth
            Log.d(TAG, "setting up bluetooth");
            if (receiver == null) {
                receiver = new BluetoothBroadcastReceiver();
                registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        deviceName = intent.getStringExtra("deviceName");
        familyName = intent.getStringExtra("familyName");
        locationName = intent.getStringExtra("locationName");
        serverAddress = intent.getStringExtra("serverAddress");
        allowGPS = intent.getBooleanExtra("allowGPS", false);

        Log.d(TAG, "familyName: " + familyName);

        if (intent.getAction().equals("start")) {
            IS_SERVICE_RUNNING = true;
            mServiceStopped = false;
            Log.i(TAG, "Received Start Foreground Intent ");
            mThread = new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            int i=0;
                            while (!mServiceStopped) {
                                Log.d("Service", "Service is running...");
                                try {
                                    if ((System.currentTimeMillis() - mLastSuccessfulScan) > mWifiTimeout){
                                        synchronized (lock) {
                                            if (isScanning == false) {
                                                doScan();
                                            }
                                        }
                                    }
                                    if(i == 60){
                                        mScansLastMinute = 0;
                                        mUploadsLastMinute = 0;
                                        i=0;
                                    }
                                    if(mLastSuccessfulScan != 0l) {
                                        String notificationText = "%d scans in the last minute (%dmin %ds ago)\n%d uploads in the last minute (%dmin %ds ago)";

                                        long minutesSinceLastSuccessfulScan = (System.currentTimeMillis() - mLastSuccessfulScan)/(1000*60);
                                        long secondsSinceLastSuccessfulScan = (System.currentTimeMillis() - mLastSuccessfulScan)/1000;
                                        long minutesSinceLastSuccessfulUpload = (System.currentTimeMillis() - mLastSuccessfulUpload)/(1000*60);
                                        long secondsSinceLastSuccessfulUpload = (System.currentTimeMillis() - mLastSuccessfulUpload)/1000;
                                        notificationText = String.format(notificationText, mScansLastMinute, minutesSinceLastSuccessfulScan, secondsSinceLastSuccessfulScan-(minutesSinceLastSuccessfulScan*60), mUploadsLastMinute, minutesSinceLastSuccessfulUpload, secondsSinceLastSuccessfulUpload-(minutesSinceLastSuccessfulUpload*60));
                                        updateNotification(notificationText, "Scanner is running");
                                    }
                                    i++;
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
            );
            mThread.start();


            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNELID,
                    NOTIFICATION_CHANNELID,
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);

            startForeground(NOTIFICATION_ID, buildNotification("Positioning Service is running", "find3"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (intent.getAction().equals( "stop")) {
            mServiceStopped = true;
            Log.i(TAG, "Received Stop Foreground Intent");
            //your end servce code
            stopForeground(true);
            IS_SERVICE_RUNNING = false;
            stopSelfResult(startId);
        }


        return super.onStartCommand(intent, flags, startId);
    }

    private Notification buildNotification(String text, String title){
        // The PendingIntent to launch our activity if the user selects
        // this notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent,
                        PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder notification = new Notification.Builder(this, NOTIFICATION_CHANNELID)
                .setContentText(text)
                .setContentTitle(title)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_find3_01);
        return notification.build();
    }
    private void updateNotification(String text, String title){
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text, title));
    }

    @Override
    public IBinder onBind(Intent intent) {
        // A client is binding to the service with bindService()
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        return mAllowRebind;
    }

    @Override
    public void onRebind(Intent intent) {
        // A client is binding to the service with bindService(),
        // after onUnbind() has already been called
    }

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed
        Log.v(TAG, "onDestroy");
        mServiceStopped = true;
        mThread.interrupt();
        try {
            if (receiver != null)
                unregisterReceiver(receiver);
        } catch (Exception e) {
            Log.w(TAG, e.toString());
        }
        try {
            if (mWifiScanReceiver != null)
                unregisterReceiver(mWifiScanReceiver);
        } catch (Exception e) {
            Log.w(TAG, e.toString());
        }
        stopSelf();
        IS_SERVICE_RUNNING = false;
        super.onDestroy();

    }

    private final BroadcastReceiver mWifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            // This condition is not necessary if you listen to only one action
            if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                Log.d(TAG, "timer off, trying to send data");
                List<ScanResult> wifiScanList = wifiManager.getScanResults();
                mLastSuccessfulScan = System.currentTimeMillis();
                for (int i = 0; i < wifiScanList.size(); i++) {
                    String name = wifiScanList.get(i).BSSID.toLowerCase();
                    int rssi = wifiScanList.get(i).level;
                    Log.v(TAG, "wifi: " + name + " => " + rssi + "dBm");
                    try {
                        wifiResults.put(name, rssi);
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                    }
                }
                sendData();
                BTAdapter.cancelDiscovery();
                BTAdapter = BluetoothAdapter.getDefaultAdapter();
                synchronized (lock) {
                    isScanning = false;
                    mScansLastMinute++;
                }
            }
        }
    };


    private void doScan() {
        Log.d(TAG, "doScan");
        synchronized (lock) {
            if (isScanning == true) {
                return;
            }
            isScanning = true;
        }
        bluetoothResults = new JSONObject();
        wifiResults = new JSONObject();
        BTAdapter.startDiscovery();
        // register wifi intent filter
        if (wifiManager.startScan())
        {
            Log.d(TAG, "Wifi Scan");
        } else {
            synchronized (lock) {
                isScanning = false;
            }
            Log.w(TAG, "Wifi scan throttled");
        }

    }

    // bluetooth reciever
    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = device.getAddress().toLowerCase();
                Log.v(TAG, "bluetooth: " + name + " => " + rssi + "dBm");
                try {
                    bluetoothResults.put(name, rssi);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                //sendData();
            }
        }
    }

    ;


    public void sendData() {
        try {
            String URL = serverAddress + "/data";
            jsonBody.put("f", familyName);
            jsonBody.put("d", deviceName);
            jsonBody.put("l", locationName);
            jsonBody.put("t", System.currentTimeMillis());
            JSONObject sensors = new JSONObject();
            sensors.put("bluetooth", bluetoothResults);
            sensors.put("wifi", wifiResults);
            jsonBody.put("s", sensors);
            if (allowGPS) {
                JSONObject gps = new JSONObject();
                Location loc = getLastBestLocation();
                if (loc != null) {
                    gps.put("lat",loc.getLatitude());
                    gps.put("lon",loc.getLongitude());
                    gps.put("alt",loc.getAltitude());
                    jsonBody.put("gps",gps);
                }
            }

            final String mRequestBody = jsonBody.toString();
            Log.d(TAG, mRequestBody);
            StringRequest stringRequest = new StringRequest(Request.Method.POST, URL, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {
                    Log.d(TAG, response);
                    mLastSuccessfulUpload = System.currentTimeMillis();
                    mUploadsLastMinute++;

                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e(TAG, error.toString());
                }
            }) {
                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return mRequestBody == null ? null : mRequestBody.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", mRequestBody, "utf-8");
                        return null;
                    }
                }

                @Override
                protected Response<String> parseNetworkResponse(NetworkResponse response) {
                    String responseString = "";
                    if (response != null) {
                        responseString = new String(response.data);
                    }
                    return Response.success(responseString, HttpHeaderParser.parseCacheHeaders(response));
                }
            };

            queue.add(stringRequest);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return the last know best location
     */
    private Location getLastBestLocation() {
        LocationManager mLocationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);

        Location locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);


        long GPSLocationTime = 0;
        if (null != locationGPS) {
            GPSLocationTime = locationGPS.getTime();
        }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if (0 < GPSLocationTime - NetLocationTime) {
            Log.d("GPS",locationGPS.toString());
            return locationGPS;
        } else {
            Log.d("GPS",locationNet.toString());
            return locationNet;
        }
    }
}