/**
*    Copyright 2013 University of Helsinki
*
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package eit.sdn.sdncontroller;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IntentService;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * This class is used for mobile deveice to listen to the UDP messages from
 * SDN AP agent, and react to specific management message defined by ourselves
 *
 * @author Yanhe Liu <yanhe.liu@cs.helsinki.fi>
 *
 **/

public class UDPListeningService extends IntentService {

    private boolean isEnabled = true;
    private DatagramSocket socket = null;
    private int preNetId;
    private ConnectivityChangeReceiver connChangeReceiver; // used for switch detection
    private WifiScanReceiver wifiScanReceiver; // used for scan wifi ap
    private Long startTimestamp;


    // some defaults
    private String LOG_TAG = "UDPListeningService";
    private String OUT_FILE = "result.txt";
    private int MAX_BUF_LEN = 1024;
    private String UDP_SERVER_PORT_KEY = "recv_udp_port";
    private String UDP_SERVER_PORT_DEFAULT = "7755";
    private int UDP_SERVER_PORT = 7755;
    private int AGENT_PORT = 6777;
    private String TOKEN = "\\|";
    private long DELAY_TIME_MS = 12000;
    private int DELAY_TIMES = 2;

    // Message types
    private final String MSG_SCAN = "scan";
    private final String MSG_SWITCH = "switch";
    private final String MSG_APP = "app";


    // broadcast receiver for network connection info
    private class ConnectivityChangeReceiver extends BroadcastReceiver {
        public boolean isServerAsked = false;
        public String ssid = "";

