package cc.ganhualin.xiaomusictv;

import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import okhttp3.ResponseBody;

import com.google.gson.JsonObject;

public interface ApiService {
    @GET("musiclist")
    Call<Map<String, List<String>>> getMusicList();

    @GET("music")
    Call<ResponseBody> getMusic(@Query("name") String name);

    @GET("musicinfo")
    Call<JsonObject> getMusicInfo(@Query("name") String name, @Query("musictag") boolean musicTag);

    // POST /delmusic {"name": "songName"}
    @POST("delmusic")
    Call<ResponseBody> delMusic(@Body Map<String, String> body);

    // POST /playlistaddmusic {"name": "playlistName", "music_list": ["songName"]}
    @POST("playlistaddmusic")
    Call<ResponseBody> addToPlaylist(@Body Map<String, Object> body);

    // POST /playlistdelmusic {"name": "playlistName", "music_list": ["songName"]}
    @POST("playlistdelmusic")
    Call<ResponseBody> removeFromPlaylist(@Body Map<String, Object> body);

    // POST /setmusictag {"musicname": "songName", "title": "...", "artist": "...", "album": "...", "year": "...", "genre": "...", "lyrics": "...", "picture": "..."}
    @POST("setmusictag")
    Call<ResponseBody> setMusicTag(@Body Map<String, Object> body);
}
