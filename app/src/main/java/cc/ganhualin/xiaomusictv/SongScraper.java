package cc.ganhualin.xiaomusictv;

import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class SongScraper {
    private static final String TAG = "SongScraper";
    private static final String BASE_URL = "https://music.163.com/";
    private final ExternalApiService api;
    private final java.util.Set<String> activeScrapes = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface ScrapeCallback {
        void onSuccess(String artist, String picUrl, String lyrics);
        void onError(String msg);
    }

    public SongScraper() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(ExternalApiService.class);
    }

    public void scrape(String songName, String unusedHint, ScrapeCallback callback) {
        // 1. 清理文件名
        final String cleanedName = cleanSongName(songName);
        final String finalQuery = cleanedName;
        
        // 解析潜在的歌手和歌名 (针对 "歌手 - 歌名" 格式)
        String potentialArtist = "";
        String potentialTitle = "";
        if (cleanedName.contains(" - ")) {
            String[] parts = cleanedName.split(" - ", 2);
            potentialArtist = parts[0].trim();
            potentialTitle = parts[1].trim();
        }

        if (!activeScrapes.add(finalQuery)) {
            Log.d(TAG, "歌曲正在刮削中: " + finalQuery + "，跳过重复请求。");
            return;
        }
        
        Log.d(TAG, "开始刮削。检索词: " + finalQuery);

        final String finalPotentialArtist = potentialArtist;
        final String finalPotentialTitle = potentialTitle;

        api.search(finalQuery, 1, 5).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                try {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        Log.d(TAG, "搜索接口返回原始 JSON: " + response.body().toString());
                        JsonObject result = response.body().getAsJsonObject("result");
                        if (result != null && result.has("songs")) {
                            JsonArray songs = result.getAsJsonArray("songs");
                            if (songs.size() > 0) {
                                JsonObject bestMatch = null;
                                
                                // 智能筛选逻辑
                                if (!finalPotentialArtist.isEmpty()) {
                                    for (int i = 0; i < songs.size(); i++) {
                                        JsonObject song = songs.get(i).getAsJsonObject();
                                        String rArtist = getArtistFromJson(song);
                                        String rTitle = song.has("name") ? song.get("name").getAsString() : "";
                                        
                                        // 完美匹配优先
                                        if (rArtist.equalsIgnoreCase(finalPotentialArtist) && rTitle.equalsIgnoreCase(finalPotentialTitle)) {
                                            bestMatch = song;
                                            Log.d(TAG, "在索引 " + i + " 处找到完美匹配");
                                            break;
                                        }
                                        // 歌手匹配次之
                                        if (bestMatch == null && rArtist.equalsIgnoreCase(finalPotentialArtist)) {
                                            bestMatch = song;
                                        }
                                    }
                                }
                                
                                // 如果没找到匹配的，取第搜索结果第一个
                                if (bestMatch == null) {
                                    bestMatch = songs.get(0).getAsJsonObject();
                                }

                                JsonObject song = bestMatch;
                                long songId = song.get("id").getAsLong();
                                String artist = getArtistFromJson(song);

                                // 2. 发起详情请求获取封面图和歌词
                                final String finalArtist = artist;
                                fetchDetailsAndLyrics(songId, new DetailCallback() {
                                    @Override
                                    public void onComplete(String picUrl, String lyrics) {
                                        activeScrapes.remove(finalQuery);
                                        Log.d(TAG, "刮削成功: " + finalArtist + ", 封面=" + picUrl + ", 歌词长度=" + (lyrics != null ? lyrics.length() : 0));
                                        callback.onSuccess(finalArtist, picUrl, lyrics);
                                    }
                                });
                                return;
                            }
                        }
                        activeScrapes.remove(finalQuery);
                        callback.onError("未找到检索结果");
                    } catch (Exception e) {
                        activeScrapes.remove(finalQuery);
                        callback.onError("解析失败: " + e.getMessage());
                    }
                } else {
                    activeScrapes.remove(finalQuery);
                    callback.onError("搜索失败: " + response.code());
                }
                } catch (Exception e) {
                    activeScrapes.remove(finalQuery);
                    callback.onError("未知错误: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                activeScrapes.remove(finalQuery);
                callback.onError("网络错误: " + t.getMessage());
            }
        });
    }

    private String getArtistFromJson(JsonObject song) {
        if (song.has("ar")) {
            JsonArray ar = song.getAsJsonArray("ar");
            if (ar.size() > 0) return ar.get(0).getAsJsonObject().get("name").getAsString();
        }
        if (song.has("artists")) {
            JsonArray artists = song.getAsJsonArray("artists");
            if (artists.size() > 0) return artists.get(0).getAsJsonObject().get("name").getAsString();
        }
        return "";
    }

    private interface DetailCallback {
        void onComplete(String picUrl, String lyrics);
    }

    private void fetchDetailsAndLyrics(long songId, DetailCallback callback) {
        // 先获取详情（封面）
        api.getSongDetail(songId, "[" + songId + "]").enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                String picUrl = "";
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JsonArray songs = response.body().getAsJsonArray("songs");
                        if (songs != null && songs.size() > 0 && !songs.get(0).isJsonNull()) {
                            JsonObject detail = songs.get(0).getAsJsonObject();
                            if (detail.has("album") && !detail.get("album").isJsonNull()) {
                                JsonObject album = detail.getAsJsonObject("album");
                                if (album.has("picUrl") && !album.get("picUrl").isJsonNull()) {
                                    picUrl = album.get("picUrl").getAsString();
                                }
                            } else if (detail.has("al") && !detail.get("al").isJsonNull()) {
                                // 兼容另一个版本的 API
                                JsonObject al = detail.getAsJsonObject("al");
                                if (al.has("picUrl") && !al.get("picUrl").isJsonNull()) {
                                    picUrl = al.get("picUrl").getAsString();
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析搜索结果异常: " + e.getMessage());
                    }
                }
                
                if (picUrl != null && picUrl.startsWith("http://")) {
                    picUrl = picUrl.replace("http://", "https://");
                }

                // 再获取歌词
                final String finalPic = picUrl;
                api.getLyrics(songId, 1, 1, -1).enqueue(new Callback<JsonObject>() {
                    @Override
                    public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                        String lyrics = "";
                        if (response.isSuccessful() && response.body() != null) {
                            try {
                                if (response.body().has("lrc") && !response.body().get("lrc").isJsonNull()) {
                                    JsonObject lrc = response.body().getAsJsonObject("lrc");
                                    if (lrc != null && lrc.has("lyric") && !lrc.get("lyric").isJsonNull()) {
                                        lyrics = lrc.get("lyric").getAsString();
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "歌词解析错误: " + e.getMessage());
                            }
                        }
                        callback.onComplete(finalPic, lyrics);
                    }

                    @Override
                    public void onFailure(Call<JsonObject> call, Throwable t) {
                        callback.onComplete(finalPic, "");
                    }
                });
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                // 详情失败也尝试去拿歌词
                api.getLyrics(songId, 1, 1, -1).enqueue(new Callback<JsonObject>() {
                    @Override
                    public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                        String lyrics = "";
                        if (response.isSuccessful() && response.body() != null) {
                             try {
                                if (response.body().has("lrc") && !response.body().get("lrc").isJsonNull()) {
                                    JsonObject lrc = response.body().getAsJsonObject("lrc");
                                    if (lrc != null && lrc.has("lyric") && !lrc.get("lyric").isJsonNull()) {
                                        lyrics = lrc.get("lyric").getAsString();
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "歌词解析错误（降级方案）: " + e.getMessage());
                            }
                        }
                        callback.onComplete("", lyrics);
                    }
                    @Override public void onFailure(Call<JsonObject> call, Throwable t2) {
                        callback.onComplete("", "");
                    }
                });
            }
        });
    }

    private String cleanSongName(String name) {
        if (name == null) return "";
        // 记录原始名称，备用
        String original = name;
        // 去除后缀
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }
        // 去除常见的干扰字符 (如 [LRC], (Live) 等)
        String cleaned = name.replaceAll("\\[.*?\\]", "")
                    .replaceAll("\\(.*?\\)", "")
                    .trim();
        
        // 如果清理后变空了（比如文件名全是括号），则返回原始去掉后缀的名称
        if (cleaned.isEmpty()) {
            return name.trim();
        }
        return cleaned;
    }
}
