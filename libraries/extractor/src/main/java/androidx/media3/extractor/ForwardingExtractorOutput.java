/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

/**
 * An overridable {@link ExtractorOutput} implementation forwarding all methods to another input.
 */
@UnstableApi
public class ForwardingExtractorOutput implements ExtractorOutput {
  private final ExtractorOutput output;

  public ForwardingExtractorOutput(ExtractorOutput output) {
    this.output = output;
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    return output.track(id, type);
  }

  @Override
  public void endTracks() {
    output.endTracks();
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    output.seekMap(seekMap);
  }
}
