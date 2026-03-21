package cc.ganhualin.xiaomusictv;

import android.content.Intent; 
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.view.View;
import android.view.KeyEvent;
import android.animation.ObjectAnimator;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;

import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import com.google.common.util.concurrent.ListenableFuture;
import android.content.ComponentName;
import java.util.concurrent.ExecutionException;
import android.net.Uri;
import android.content.SharedPreferences;
import com.bumptech.glide.Glide;
import com.google.gson.JsonObject;

public class LibraryActivity extends AppCompatActivity {
    private static final String TAG = "LibraryActivity";
    private RecyclerView rvPlaylists;
    private RecyclerView rvSongs;
    private PlaylistAdapter playlistAdapter;
    private SongAdapter songAdapter;
    private Map<String, List<String>> musicData;
    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private String baseUrl;

    private TextView tvCurrentTitle, tvCurrentTime; 
    private ImageView ivCurrentCover;
    private ImageButton btnPlayPause, btnNext;
    private ApiService apiService;
    
    private TextView tvPlaylistTitle, tvSongCount;
    private ImageButton btnSettings;
    private long lastBackPressTime;
    private ObjectAnimator rotateAnim;
    // Tracks target position during fast scroll to allow continuous advancement
    private int pendingFocusPos = -1;
    // Tracks which child view ID should receive focus in the target item
    private int pendingFocusChildId = View.NO_ID;

    private List<String> currentPlaylistSongs = new ArrayList<>();
    private String currentPlaylistName = "";
    private int lastFocusedSongIndex = -1; // Added to track position for back navigation

    // Define excluded playlists
    private static final Set<String> EXCLUDED_PLAYLISTS = new HashSet<>(Arrays.asList(
        "所有电台", "收藏", "全部", "其他", "最近新增"
    ));

    private boolean isRefreshingAfterDelete = false;
    private boolean isInitialLoad = true;
    private boolean isFadingFocusDuringRefresh = false;
    private int pendingDeleteFocusIndex = -1; // 刷新期间锁定焦点，防止飘走

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        SharedPreferences settings = getSharedPreferences("XiaoMusicPrefs", 0);
        baseUrl = settings.getString("server_url", "");
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        apiService = RetrofitClient.getClient(this).create(ApiService.class);

