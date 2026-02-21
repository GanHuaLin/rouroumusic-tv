package cc.ganhualin.xiaomusictv;

import android.content.Context;
import android.content.SharedPreferences;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static Retrofit retrofit = null;
    private static final String PREFS_NAME = "XiaoMusicPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    public static Retrofit getClient(Context context) {
        // Clear old instance if we need fresh config
        retrofit = null; 
        
        SharedPreferences settings = context.getSharedPreferences(PREFS_NAME, 0);
        String baseUrl = settings.getString(KEY_SERVER_URL, "");
        String username = settings.getString(KEY_USERNAME, "");
        String password = settings.getString(KEY_PASSWORD, "");

        if (baseUrl.isEmpty()) {
            // Default to something safe or return a builder that will fail later
            baseUrl = "http://localhost/"; 
        }

        if (!baseUrl.startsWith("http")) {
            baseUrl = "http://" + baseUrl;
        }

        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(logging);

        // Auth Interceptor
        if (!username.isEmpty() && !password.isEmpty()) {
            builder.addInterceptor(chain -> {
                okhttp3.Request original = chain.request();
                String credentials = username + ":" + password;
                String basic = "Basic " + android.util.Base64.encodeToString(credentials.getBytes(), android.util.Base64.NO_WRAP);
                
                okhttp3.Request.Builder requestBuilder = original.newBuilder()
                        .header("Authorization", basic);
                
                return chain.proceed(requestBuilder.build());
            });
        }

        // 401/403 Error Interceptor
        builder.addInterceptor(chain -> {
            okhttp3.Request request = chain.request();
            Response response = chain.proceed(request);

            if (response.code() == 401 || response.code() == 403) {
                // Ignore 401 for ConfigActivity itself (avoid loop)
                boolean isConfigActivity = false;
                if (context instanceof ConfigActivity) {
                    isConfigActivity = true;
                }

                if (!isConfigActivity) {
                    // Clear user info
                    settings.edit().clear().apply();

                    // Notify and Redirect on UI thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context, "登录失效或认证失败，请重新登录", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(context, ConfigActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        context.startActivity(intent);
                    });
                }
            }
            return response;
        });

        OkHttpClient client = builder.build();

        retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();
                
        return retrofit;
    }
}
