package cc.ganhualin.xiaomusictv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class LoginWebServer extends NanoHTTPD {
    private static final String TAG = "LoginWebServer";
    private Context context;
    private OnLoginDataReceivedListener listener;

    public interface OnLoginDataReceivedListener {
        void onDataReceived(String url, String username, String password);
    }

    public LoginWebServer(Context context, int port, OnLoginDataReceivedListener listener) {
        super(port);
        this.context = context;
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.GET.equals(method) && "/".equals(uri)) {
            return newFixedLengthResponse(getHtml());
        }

        if (Method.POST.equals(method) && "/login".equals(uri)) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                Map<String, String> params = session.getParms();
                
                String url = params.get("url");
                String username = params.get("username");
                String password = params.get("password");

                if (listener != null) {
                    listener.onDataReceived(url, username, password);
                }

                return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "SUCCESS");
            } catch (IOException | NanoHTTPD.ResponseException e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "ERROR: " + e.getMessage());
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
    }

    private String getHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>肉肉音乐 - 扫码登录</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, sans-serif; background: #0f172a; color: white; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; }\n" +
                "        .card { background: #1e293b; padding: 24px; border-radius: 16px; width: 100%; max-width: 400px; box-shadow: 0 10px 25px rgba(0,0,0,0.3); }\n" +
                "        h2 { margin-top: 0; text-align: center; color: #38bdf8; }\n" +
                "        .desc { text-align: center; color: #94a3b8; font-size: 14px; margin-bottom: 24px; }\n" +
                "        .field { margin-bottom: 16px; }\n" +
                "        label { display: block; margin-bottom: 6px; color: #94a3b8; font-size: 13px; }\n" +
                "        input { width: 100%; padding: 12px; border-radius: 8px; border: 1px solid #334155; background: #0f172a; color: white; box-sizing: border-box; font-size: 16px; }\n" +
                "        button { width: 100%; padding: 14px; border-radius: 8px; border: none; background: #38bdf8; color: #0f172a; font-weight: bold; font-size: 16px; cursor: pointer; margin-top: 8px; }\n" +
                "        #status { text-align: center; margin-top: 16px; font-size: 14px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"card\">\n" +
                "        <h2>肉肉音乐 - 快速登录 TV</h2>\n" +
                "        <p class=\"desc\">在手机上输入 XiaoMusic 服务器信息推送到电视</p>\n" +
                "        <div class=\"field\">\n" +
                "            <label>服务地址</label>\n" +
                "            <input type=\"url\" id=\"url\" placeholder=\"http://192.168.x.x:58090\" required>\n" +
                "        </div>\n" +
                "        <div class=\"field\">\n" +
                "            <label>用户名 (可选)</label>\n" +
                "            <input type=\"text\" id=\"username\" placeholder=\"Username\">\n" +
                "        </div>\n" +
                "        <div class=\"field\">\n" +
                "            <label>密码 (可选)</label>\n" +
                "            <input type=\"password\" id=\"password\" placeholder=\"Password\">\n" +
                "        </div>\n" +
                "        <button onclick=\"submitLogin()\" id=\"btn\">推送到电视</button>\n" +
                "        <div id=\"status\"></div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        function submitLogin() {\n" +
                "            const url = document.getElementById('url').value;\n" +
                "            const username = document.getElementById('username').value;\n" +
                "            const password = document.getElementById('password').value;\n" +
                "            const btn = document.getElementById('btn');\n" +
                "            const status = document.getElementById('status');\n" +
                "\n" +
                "            if (!url) { alert('请输入服务地址'); return; }\n" +
                "\n" +
                "            btn.disabled = true; btn.innerText = '正在推送...';\n" +
                "            \n" +
                "            const formData = new URLSearchParams();\n" +
                "            formData.append('url', url);\n" +
                "            formData.append('username', username);\n" +
                "            formData.append('password', password);\n" +
                "\n" +
                "            fetch('/login', {\n" +
                "                method: 'POST',\n" +
                "                body: formData\n" +
                "            })\n" +
                "            .then(res => res.text())\n" +
                "            .then(data => {\n" +
                "                if (data === 'SUCCESS') {\n" +
                "                    status.style.color = '#4ade80';\n" +
                "                    status.innerText = '✅ 推送成功！电视端正在自动登录...';\n" +
                "                } else {\n" +
                "                    throw new Error(data);\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(err => {\n" +
                "                status.style.color = '#f87171';\n" +
                "                status.innerText = '❌ 推送失败: ' + err.message;\n" +
                "                btn.disabled = false; btn.innerText = '重试推送';\n" +
                "            });\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