        rvPlaylists = findViewById(R.id.rvPlaylists);
        rvSongs = findViewById(R.id.rvSongs);
        tvCurrentTitle = findViewById(R.id.tvCurrentTitle);
        tvCurrentTitle.setSelected(true); 
        tvCurrentTime = findViewById(R.id.tvCurrentArtist); 
        ivCurrentCover = findViewById(R.id.ivCurrentCover);
        View cvCurrentCover = findViewById(R.id.cvCurrentCover);
        if (cvCurrentCover != null) { // Check for null safety
            cvCurrentCover.setLayerType(View.LAYER_TYPE_HARDWARE, null); // Optimize for animation
            rotateAnim = ObjectAnimator.ofFloat(cvCurrentCover, "rotation", 0f, 360f);
            rotateAnim.setDuration(10000); // 10s per rotation
            rotateAnim.setInterpolator(new LinearInterpolator());
            rotateAnim.setRepeatCount(ObjectAnimator.INFINITE);
            rotateAnim.setRepeatMode(ObjectAnimator.RESTART);
        }

        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);
        tvPlaylistTitle = findViewById(R.id.tvPlaylistTitle);
        tvSongCount = findViewById(R.id.tvSongCount);
        btnSettings = findViewById(R.id.btnSettings);
        
        // 确保列表容器本身可聚焦，作为焦点丢失时的缓冲
        rvPlaylists.setFocusable(true);
        rvSongs.setFocusable(true);
        
        btnSettings.setOnClickListener(this::showSettingsMenu);
        btnSettings.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && (isRefreshingAfterDelete || isFadingFocusDuringRefresh) && pendingDeleteFocusIndex >= 0) {
                // 如果发现焦点逃逸到了设置按钮，强行弹回列表
                rvSongs.post(() -> {
                    if (rvSongs.getLayoutManager() != null) {
                        View target = rvSongs.getLayoutManager().findViewByPosition(pendingDeleteFocusIndex);
                        if (target != null) {
                            View db = target.findViewById(R.id.btnItemDelete);
                            if (db != null) db.requestFocus();
                            else target.requestFocus();
                        } else {
                            rvSongs.requestFocus();
                        }
                    }
                });
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (player != null) player.seekToNext();
        });

        // Right key from btnNext → jump to closest song item by Y position
        btnNext.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && rvSongs.getLayoutManager() != null) {

                LinearLayoutManager songLm = (LinearLayoutManager) rvSongs.getLayoutManager();
                int firstVisible = songLm.findFirstVisibleItemPosition();
                int lastVisible = songLm.findLastVisibleItemPosition();

                if (firstVisible >= 0) {
                    int[] btnLoc = new int[2];
                    v.getLocationOnScreen(btnLoc);
                    int btnCenterY = btnLoc[1] + v.getHeight() / 2;

                    View bestMatch = null;
                    int bestDiff = Integer.MAX_VALUE;
                    for (int i = firstVisible; i <= lastVisible; i++) {
                        View child = songLm.findViewByPosition(i);
                        if (child == null) continue;
                        int[] childLoc = new int[2];
                        child.getLocationOnScreen(childLoc);
                        int childCenterY = childLoc[1] + child.getHeight() / 2;
                        int diff = Math.abs(childCenterY - btnCenterY);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestMatch = child;
                        }
                    }
                    if (bestMatch != null) {
                        bestMatch.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        });

        findViewById(R.id.btnOpenPlayer).setOnClickListener(v -> {
            startActivity(new Intent(this, PlayerActivity.class));
        });

        // Keep screen on while this activity is in foreground
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        if (rvPlaylists.getItemAnimator() != null) {
            ((SimpleItemAnimator) rvPlaylists.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        playlistAdapter = new PlaylistAdapter();
        rvPlaylists.setAdapter(playlistAdapter);
        
        rvPlaylists.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public View onInterceptFocusSearch(View focused, int direction) {
                if (direction == View.FOCUS_RIGHT && rvSongs.getLayoutManager() != null) {
                    // Find the closest item in rvSongs by Y position
                    LinearLayoutManager songLm = (LinearLayoutManager) rvSongs.getLayoutManager();
                    int firstVisible = songLm.findFirstVisibleItemPosition();
                    int lastVisible = songLm.findLastVisibleItemPosition();

                    if (firstVisible < 0) return rvSongs;

                    // Get Y center of the currently focused playlist item
                    int[] focusedLoc = new int[2];
                    focused.getLocationOnScreen(focusedLoc);
                    int focusedCenterY = focusedLoc[1] + focused.getHeight() / 2;

                    // Find song item whose center Y is closest to focus center Y
                    View bestMatch = null;
                    int bestDiff = Integer.MAX_VALUE;
                    for (int i = firstVisible; i <= lastVisible; i++) {
                        View child = songLm.findViewByPosition(i);
                        if (child == null) continue;
                        int[] childLoc = new int[2];
                        child.getLocationOnScreen(childLoc);
                        int childCenterY = childLoc[1] + child.getHeight() / 2;
                        int diff = Math.abs(childCenterY - focusedCenterY);
                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestMatch = child;
                        }
                    }
                    if (bestMatch != null) return bestMatch;
                    // Fallback to first visible
                    View fallback = songLm.findViewByPosition(firstVisible);
                    return fallback != null ? fallback : rvSongs;
                }
                return super.onInterceptFocusSearch(focused, direction);
            }
        });
        
        rvSongs.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public View onInterceptFocusSearch(View focused, int direction) {
                // 如果正处于删除后的刷新期，强行拦截试图离开当前 Item 的上下焦点搜索（防止动画期间跳到其它项）
                if (isFadingFocusDuringRefresh && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                    return focused;
                }
                if (direction == View.FOCUS_LEFT) {
                    // 1. 找到当前项的根容器
                    View itemRoot = rvSongs.findContainingItemView(focused);
                    if (itemRoot != null && itemRoot instanceof android.view.ViewGroup) {
                        // 2. 检查在【当前项内部】是否还有更左侧的 View 可聚焦
                        View nextInside = android.view.FocusFinder.getInstance()
                                .findNextFocus((android.view.ViewGroup) itemRoot, focused, View.FOCUS_LEFT);
                        
                        if (nextInside != null) {
                            // 内部还有按钮（比如从“删除”向左移到“收藏”），走默认流程
                            return null;
                        }
                    }
                    // 3. 已经在项的最左侧了，强行跳往歌单列表
                    if (rvPlaylists != null) return rvPlaylists;
                }
                if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
                    RecyclerView.ViewHolder holder = rvSongs.findContainingViewHolder(focused);

                    int currentPos;
                    if (holder != null) {
                        currentPos = holder.getAdapterPosition();
                        // Lock at boundaries
                        if (direction == View.FOCUS_UP && currentPos == 0) return focused;
                        if (direction == View.FOCUS_DOWN && currentPos == getItemCount() - 1) return focused;
                        // Record the focused child's ID so we can match it in the target item
                        pendingFocusChildId = focused.getId();
                    } else if (pendingFocusPos >= 0) {
                        currentPos = pendingFocusPos;
                    } else {
                        currentPos = (direction == View.FOCUS_DOWN)
                                ? findLastVisibleItemPosition()
                                : findFirstVisibleItemPosition();
                    }

                    if (currentPos < 0) return focused;

                    int targetPos = (direction == View.FOCUS_DOWN) ? currentPos + 1 : currentPos - 1;
                    if (targetPos < 0 || targetPos >= getItemCount()) return focused;

                    int first = findFirstVisibleItemPosition();
                    int last = findLastVisibleItemPosition();

                    if (targetPos >= first && targetPos <= last) {
                        // Target is on-screen: find the matching child view
                        View targetItemRoot = findViewByPosition(targetPos);
                        if (targetItemRoot != null) {
                            pendingFocusPos = -1;
                            return focusInItem(targetItemRoot, pendingFocusChildId);
                        }
                    }

                    // Target not yet on-screen: start pendingFocusPos mechanism
                    pendingFocusPos = targetPos;
                    scrollToPosition(targetPos);
                    rvSongs.post(() -> {
                        int f = findFirstVisibleItemPosition();
                        int l = findLastVisibleItemPosition();
                        if (pendingFocusPos >= f && pendingFocusPos <= l) {
                            View targetItemRoot = findViewByPosition(pendingFocusPos);
                            if (targetItemRoot != null) {
                                focusInItem(targetItemRoot, pendingFocusChildId).requestFocus();
                                pendingFocusPos = -1;
                                pendingFocusChildId = View.NO_ID;
                            }
                        }
                        // else: leave for onScrollStateChanged
                    });
                    return focused; // Hold current focus until post fires
                }
                return super.onInterceptFocusSearch(focused, direction);
            }

            @Override
            public View onFocusSearchFailed(View focused, int focusDirection,
                    RecyclerView.Recycler recycler, RecyclerView.State state) {
                // Fallback: should rarely be called now that onInterceptFocusSearch handles UP/DOWN
                if (focusDirection == View.FOCUS_DOWN || focusDirection == View.FOCUS_UP) {
                    if (pendingFocusPos >= 0) {
                        int f = findFirstVisibleItemPosition();
                        int l = findLastVisibleItemPosition();
                        if (pendingFocusPos >= f && pendingFocusPos <= l) {
                            View targetItemRoot = findViewByPosition(pendingFocusPos);
                            if (targetItemRoot != null) {
                                focusInItem(targetItemRoot, pendingFocusChildId).requestFocus();
                                pendingFocusPos = -1;
                                pendingFocusChildId = View.NO_ID;
                            }
                        }
                    }
                    return focused; // Never let focus escape
                }
                return null;
            }
        });
        
        songAdapter = new SongAdapter(this);
        rvSongs.setAdapter(songAdapter);

        // Allow rvSongs itself to hold focus as a fallback when items are recycled during fast scroll.
        // Without this, focus escapes to the nearest focusable outside (the Settings button).
        rvSongs.setFocusable(true);
        rvSongs.setFocusableInTouchMode(true);

        // When rvSongs itself has focus (child views were recycled), intercept UP/DOWN keys
        // and redirect focus back to the nearest visible item.
        rvSongs.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return false;

            LinearLayoutManager lm = (LinearLayoutManager) rvSongs.getLayoutManager();
            if (lm == null) return false;

            // Only act when rvSongs itself holds focus (not a child item)
            if (rvSongs.getFocusedChild() != null) return false;

            int pos = (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                    ? lm.findLastVisibleItemPosition()
                    : lm.findFirstVisibleItemPosition();

            if (pos >= 0) {
                View target = lm.findViewByPosition(pos);
                if (target != null) {
                    target.requestFocus();
                    return true;
                }
            }
            return false;
        });

        // When fast-scroll ends, rvSongs may hold focus without any child item highlighted.
        // Restore focus when scrolling stops if no child item has focus.
        rvSongs.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                LinearLayoutManager lm = (LinearLayoutManager) rvSongs.getLayoutManager();
                if (lm == null) return;

                int first = lm.findFirstVisibleItemPosition();
                int last = lm.findLastVisibleItemPosition();

                if (pendingFocusPos >= 0) {
                    if (pendingFocusPos >= first && pendingFocusPos <= last) {
                        // Pending target is now visible — focus it
                        View target = lm.findViewByPosition(pendingFocusPos);
                        if (target != null) target.requestFocus();
                    }
                    // Whether we focused or not, clear the stale pending state
                    pendingFocusPos = -1;
                    return;
                }

                // No pending: only restore focus if rvSongs itself holds focus (child was recycled)
                if (rvSongs.hasFocus() && rvSongs.getFocusedChild() == null) {
                    int pos = lm.findFirstCompletelyVisibleItemPosition();
                    if (pos < 0) pos = first;
                    View target = lm.findViewByPosition(pos);
                    if (target != null) target.requestFocus();
                }
            }
        });


        playlistAdapter.setOnItemClickListener(playlistName -> {
            if (musicData != null) {
                List<String> songs = musicData.get(playlistName);
                if (songs != null) {
                    currentPlaylistSongs = songs;
                    currentPlaylistName = playlistName;
                    songAdapter.setSongs(songs);
                    rvSongs.scrollToPosition(0);
                    tvPlaylistTitle.setText(playlistName);
                    tvSongCount.setText(songs.size() + " 首歌曲");
                    updatePlayingStatus();
                }
            }
        });

        songAdapter.setOnItemClickListener((song, position) -> {
            if (player != null && player.getCurrentMediaItem() != null 
                && player.getCurrentMediaItem().mediaMetadata.title != null
                && song.equals(player.getCurrentMediaItem().mediaMetadata.title.toString())) {
                if (player.isPlaying()) player.pause(); else player.play();
            } else {
                playSongAtIndex(position);
            }
        });
        songAdapter.setOnActionClickListener(new SongAdapter.OnActionClickListener() {
            @Override public void onFullscreen(String song, int position) {
                lastFocusedSongIndex = position; // Record position before leaving
                if (player == null || player.getCurrentMediaItem() == null || player.getCurrentMediaItem().mediaMetadata.title == null || !song.equals(player.getCurrentMediaItem().mediaMetadata.title.toString())) playSongAtIndex(position);
                startActivity(new Intent(LibraryActivity.this, PlayerActivity.class));
            }
            @Override public void onFav(String song, int position) { toggleFavorite(song); }
            @Override public void onDelete(String song, int position) { confirmDelete(song, position); }
        });

        fetchMusicList();
    }

    private void updatePlayingStatus() {
        if (player == null || songAdapter == null) return;
        int index = -1;
        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem != null && currentItem.mediaMetadata.title != null) {
            String title = currentItem.mediaMetadata.title.toString();
            // 增强匹配：处理列表内容可能带有的空格等差异
            for (int i = 0; i < currentPlaylistSongs.size(); i++) {
                if (title.equals(currentPlaylistSongs.get(i))) {
                    index = i;
                    break;
                }
            }
        }
        
        // 关键：通知适配器当前的播放索引和播放状态
        songAdapter.setPlayingIndex(index);
        songAdapter.setPlayerPlaying(player.isPlaying());
    }
    
    private void toggleFavorite(String song) {
        boolean isFav = false;
        List<String> favs = new ArrayList<>();
        if (musicData != null) {
             if (musicData.containsKey("我的收藏")) {
                 favs = new ArrayList<>(musicData.get("我的收藏")); // Copy to modify
                 if (favs.contains(song)) isFav = true;
             } else {
                 musicData.put("我的收藏", favs);
             }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("name", "我的收藏");
        body.put("music_list", Arrays.asList(song));

        Call<ResponseBody> call;
        if (isFav) call = apiService.removeFromPlaylist(body);
        else call = apiService.addToPlaylist(body);

        final boolean finalIsFav = isFav; 
        final List<String> finalFavs = favs;

        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(LibraryActivity.this, finalIsFav ? "取消收藏" : "已收藏", Toast.LENGTH_SHORT).show();
                    
                    // Update local memory directly without refresh
                    if (finalIsFav) {
                        finalFavs.remove(song);
                    } else {
                        finalFavs.add(song);
                    }
                    if (musicData != null) musicData.put("我的收藏", finalFavs);
                    
                    // Update Adapter without full refresh
                    songAdapter.setFavorites(finalFavs, false);
                    int updateIndex = currentPlaylistSongs.indexOf(song);
                    if (updateIndex != -1) songAdapter.updateItem(updateIndex);
                    
                    // If we are currently viewing "我的收藏", update the list
                    if ("我的收藏".equals(currentPlaylistName)) {
                        currentPlaylistSongs = finalFavs;
                        songAdapter.setSongs(currentPlaylistSongs);
                        tvSongCount.setText(currentPlaylistSongs.size() + " 首歌曲");
                    }
                    
                    // Update Playlist Adapter Count
                    playlistAdapter.notifyPlaylistUpdated("我的收藏", finalFavs);
                    
                } else {
                    Toast.makeText(LibraryActivity.this, "操作失败: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                Toast.makeText(LibraryActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDelete(String song, int position) {
        new AlertDialog.Builder(this)
            .setTitle("删除歌曲")
            .setMessage("确定要删除 " + song + " 吗？")
            .setPositiveButton("删除", (dialog, which) -> deleteSong(song, position))
            .setNegativeButton("取消", null)
            .show();
    }

    /** Returns the child view matching childId inside itemRoot, or itemRoot itself as fallback. */
    private View focusInItem(View itemRoot, int childId) {
        if (childId != View.NO_ID && itemRoot instanceof android.view.ViewGroup) {
            View match = itemRoot.findViewById(childId);
            if (match != null && match.isFocusable()) return match;
        }
        return itemRoot;
    }

    private void deleteSong(String song, int position) {
        Map<String, String> body = new HashMap<>();
        body.put("name", song);
        
        Call<ResponseBody> call2;
        if ("我的收藏".equals(currentPlaylistName)) {
             Map<String, Object> plBody = new HashMap<>();
             plBody.put("name", "我的收藏");
             plBody.put("music_list", Arrays.asList(song));
             call2 = apiService.removeFromPlaylist(plBody);
        } else {
             call2 = apiService.delMusic(body);
        }

        call2.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    Toast.makeText(LibraryActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                    // 3. 处理焦点移动逻辑：下一行 -> 上一行 -> 歌单列表
                    isFadingFocusDuringRefresh = true; 
                    // 不再粗暴禁用可聚焦性，以保证导航链路不中断
                    
                    // 1. 立即从当前显示的列表中移除，提供即时反馈 (数据变化会引起焦点变动，所以必须在护盾后执行)
                    if (currentPlaylistSongs != null && currentPlaylistSongs.contains(song)) {
                        currentPlaylistSongs.remove(song);
                        songAdapter.setSongs(currentPlaylistSongs);
                        tvSongCount.setText(currentPlaylistSongs.size() + " 首歌曲");
                    }
                    
                    // 2. 从全局数据缓存中也移除该歌曲，确保其他地方数据一致
                    if (musicData != null) {
                        for (Map.Entry<String, List<String>> entry : musicData.entrySet()) {
                            if (entry.getValue() != null) {
                                entry.getValue().remove(song);
                            }
                        }
                    }
                    
                    if (currentPlaylistSongs != null) {
                        int currentCount = currentPlaylistSongs.size();
                        if (currentCount > 0) {
                            final int targetPos = (position < currentCount) ? position : currentCount - 1;
                            pendingDeleteFocusIndex = targetPos; 
                            
                            // 核心修复：如果删除的是当前正在播放的歌，需要同步播放器的队列
                            if (player != null && player.getCurrentMediaItem() != null) {
                                String currentPlayingTitle = player.getCurrentMediaItem().mediaMetadata.title != null ? 
                                    player.getCurrentMediaItem().mediaMetadata.title.toString() : "";
                                if (song.equals(currentPlayingTitle)) {
                                    // 重新加载当前列表到播放器，并跳转到目标位置
                                    playSongAtIndex(targetPos);
                                }
                            }
                            
                            rvSongs.scrollToPosition(targetPos);
                            rvSongs.postDelayed(() -> {
                                if (rvSongs.getLayoutManager() != null) {
                                    View view = rvSongs.getLayoutManager().findViewByPosition(targetPos);
                                    if (view != null) {
                                        View deleteBtn = view.findViewById(R.id.btnItemDelete);
                                        if (deleteBtn != null && deleteBtn.isFocusable()) {
                                            deleteBtn.requestFocus();
                                        } else {
                                            view.requestFocus();
                                        }
                                        isFadingFocusDuringRefresh = false; 
                                    } else {
                                        // 如果 view 还没出来，再等一会聚焦，护盾继续开启
                                        rvSongs.postDelayed(() -> {
                                            isFadingFocusDuringRefresh = false;
                                            View v2 = rvSongs.getLayoutManager().findViewByPosition(targetPos);
                                            if (v2 != null) {
                                                View db2 = v2.findViewById(R.id.btnItemDelete);
                                                if (db2 != null) db2.requestFocus();
                                                else v2.requestFocus();
                                            } else {
                                                rvSongs.requestFocus();
                                            }
                                        }, 100);
                                    }
                                }
                            }, 100);
                        } else {
                            pendingDeleteFocusIndex = -1;
                            isFadingFocusDuringRefresh = false;
                            isRefreshingAfterDelete = false;
                            
                            if (btnSettings != null) btnSettings.setFocusable(true);
                            if (tvPlaylistTitle != null) tvPlaylistTitle.setFocusable(true);
                            if (rvPlaylists != null) {
                                rvPlaylists.setFocusable(true);
                                rvPlaylists.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                                rvPlaylists.requestFocus();
                            }
                        }
                    }

                    // 4. 全局刷新
                    isRefreshingAfterDelete = true;
                    fetchMusicList();
                    updatePlayingStatus(); // 立即更新 UI 状态显示
                } else {
                     Toast.makeText(LibraryActivity.this, "删除失败: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> call, Throwable t) {
                Toast.makeText(LibraryActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void showSettingsMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add("刷新列表");
        popup.getMenu().add("退出登录");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("刷新列表")) { fetchMusicList(); return true; }
            if (item.getTitle().equals("退出登录")) {
                if (player != null) player.stop();
                getSharedPreferences("XiaoMusicPrefs", 0).edit().clear().apply();
                Intent intent = new Intent(this, ConfigActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void playSongAtIndex(int index) {
        if (index < 0 || index >= currentPlaylistSongs.size() || player == null) return;
        
        java.util.List<androidx.media3.common.MediaItem> mediaItems = new java.util.ArrayList<>();
        for (String songName : currentPlaylistSongs) {
            String encodedName;
            try {
                encodedName = java.net.URLEncoder.encode(songName, "UTF-8");
            } catch (Exception e) {
                encodedName = songName;
            }
            // 使用标准的 http 协议代替自定义协议，彻底根除老电视对于未知协议的排斥
            // 我们构造一个本机的虚拟 HTTP 地址作为标记
            android.net.Uri fakeUri = android.net.Uri.parse("http://127.0.0.1/xiaomusic_resolve?name=" + encodedName);
            
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("original_name", songName);

            androidx.media3.common.MediaItem item = new androidx.media3.common.MediaItem.Builder()
                    .setMediaId(songName)
                    .setUri(fakeUri)
                    .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(songName)
                            .setExtras(extras)
                            .build())
                    .build();
            mediaItems.add(item);
        }

        player.setMediaItems(mediaItems, index, 0);
        player.prepare();
        player.play();
        
        // Default repeat mode logic if needed, but PlayerActivity handles the UI
        if (player.getRepeatMode() == androidx.media3.common.Player.REPEAT_MODE_OFF) {
             player.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ALL); // Default to list loop as user requested
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, MusicService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                player = controllerFuture.get();
                player.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                        if (isPlaying) {
                            startProgressUpdater();
                            if (rotateAnim != null) {
                                if (rotateAnim.isPaused()) rotateAnim.resume();
                                else if (!rotateAnim.isRunning()) rotateAnim.start();
                            }
                        } else {
                            stopProgressUpdater();
                            if (rotateAnim != null) rotateAnim.pause();
                        }
                        if (songAdapter != null) songAdapter.setPlayerPlaying(isPlaying);
                    }
                    @Override
                    public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                        if (mediaItem != null) {
                            String title = mediaItem.mediaMetadata.title != null ? mediaItem.mediaMetadata.title.toString() : "";
                            tvCurrentTitle.setText(title);
                            updatePlayingStatus();
                            
                            String queryName = title;
                            if (mediaItem.mediaMetadata.extras != null) {
                                String original = mediaItem.mediaMetadata.extras.getString("original_name");
                                if (original != null) queryName = original;
                            }
                            
                            getSharedPreferences("XiaoMusicPrefs", 0).edit()
                                .putString("current_song_name", queryName)
                                .putString("last_playlist_name", currentPlaylistName)
                                .apply();
                            
                            if (mediaItem.mediaMetadata.artworkData != null) {
                                Glide.with(LibraryActivity.this)
                                    .load(mediaItem.mediaMetadata.artworkData)
                                    .placeholder(R.drawable.ic_cover_placeholder)
                                    .error(R.drawable.ic_cover_placeholder)
                                    .into(ivCurrentCover);
                            } else if (mediaItem.mediaMetadata.artworkUri != null) {
                                Glide.with(LibraryActivity.this)
                                    .load(mediaItem.mediaMetadata.artworkUri)
                                    .placeholder(R.drawable.ic_cover_placeholder)
                                    .error(R.drawable.ic_cover_placeholder)
                                    .into(ivCurrentCover);
                            } else {
                                ivCurrentCover.setImageResource(R.drawable.ic_cover_placeholder);
                            }
                        }
                    }
                    @Override 
                    public void onPlaybackStateChanged(int playbackState) {
                        // Native ExoPlayer handles queue transition via MediaItems now.
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        String errorMsg = error.getErrorCodeName() + " - " + error.getMessage();
                        Throwable cause = error.getCause();
                        if (cause != null) {
                            if (cause.getMessage() != null) {
                                errorMsg += "\nCause: " + cause.getMessage();
                            } else {
                                errorMsg += "\nCause: " + cause.toString();
                            }
                        }
                        Log.e(TAG, "Player Error Details: " + errorMsg);
                        Toast.makeText(LibraryActivity.this, "播放失败: " + errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
                if (player.getCurrentMediaItem() != null) {
                    MediaItem current = player.getCurrentMediaItem();
                    String title = current.mediaMetadata.title.toString();
                    tvCurrentTitle.setText(title);
                    if (current.mediaMetadata.artworkData != null) {
                        Glide.with(LibraryActivity.this)
                            .load(current.mediaMetadata.artworkData)
                            .placeholder(R.drawable.ic_cover_placeholder)
                            .error(R.drawable.ic_cover_placeholder)
                            .into(ivCurrentCover);
                    } else if (current.mediaMetadata.artworkUri != null) {
                        Glide.with(LibraryActivity.this)
                            .load(current.mediaMetadata.artworkUri)
                            .placeholder(R.drawable.ic_cover_placeholder)
                            .error(R.drawable.ic_cover_placeholder)
                            .into(ivCurrentCover);
                    } else {
                        ivCurrentCover.setImageResource(R.drawable.ic_cover_placeholder);
                    }
                    
                    // 核心修复：连接成功后立即更新列表播放高亮
                    updatePlayingStatus();
                } else {
                    tryRestoreLastSong();
                }
                btnPlayPause.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                if (player.isPlaying()) {
                    startProgressUpdater();
                    if (rotateAnim != null) {
                        if (rotateAnim.isPaused()) rotateAnim.resume();
                        else if (!rotateAnim.isRunning()) rotateAnim.start();
                    }
                }
                if (songAdapter != null) songAdapter.setPlayerPlaying(player.isPlaying());
                updateProgress();
            } catch (Exception e) { e.printStackTrace(); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }


    @Override
    protected void onStop() {
        super.onStop();
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        stopProgressUpdater();
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // onStart 的 MediaController 回调中会执行 updatePlayingStatus

        // 2. 处理焦点恢复
        if (lastFocusedSongIndex >= 0) {
            int indexToFocus = lastFocusedSongIndex;
            lastFocusedSongIndex = -1;

            rvSongs.postDelayed(() -> {
                LinearLayoutManager lm = (LinearLayoutManager) rvSongs.getLayoutManager();
                if (lm != null) {
                    // 如果播的那首歌不是离开时的那首（全屏切歌了），跳转到新歌
                    int currentIndex = (songAdapter != null) ? songAdapter.getPlayingIndex() : -1;
                    int targetIndex = (currentIndex >= 0) ? currentIndex : indexToFocus;
                    
                    View item = lm.findViewByPosition(targetIndex);
                    if (item != null) {
                        View fullBtn = item.findViewById(R.id.btnItemFullscreen);
                        if (fullBtn != null) fullBtn.requestFocus();
                        else item.requestFocus();
                    } else {
                        rvSongs.scrollToPosition(targetIndex);
                        rvSongs.post(() -> {
                            View newItem = lm.findViewByPosition(targetIndex);
                            if (newItem != null) {
                                View fullBtn = newItem.findViewById(R.id.btnItemFullscreen);
                                if (fullBtn != null) fullBtn.requestFocus();
                                else newItem.requestFocus();
                            }
                        });
                    }
                }
            }, 300);
        }
    }

    private void startProgressUpdater() { handler.removeCallbacks(progressUpdater); handler.post(progressUpdater); }
    private void stopProgressUpdater() { handler.removeCallbacks(progressUpdater); }
    private void updateProgress() {
        if (player == null) return;
        tvCurrentTime.setText(String.format("%s / %s", formatTime(player.getCurrentPosition()), formatTime(player.getDuration() < 0 ? 0 : player.getDuration())));
    }
    private String formatTime(long ms) { long sec = ms / 1000; return String.format("%02d:%02d", sec / 60, sec % 60); }
    private void fetchMusicList() {
        apiService.getMusicList().enqueue(new Callback<Map<String, List<String>>>() {
            @Override
            public void onResponse(Call<Map<String, List<String>>> call, Response<Map<String, List<String>>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    // Filter the playlists
                    Map<String, List<String>> rawData = response.body();
                    Map<String, List<String>> filteredData = new LinkedHashMap<>();
                    
                    // Priority 1: All Songs / 全部 / All
                    if (rawData.containsKey("所有歌曲")) filteredData.put("所有歌曲", rawData.get("所有歌曲"));
                    else if (rawData.containsKey("All Songs")) filteredData.put("All Songs", rawData.get("All Songs"));
                    else if (rawData.containsKey("全部")) filteredData.put("全部", rawData.get("全部"));
                    else if (rawData.containsKey("All")) filteredData.put("All", rawData.get("All"));
                    
                    // Priority 2: Favorites
                    if (rawData.containsKey("我的收藏")) filteredData.put("我的收藏", rawData.get("我的收藏"));
                    
                    // Others
                    for (Map.Entry<String, List<String>> entry : rawData.entrySet()) {
                        String key = entry.getKey();
                        if ("所有歌曲".equals(key) || "All Songs".equals(key) || "我的收藏".equals(key) || "全部".equals(key) || "All".equals(key)) continue;
                        if (!EXCLUDED_PLAYLISTS.contains(key)) {
                            filteredData.put(key, entry.getValue());
                        }
                    }
                    
                    musicData = rawData; // Keep full data for logic (like favorites check)
                    playlistAdapter.setData(filteredData); // UI only shows filtered
                    
                    // Update Adapter Favorites (Using full data)
                    if (musicData.containsKey("我的收藏")) {
                        songAdapter.setFavorites(musicData.get("我的收藏"));
                    } else {
                        songAdapter.setFavorites(null);
                    }
                    
                    // Note: musicData is used for "current song list" if we clicked a playlist
                    // But now that we hide "收藏", clicking it is impossible via UI.
                    // However, if we were playing from "收藏" previously, logic might be tricky.
                    // For now, assume user only clicks visible ones.


                    if (!filteredData.isEmpty()) {
                        String targetPlaylist = "";
                        SharedPreferences prefs = getSharedPreferences("XiaoMusicPrefs", 0);
                        String lastPlaylist = prefs.getString("last_playlist_name", "");
                        
                        // Try to restore last playlist
                        if (!lastPlaylist.isEmpty() && filteredData.containsKey(lastPlaylist)) {
                            targetPlaylist = lastPlaylist;
                        } else if (filteredData.containsKey("所有歌曲")) {
                            targetPlaylist = "所有歌曲"; 
                        } else if (filteredData.containsKey("All Songs")) {
                            targetPlaylist = "All Songs";
                        } else if (filteredData.containsKey("全部")) {
                            targetPlaylist = "全部";
                        } else if (filteredData.containsKey("All")) {
                            targetPlaylist = "All";
                        } else {
                            targetPlaylist = filteredData.keySet().iterator().next();
                        }
                        
                        // 如果当前查看的歌单仍然存在，更新它的内容 (解决删除歌曲后列表不更新的问题)
                        if (filteredData.containsKey(currentPlaylistName)) {
                            currentPlaylistSongs = filteredData.get(currentPlaylistName);
                            songAdapter.setSongs(currentPlaylistSongs);
                            tvSongCount.setText(currentPlaylistSongs.size() + " 首歌曲");
                            tvPlaylistTitle.setText(currentPlaylistName);
                        } else if (!targetPlaylist.equals(currentPlaylistName) || currentPlaylistSongs.isEmpty()) {
                            currentPlaylistName = targetPlaylist;
                            currentPlaylistSongs = filteredData.get(targetPlaylist);
                            songAdapter.setSongs(currentPlaylistSongs);
                            tvPlaylistTitle.setText(targetPlaylist);
                            tvSongCount.setText(currentPlaylistSongs.size() + " 首歌曲");
                        }
                        
                        // Try to scroll to "All Songs" or target in Playlist View?
                        // If we restored a playlist, we might want to highlight it in the PlaylistAdapter?
                        // Currently PlaylistAdapter logic is simple.
                        
                        // Request focus on adapter? 
                        // Existing logic already requests focus on first item (index 0). 
                        // If "Ah Songs" is index 0, it works.
                        // If we restored "Favorite", we might be viewing it but focus is on "All Songs".
                        // This is acceptable behavior (focus defaults to top).
                        
                        tryRestoreLastSong();
                        
                        // 初始焦点逻辑
                        final String finalTargetPlaylist = targetPlaylist;
                        rvPlaylists.postDelayed(() -> {
                            // 只有在首次启动加载数据后才执行初始聚焦
                            if (isInitialLoad && !isRefreshingAfterDelete) {
                                isInitialLoad = false;
                                int playlistIndex = 0;
                                List<String> names = new ArrayList<>(filteredData.keySet());
                                for (int i = 0; i < names.size(); i++) {
                                    if (names.get(i).equals(finalTargetPlaylist)) {
                                        playlistIndex = i;
                                        break;
                                    }
                                }
                                playlistAdapter.setSelection(playlistIndex);
                                View view = rvPlaylists.getLayoutManager().findViewByPosition(playlistIndex);
                                if (view != null) {
                                    view.requestFocus();
                                } else {
                                    rvPlaylists.scrollToPosition(playlistIndex);
                                    final int idx = playlistIndex;
                                    rvPlaylists.postDelayed(() -> {
                                        View v = rvPlaylists.getLayoutManager().findViewByPosition(idx);
                                        if (v != null) v.requestFocus();
                                    }, 150);
                                }
                                return;
                            }
                            // 刷新完成后的清理
                            if (isRefreshingAfterDelete && pendingDeleteFocusIndex >= 0) {
                                // 确认焦点是否停留在该位置，如果没有则补一刀聚焦
                                if (!rvSongs.hasFocus() || rvSongs.getFocusedChild() == null) {
                                    rvSongs.scrollToPosition(pendingDeleteFocusIndex);
                                    rvSongs.post(() -> {
                                        View v = rvSongs.getLayoutManager().findViewByPosition(pendingDeleteFocusIndex);
                                        if (v != null) {
                                            View db = v.findViewById(R.id.btnItemDelete);
                                            if (db != null) db.requestFocus();
                                            else v.requestFocus();
                                        }
                                    });
                                }
                            }
                            isFadingFocusDuringRefresh = false;
                            isRefreshingAfterDelete = false;
                            pendingDeleteFocusIndex = -1;
                        }, 200); // 缩短整体延迟，提高响应速度
                    }
                }
            }
            @Override public void onFailure(Call<Map<String, List<String>>> call, Throwable t) {}
        });
    }
    
    private void tryRestoreLastSong() {
        if (player == null || currentPlaylistSongs == null || currentPlaylistSongs.isEmpty()) return;
        if (player.getCurrentMediaItem() != null) return; // Already loaded/playing

        SharedPreferences prefs = getSharedPreferences("XiaoMusicPrefs", 0);
        String lastSong = prefs.getString("current_song_name", "");
        if (lastSong.isEmpty() || !currentPlaylistSongs.contains(lastSong)) return;

        int index = currentPlaylistSongs.indexOf(lastSong);
        if (index != -1) {
             playSongAtIndex(index);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            // First check: if focus is in the songs list, move it back to playlists
            if (rvSongs.hasFocus()) {
                rvPlaylists.requestFocus();
                return true;
            }
            
            // Second check: double press to exit
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastBackPressTime < 2000) {
                finish();
            } else {
                Toast.makeText(this, "再按一次退出程序", Toast.LENGTH_SHORT).show();
                lastBackPressTime = currentTime;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
