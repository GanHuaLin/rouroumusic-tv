package cc.ganhualin.xiaomusictv;

import com.google.gson.JsonObject;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;
import retrofit2.http.Headers;

public interface ExternalApiService {
    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
    @GET("api/search/get/web")
    Call<JsonObject> search(@Query("s") String name, @Query("type") int type, @Query("limit") int limit);

    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
    @GET("api/song/lyric")
    Call<JsonObject> getLyrics(@Query("id") long id, @Query("lv") int lv, @Query("kv") int kv, @Query("tv") int tv);

    @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
    @GET("api/song/detail")
    Call<JsonObject> getSongDetail(@Query("id") long id, @Query("ids") String ids);
}
