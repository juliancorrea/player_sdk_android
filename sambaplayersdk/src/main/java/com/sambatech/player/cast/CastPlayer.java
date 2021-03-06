/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sambatech.player.cast;

import android.content.Context;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.sambatech.player.event.SambaEvent;
import com.sambatech.player.event.SambaEventBus;
import com.sambatech.player.event.SambaPlayerListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@link Player} implementation that communicates with a Cast receiver app.
 *
 * <p>The behavior of this class depends on the underlying Cast session, which is obtained from the
 * Cast context passed to {@link #CastPlayer}. To keep track of the session,
 * {@link #isCastSessionAvailable()} can be queried and {@link SessionAvailabilityListener} can be
 * implemented and attached to the player.</p>
 *
 * <p> If no session is available, the player state will remain unchanged and calls to methods that
 * alter it will be ignored. Querying the player state is possible even when no session is
 * available, in which case, the last observed receiver app state is reported.</p>
 *
 * <p>Methods should be called on the application's main thread.</p>
 */
public final class CastPlayer implements Player {

  private final Context context;

  /**
   * Listener of changes in the cast session availability.
   */
  public interface SessionAvailabilityListener {

    /**
     * Called when a cast session becomes available to the player.
     */
    void onCastSessionAvailable();

    /**
     * Called when the cast session becomes unavailable.
     */
    void onCastSessionUnavailable();

  }

  private static final String TAG = "CastPlayer";

  private static final int RENDERER_COUNT = 3;
  private static final int RENDERER_INDEX_VIDEO = 0;
  private static final int RENDERER_INDEX_AUDIO = 1;
  private static final int RENDERER_INDEX_TEXT = 2;
  private static final long PROGRESS_REPORT_PERIOD_MS = 1000;
  private static final TrackSelectionArray EMPTY_TRACK_SELECTION_ARRAY =
      new TrackSelectionArray(null, null, null);
  private static final long[] EMPTY_TRACK_ID_ARRAY = new long[0];

  private final CastContext castContext;
  // TODO: Allow custom implementations of CastTimelineTracker.
  private final CastTimelineTracker timelineTracker;
  private final Timeline.Window window;
  private final Timeline.Period period;

  private RemoteMediaClient remoteMediaClient;

  // Result callbacks.
  private final StatusListener statusListener;
  private final SeekResultCallback seekResultCallback;

  // Listeners.
  private final CopyOnWriteArraySet<EventListener> listeners;
  private SessionAvailabilityListener sessionAvailabilityListener;

  // Internal state.
  private CastTimeline currentTimeline;
  private TrackGroupArray currentTrackGroups;
  private TrackSelectionArray currentTrackSelection;
  private int playbackState;
  private int repeatMode;
  private int currentWindowIndex = -1;
  private boolean playWhenReady = false;
  private static long previousReportedPositionMs = 0;
  private long lastReportedPositionMs = 0;
  private long lastReportedDurationMs;
  private int pendingSeekCount;
  private int pendingSeekWindowIndex;
  private long pendingSeekPositionMs;
  private boolean waitingForInitialTimeline;
  private boolean isLive;

  private SambaCast sambaCast;

  private List<MediaQueueItem> mediaQueueItems;



  Cast.MessageReceivedCallback messageReceived = new Cast.MessageReceivedCallback() {
    @Override
    public void onMessageReceived(CastDevice castDevice, String namespace, String message)  {
      Log.i("Message Received", castDevice.toString() + namespace + message);

      try {
        JSONObject jsonObject = new JSONObject(message);

        if (jsonObject.has("progress") && jsonObject.has("duration")) {
          previousReportedPositionMs = lastReportedPositionMs;
          lastReportedPositionMs = (long) (jsonObject.getDouble("progress") * 1000);
          lastReportedDurationMs = (long) (jsonObject.getDouble("duration") * 1000);
          updateInternalState();
          SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.PROGRESS, (float)lastReportedPositionMs, (float) lastReportedDurationMs));

         // eventListener.onPlayerStateChanged(true, Player.STATE_READY );

        }
        else if (jsonObject.has("type")) {
          jsonObject = new JSONObject(message);
          String type = jsonObject.getString("type");

          if (type.equalsIgnoreCase("finish")) {
            loadItem(mediaQueueItems.get(0), 0);
            seekTo(0);
            playWhenReady = false;
            SambaCast.setCurrentStatus(context, playWhenReady);
            updateInternalState();
            SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.CAST_FINISH));
          }
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
  };

  /**
   * @param context
   * @param sambaCast The context from which the cast session is obtained.
   */
  public CastPlayer(Context context, SambaCast sambaCast) {
    this.context = context;
    this.sambaCast = sambaCast;
    this.castContext = sambaCast.getCastContext();
    timelineTracker = new CastTimelineTracker();
    window = new Timeline.Window();
    period = new Timeline.Period();
    statusListener = new StatusListener();
    seekResultCallback = new SeekResultCallback();
    listeners = new CopyOnWriteArraySet<>();

    SessionManager sessionManager = castContext.getSessionManager();
    sessionManager.addSessionManagerListener(statusListener, CastSession.class);
    CastSession session = sessionManager.getCurrentCastSession();
    remoteMediaClient = session != null ? session.getRemoteMediaClient() : null;

    playbackState = STATE_READY;
    repeatMode = REPEAT_MODE_OFF;
    currentTimeline = CastTimeline.EMPTY_CAST_TIMELINE;
    currentTrackGroups = TrackGroupArray.EMPTY;
    currentTrackSelection = EMPTY_TRACK_SELECTION_ARRAY;
    pendingSeekWindowIndex = C.INDEX_UNSET;
    pendingSeekPositionMs = C.TIME_UNSET;
    updateInternalState();
  }


  public void setMessageListener(CastSession castSession) {
    try {
      castSession.setMessageReceivedCallbacks(CastOptionsProvider.CUSTOM_NAMESPACE,messageReceived);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // Media Queue manipulation methods.

  /**
   * Loads a single item media queue. If no session is available, does nothing.
   *
   * @param item The item to load.
   * @param positionMs The position at which the playback should start in milliseconds relative to
   *     the start of the item at {@code startIndex}. If {@link C#TIME_UNSET} is passed, playback
   *     starts at position 0.
   * @return The Cast {@code PendingResult}, or null if no session is available.
   */
  public PendingResult<MediaChannelResult> loadItem(MediaQueueItem item, long positionMs) {
    return loadItems(new MediaQueueItem[] {item}, 0, positionMs, REPEAT_MODE_OFF);
  }

  /**
   * Loads a media queue. If no session is available, does nothing.
   *
   * @param items The items to load.
   * @param startIndex The index of the item at which playback should start.
   * @param positionMs The position at which the playback should start in milliseconds relative to
   *     the start of the item at {@code startIndex}. If {@link C#TIME_UNSET} is passed, playback
   *     starts at position 0.
   * @param repeatMode The repeat mode for the created media queue.
   * @return The Cast {@code PendingResult}, or null if no session is available.
   */
  public PendingResult<MediaChannelResult> loadItems(MediaQueueItem[] items, int startIndex,
      long positionMs, @RepeatMode int repeatMode) {
    if (remoteMediaClient != null) {
      positionMs = positionMs != C.TIME_UNSET ? positionMs : 0;
      waitingForInitialTimeline = true;
      mediaQueueItems = new ArrayList<>();
      Collections.addAll(mediaQueueItems, items);
      return remoteMediaClient.queueLoad(items, startIndex, getCastRepeatMode(repeatMode),
          positionMs, null);
    }
    return null;
  }

    public void resumeItems(MediaQueueItem[] items, int startIndex, @RepeatMode int repeatMode) {
        if (remoteMediaClient != null) {
            waitingForInitialTimeline = true;
            mediaQueueItems = new ArrayList<>();
            Collections.addAll(mediaQueueItems, items);
        }
    }

  /**
   * Appends a sequence of items to the media queue. If no media queue exists, does nothing.
   *
   * @param items The items to append.
   * @return The Cast {@code PendingResult}, or null if no media queue exists.
   */
  public PendingResult<MediaChannelResult> addItems(MediaQueueItem... items) {
    return addItems(MediaQueueItem.INVALID_ITEM_ID, items);
  }

  /**
   * Inserts a sequence of items into the media queue. If no media queue or period with id
   * {@code periodId} exist, does nothing.
   *
   * @param periodId The id of the period ({@see #getCurrentTimeline}) that corresponds to the item
   *     that will follow immediately after the inserted items.
   * @param items The items to insert.
   * @return The Cast {@code PendingResult}, or null if no media queue or no period with id
   *     {@code periodId} exist.
   */
  public PendingResult<MediaChannelResult> addItems(int periodId, MediaQueueItem... items) {
    if (getMediaStatus() != null && (periodId == MediaQueueItem.INVALID_ITEM_ID
        || currentTimeline.getIndexOfPeriod(periodId) != C.INDEX_UNSET)) {
      mediaQueueItems = new ArrayList<>();
      Collections.addAll(mediaQueueItems, items);
      return remoteMediaClient.queueInsertItems(items, periodId, null);
    }
    return null;
  }

  /**
   * Removes an item from the media queue. If no media queue or period with id {@code periodId}
   * exist, does nothing.
   *
   * @param periodId The id of the period ({@see #getCurrentTimeline}) that corresponds to the item
   *     to remove.
   * @return The Cast {@code PendingResult}, or null if no media queue or no period with id
   *     {@code periodId} exist.
   */
  public PendingResult<MediaChannelResult> removeItem(int periodId) {
    if (getMediaStatus() != null && currentTimeline.getIndexOfPeriod(periodId) != C.INDEX_UNSET) {
      return remoteMediaClient.queueRemoveItem(periodId, null);
    }
    return null;
  }

  /**
   * Moves an existing item within the media queue. If no media queue or period with id
   * {@code periodId} exist, does nothing.
   *
   * @param periodId The id of the period ({@see #getCurrentTimeline}) that corresponds to the item
   *     to move.
   * @param newIndex The target index of the item in the media queue. Must be in the range
   *     0 &lt;= index &lt; {@link Timeline#getPeriodCount()}, as provided by
   *     {@link #getCurrentTimeline()}.
   * @return The Cast {@code PendingResult}, or null if no media queue or no period with id
   *     {@code periodId} exist.
   */
  public PendingResult<MediaChannelResult> moveItem(int periodId, int newIndex) {
    Assertions.checkArgument(newIndex >= 0 && newIndex < currentTimeline.getPeriodCount());
    if (getMediaStatus() != null && currentTimeline.getIndexOfPeriod(periodId) != C.INDEX_UNSET) {
      return remoteMediaClient.queueMoveItemToNewIndex(periodId, newIndex, null);
    }
    return null;
  }

  /**
   * Returns the item that corresponds to the period with the given id, or null if no media queue or
   * period with id {@code periodId} exist.
   *
   * @param periodId The id of the period ({@see #getCurrentTimeline}) that corresponds to the item
   *     to get.
   * @return The item that corresponds to the period with the given id, or null if no media queue or
   *     period with id {@code periodId} exist.
   */
  public MediaQueueItem getItem(int periodId) {
    MediaStatus mediaStatus = getMediaStatus();
    return mediaStatus != null && currentTimeline.getIndexOfPeriod(periodId) != C.INDEX_UNSET
        ? mediaStatus.getItemById(periodId) : null;
  }

  // CastSession methods.

  /**
   * Returns whether a cast session is available.
   */
  public boolean isCastSessionAvailable() {
    return remoteMediaClient != null;
  }

  /**
   * Sets a listener for updates on the cast session availability.
   *
   * @param listener The {@link SessionAvailabilityListener}.
   */
  public void setSessionAvailabilityListener(SessionAvailabilityListener listener) {
    sessionAvailabilityListener = listener;
  }

  // Player implementation.


  @Nullable
  @Override
  public AudioComponent getAudioComponent() {
    return null;
  }

  @Nullable
  @Override
  public VideoComponent getVideoComponent() {
    return null;
  }

  @Nullable
  @Override
  public TextComponent getTextComponent() {
    return null;
  }

  @Override
  public Looper getApplicationLooper() {
    return Looper.getMainLooper();
  }

  @Override
  public void addListener(EventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(EventListener listener) {
    listeners.remove(listener);
  }

  @Override
  public int getPlaybackState() {
    return playbackState;
  }

  @Nullable
  @Override
  public ExoPlaybackException getPlaybackError() {
    return null;
  }

  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    this.playWhenReady = playWhenReady;
    SambaCast.setCurrentStatus(context, this.playWhenReady);
    if (sambaCast == null) {
      return;
    }
    updateInternalState();

    if (playWhenReady) {
      sambaCast.playCast();
      SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.CAST_PLAY));
    } else {
      sambaCast.pauseCast();
      SambaEventBus.post(new SambaEvent(SambaPlayerListener.EventType.CAST_PAUSE));
    }
  }

  public void syncInternalState() {
      this.playWhenReady = SambaCast.getCurrentStatus(context);
      updateInternalState();
  }

  @Override
  public boolean getPlayWhenReady() {
    return playWhenReady;
  }

  @Override
  public void seekToDefaultPosition() {
    seekTo(0);
  }

  @Override
  public void seekToDefaultPosition(int windowIndex) {
    seekTo(windowIndex, 0);
  }

  @Override
  public void seekTo(long positionMs) {
    seekTo(getCurrentWindowIndex(), positionMs);
  }

  @Override
  public void seekTo(int windowIndex, long positionMs) {
    positionMs = positionMs != C.TIME_UNSET ? positionMs : 0;

      sambaCast.seekTo( (int) positionMs);
      lastReportedPositionMs = positionMs;
      pendingSeekCount++;
      pendingSeekWindowIndex = windowIndex;
      pendingSeekPositionMs = positionMs;
      for (EventListener listener : listeners) {
        listener.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
      }
  }

  @Override
  public boolean hasPrevious() {
    return false;
  }

  @Override
  public void previous() {

  }

  @Override
  public boolean hasNext() {
    return false;
  }

  @Override
  public void next() {

  }

  @Override
  public void setPlaybackParameters(@Nullable PlaybackParameters playbackParameters) {
    // Unsupported by the RemoteMediaClient API. Do nothing.
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return PlaybackParameters.DEFAULT;
  }

  @Override
  public void stop() {
    playbackState = STATE_IDLE;
    sambaCast.stopCasting();
  }

  @Override
  public void stop(boolean reset) {

  }

  @Override
  public void release() {
    SessionManager sessionManager = castContext.getSessionManager();
    sessionManager.removeSessionManagerListener(statusListener, CastSession.class);
    sessionManager.endCurrentSession(false);
  }

  @Override
  public int getRendererCount() {
    // We assume there are three renderers: video, audio, and text.
    return RENDERER_COUNT;
  }

  @Override
  public int getRendererType(int index) {
    switch (index) {
      case RENDERER_INDEX_VIDEO:
        return C.TRACK_TYPE_VIDEO;
      case RENDERER_INDEX_AUDIO:
        return C.TRACK_TYPE_AUDIO;
      case RENDERER_INDEX_TEXT:
        return C.TRACK_TYPE_TEXT;
      default:
        throw new IndexOutOfBoundsException();
    }
  }

  @Override
  public void setRepeatMode(@RepeatMode int repeatMode) {

  }

  @Override
  @RepeatMode public int getRepeatMode() {
    return repeatMode;
  }

  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    // TODO: Support shuffle mode.
  }

  @Override
  public boolean getShuffleModeEnabled() {
    // TODO: Support shuffle mode.
    return false;
  }

  @Override
  public TrackSelectionArray getCurrentTrackSelections() {
    return currentTrackSelection;
  }

  @Override
  public TrackGroupArray getCurrentTrackGroups() {
    return currentTrackGroups;
  }

  @Override
  public Timeline getCurrentTimeline() {
    return currentTimeline;
  }

  @Override
  @Nullable public Object getCurrentManifest() {
    return null;
  }

  @Override
  public int getCurrentPeriodIndex() {
    return getCurrentWindowIndex();
  }

  @Override
  public int getCurrentWindowIndex() {
    return pendingSeekWindowIndex != C.INDEX_UNSET ? pendingSeekWindowIndex : currentWindowIndex;
  }

  @Override
  public int getNextWindowIndex() {
    return currentTimeline.isEmpty() ? C.INDEX_UNSET
        : currentTimeline.getNextWindowIndex(getCurrentWindowIndex(), repeatMode, false);
  }

  @Override
  public int getPreviousWindowIndex() {
    return currentTimeline.isEmpty() ? C.INDEX_UNSET
        : currentTimeline.getPreviousWindowIndex(getCurrentWindowIndex(), repeatMode, false);
  }

  @Nullable
  @Override
  public Object getCurrentTag() {
    return null;
  }

  // TODO: Fill the cast timeline information with ProgressListener's duration updates.
  // See [Internal: b/65152553].
  @Override
  public long getDuration() {
    return lastReportedDurationMs;
  }

  @Override
  public long getCurrentPosition() {
    return lastReportedPositionMs;
  }

  @Override
  public long getBufferedPosition() {
    return getCurrentPosition();
  }

  @Override
  public int getBufferedPercentage() {
    return 100;
  }

  @Override
  public long getTotalBufferedDuration() {
    return 0;
  }

  @Override
  public boolean isCurrentWindowDynamic() {
    return true;
  }

  @Override
  public boolean isCurrentWindowSeekable() {
    return true;
  }

  @Override
  public boolean isPlayingAd() {
    return false;
  }

  @Override
  public int getCurrentAdGroupIndex() {
    return C.INDEX_UNSET;
  }

  @Override
  public int getCurrentAdIndexInAdGroup() {
    return C.INDEX_UNSET;
  }

  @Override
  public long getContentDuration() {
    return 0;
  }

  @Override
  public boolean isLoading() {
    return false;
  }

  @Override
  public long getContentPosition() {
    return getCurrentPosition();
  }

  @Override
  public long getContentBufferedPosition() {
    return 0;
  }

  // Internal methods.

  public void updateInternalState() {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return;
    }


    for (EventListener listener : listeners) {
        listener.onPlayerStateChanged(this.playWhenReady, this.playbackState);
    }

    @RepeatMode int repeatMode = fetchRepeatMode(remoteMediaClient);
    for (EventListener listener : listeners) {
      listener.onRepeatModeChanged(repeatMode);
    }
    int currentWindowIndex = fetchCurrentWindowIndex(getMediaStatus());
    if (this.currentWindowIndex != currentWindowIndex && pendingSeekCount == 0) {
      this.currentWindowIndex = currentWindowIndex;
      for (EventListener listener : listeners) {
        listener.onPositionDiscontinuity(DISCONTINUITY_REASON_PERIOD_TRANSITION);
      }
    }
    if (updateTracksAndSelections()) {
      for (EventListener listener : listeners) {
        listener.onTracksChanged(currentTrackGroups, currentTrackSelection);
      }
    }
    maybeUpdateTimelineAndNotify();
  }

  private void maybeUpdateTimelineAndNotify() {
    if (updateTimeline()) {
      waitingForInitialTimeline = false;
      for (EventListener listener : listeners) {
        listener.onTimelineChanged(currentTimeline, null, TIMELINE_CHANGE_REASON_DYNAMIC);
      }
    }
  }

  /**
   * Updates the current timeline and returns whether it has changed.
   */
  private boolean updateTimeline() {
    CastTimeline oldTimeline = currentTimeline;
    if(mediaQueueItems != null && mediaQueueItems.size() > 0)
      currentTimeline = timelineTracker.getCastTimeline(mediaQueueItems, mediaQueueItems.get(0).getMedia().getContentId(), lastReportedDurationMs * 1000);
    return !oldTimeline.equals(currentTimeline);
  }

  /**
   * Updates the internal tracks and selection and returns whether they have changed.
   */
  private boolean updateTracksAndSelections() {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return false;
    }

    MediaStatus mediaStatus = getMediaStatus();
    MediaInfo mediaInfo = mediaStatus != null ? mediaStatus.getMediaInfo() : null;
    List<MediaTrack> castMediaTracks = mediaInfo != null ? mediaInfo.getMediaTracks() : null;
    if (castMediaTracks == null || castMediaTracks.isEmpty()) {
      boolean hasChanged = !currentTrackGroups.isEmpty();
      currentTrackGroups = TrackGroupArray.EMPTY;
      currentTrackSelection = EMPTY_TRACK_SELECTION_ARRAY;
      return hasChanged;
    }
    long[] activeTrackIds = mediaStatus.getActiveTrackIds();
    if (activeTrackIds == null) {
      activeTrackIds = EMPTY_TRACK_ID_ARRAY;
    }

    TrackGroup[] trackGroups = new TrackGroup[castMediaTracks.size()];
    TrackSelection[] trackSelections = new TrackSelection[RENDERER_COUNT];
    for (int i = 0; i < castMediaTracks.size(); i++) {
      MediaTrack mediaTrack = castMediaTracks.get(i);
      trackGroups[i] = new TrackGroup(CastUtils.mediaTrackToFormat(mediaTrack));

      long id = mediaTrack.getId();
      int trackType = MimeTypes.getTrackType(mediaTrack.getContentType());
      int rendererIndex = getRendererIndexForTrackType(trackType);
      if (isTrackActive(id, activeTrackIds) && rendererIndex != C.INDEX_UNSET
          && trackSelections[rendererIndex] == null) {
        trackSelections[rendererIndex] = new FixedTrackSelection(trackGroups[i], 0);
      }
    }
    TrackGroupArray newTrackGroups = new TrackGroupArray(trackGroups);
    TrackSelectionArray newTrackSelections = new TrackSelectionArray(trackSelections);

    if (!newTrackGroups.equals(currentTrackGroups)
        || !newTrackSelections.equals(currentTrackSelection)) {
      currentTrackSelection = new TrackSelectionArray(trackSelections);
      currentTrackGroups = new TrackGroupArray(trackGroups);
      return true;
    }
    return false;
  }

  public void setRemoteMediaClient(@Nullable RemoteMediaClient remoteMediaClient) {
    if (this.remoteMediaClient == remoteMediaClient) {
      // Do nothing.
      return;
    }
    if (this.remoteMediaClient != null) {
      this.remoteMediaClient.removeListener(statusListener);
      this.remoteMediaClient.removeProgressListener(statusListener);
    }
    this.remoteMediaClient = remoteMediaClient;
    if (remoteMediaClient != null) {
      if (sessionAvailabilityListener != null) {
        sessionAvailabilityListener.onCastSessionAvailable();
      }
      remoteMediaClient.addListener(statusListener);
      remoteMediaClient.addProgressListener(statusListener, PROGRESS_REPORT_PERIOD_MS);
      updateInternalState();
    } else {
      if (sessionAvailabilityListener != null) {
        sessionAvailabilityListener.onCastSessionUnavailable();
      }
    }
  }

  private @Nullable MediaStatus getMediaStatus() {
    return remoteMediaClient != null ? remoteMediaClient.getMediaStatus() : null;
  }

  /**
   * Retrieves the playback state from {@code remoteMediaClient} and maps it into a {@link Player}
   * state
   */
  private static int fetchPlaybackState(RemoteMediaClient remoteMediaClient) {
    return previousReportedPositionMs == 0 ? STATE_IDLE: STATE_READY;
  }

  /**
   * Retrieves the repeat mode from {@code remoteMediaClient} and maps it into a
   * {@link Player.RepeatMode}.
   */
  @RepeatMode
  private static int fetchRepeatMode(RemoteMediaClient remoteMediaClient) {
    return REPEAT_MODE_OFF;
  }

  /**
   * Retrieves the current item index from {@code mediaStatus} and maps it into a window index. If
   * there is no media session, returns 0.
   */
  private static int fetchCurrentWindowIndex(@Nullable MediaStatus mediaStatus) {
    Integer currentItemId = mediaStatus != null
        ? mediaStatus.getIndexById(mediaStatus.getCurrentItemId()) : null;
    return currentItemId != null ? currentItemId : 0;
  }

  private static boolean isTrackActive(long id, long[] activeTrackIds) {
    for (long activeTrackId : activeTrackIds) {
      if (activeTrackId == id) {
        return true;
      }
    }
    return false;
  }

  private static int getRendererIndexForTrackType(int trackType) {
    return trackType == C.TRACK_TYPE_VIDEO
        ? RENDERER_INDEX_VIDEO
        : trackType == C.TRACK_TYPE_AUDIO
            ? RENDERER_INDEX_AUDIO
            : trackType == C.TRACK_TYPE_TEXT ? RENDERER_INDEX_TEXT : C.INDEX_UNSET;
  }

  private static int getCastRepeatMode(@RepeatMode int repeatMode) {
    switch (repeatMode) {
      case REPEAT_MODE_ONE:
        return MediaStatus.REPEAT_MODE_REPEAT_SINGLE;
      case REPEAT_MODE_ALL:
        return MediaStatus.REPEAT_MODE_REPEAT_ALL;
      case REPEAT_MODE_OFF:
        return MediaStatus.REPEAT_MODE_REPEAT_OFF;
      default:
        throw new IllegalArgumentException();
    }
  }

  private final class StatusListener implements RemoteMediaClient.Listener,
      SessionManagerListener<CastSession>, RemoteMediaClient.ProgressListener {

    // RemoteMediaClient.ProgressListener implementation.

    @Override
    public void onProgressUpdated(long progressMs, long unusedDurationMs) {
      lastReportedPositionMs = progressMs;
    }

    // RemoteMediaClient.Listener implementation.

    @Override
    public void onStatusUpdated() {
      updateInternalState();
    }

    @Override
    public void onMetadataUpdated() {}

    @Override
    public void onQueueStatusUpdated() {
      maybeUpdateTimelineAndNotify();
    }

    @Override
    public void onPreloadStatusUpdated() {}

    @Override
    public void onSendingRemoteMediaRequest() {}

    @Override
    public void onAdBreakStatusUpdated() {}


    // SessionManagerListener implementation.

    @Override
    public void onSessionStarted(CastSession castSession, String s) {
      setRemoteMediaClient(castSession.getRemoteMediaClient());
    }

    @Override
    public void onSessionResumed(CastSession castSession, boolean b) {
      setRemoteMediaClient(castSession.getRemoteMediaClient());
    }

    @Override
    public void onSessionEnded(CastSession castSession, int i) {
      setRemoteMediaClient(null);
    }

    @Override
    public void onSessionSuspended(CastSession castSession, int i) {
      setRemoteMediaClient(null);
    }

    @Override
    public void onSessionResumeFailed(CastSession castSession, int statusCode) {
      Log.e(TAG, "Session resume failed. Error code " + statusCode + ": "
          + CastUtils.getLogString(statusCode));
    }

    @Override
    public void onSessionStarting(CastSession castSession) {
      // Do nothing.
    }

    @Override
    public void onSessionStartFailed(CastSession castSession, int statusCode) {
      Log.e(TAG, "Session start failed. Error code " + statusCode + ": "
          + CastUtils.getLogString(statusCode));
    }

    @Override
    public void onSessionEnding(CastSession castSession) {
      // Do nothing.
    }

    @Override
    public void onSessionResuming(CastSession castSession, String s) {
      // Do nothing.
    }

  }

  // Result callbacks hooks.

  private final class SeekResultCallback implements ResultCallback<MediaChannelResult> {

    @Override
    public void onResult(@NonNull MediaChannelResult result) {
      int statusCode = result.getStatus().getStatusCode();
      if (statusCode != CastStatusCodes.SUCCESS && statusCode != CastStatusCodes.REPLACED) {
        Log.e(TAG, "Seek failed. Error code " + statusCode + ": "
            + CastUtils.getLogString(statusCode));
      }
      if (--pendingSeekCount == 0) {
        pendingSeekWindowIndex = C.INDEX_UNSET;
        pendingSeekPositionMs = C.TIME_UNSET;
        for (EventListener listener : listeners) {
          listener.onSeekProcessed();
        }
      }
    }
  }

  public void sendSubtitle(String lenguage){
    sambaCast.changeSubtitle(lenguage);
  }

  public void setIsLive(boolean isLive) {
    this.isLive = isLive;
  }

}
