package cc.ganhualin.xiaomusictv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import android.content.Intent;
import android.content.SharedPreferences;

public class ConfigActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "XiaoMusicPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    private LoginWebServer webServer;
    private static final int SERVER_PORT = 8088;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isQrMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if URL exists and jump directly
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        String savedUrl = settings.getString(KEY_SERVER_URL, null);

        if (savedUrl != null && !savedUrl.isEmpty()) {
            startActivity(new Intent(this, LibraryActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_config);

        EditText etUrl = findViewById(R.id.etUrl);
        EditText etUsername = findViewById(R.id.etUsername);
        EditText etPassword = findViewById(R.id.etPassword);
        Button btnConnect = findViewById(R.id.btnConnect);
        ImageView ivQrCode = findViewById(R.id.ivQrCode);
        TextView tvIpAddress = findViewById(R.id.tvIpAddress);
        View layoutManual = findViewById(R.id.layoutManual);
        View layoutQr = findViewById(R.id.layoutQr);
        Button btnToggleMode = findViewById(R.id.btnToggleMode);

        btnToggleMode.setOnClickListener(v -> {
            isQrMode = !isQrMode;
            if (isQrMode) {
                btnToggleMode.setText("手动登录");
                layoutManual.setVisibility(View.GONE);
                layoutQr.setVisibility(View.VISIBLE);
                
                // Disable focus on manual inputs when in QR mode
                etUrl.setFocusable(false);
                etUsername.setFocusable(false);
                etPassword.setFocusable(false);
                btnConnect.setFocusable(false);
                
                startLoginWebServer(tvIpAddress, ivQrCode, etUrl, etUsername, etPassword, btnConnect);
            } else {
                btnToggleMode.setText("扫码登录");
                layoutManual.setVisibility(View.VISIBLE);
                layoutQr.setVisibility(View.GONE);

                // Enable focus on manual inputs when in manual mode
                etUrl.setFocusable(true);
                etUrl.setFocusableInTouchMode(true);
                etUsername.setFocusable(true);
                etUsername.setFocusableInTouchMode(true);
                etPassword.setFocusable(true);
                etPassword.setFocusableInTouchMode(true);
                btnConnect.setFocusable(true);

                if (webServer != null) {
                    webServer.stop();
                    webServer = null;
                }
            }
        });

        // On TV, we don't want the keyboard to pop up immediately on focus.
        // Instead, show it only when user clicks/presses Enter on the EditText.
        View.OnClickListener clickToShowKeyboard = v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(v, InputMethodManager.SHOW_FORCED);
            }
        };

        etUrl.setOnClickListener(clickToShowKeyboard);
        etUsername.setOnClickListener(clickToShowKeyboard);
        etPassword.setOnClickListener(clickToShowKeyboard);

        btnConnect.setOnClickListener(v -> {
            // Hide keyboard first
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

            String urlRaw = etUrl.getText().toString().trim();
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            if (urlRaw.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!urlRaw.startsWith("http")) {
                urlRaw = "http://" + urlRaw;
            }
            final String finalUrl = urlRaw.endsWith("/") ? urlRaw : urlRaw + "/";

            btnConnect.setEnabled(false);
            btnConnect.setText("连接中...");

            // Temporarily save prefs for test
            SharedPreferences.Editor editor = settings.edit();
            editor.putString(KEY_SERVER_URL, finalUrl);
            editor.putString(KEY_USERNAME, username);
            editor.putString(KEY_PASSWORD, password);
            editor.apply();

            // Perform test connection using musiclist API
            ApiService apiService = RetrofitClient.getClient(this).create(ApiService.class);
            apiService.getMusicList().enqueue(new retrofit2.Callback<java.util.Map<java.lang.String, java.util.List<java.lang.String>>>() {
                @Override
                public void onResponse(retrofit2.Call<java.util.Map<java.lang.String, java.util.List<java.lang.String>>> call, retrofit2.Response<java.util.Map<java.lang.String, java.util.List<java.lang.String>>> response) {
                    btnConnect.setEnabled(true);
                    btnConnect.setText("连　接");

                    if (response.isSuccessful()) {
                        Toast.makeText(ConfigActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(ConfigActivity.this, LibraryActivity.class));
                        finish();
                    } else if (response.code() == 401 || response.code() == 403) {
                        Toast.makeText(ConfigActivity.this, "认证失败，请检查用户名密码", Toast.LENGTH_LONG).show();
                        settings.edit().clear().apply();
                    } else {
                        Toast.makeText(ConfigActivity.this, "服务器响应异常: " + response.code(), Toast.LENGTH_SHORT).show();
                        settings.edit().clear().apply();
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<java.util.Map<java.lang.String, java.util.List<java.lang.String>>> call, Throwable t) {
                    btnConnect.setEnabled(true);
                    btnConnect.setText("连　接");
                    Toast.makeText(ConfigActivity.this, "连接超时或地址错误", Toast.LENGTH_LONG).show();
                    settings.edit().clear().apply();
                }
            });
        });
    }

    private void startLoginWebServer(TextView tvIp, ImageView ivQr, EditText etUrl, EditText etUsername, EditText etPassword, Button btnConnect) {
        String ipAddress = getIPAddress();
        if (ipAddress == null) {
            tvIp.setText("无法获取局域网地址，请检查网络");
            return;
        }

        String loginUrl = "http://" + ipAddress + ":" + SERVER_PORT;
        tvIp.setText("访问管理: " + loginUrl);

        // Generate QR Code
        generateQrCode(loginUrl, ivQr);

        webServer = new LoginWebServer(this, SERVER_PORT, (url, username, password) -> {
            mainHandler.post(() -> {
                etUrl.setText(url);
                etUsername.setText(username);
                etPassword.setText(password);
                Toast.makeText(this, "收到推送信息，正在登录...", Toast.LENGTH_SHORT).show();
                btnConnect.performClick();
            });
        });

        try {
            webServer.start();
        } catch (IOException e) {
            e.printStackTrace();
            tvIp.setText("服务启动失败: " + e.getMessage());
        }
    }

    private void generateQrCode(String text, ImageView imageView) {
        QRCodeWriter writer = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            imageView.setImageBitmap(bmp);
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    private String getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) return sAddr;
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServer != null) {
            webServer.stop();
        }
    }
}
