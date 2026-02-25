package cc.ganhualin.xiaomusictv;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.google.gson.JsonObject;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MusicService extends MediaSessionService {
    private MediaSession mediaSession;
    private ExoPlayer player;
    private SongScraper songScraper;

    private static final String PREFS_NAME = "XiaoMusicPrefs";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    @Override
    public void onCreate() {
        super.onCreate();

        // Get Credentials
        android.content.SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        String username = settings.getString(KEY_USERNAME, "");
        String password = settings.getString(KEY_PASSWORD, "");

        androidx.media3.exoplayer.ExoPlayer.Builder playerBuilder = new androidx.media3.exoplayer.ExoPlayer.Builder(this);

        androidx.media3.datasource.DefaultHttpDataSource.Factory httpDataSourceFactory = 
            new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("RouRouMusicTV-Native/1.0");

        if (!username.isEmpty() && !password.isEmpty()) {
            String credentials = username + ":" + password;
            String basic = "Basic " + android.util.Base64.encodeToString(credentials.getBytes(), android.util.Base64.NO_WRAP);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Authorization", basic);
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        androidx.media3.datasource.DataSource.Factory dataSourceFactory = httpDataSourceFactory;
        
        androidx.media3.datasource.ResolvingDataSource.Factory resolvingFactory = new androidx.media3.datasource.ResolvingDataSource.Factory(
            dataSourceFactory,
            new androidx.media3.datasource.ResolvingDataSource.Resolver() {
                @Override
                public androidx.media3.datasource.DataSpec resolveDataSpec(androidx.media3.datasource.DataSpec dataSpec) throws java.io.IOException {
                    android.net.Uri uri = dataSpec.uri;
                    if ("/xiaomusic_resolve".equals(uri.getPath())) {
                        String songName = uri.getQueryParameter("name");
                        if (songName != null) {
                            try {
                                ApiService apiService = RetrofitClient.getClient(MusicService.this).create(ApiService.class);
                                retrofit2.Response<com.google.gson.JsonObject> response = apiService.getMusicInfo(songName, true).execute();
                                if (response.isSuccessful() && response.body() != null) {
                                    com.google.gson.JsonObject json = response.body();
                                    String rawUrl = json.has("url") ? json.get("url").getAsString() : null;
                                    if (rawUrl != null) {
                                        String finalUrl = rawUrl;
                                        android.content.SharedPreferences s = getSharedPreferences(PREFS_NAME, 0);
                                        String base = s.getString("server_url", "");
                                        if (!base.endsWith("/")) base += "/";
                                        if (!finalUrl.startsWith("http")) {
                                            if (finalUrl.startsWith("/")) finalUrl = base + finalUrl.substring(1);
                                            else finalUrl = base + finalUrl;
                                        }
                                        String encodedTarget = android.net.Uri.encode(finalUrl, "@#&=*+-_.,:!?()/~'%");
                                        return dataSpec.withUri(android.net.Uri.parse(encodedTarget));
                                    }
                                } else {
                                    throw new java.io.IOException("API Error: HTTP " + response.code());
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                android.util.Log.e("MusicService", "Failed to resolve URL: " + e.getMessage());
                                if (e instanceof java.io.IOException) {
                                    throw (java.io.IOException) e;
                                } else {
                                    throw new java.io.IOException(e.getMessage(), e);
                                }
                            }
                            throw new java.io.IOException("Failed to get music URL from Info API.");
                        }
                    }
                    return dataSpec;
                }
            }
        );

        playerBuilder.setMediaSourceFactory(
            new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                .setDataSourceFactory(resolvingFactory)
        );

        // Configure audio attributes for music playback
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = playerBuilder
                .setAudioAttributes(audioAttributes, true) // Handle audio focus internally
                .setWakeMode(C.WAKE_MODE_NETWORK) // Keep CPU and Wifi alive during playback
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    enrichMetadata(mediaItem);
                }
            }
        });

        // Create a PendingIntent for the PlayerActivity
        Intent intent = new Intent(this, LibraryActivity.class); // Use Library as root if needed, or Player
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 
                0, 
                intent, 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .setId("cc.ganhualin.xiaomusictv.session")
                .build();
        
        // Ensure session persists even if controller is disconnected
        // This is implicit in MediaSessionService but some settings help

        // Configure notification provider for Xiaomi HyperOS compatibility
        // Small icon is configured via res/values/drawables.xml (media3_notification_small_icon)
        DefaultMediaNotificationProvider notificationProvider = new DefaultMediaNotificationProvider.Builder(this)
                .build();
        setMediaNotificationProvider(notificationProvider);
        songScraper = new SongScraper();
    }

    private void enrichMetadata(MediaItem mediaItem) {
        // Only enrich if metadata is incomplete
        if (mediaItem.mediaMetadata.artworkUri != null && mediaItem.mediaMetadata.artist != null) {
            return; 
        }

        String songName = mediaItem.mediaId;
        if (mediaItem.mediaMetadata.extras != null) {
            String original = mediaItem.mediaMetadata.extras.getString("original_name");
            if (original != null) songName = original;
        }

        final String finalSongName = songName;
        ApiService apiService = RetrofitClient.getClient(this).create(ApiService.class);
        apiService.getMusicInfo(songName, true).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject json = response.body();
                    if (json.has("tags")) {
                        JsonObject tags = json.getAsJsonObject("tags");
                        
                        String artist = tags.has("artist") && !tags.get("artist").isJsonNull() ? tags.get("artist").getAsString() : "";
                        String album = tags.has("album") && !tags.get("album").isJsonNull() ? tags.get("album").getAsString() : "";
                        String pic = tags.has("picture") && !tags.get("picture").isJsonNull() ? tags.get("picture").getAsString() : null;
                        String lyrics = tags.has("lyrics") && !tags.get("lyrics").isJsonNull() ? tags.get("lyrics").getAsString() : null;
                        
                        if (pic != null && !pic.isEmpty()) {
                            if (!pic.startsWith("http")) {
                                android.content.SharedPreferences s = getSharedPreferences(PREFS_NAME, 0);
                                String base = s.getString("server_url", "");
                                if (!base.endsWith("/")) base += "/";
                                pic = base + (pic.startsWith("/") ? pic.substring(1) : pic);
                            }
                        }

                        // 如果基本信息依然缺失，尝试第三方刮削
                        if ((pic == null || pic.isEmpty()) || (lyrics == null || lyrics.isEmpty())) {
                            triggerExternalScrape(mediaItem, finalSongName);
                        }

                        // 更新已有的元数据
                        updateMediaItemMetadata(mediaItem, artist, album, pic, lyrics);
                    } else {
                        // tags 为空，触发第三方刮削
                        triggerExternalScrape(mediaItem, finalSongName);
                    }
                } else {
                    // 请求失败，触发第三方刮削
                    triggerExternalScrape(mediaItem, finalSongName);
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                triggerExternalScrape(mediaItem, finalSongName);
            }
        });
    }

    private void triggerExternalScrape(MediaItem mediaItem, String songName) {
        if (songScraper == null) return;
        String artistHint = "";
        if (mediaItem.mediaMetadata.artist != null) {
            artistHint = mediaItem.mediaMetadata.artist.toString();
        }
        
        songScraper.scrape(songName, artistHint, new SongScraper.ScrapeCallback() {
            @Override
            public void onSuccess(String artist, String picUrl, String lyrics) {
                updateMediaItemMetadata(mediaItem, artist, "", picUrl, lyrics);
            }

            @Override
            public void onError(String msg) {
                android.util.Log.e("MusicService", "External scrape failed: " + msg);
            }
        });
    }

    private void updateMediaItemMetadata(MediaItem mediaItem, String artist, String album, String pic, String lyrics) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null && current.mediaId.equals(mediaItem.mediaId)) {
            MediaMetadata.Builder metaBuilder = current.mediaMetadata.buildUpon();
            if (artist != null && !artist.isEmpty()) metaBuilder.setArtist(artist);
            if (album != null && !album.isEmpty()) metaBuilder.setAlbumTitle(album);
            if (pic != null && !pic.isEmpty()) metaBuilder.setArtworkUri(android.net.Uri.parse(pic));
            
            android.os.Bundle extras = current.mediaMetadata.extras != null ? 
                new android.os.Bundle(current.mediaMetadata.extras) : new android.os.Bundle();
            if (lyrics != null && !lyrics.isEmpty()) extras.putString("lyrics", lyrics);
            metaBuilder.setExtras(extras);
            
            MediaItem newItem = current.buildUpon()
                .setMediaMetadata(metaBuilder.build())
                .build();
            
            player.replaceMediaItem(player.getCurrentMediaItemIndex(), newItem);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Sticky helps service to restart if OS kills it
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // When user swipes away from recent tasks, keep playing if active
        if (player != null && !player.getPlayWhenReady()) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }
}
