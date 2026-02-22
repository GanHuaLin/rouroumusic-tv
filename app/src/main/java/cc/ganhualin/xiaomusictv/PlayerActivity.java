package cc.ganhualin.xiaomusictv;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import okhttp3.ResponseBody;
import android.content.SharedPreferences;

public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "PlayerActivity";
    private ImageView ivBigCover;
    private ImageView ivBlurBackground;
    private TextView tvBigTitle;
    private TextView tvBigArtist;
    private RecyclerView rvLyrics;
    private TextView tvNoLyrics; 
    private SeekBar progressBar; // Changed to SeekBar
    private TextView tvCurrentTime, tvDuration;
    private TextView tvTooltip, tvSeekProgress, tvPlaylistName, tvPlaylistCount;
    private android.widget.LinearLayout layoutTooltip, layoutSeekProgress;
    private ImageButton btnRepeat, btnPrev, btnPlayPause, btnNext, btnFav, btnQueue;
    private android.view.View layoutDrawer;
    private android.view.ViewGroup layoutMainContent;
    private RecyclerView rvDrawerSongs;
    private DrawerSongAdapter drawerSongAdapter;
    private boolean isFavorited = false;
    private java.util.Set<String> favoritesSet = new java.util.HashSet<>();

    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private LyricAdapter lyricAdapter;
    private ApiService apiService;
    private String baseUrl;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    // Auto-hide bottom controls logic
    private android.view.View layoutBottomControls;
    private static final int CONTROLS_HIDE_DELAY_MS = 10000;
    private final Runnable hideControlsRunnable = this::hideBottomControls;

    // Remote capture long press / double click
    private long lastLeftClickTime = 0;
    private long lastRightClickTime = 0;
    private static final long DOUBLE_CLICK_INTERVAL = 300;
    private boolean isSeeking = false;
    private final Runnable seekForwardRunnable = new Runnable() {
        @Override public void run() {
            if (player != null) {
                player.seekForward();
                updateTooltip(true);
                handler.postDelayed(this, 300);
            }
        }
    };
    private final Runnable seekBackwardRunnable = new Runnable() {
        @Override public void run() {
            if (player != null) {
                player.seekBack();
                updateTooltip(false);
                handler.postDelayed(this, 300);
            }
        }
    };

    private void updateTooltip(boolean isForward) {
        if (player == null) return;
        handler.removeCallbacks(showShortcutHintRunnable); // Cancel hint if seeking starts
        handler.removeCallbacks(hideSeekHintRunnable);
        long current = player.getCurrentPosition();
        long duration = player.getDuration();
        String prefix = isForward ? "▶▶ " : "◀◀ ";
        tvSeekProgress.setText(prefix + formatTime(current) + " / " + formatTime(duration));
        layoutSeekProgress.setVisibility(View.VISIBLE);
    }

    private final Runnable hideSeekHintRunnable = () -> {
        layoutSeekProgress.setVisibility(View.GONE);
    };

    private final Runnable showShortcutHintRunnable = () -> {
        if (!isSeeking) { // Only show if not currently seeking
            tvSeekProgress.setText("长按「左」「右」键快进快退，双击「左」「右」键切换歌曲");
            layoutSeekProgress.setVisibility(View.VISIBLE);
            handler.postDelayed(hideSeekHintRunnable, 2000);
        }
    };

    private void showShortcutHint() {
        handler.removeCallbacks(showShortcutHintRunnable);
        handler.removeCallbacks(hideSeekHintRunnable);
        // Increase delay to 500ms to ensure long press (which starts around 300-500ms) 
        // has enough time to cancel this hint.
        handler.postDelayed(showShortcutHintRunnable, 500);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        ivBigCover = findViewById(R.id.ivBigCover);
        ivBlurBackground = findViewById(R.id.ivBlurBackground);
        tvBigTitle = findViewById(R.id.tvBigTitle);
        tvBigArtist = findViewById(R.id.tvBigArtist);
        rvLyrics = findViewById(R.id.rvLyrics);
        tvNoLyrics = findViewById(R.id.tvNoLyrics); 
        progressBar = findViewById(R.id.progressBar);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvDuration = findViewById(R.id.tvDuration);
        tvTooltip = findViewById(R.id.tvTooltip);
        tvSeekProgress = findViewById(R.id.tvSeekProgress);
        tvPlaylistName = findViewById(R.id.tvPlaylistName);
        tvPlaylistCount = findViewById(R.id.tvPlaylistCount);
        layoutTooltip = findViewById(R.id.layoutTooltip);
        layoutSeekProgress = findViewById(R.id.layoutSeekProgress);

        btnRepeat = findViewById(R.id.btnRepeat);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);
        btnFav = findViewById(R.id.btnFav);
        btnQueue = findViewById(R.id.btnQueue);
        layoutDrawer = findViewById(R.id.layoutDrawer);
        layoutMainContent = findViewById(R.id.layoutMainContent);
        layoutBottomControls = findViewById(R.id.layoutBottomControls);
        
        rvDrawerSongs = findViewById(R.id.rvDrawerSongs);
        
        rvLyrics.setLayoutManager(new LinearLayoutManager(this));
        rvLyrics.setItemAnimator(null); // Disable animations to prevent marquee reset
        lyricAdapter = new LyricAdapter();
        rvLyrics.setAdapter(lyricAdapter);

        // Sidebar Drawer Setup
        rvDrawerSongs.setLayoutManager(new LinearLayoutManager(this));
        drawerSongAdapter = new DrawerSongAdapter(this);
        rvDrawerSongs.setAdapter(drawerSongAdapter);
        drawerSongAdapter.setOnItemClickListener((song, position) -> {
            if (player != null) {
                player.seekToDefaultPosition(position);
                player.play();
                toggleDrawer(false);
            }
        });
        
        // Optimize Marquee performance and isolation
        // Removed Hardware Layer to prevent artifacts during layout updates

        SharedPreferences settings = getSharedPreferences("XiaoMusicPrefs", 0);
        baseUrl = settings.getString("server_url", "");
        apiService = RetrofitClient.getClient(this).create(ApiService.class);

        // Enable marquee
        tvBigTitle.setSelected(true);
        tvBigArtist.setSelected(true);

        progressBar.setOnFocusChangeListener((v, hasFocus) -> {
            layoutTooltip.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            if (hasFocus) {
                updateProgress();
                resetControlsTimer();
            }
        });

        btnPlayPause.post(() -> btnPlayPause.requestFocus());
        setupControls();
        setupSeekBarListener();

        // Keep screen on while this activity is in foreground
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        resetControlsTimer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new android.content.ComponentName(this, MusicService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                player = controllerFuture.get();
                setupPlayer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    private void setupControls() {
        btnPlayPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.play();
            resetControlsTimer();
        });

        btnPrev.setOnClickListener(v -> {
            skipToPrevious();
            resetControlsTimer();
        });

        btnNext.setOnClickListener(v -> {
            if (player != null) {
                player.seekToNext();
                resetControlsTimer();
            }
        });

        btnFav.setOnClickListener(v -> {
            toggleFavorite();
            resetControlsTimer();
        });

        btnQueue.setOnClickListener(v -> {
            toggleDrawer(true);
            resetControlsTimer();
        });
        
        layoutDrawer.setOnClickListener(v -> toggleDrawer(false));

        btnRepeat.setOnClickListener(v -> {
            if (player == null) return;
            
            // Current State
            int repeatMode = player.getRepeatMode();
            boolean isShuffle = player.getShuffleModeEnabled();

            // Next State Logic: All(Loop) -> Shuffle(All+S) -> One(1) -> All(Loop)...
            if (isShuffle) {
                // Currently Shuffle -> Switch to Single Loop (One)
                player.setShuffleModeEnabled(false);
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                Toast.makeText(this, "已切换为单曲循环", Toast.LENGTH_SHORT).show();
            } else if (repeatMode == Player.REPEAT_MODE_ONE) {
                // Currently Single Loop -> Switch to List Loop (All)
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                Toast.makeText(this, "已切换为列表循环", Toast.LENGTH_SHORT).show();
            } else {
                // Currently List Loop (or somehow Off) -> Switch to Shuffle
                player.setRepeatMode(Player.REPEAT_MODE_ALL); 
                player.setShuffleModeEnabled(true);
                Toast.makeText(this, "已切换为随机播放", Toast.LENGTH_SHORT).show();
            }
            updateControlsUI();
            resetControlsTimer();
        });

        loadFavorites();
    }

    private void toggleDrawer(boolean show) {
        if (show) {
            layoutDrawer.setVisibility(View.VISIBLE);
            // Lock focus to the drawer by blocking background interaction
            if (layoutMainContent != null) {
                layoutMainContent.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            // Prepare data from current player queue
            if (player != null) {
                List<String> queue = new ArrayList<>();
                for (int i = 0; i < player.getMediaItemCount(); i++) {
                    queue.add(player.getMediaItemAt(i).mediaMetadata.title.toString());
                }
                drawerSongAdapter.setSongs(queue);
                drawerSongAdapter.setPlayingIndex(player.getCurrentMediaItemIndex());
                drawerSongAdapter.setPlayerPlaying(player.isPlaying());
            }

            // Show current playlist name from prefs
            SharedPreferences prefs = getSharedPreferences("XiaoMusicPrefs", 0);
            String playlistName = prefs.getString("last_playlist_name", "");
            if (tvPlaylistName != null) {
                tvPlaylistName.setText(playlistName.isEmpty() ? "" : "[" + playlistName + "]");
            }
            if (tvPlaylistCount != null && player != null) {
                tvPlaylistCount.setText(player.getMediaItemCount() + "首");
            }

            rvDrawerSongs.post(() -> {
                if (player != null) {
                    int currentIndex = player.getCurrentMediaItemIndex();
                    rvDrawerSongs.scrollToPosition(currentIndex);
                    
                    // Delay slightly to ensure RecyclerView has bound the view for requestFocus to succeed
                    rvDrawerSongs.postDelayed(() -> {
                        RecyclerView.ViewHolder vh = rvDrawerSongs.findViewHolderForAdapterPosition(currentIndex);
                        if (vh != null && vh.itemView != null) {
                            vh.itemView.requestFocus();
                        } else {
                            rvDrawerSongs.requestFocus();
                        }
                    }, 50);
                }
            });
        } else {
            layoutDrawer.setVisibility(View.GONE);
            // Restore background interaction when drawer is closed
            if (layoutMainContent != null) {
                layoutMainContent.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            btnQueue.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (layoutDrawer.getVisibility() == View.VISIBLE) {
            toggleDrawer(false);
            resetControlsTimer();
        } else if (isControlsVisible()) {
            hideBottomControls();
        } else {
            super.onBackPressed();
        }
    }

    private boolean isControlsVisible() {
        return layoutBottomControls.getVisibility() == View.VISIBLE && layoutBottomControls.getAlpha() > 0;
    }

    private void showBottomControls() {
        if (layoutBottomControls.getVisibility() == View.VISIBLE && layoutBottomControls.getAlpha() == 1f) return;
        
        layoutBottomControls.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(layoutBottomControls, "alpha", layoutBottomControls.getAlpha(), 1f)
                .setDuration(300)
                .start();
        
        btnPlayPause.requestFocus();
        resetControlsTimer();
    }

    private void hideBottomControls() {
        if (layoutBottomControls.getVisibility() == View.GONE) return;

        ObjectAnimator animator = ObjectAnimator.ofFloat(layoutBottomControls, "alpha", layoutBottomControls.getAlpha(), 0f);
        animator.setDuration(300);
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                layoutBottomControls.setVisibility(View.GONE);
                layoutTooltip.setVisibility(View.INVISIBLE);
            }
        });
        animator.start();
    }

    private void resetControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable);
        // If controls are visible or currently animating into visibility
        if (layoutBottomControls != null && layoutBottomControls.getVisibility() == View.VISIBLE) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (layoutDrawer.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(event);
        }

        if (action == android.view.KeyEvent.ACTION_DOWN) {
            resetControlsTimer();
            if (!isControlsVisible()) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    showBottomControls();
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.getRepeatCount() == 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastLeftClickTime < DOUBLE_CLICK_INTERVAL) {
                            handler.removeCallbacks(showShortcutHintRunnable);
                            handler.removeCallbacks(hideSeekHintRunnable);
                            skipToPrevious();
                            lastLeftClickTime = 0;
                        } else {
                            lastLeftClickTime = now;
                            showShortcutHint();
                        }
                    } else {
                        // Any repeat means the user is holding the key
                        handler.removeCallbacks(showShortcutHintRunnable);
                        handler.removeCallbacks(hideSeekHintRunnable);
                        if (!isSeeking) {
                            layoutSeekProgress.setVisibility(View.GONE);
                        }
                        if (event.getRepeatCount() > 2) {
                            if (!isSeeking) {
                                isSeeking = true;
                                handler.post(seekBackwardRunnable);
                            }
                        }
                    }
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getRepeatCount() == 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastRightClickTime < DOUBLE_CLICK_INTERVAL) {
                            handler.removeCallbacks(showShortcutHintRunnable);
                            handler.removeCallbacks(hideSeekHintRunnable);
                            if (player != null) player.seekToNext();
                            lastRightClickTime = 0;
                        } else {
                            lastRightClickTime = now;
                            showShortcutHint();
                        }
                    } else {
                        // Any repeat means the user is holding the key
                        handler.removeCallbacks(showShortcutHintRunnable);
                        handler.removeCallbacks(hideSeekHintRunnable);
                        if (!isSeeking) {
                            layoutSeekProgress.setVisibility(View.GONE);
                        }
                        if (event.getRepeatCount() > 2) {
                            if (!isSeeking) {
                                isSeeking = true;
                                handler.post(seekForwardRunnable);
                            }
                        }
                    }
                    return true;
                }
            } else {
                // Controls visible
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    // If focus is already on bottom row, hide
                    View focused = getCurrentFocus();
                    if (focused == btnPlayPause || focused == btnPrev || focused == btnNext || focused == btnFav || focused == btnRepeat || focused == btnQueue) {
                        hideBottomControls();
                        return true;
                    }
                }
            }
        } else if (action == android.view.KeyEvent.ACTION_UP) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (isSeeking) {
                    handler.removeCallbacks(showShortcutHintRunnable);
                    isSeeking = false;
                    handler.removeCallbacks(seekForwardRunnable);
                    handler.removeCallbacks(seekBackwardRunnable);
                    layoutSeekProgress.setVisibility(View.GONE);
                }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void skipToPrevious() {
        if (player == null) return;
        // If the track has played for a while, seekToPrevious normally just restarts the current song.
        // We want to force it to ALWAYS skip to the previous song.
        // So if we are past the threshold (typically 3s), we seek to 0 first, then call seekToPrevious.
        if (player.getCurrentPosition() > player.getMaxSeekToPreviousPosition()) {
            player.seekTo(0);
        }
        player.seekToPrevious();
    }
    
    // This was originally inside setupControls(), moved out as per instruction's implied structure
    // to accommodate toggleDrawer and onBackPressed.
    private void setupSeekBarListener() {
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                         long newPos = (duration * progress) / 100;
                         tvCurrentTime.setText(formatTime(newPos));
                         player.seekTo(newPos);
                         
                         // Instantly trace tooltip changes
                         if (layoutTooltip.getVisibility() == View.VISIBLE) {
                             tvTooltip.setText(formatTime(newPos) + "/" + formatTime(duration));
                             int availableWidth = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
                             float thumbX = seekBar.getPaddingLeft() + (availableWidth * (float) progress / 100f);
                             layoutTooltip.setTranslationX(thumbX - (layoutTooltip.getWidth() / 2f));
                         }
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                 stopProgressUpdater();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                 startProgressUpdater();
            }
        });
    }

    private void updateControlsUI() {
        if (player == null) return;
        
        // Play/Pause
        btnPlayPause.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play); 

        // Mode Display
        int repeatMode = player.getRepeatMode();
        boolean isShuffle = player.getShuffleModeEnabled();

        if (isShuffle) {
            btnRepeat.setImageResource(R.drawable.ic_shuffle);
            btnRepeat.setAlpha(1.0f);
        } else if (repeatMode == Player.REPEAT_MODE_ONE) {
            btnRepeat.setImageResource(R.drawable.ic_repeat_one);
            btnRepeat.setAlpha(1.0f);
        } else if (repeatMode == Player.REPEAT_MODE_ALL) {
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setAlpha(1.0f);
        } else {
            // Fallback (Off) visually mapped to List Loop as it's the enforced default
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setAlpha(1.0f);
        }
        
        updateFavUI();
        if (layoutDrawer.getVisibility() == View.VISIBLE) {
            drawerSongAdapter.setPlayingIndex(player.getCurrentMediaItemIndex());
            drawerSongAdapter.setPlayerPlaying(player.isPlaying());
        }
    }

    private void loadFavorites() {
        // Fetch favorites from server instead of local cache
        apiService.getMusicList().enqueue(new Callback<java.util.Map<String, java.util.List<String>>>() {
            @Override
            public void onResponse(Call<java.util.Map<String, java.util.List<String>>> call,
                                   Response<java.util.Map<String, java.util.List<String>>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    java.util.List<String> favList = response.body().get("我的收藏");
                    favoritesSet = (favList != null)
                            ? new java.util.HashSet<>(favList)
                            : new java.util.HashSet<>();
                    updateFavUI();
                }
            }
            @Override
            public void onFailure(Call<java.util.Map<String, java.util.List<String>>> call, Throwable t) {
                // Keep empty set on failure
            }
        });
    }

    private void updateFavUI() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        
        String songName = tvBigTitle.getText().toString(); // Use current displayed title
        isFavorited = favoritesSet.contains(songName);
        
        btnFav.setImageResource(isFavorited ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        btnFav.setColorFilter(isFavorited ? android.graphics.Color.RED : android.graphics.Color.WHITE);
        btnFav.setAlpha(1.0f);
    }

    private void toggleFavorite() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        
        String songName = tvBigTitle.getText().toString();
        if (songName.isEmpty()) return;

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "我的收藏");
        java.util.List<String> songs = new java.util.ArrayList<>();
        songs.add(songName);
        body.put("music_list", songs);

        if (isFavorited) {
            // Remove
            apiService.removeFromPlaylist(body).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful()) {
                        favoritesSet.remove(songName);
                        updateFavUI();
                        Toast.makeText(PlayerActivity.this, "已从收藏中移除", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    Toast.makeText(PlayerActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Add
            apiService.addToPlaylist(body).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful()) {
                        favoritesSet.add(songName);
                        updateFavUI();
                        Toast.makeText(PlayerActivity.this, "已加入收藏", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    Toast.makeText(PlayerActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void setupPlayer() {
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                updateMetadata(mediaItem);
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) startProgressUpdater();
                else stopProgressUpdater();
                updateControlsUI();
            }
            @Override
            public void onRepeatModeChanged(int repeatMode) {
                updateControlsUI();
            }
            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                updateControlsUI();
            }
        });
        
        // Enforce Default Mode: List Loop (REPEAT_MODE_ALL)
        if (player.getRepeatMode() == Player.REPEAT_MODE_OFF && !player.getShuffleModeEnabled()) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        }
        
        if (player.getCurrentMediaItem() != null) {
            updateMetadata(player.getCurrentMediaItem());
        }
        if (player.isPlaying()) startProgressUpdater();
        updateControlsUI();
    }

    private void updateMetadata(MediaItem mediaItem) {
        if (mediaItem == null) return;
        
        CharSequence titleSeq = mediaItem.mediaMetadata.title;
        String title = titleSeq != null ? titleSeq.toString() : null;
        String originalName = null;
        String lyrics = null;

        if (mediaItem.mediaMetadata.extras != null) {
            originalName = mediaItem.mediaMetadata.extras.getString("original_name");
            lyrics = mediaItem.mediaMetadata.extras.getString("lyrics");
        }
        
        SharedPreferences prefs = getSharedPreferences("XiaoMusicPrefs", 0);
        String savedName = prefs.getString("current_song_name", null);
        
        Log.d(TAG, "updateMetadata: title=" + title + ", lrc=" + (lyrics != null ? "yes" : "no"));
        
        String queryName = originalName != null ? originalName : (savedName != null ? savedName : title);
        
        if (queryName != null && !queryName.equals(savedName)) {
            prefs.edit().putString("current_song_name", queryName).apply();
        }
        
        if (title != null) {
            tvBigTitle.setText(title);
            tvBigArtist.setText(mediaItem.mediaMetadata.artist != null ? mediaItem.mediaMetadata.artist : "");
        } else if (queryName != null) {
            tvBigTitle.setText(queryName);
            tvBigArtist.setText("加载中...");
        }

        // Handle Lyrics from Metadata
        if (lyrics != null) {
            String lyricArtist = extractArtistFromLyrics(lyrics);
            if (lyricArtist != null && !lyricArtist.isEmpty()) {
                tvBigArtist.setText(lyricArtist);
            }
            parseLyrics(lyrics);
        } else {
            // Keep previous visibility if waiting for fetch, but if it's been a while...
            // Actually MusicService will push an update soon.
            showNoLyrics("加载歌词...");
        }
        
        // Handle Artwork from Metadata
        if (mediaItem.mediaMetadata.artworkUri != null) {
            String uri = mediaItem.mediaMetadata.artworkUri.toString();
            // Load Cover
            Glide.with(this).load(uri)
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .transform(new RoundedCorners(80)).into(ivBigCover);
            // Load Blur Background
            Glide.with(this).load(uri)
                .transform(new jp.wasabeef.glide.transformations.BlurTransformation(20, 3))
                .into(ivBlurBackground);
        } else {
            ivBigCover.setImageResource(R.drawable.ic_cover_placeholder);
        }
    }


    private void showNoLyrics(String msg) {
        rvLyrics.setVisibility(View.GONE);
        tvNoLyrics.setVisibility(View.VISIBLE);
        tvNoLyrics.setText(msg);
        // Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); // Optional toast
    }

    private void parseLyrics(String lrc) {
        Log.d(TAG, "Parsing lyrics length: " + lrc.length());
        List<LyricAdapter.LyricLine> lines = new ArrayList<>();
        
        // Try to parse timestamps
        try (BufferedReader reader = new BufferedReader(new StringReader(lrc))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int closing = line.indexOf(']');
                if (closing > 0 && line.startsWith("[")) {
                     String timePart = line.substring(1, closing);
                     String textPart = line.substring(closing + 1).trim();
                     
                     try {
                         String[] parts = timePart.split(":");
                         if (parts.length >= 2) {
                             long min = Long.parseLong(parts[0].trim());
                             double sec = Double.parseDouble(parts[1].trim());
                             long timeMs = (long) ((min * 60 + sec) * 1000);
                             if (!textPart.isEmpty()) { 
                                 lines.add(new LyricAdapter.LyricLine(timeMs, textPart));
                             }
                         }
                     } catch (Exception e) {
                         // ignore invalid line
                     }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        Log.d(TAG, "Parsed lines: " + lines.size());
        
        if (lines.isEmpty()) {
             if (!lrc.trim().isEmpty()) {
                 showNoLyrics(lrc); 
                 tvNoLyrics.setMaxLines(20);
                 tvNoLyrics.setEllipsize(android.text.TextUtils.TruncateAt.END);
             } else {
                 showNoLyrics("没有歌词");
             }
             lyricAdapter.setLyrics(new ArrayList<>());
        } else {
             rvLyrics.setVisibility(View.VISIBLE);
             tvNoLyrics.setVisibility(View.GONE);
             lyricAdapter.setCurrentIndex(-1);
             rvLyrics.scrollToPosition(0);
             lyricAdapter.setLyrics(lines);
             // Toast.makeText(this, "Loaded " + lines.size() + " lines", Toast.LENGTH_SHORT).show();
        }
    }

    private String extractArtistFromLyrics(String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return null;
        try (BufferedReader reader = new BufferedReader(new StringReader(lyrics))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase().startsWith("[ar:")) {
                    int closing = line.indexOf(']');
                    if (closing > 4) {
                        return line.substring(4, closing).trim();
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    private void startProgressUpdater() {
        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }
    
    private void stopProgressUpdater() {
        handler.removeCallbacks(progressUpdater);
    }

    private void updateProgress() {
        if (player == null) return;
        long current = player.getCurrentPosition();
        long duration = player.getDuration();
        
        tvCurrentTime.setText(formatTime(current));
        tvDuration.setText(formatTime(duration));
        if (duration > 0) {
            int prog = (int) ((current * 100) / duration);
            progressBar.setProgress(prog);
            
            // Update Tooltip
            if (layoutTooltip.getVisibility() == View.VISIBLE) {
                tvTooltip.setText(formatTime(current) + "/" + formatTime(duration));
                int availableWidth = progressBar.getWidth() - progressBar.getPaddingLeft() - progressBar.getPaddingRight();
                float thumbX = progressBar.getPaddingLeft() + (availableWidth * (float) prog / 100f);
                layoutTooltip.setTranslationX(thumbX - (layoutTooltip.getWidth() / 2f));
            }
        }

        // Sync Lyric
        updateLyric(current);
    }

    private void updateLyric(long currentPos) {
        if (lyricAdapter.getItemCount() == 0) return;
        
        // 注意：因为 LyricAdapter 内部在 setLyrics 时在开头加了一个空行，
        // 这里的原始数据索引需要对应调整。
        List<LyricAdapter.LyricLine> rawLyrics = lyricAdapter.getLyrics();
        if (rawLyrics.size() < 3) return; // 只有空行，没有实际歌词

        int activeIdx = -1;
        // 注意：i 从 1 开始，避开前置空行；i 到 size-1 结束，避开后置空行
        for (int i = 1; i < rawLyrics.size() - 1; i++) {
            if (currentPos >= rawLyrics.get(i).timeMs) {
                activeIdx = i;
            } else {
                break;
            }
        }
        
        if (activeIdx != -1 && activeIdx != lyricAdapter.getCurrentIndex()) {
            lyricAdapter.setCurrentIndex(activeIdx);
            
            androidx.recyclerview.widget.LinearSmoothScroller smoothScroller = new androidx.recyclerview.widget.LinearSmoothScroller(this) {
                @Override
                public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                    return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
                }
                @Override
                protected float calculateSpeedPerPixel(android.util.DisplayMetrics displayMetrics) {
                    return 200f / displayMetrics.densityDpi; // Slower smooth scroll
                }
            };
            
            smoothScroller.setTargetPosition(activeIdx);
            if (rvLyrics.getLayoutManager() != null) {
                rvLyrics.getLayoutManager().startSmoothScroll(smoothScroller);
            }
        }
    }

    private String formatTime(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        stopProgressUpdater();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (btnPlayPause != null) {
            btnPlayPause.post(() -> btnPlayPause.requestFocus());
        }
    }
}