        @Override
        public void onReceive(Context context, Intent intent) {

            if (isServerAsked) {
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    NetworkInfo nInfo = (NetworkInfo)extras.get("networkInfo");
                    if (nInfo.isConnected()) {
                        WifiInfo wInfo = (WifiInfo)extras.get("wifiInfo");
                        if (wInfo.getSSID().equals(ssid)) {

                            long endTimestamp = System.currentTimeMillis();
                            float delay = (endTimestamp - startTimestamp) / (float)1000.0;
                            SDNCommonUtil.writeToExternalFile(Float.toString(delay), LOG_TAG, OUT_FILE);

                            CharSequence text = "Connected to WiFi network " + ssid;
                            int duration = Toast.LENGTH_LONG;

                            Toast toast = Toast.makeText(context, text, duration);
                            toast.show();
                            Log.i(LOG_TAG, "connected to new network: " + ssid);
                            isServerAsked = false;
                        }
                    }

                } else {
                    Log.d(LOG_TAG, "no required extras info for connChangeReceiver");
                }
            }
        }
    }

    /**
     * receive wifi scan result broadcast and then trigger our own functions
     *
     */
    private class WifiScanReceiver extends BroadcastReceiver {
        public boolean isServerAsked = false;

        public void onReceive(Context c, Intent intent) {

            if (isServerAsked) {
                StringBuilder sb = new StringBuilder();
                sb.append("s|scan|");

                WifiManager wifiManager = (WifiManager)c.getSystemService(Context.WIFI_SERVICE);
                String mac = wifiManager.getConnectionInfo().getMacAddress();
                sb.append(mac);

                List<ScanResult> scanResultList = wifiManager.getScanResults();
                for (ScanResult r: scanResultList) {
                    sb.append("|" + r.SSID + "&" + r.BSSID + "&" + r.level );
                }
                Log.d(LOG_TAG, "scan result message: " + sb.toString());

                try {
                    int dst = wifiManager.getDhcpInfo().gateway;
                    InetAddress ipAddr = InetAddress.getByName(SDNCommonUtil.littleEndianIntToIpAddress(dst));
                    new UDPSendingTask().execute(sb.toString(), ipAddr, AGENT_PORT);
                    Log.i(LOG_TAG, "sent scan reply to agent " + ipAddr.getHostAddress());
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "stop scan replying: can not using current IP address");
                    e.printStackTrace();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "unknown udp sending error");
                    e.printStackTrace();
                }

                isServerAsked = false;
            }
        }
    }

    /**
     * AsyncTask class for sending udp packets.
     *
     * Android requires to execute networking operations in a different
     * AsyncTask thread like this
     *
     */
    private class UDPSendingTask extends AsyncTask<Object, Void, Void> {
        String LOG_TAG = "UDPSendingTask";

        @Override
        protected Void doInBackground(Object... params) {
            String message = (String)params[0];
            InetAddress ip = (InetAddress)params[1];
            int port = (Integer)params[2];
            byte[] buf = message.getBytes();

            try {
                DatagramSocket socket = new DatagramSocket();
                DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, port);
                socket.send(packet);
                socket.close();
            } catch (SocketException e) {
                Log.e(LOG_TAG, "udp socket error");
                e.printStackTrace();
            } catch (IOException e) {
                Log.e(LOG_TAG, "failed to send udp packet");
                e.printStackTrace();
            }
            return null;
        }

    }


    /**
     * A required constructor for this service
     *
     */
    public UDPListeningService() {
        super("UDPListeningService");
    }

    /**
     * main logic function of this service
     *
     */
    @SuppressLint("DefaultLocale")
    @Override
    protected void onHandleIntent(Intent arg0) {
        String message;
        byte[] recvBuf = new byte[MAX_BUF_LEN];

        isEnabled = true;
        connChangeReceiver = new ConnectivityChangeReceiver();
        registerReceiver(connChangeReceiver,
                new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

        wifiScanReceiver = new WifiScanReceiver();
        registerReceiver(wifiScanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String portString = prefs.getString(UDP_SERVER_PORT_KEY, UDP_SERVER_PORT_DEFAULT);
        int udpServerPort = Integer.parseInt(portString);

        if (udpServerPort < 1024 || udpServerPort > 65535) {
            udpServerPort = UDP_SERVER_PORT;
        }

        try {
            socket = new DatagramSocket(udpServerPort);
            Log.i("UDPListeningService", "UDP receiver started on port " + Integer.toString(udpServerPort));

            while (isEnabled) {

                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(packet);

                // get packet data with specific length from recvBuf
                message = new String(packet.getData(), packet.getOffset(),
                                    packet.getLength()).trim();
                Log.d("UDPListeningService", "received packet: " + message);

                String[] fields = message.split(TOKEN);
                String msg_type = fields[0].toLowerCase();

                if (msg_type.equals(MSG_SWITCH)) { // switch to another access point
                    wifiSwitch(fields);

                } else if (msg_type.equals(MSG_SCAN)) { // using for ap scanning
                    startTimestamp = System.currentTimeMillis();
                    WifiManager wifiManager = (WifiManager)this.getSystemService(Context.WIFI_SERVICE);
                    wifiScanReceiver.isServerAsked = true;
                    wifiManager.startScan();
                    Log.i(LOG_TAG, "starting wifi scanning...");
                } else if (msg_type.equals(MSG_APP)) { // get running app info
                    Log.i(LOG_TAG, "collecting running app info...");
                    getRunningAppInfo();
                }
            }

            socket.close();

        // FIXME current thread termination may cause socket exception
        // now we just ignore it
        } catch (SocketException e) {
            Log.w("UDPListeningService", e.toString());
            // e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    public void stopListening() {
        isEnabled = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    @Override
    public void onDestroy() {
        stopListening();
        unregisterReceiver(connChangeReceiver);
        unregisterReceiver(wifiScanReceiver);
        Log.d("UDPListeningService", "UDP receiver successfully stopped.");
        super.onDestroy();
    }

    /**
     * switch wifi connection to a specific one defined in the received message
     * The management pkt should be like this:
     * switch|ssid|auth_alg|passwd|wep_options_to_be_added...
     *
     * TODO Now WEP configuration is still missing
     *
     * @param fields the splitted udp message
     */
    private void wifiSwitch(String[] fields) {

        if (!fields[1].equals("")) {

            WifiManager wifiManager = (WifiManager)this.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            preNetId = wifiInfo.getNetworkId();

            if (wifiInfo.getSSID() != null && wifiInfo.getSSID().equals(fields[1])) {
                Log.i("UDPListeningService", "same ssid to current one, ignore the request");
            } else {
                // find corresponding config
                List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
                for(WifiConfiguration i : list) {
                    // Log.d("test", i.SSID);
                    if(i.SSID != null && i.SSID.equals("\"" + fields[1] + "\"")) {
                        Log.d("UDPListeningService", "find existing config for ssid: " + fields[1]);
                        connectWifiNetwork(wifiManager, i);
                        return;
                    }
                }


                // TODO this part of logic is not complete at all
                // not find existing config for the new ssid
                Log.d("UDPListeningService", "create new config for ssid: " + fields[1]);
                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = "\"" + fields[1] + "\"";
                if (fields[2].equals("open")) {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if (fields[2].equals("wep")) {
                    // TODO add WEP condition
                } else if (fields[2].equals("wpa") && !fields[3].equals("")) {
                    conf.preSharedKey = "\""+ fields[3] +"\"";
                } else {
                    Log.w("UDPListeningService", "illegal mgt packet, ignore it");
                    return;
                }


                wifiManager.addNetwork(conf);
                Log.d("test", "created new config successfully");
                list = wifiManager.getConfiguredNetworks();
                for(WifiConfiguration i : list) {
                    // Log.d("test", i.SSID);
                    if(i.SSID != null && i.SSID.equals("\"" + fields[1] + "\"")) {
                        connectWifiNetwork(wifiManager, i);
                        break;
                    }
                }
            }
        } else {
            Log.w("UDPListeningService", "Invalid SSID, ignore it");
        }
    }


    /**
     * connect to a specific wifi network
     *
     * @param wifiManager
     * @param config wifi config
     */
    private void connectWifiNetwork(WifiManager wifiManager, WifiConfiguration config) {
        ConnectivityManager connManager = (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);
        Log.d(LOG_TAG, "trying to switch network...");

        wifiManager.disconnect();
        wifiManager.enableNetwork(config.networkId, true);
        wifiManager.reconnect();
        connChangeReceiver.ssid = config.SSID.replace("\"", "");
        connChangeReceiver.isServerAsked = true;

        for (int i = 0; i < DELAY_TIMES; i++) {
            SystemClock.sleep(DELAY_TIME_MS);
            NetworkInfo networkInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (networkInfo != null && networkInfo.isConnected()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();

                // Log.d(LOG_TAG, wifiInfo.getSSID());
                // Log.d(LOG_TAG, config.SSID);

                if (("\"" + wifiInfo.getSSID() + "\"").equals(config.SSID)) {
                    return;
                }
            }
        }

        connChangeReceiver.isServerAsked = false;
        SDNCommonUtil.writeToExternalFile("20+", LOG_TAG, OUT_FILE);
        Log.w(LOG_TAG, "can not connect to new network: " + config.SSID);
        Log.i(LOG_TAG, "try to connect back to previous network");
        wifiManager.disableNetwork(config.networkId);
        wifiManager.removeNetwork(config.networkId);

        wifiManager.disconnect();
        wifiManager.enableNetwork(preNetId, true);
        wifiManager.reconnect();

        // FIXME If device fails to connect back to the previous network, it
        // will be off-line. However, not we just ignore this kind of condition
    }

    private void getRunningAppInfo() {
        String runningApp = "trivial";
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

//        List<RunningTaskInfo> recentTasks = activityManager.getRunningTasks(1);
//        for (RunningTaskInfo i: recentTasks) {
//            if (i.baseActivity.toShortString().matches(".*android\\.youtube.*")) {
//                Log.d("app", "task: " + i.baseActivity.toShortString() + " " + i.numRunning);
//            }
//        }

        List<ActivityManager.RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);
        for (ActivityManager.RunningServiceInfo i: services) {
            if (i.service.toShortString().matches(".*DownloadService\\}")) {
                Log.d(LOG_TAG, "running service: " + i.service.toShortString());
                // String callingApp = context.getPackageManager().getNameForUid(i.uid);
                // Log.d("app", "calling app: " + callingApp);
                runningApp = "download";
                break;
            } else if (i.service.toShortString().matches(".*YouTube.*")) {
                Log.d(LOG_TAG, "running service: " + i.service.toShortString());
                runningApp = "youtube";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("s|app|");

        WifiManager wifiManager = (WifiManager)this.getSystemService(Context.WIFI_SERVICE);
        String mac = wifiManager.getConnectionInfo().getMacAddress();
        sb.append(mac + "|" + runningApp);

        Log.d(LOG_TAG, sb.toString());

        try {
            int dst = wifiManager.getDhcpInfo().gateway;
            InetAddress ipAddr = InetAddress.getByName(SDNCommonUtil.littleEndianIntToIpAddress(dst));
            new UDPSendingTask().execute(sb.toString(), ipAddr, AGENT_PORT);
            Log.i(LOG_TAG, "running app scan reply to agent " + ipAddr.getHostAddress());
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "stop sending app reply: can not using current IP address");
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(LOG_TAG, "unknown udp sending error");
            e.printStackTrace();
        }

    }


}
