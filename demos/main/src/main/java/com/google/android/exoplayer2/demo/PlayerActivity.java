/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.demo;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer2.offline.DownloadRequest;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ads.AdsLoader;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.StyledPlayerControlView;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.DebugTextViewHelper;
import com.google.android.exoplayer2.util.ErrorMessageProvider;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** An activity that plays media using {@link SimpleExoPlayer}. */
public class PlayerActivity extends AppCompatActivity
    implements OnClickListener, StyledPlayerControlView.VisibilityListener {

  // Saved instance state keys.

  private static final String KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters";
  private static final String KEY_WINDOW = "window";
  private static final String KEY_POSITION = "position";
  private static final String KEY_AUTO_PLAY = "auto_play";

  protected StyledPlayerView playerView;
  protected LinearLayout debugRootView;
  protected TextView debugTextView;
  protected @Nullable SimpleExoPlayer player;

  private boolean isShowingTrackSelectionDialog;
  private Button selectTracksButton;
  private DataSource.Factory dataSourceFactory;
  private List<MediaItem> mediaItems;
  private DefaultTrackSelector trackSelector;
  private DefaultTrackSelector.Parameters trackSelectorParameters;
  private DebugTextViewHelper debugViewHelper;
  private TrackGroupArray lastSeenTrackGroupArray;
  private boolean startAutoPlay;
  private int startWindow;
  private long startPosition;

  // For ad playback only.

  private AdsLoader adsLoader;

  // Activity lifecycle.

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dataSourceFactory = DemoUtil.getDataSourceFactory(/* context= */ this);

    setContentView();
    debugRootView = findViewById(R.id.controls_root);
    debugTextView = findViewById(R.id.debug_text_view);
    selectTracksButton = findViewById(R.id.select_tracks_button);
    selectTracksButton.setOnClickListener(this);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    if (savedInstanceState != null) {
      trackSelectorParameters = savedInstanceState.getParcelable(KEY_TRACK_SELECTOR_PARAMETERS);
      startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
      startWindow = savedInstanceState.getInt(KEY_WINDOW);
      startPosition = savedInstanceState.getLong(KEY_POSITION);
    } else {
      DefaultTrackSelector.ParametersBuilder builder =
          new DefaultTrackSelector.ParametersBuilder(/* context= */ this);
      trackSelectorParameters = builder.build();
      clearStartPosition();
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    releasePlayer();
    releaseAdsLoader();
    clearStartPosition();
    setIntent(intent);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Util.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Util.SDK_INT <= 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Util.SDK_INT <= 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Util.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releaseAdsLoader();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    updateTrackSelectorParameters();
    updateStartPosition();
    outState.putParcelable(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters);
    outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
    outState.putInt(KEY_WINDOW, startWindow);
    outState.putLong(KEY_POSITION, startPosition);
  }

  // Activity input

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // See whether the player view wants to handle media or DPAD keys events.
    return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  // OnClickListener methods

  @Override
  public void onClick(View view) {
    if (view == selectTracksButton
        && !isShowingTrackSelectionDialog
        && TrackSelectionDialog.willHaveContent(trackSelector)) {
      isShowingTrackSelectionDialog = true;
      TrackSelectionDialog trackSelectionDialog =
          TrackSelectionDialog.createForTrackSelector(
              trackSelector,
              /* onDismissListener= */ dismissedDialog -> isShowingTrackSelectionDialog = false);
      trackSelectionDialog.show(getSupportFragmentManager(), /* tag= */ null);
    }
  }

  // PlayerControlView.VisibilityListener implementation

  @Override
  public void onVisibilityChange(int visibility) {
    debugRootView.setVisibility(visibility);
  }

  // Internal methods

  protected void setContentView() {
    setContentView(R.layout.player_activity);
  }

  /** @return Whether initialization was successful. */
  protected boolean initializePlayer() {
    if (player == null) {
      Intent intent = getIntent();

//      mediaItems = createMediaItems(intent);
      mediaItems = createMediaItems();
      if (mediaItems.isEmpty()) {
        return false;
      }

      boolean preferExtensionDecoders =
          intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false);
      RenderersFactory renderersFactory =
          DemoUtil.buildRenderersFactory(/* context= */ this, preferExtensionDecoders);
      MediaSourceFactory mediaSourceFactory =
          new DefaultMediaSourceFactory(dataSourceFactory)
              .setAdsLoaderProvider(this::getAdsLoader)
              .setAdViewProvider(playerView);

      trackSelector = new DefaultTrackSelector(/* context= */ this);
      trackSelector.setParameters(trackSelectorParameters);
      lastSeenTrackGroupArray = null;
      player =
          new SimpleExoPlayer.Builder(/* context= */ this, renderersFactory)
              .setMediaSourceFactory(mediaSourceFactory)
              .setTrackSelector(trackSelector)
              .build();
      player.addListener(new PlayerEventListener());
      player.addAnalyticsListener(new EventLogger(trackSelector));
      player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
      player.setPlayWhenReady(startAutoPlay);
      playerView.setPlayer(player);
      debugViewHelper = new DebugTextViewHelper(player, debugTextView);
      debugViewHelper.start();
    }
    boolean haveStartPosition = startWindow != C.INDEX_UNSET;
    if (haveStartPosition) {
      player.seekTo(startWindow, startPosition);
    }
    player.setMediaItems(mediaItems, /* resetPosition= */ !haveStartPosition);
    player.prepare();
    updateButtonVisibility();
    return true;
  }

  private List<MediaItem> createMediaItems() {
    /**
     *
     * The idea is to create a list of media items so that the playlist is compiled as follows :
     *
     *  Pre-roll Ad
     *  Pre-roll Ad
     *  Main Content (30 seconds each)
     *  Mid-roll Ad
     *  Mid-roll Ad
     *  Main Content
     *  Mid-roll Ad
     *  Mid-roll Ad
     *  Main Content
     *  Mid-roll Ad
     *  Mid-roll Ad
     *  Main Content
     *
     */
    List<MediaItem> mediaItems = new ArrayList<>();
    String mainContentUrl = "https://storage.googleapis.com/wvmedia/cenc/vp9/tears/tears.mpd";
    String licenseKeyUrl = "https://proxy.uat.widevine.com/proxy?provider=widevine_test";

    String ads = "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">\n"
        + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\" breakId=\"preroll\">\n"
        + "<vmap:AdSource id=\"preroll-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast4\">\n"
        + "<![CDATA[ https://tom.itv.com/itv/tserver/random=1955446721837844946/supertag=dflt,itv.2_4269_0018/area=itvplayer.video/vfunapod=true/hdevid=sailfish/hmod=pixel/hman=google/osver=10/dm=2c7488a8-23aa-4613-b7db-c526e1150311/pv=android.9.12.0-snapshot/zgid=f3c193b2-0cbd-47de-a084-96a2c89363c2/source=catchup/us=reg/arp=none/tparts=4/tdur=2994/player=x/os=android/pm=free/chanbrand=itv2/site=itv.mobile/size=videoident/adpos=iden1/breaknum=0/viewid=0.0333552b-c297-4fda-99e0-2ab430191be7/generic=0333552b-c297-4fda-99e0-2ab430191be7/plist=i2.s1.pm1.pr3.m8.po6/plfcid=43896/linked_fcid=43896/platform=mobile/temphint=i-0de2558893be7a623/default=defaultvideo ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\" breakId=\"preroll\">\n"
        + "<vmap:AdSource id=\"preroll-ad-2\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast4\">\n"
        + "<![CDATA[ https://tom.itv.com/itv/tserver/random=1955446721837844946/supertag=dflt,itv.2_4269_0018/area=itvplayer.video/vfunapod=true/hdevid=sailfish/hmod=pixel/hman=google/osver=10/dm=2c7488a8-23aa-4613-b7db-c526e1150311/pv=android.9.12.0-snapshot/zgid=f3c193b2-0cbd-47de-a084-96a2c89363c2/source=catchup/us=reg/arp=none/tparts=4/tdur=2994/player=x/os=android/pm=free/chanbrand=itv2/site=itv.mobile/size=video/adpos=2/breaknum=0/viewid=0.0333552b-c297-4fda-99e0-2ab430191be7/generic=0333552b-c297-4fda-99e0-2ab430191be7/plist=i2.s1.pm1.pr3.m8.po6/plfcid=43896/linked_fcid=43896/platform=mobile/temphint=i-0de2558893be7a623/default=defaultvideo ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:10.000\" breakType=\"linear\" breakId=\"midroll-1\">\n"
        + "<vmap:AdSource id=\"midroll-1-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://tom.itv.com/itv/tserver/random=1955446721837844946/supertag=dflt,itv.2_4269_0018/area=itvplayer.video/vfunapod=true/hdevid=sailfish/hmod=pixel/hman=google/osver=10/dm=2c7488a8-23aa-4613-b7db-c526e1150311/pv=android.9.12.0-snapshot/zgid=f3c193b2-0cbd-47de-a084-96a2c89363c2/source=catchup/us=reg/arp=none/tparts=4/tdur=2994/player=x/os=android/pm=free/chanbrand=itv2/site=itv.mobile/size=videosponsor/adpos=spon1/breaknum=1/viewid=spon1.1.0333552b-c297-4fda-99e0-2ab430191be7/generic=0333552b-c297-4fda-99e0-2ab430191be7/plist=i2.s1.pm1.pr3.m8.po6/plfcid=43896/linked_fcid=43896/platform=mobile/temphint=i-0de2558893be7a623/default=defaultvideo ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:10.000\" breakType=\"linear\" breakId=\"midroll-1\">\n"
        + "<vmap:AdSource id=\"midroll-1-ad-2\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://tom.itv.com/itv/tserver/random=1955446721837844946/supertag=dflt,itv.2_4269_0018/area=itvplayer.video/vfunapod=true/hdevid=sailfish/hmod=pixel/hman=google/osver=10/dm=2c7488a8-23aa-4613-b7db-c526e1150311/pv=android.9.12.0-snapshot/zgid=f3c193b2-0cbd-47de-a084-96a2c89363c2/source=catchup/us=reg/arp=none/tparts=4/tdur=2994/player=x/os=android/pm=free/chanbrand=itv2/site=itv.mobile/size=video/adpos=1/breaknum=1/viewid=1.0333552b-c297-4fda-99e0-2ab430191be7/generic=0333552b-c297-4fda-99e0-2ab430191be7/plist=i2.s1.pm1.pr3.m8.po6/plfcid=43896/linked_fcid=43896/platform=mobile/temphint=i-0de2558893be7a623/default=defaultvideo ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:20.000\" breakType=\"linear\" breakId=\"midroll-2\">\n"
        + "<vmap:AdSource id=\"midroll-2-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=20000&vad_type=linear&vpos=midroll&pod=3&mridx=2&rmridx=9&ppos=1&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:20.000\" breakType=\"linear\" breakId=\"midroll-2\">\n"
        + "<vmap:AdSource id=\"midroll-2-ad-2\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=20000&vad_type=linear&vpos=midroll&pod=3&mridx=2&rmridx=9&ppos=2&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:30.000\" breakType=\"linear\" breakId=\"midroll-3\">\n"
        + "<vmap:AdSource id=\"midroll-3-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=30000&vad_type=linear&vpos=midroll&pod=4&mridx=3&rmridx=8&ppos=1&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:30.000\" breakType=\"linear\" breakId=\"midroll-3\">\n"
        + "<vmap:AdSource id=\"midroll-3-ad-2\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=30000&vad_type=linear&vpos=midroll&pod=4&mridx=3&rmridx=8&ppos=2&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:40.000\" breakType=\"linear\" breakId=\"midroll-4\">\n"
        + "<vmap:AdSource id=\"midroll-4-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=40000&vad_type=linear&vpos=midroll&pod=5&mridx=4&rmridx=7&ppos=1&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"00:00:40.000\" breakType=\"linear\" breakId=\"midroll-4\">\n"
        + "<vmap:AdSource id=\"midroll-4-ad-2\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&cue=40000&vad_type=linear&vpos=midroll&pod=5&mridx=4&rmridx=7&ppos=2&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "<vmap:AdBreak timeOffset=\"end\" breakType=\"linear\" breakId=\"postroll\">\n"
        + "<vmap:AdSource id=\"postroll-ad-1\" allowMultipleAds=\"false\" followRedirects=\"true\">\n"
        + "<vmap:AdTagURI templateType=\"vast3\">\n"
        + "<![CDATA[ https://pubads.g.doubleclick.net/gampad/ads?slotname=/124319096/external/ad_rule_samples&sz=640x480&ciu_szs=300x250&cust_params=deployment%3Ddevsite%26sample_ar%3Dpremidpostlongpod&url=&unviewed_position_start=1&output=xml_vast3&impl=s&env=vp&gdfp_req=1&ad_rule=0&vad_type=linear&vpos=postroll&pod=12&ppos=1&lip=true&min_ad_duration=0&max_ad_duration=30000&vrid=7096&cmsid=496&video_doc_id=short_tencue&kfa=0&tfcd=0 ]]>\n"
        + "</vmap:AdTagURI>\n"
        + "</vmap:AdSource>\n"
        + "</vmap:AdBreak>\n"
        + "</vmap:VMAP>";

        MediaItem mediaItem = new MediaItem.Builder()
            .setUri(mainContentUrl)
            .setAdTagUri(Util.getDataUriForString("text/xml", ads))
            .setDrmPlayClearContentWithoutKey(true)
            .setDrmSessionForClearPeriods(true)
            .setDrmLicenseUri(licenseKeyUrl)
            .setDrmUuid(C.WIDEVINE_UUID)
            .build();

        mediaItems.add(mediaItem);

    return mediaItems;
  }

  private List<MediaItem> createMediaItems(Intent intent) {
    String action = intent.getAction();
    boolean actionIsListView = IntentUtil.ACTION_VIEW_LIST.equals(action);
    if (!actionIsListView && !IntentUtil.ACTION_VIEW.equals(action)) {
      showToast(getString(R.string.unexpected_intent_action, action));
      finish();
      return Collections.emptyList();
    }

    List<MediaItem> mediaItems =
        createMediaItems(intent, DemoUtil.getDownloadTracker(/* context= */ this));
    boolean hasAds = false;
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);

      if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
        showToast(R.string.error_cleartext_not_permitted);
        finish();
        return Collections.emptyList();
      }
      if (Util.maybeRequestReadExternalStoragePermission(/* activity= */ this, mediaItem)) {
        // The player will be reinitialized if the permission is granted.
        return Collections.emptyList();
      }

      MediaItem.DrmConfiguration drmConfiguration =
          checkNotNull(mediaItem.playbackProperties).drmConfiguration;
      if (drmConfiguration != null) {
        if (Util.SDK_INT < 18) {
          showToast(R.string.error_drm_unsupported_before_api_18);
          finish();
          return Collections.emptyList();
        } else if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.uuid)) {
          showToast(R.string.error_drm_unsupported_scheme);
          finish();
          return Collections.emptyList();
        }
      }
      hasAds |= mediaItem.playbackProperties.adsConfiguration != null;
    }
    if (!hasAds) {
      releaseAdsLoader();
    }
    return mediaItems;
  }

  private AdsLoader getAdsLoader(MediaItem.AdsConfiguration adsConfiguration) {
    // The ads loader is reused for multiple playbacks, so that ad playback can resume.
    if (adsLoader == null) {
      adsLoader = new ImaAdsLoader.Builder(/* context= */ this).build();
    }
    adsLoader.setPlayer(player);
    return adsLoader;
  }

  protected void releasePlayer() {
    if (player != null) {
      updateTrackSelectorParameters();
      updateStartPosition();
      debugViewHelper.stop();
      debugViewHelper = null;
      player.release();
      player = null;
      mediaItems = Collections.emptyList();
      trackSelector = null;
    }
    if (adsLoader != null) {
      adsLoader.setPlayer(null);
    }
  }

  private void releaseAdsLoader() {
    if (adsLoader != null) {
      adsLoader.release();
      adsLoader = null;
      playerView.getOverlayFrameLayout().removeAllViews();
    }
  }

  private void updateTrackSelectorParameters() {
    if (trackSelector != null) {
      trackSelectorParameters = trackSelector.getParameters();
    }
  }

  private void updateStartPosition() {
    if (player != null) {
      startAutoPlay = player.getPlayWhenReady();
      startWindow = player.getCurrentWindowIndex();
      startPosition = Math.max(0, player.getContentPosition());
    }
  }

  protected void clearStartPosition() {
    startAutoPlay = true;
    startWindow = C.INDEX_UNSET;
    startPosition = C.TIME_UNSET;
  }

  // User controls

  private void updateButtonVisibility() {
    selectTracksButton.setEnabled(
        player != null && TrackSelectionDialog.willHaveContent(trackSelector));
  }

  private void showControls() {
    debugRootView.setVisibility(View.VISIBLE);
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

  private static boolean isBehindLiveWindow(ExoPlaybackException e) {
    if (e.type != ExoPlaybackException.TYPE_SOURCE) {
      return false;
    }
    Throwable cause = e.getSourceException();
    while (cause != null) {
      if (cause instanceof BehindLiveWindowException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private class PlayerEventListener implements Player.EventListener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED) {
        showControls();
      }
      updateButtonVisibility();
    }

    @Override
    public void onPlayerError(@NonNull ExoPlaybackException e) {
      if (isBehindLiveWindow(e)) {
        player.seekToDefaultPosition();
        player.prepare();
      } else {
        updateButtonVisibility();
        showControls();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void onTracksChanged(
        @NonNull TrackGroupArray trackGroups, @NonNull TrackSelectionArray trackSelections) {
      updateButtonVisibility();
      if (trackGroups != lastSeenTrackGroupArray) {
        MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo != null) {
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_video);
          }
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_audio);
          }
        }
        lastSeenTrackGroupArray = trackGroups;
      }
    }
  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<ExoPlaybackException> {

    @Override
    @NonNull
    public Pair<Integer, String> getErrorMessage(@NonNull ExoPlaybackException e) {
      String errorString = getString(R.string.error_generic);
      if (e.type == ExoPlaybackException.TYPE_RENDERER) {
        Exception cause = e.getRendererException();
        if (cause instanceof DecoderInitializationException) {
          // Special case for decoder initialization failures.
          DecoderInitializationException decoderInitializationException =
              (DecoderInitializationException) cause;
          if (decoderInitializationException.codecInfo == null) {
            if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
              errorString = getString(R.string.error_querying_decoders);
            } else if (decoderInitializationException.secureDecoderRequired) {
              errorString =
                  getString(
                      R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
            } else {
              errorString =
                  getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
            }
          } else {
            errorString =
                getString(
                    R.string.error_instantiating_decoder,
                    decoderInitializationException.codecInfo.name);
          }
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private static List<MediaItem> createMediaItems(Intent intent, DownloadTracker downloadTracker) {
    List<MediaItem> mediaItems = new ArrayList<>();
    for (MediaItem item : IntentUtil.createMediaItemsFromIntent(intent)) {
      @Nullable
      DownloadRequest downloadRequest =
          downloadTracker.getDownloadRequest(checkNotNull(item.playbackProperties).uri);
      if (downloadRequest != null) {
        MediaItem.Builder builder = item.buildUpon();
        builder
            .setMediaId(downloadRequest.id)
            .setUri(downloadRequest.uri)
            .setCustomCacheKey(downloadRequest.customCacheKey)
            .setMimeType(downloadRequest.mimeType)
            .setStreamKeys(downloadRequest.streamKeys)
            .setDrmKeySetId(downloadRequest.keySetId)
            .setDrmLicenseRequestHeaders(getDrmRequestHeaders(item));

        mediaItems.add(builder.build());
      } else {
        mediaItems.add(item);
      }
    }
    return mediaItems;
  }

  @Nullable
  private static Map<String, String> getDrmRequestHeaders(MediaItem item) {
    MediaItem.DrmConfiguration drmConfiguration = item.playbackProperties.drmConfiguration;
    return drmConfiguration != null ? drmConfiguration.requestHeaders : null;
  }
}
