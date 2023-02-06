/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;

import android.graphics.Bitmap;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.decoder.DecoderInputBuffer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AssetLoader} that is composed of a sequence of non-overlapping {@linkplain AssetLoader
 * asset loaders}.
 */
/* package */ final class CompositeAssetLoader implements AssetLoader, AssetLoader.Listener {

  private final List<EditedMediaItem> editedMediaItems;
  private final AtomicInteger currentMediaItemIndex;
  private final AssetLoader.Factory assetLoaderFactory;
  private final HandlerWrapper handler;
  private final Listener compositeAssetLoaderListener;
  private final Map<Integer, SampleConsumer> sampleConsumersByTrackType;
  private final Map<Integer, OnMediaItemChangedListener> mediaItemChangedListenersByTrackType;
  private final ImmutableList.Builder<TransformationResult.ProcessedInput> processedInputsBuilder;
  private final AtomicLong totalDurationUs;
  private final AtomicInteger nonEndedTracks;

  private AssetLoader currentAssetLoader;
  private int processedInputsSize;

  private volatile long currentDurationUs;

  public CompositeAssetLoader(
      EditedMediaItemSequence sequence,
      AssetLoader.Factory assetLoaderFactory,
      Looper looper,
      Listener listener,
      Clock clock) {
    this.editedMediaItems = sequence.editedMediaItems;
    this.assetLoaderFactory = assetLoaderFactory;
    compositeAssetLoaderListener = listener;
    currentMediaItemIndex = new AtomicInteger();
    handler = clock.createHandler(looper, /* callback= */ null);
    sampleConsumersByTrackType = new HashMap<>();
    mediaItemChangedListenersByTrackType = new HashMap<>();
    processedInputsBuilder = new ImmutableList.Builder<>();
    totalDurationUs = new AtomicLong();
    nonEndedTracks = new AtomicInteger();
    // It's safe to use "this" because we don't start the AssetLoader before exiting the
    // constructor.
    @SuppressWarnings("nullness:argument.type.incompatible")
    AssetLoader currentAssetLoader =
        assetLoaderFactory.createAssetLoader(editedMediaItems.get(0), looper, /* listener= */ this);
    this.currentAssetLoader = currentAssetLoader;
  }

  @Override
  public void start() {
    currentAssetLoader.start();
  }

  @Override
  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    int progressState = currentAssetLoader.getProgress(progressHolder);
    int mediaItemCount = editedMediaItems.size();
    if (mediaItemCount == 1 || progressState == PROGRESS_STATE_NOT_STARTED) {
      return progressState;
    }

    int progress = currentMediaItemIndex.get() * 100 / mediaItemCount;
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      progress += progressHolder.progress / mediaItemCount;
    }
    progressHolder.progress = progress;
    return PROGRESS_STATE_AVAILABLE;
  }

  @Override
  public ImmutableMap<Integer, String> getDecoderNames() {
    return currentAssetLoader.getDecoderNames();
  }

  /**
   * Returns the partially or entirely {@linkplain TransformationResult.ProcessedInput processed
   * inputs}.
   */
  public ImmutableList<TransformationResult.ProcessedInput> getProcessedInputs() {
    addCurrentProcessedInput();
    return processedInputsBuilder.build();
  }

  @Override
  public void release() {
    currentAssetLoader.release();
  }

  /**
   * Adds an {@link OnMediaItemChangedListener} for the given track type.
   *
   * <p>There can't be more than one {@link OnMediaItemChangedListener} for the same track type.
   *
   * @param onMediaItemChangedListener The {@link OnMediaItemChangedListener}.
   * @param trackType The {@link C.TrackType} for which to listen to {@link MediaItem} change
   *     events.
   */
  public void addOnMediaItemChangedListener(
      OnMediaItemChangedListener onMediaItemChangedListener, @C.TrackType int trackType) {
    checkArgument(mediaItemChangedListenersByTrackType.get(trackType) == null);
    mediaItemChangedListenersByTrackType.put(trackType, onMediaItemChangedListener);
  }

  // AssetLoader.Listener implementation.

  @Override
  public void onDurationUs(long durationUs) {
    currentDurationUs = durationUs;
    if (editedMediaItems.size() == 1) {
      compositeAssetLoaderListener.onDurationUs(durationUs);
    } else if (currentMediaItemIndex.get() == 0) {
      // TODO(b/252537210): support silent audio track for sequence of AssetLoaders (silent audio
      //  track is the only usage of the duration).
      compositeAssetLoaderListener.onDurationUs(C.TIME_UNSET);
    }
  }

  @Override
  public void onTrackCount(int trackCount) {
    nonEndedTracks.set(trackCount);
    // TODO(b/252537210): support varying track count and track types between AssetLoaders.
    if (currentMediaItemIndex.get() == 0) {
      compositeAssetLoaderListener.onTrackCount(trackCount);
    } else if (trackCount != sampleConsumersByTrackType.size()) {
      throw new IllegalStateException(
          "The number of tracks is not allowed to change between MediaItems.");
    }
  }

  @Override
  public SampleConsumer onTrackAdded(
      Format format,
      @SupportedOutputTypes int supportedOutputTypes,
      long streamStartPositionUs,
      long streamOffsetUs)
      throws TransformationException {
    int trackType = MimeTypes.getTrackType(format.sampleMimeType);
    SampleConsumer sampleConsumer;
    if (currentMediaItemIndex.get() == 0) {
      sampleConsumer =
          new SampleConsumerWrapper(
              compositeAssetLoaderListener.onTrackAdded(
                  format, supportedOutputTypes, streamStartPositionUs, streamOffsetUs));
      sampleConsumersByTrackType.put(trackType, sampleConsumer);
    } else {
      sampleConsumer =
          checkStateNotNull(
              sampleConsumersByTrackType.get(trackType),
              "The preceding MediaItem does not contain any track of type " + trackType);
    }
    @Nullable
    OnMediaItemChangedListener onMediaItemChangedListener =
        mediaItemChangedListenersByTrackType.get(trackType);
    if (onMediaItemChangedListener != null) {
      onMediaItemChangedListener.onMediaItemChanged(
          editedMediaItems.get(currentMediaItemIndex.get()),
          format,
          /* mediaItemOffsetUs= */ totalDurationUs.get());
    }
    return sampleConsumer;
  }

  @Override
  public void onError(TransformationException exception) {
    compositeAssetLoaderListener.onError(exception);
  }

  private void addCurrentProcessedInput() {
    int currentMediaItemIndex = this.currentMediaItemIndex.get();
    if (currentMediaItemIndex >= processedInputsSize) {
      MediaItem mediaItem = editedMediaItems.get(currentMediaItemIndex).mediaItem;
      ImmutableMap<Integer, String> decoders = currentAssetLoader.getDecoderNames();
      processedInputsBuilder.add(
          new TransformationResult.ProcessedInput(
              mediaItem, decoders.get(C.TRACK_TYPE_AUDIO), decoders.get(C.TRACK_TYPE_VIDEO)));
      processedInputsSize++;
    }
  }

  private final class SampleConsumerWrapper implements SampleConsumer {

    private final SampleConsumer sampleConsumer;

    public SampleConsumerWrapper(SampleConsumer sampleConsumer) {
      this.sampleConsumer = sampleConsumer;
    }

    @Override
    public boolean expectsDecodedData() {
      // TODO(b/252537210): handle the case where the first media item doesn't need to be encoded
      //  but a following one does.
      return sampleConsumer.expectsDecodedData();
    }

    @Nullable
    @Override
    public DecoderInputBuffer getInputBuffer() {
      DecoderInputBuffer inputBuffer = sampleConsumer.getInputBuffer();
      if (inputBuffer != null && inputBuffer.isEndOfStream()) {
        inputBuffer.clear();
        inputBuffer.timeUs = 0;
      }
      return inputBuffer;
    }

    @Override
    public void queueInputBuffer() {
      DecoderInputBuffer inputBuffer = checkStateNotNull(sampleConsumer.getInputBuffer());
      if (inputBuffer.isEndOfStream()) {
        nonEndedTracks.decrementAndGet();
        if (currentMediaItemIndex.get() < editedMediaItems.size() - 1) {
          if (nonEndedTracks.get() == 0) {
            switchAssetLoader();
          }
          return;
        }
      }
      sampleConsumer.queueInputBuffer();
    }

    // TODO(262693274): Test that concatenate 2 images or an image and a video works as expected
    //  once Image Asset Loader Implementation is complete.
    @Override
    public void queueInputBitmap(Bitmap inputBitmap, long durationUs, int frameRate) {
      sampleConsumer.queueInputBitmap(inputBitmap, durationUs, frameRate);
    }

    @Override
    public Surface getInputSurface() {
      return sampleConsumer.getInputSurface();
    }

    @Override
    public ColorInfo getExpectedInputColorInfo() {
      return sampleConsumer.getExpectedInputColorInfo();
    }

    @Override
    public int getPendingVideoFrameCount() {
      return sampleConsumer.getPendingVideoFrameCount();
    }

    @Override
    public void registerVideoFrame() {
      sampleConsumer.registerVideoFrame();
    }

    @Override
    public void signalEndOfVideoInput() {
      nonEndedTracks.decrementAndGet();
      if (currentMediaItemIndex.get() < editedMediaItems.size() - 1) {
        if (nonEndedTracks.get() == 0) {
          switchAssetLoader();
        }
        return;
      }
      sampleConsumer.signalEndOfVideoInput();
    }

    private void switchAssetLoader() {
      totalDurationUs.addAndGet(currentDurationUs);
      handler.post(
          () -> {
            addCurrentProcessedInput();
            currentAssetLoader.release();
            EditedMediaItem editedMediaItem =
                editedMediaItems.get(currentMediaItemIndex.incrementAndGet());
            currentAssetLoader =
                assetLoaderFactory.createAssetLoader(
                    editedMediaItem,
                    checkNotNull(Looper.myLooper()),
                    /* listener= */ CompositeAssetLoader.this);
            currentAssetLoader.start();
          });
    }
  }
}
