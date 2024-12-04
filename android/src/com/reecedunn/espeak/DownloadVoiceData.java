/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
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

package com.reecedunn.espeak;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DownloadVoiceData extends Service {
    public static final String BROADCAST_LANGUAGES_UPDATED = "com.reecedunn.espeak.LANGUAGES_UPDATED";

    private AsyncExtract mAsyncExtract;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Context storageContext = EspeakApp.getStorageContext();

        final File dataPath = CheckVoiceData.getDataPath(storageContext).getParentFile();

        mAsyncExtract = new AsyncExtract(storageContext, R.raw.espeakdata, dataPath) {
            @Override
            protected void onPostExecute(Integer result) {
                switch (result) {
                    case RESULT_OK:
                        final Intent intent = new Intent(BROADCAST_LANGUAGES_UPDATED);
                        sendBroadcast(intent);
                        break;
                    case RESULT_CANCELED:
                        // Do nothing?
                        break;
                }
                stopSelf();
            }
        };

        mAsyncExtract.execute();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mAsyncExtract.cancel(true);
    }

    private static final int PROGRESS_STARTING = 0;
    private static final int PROGRESS_EXTRACTING = 1;

    private static class ExtractProgress {
        int total;
        int state = PROGRESS_STARTING;
        File file;

        public ExtractProgress(int total) {
            this.total = total;
        }
    }

    private static class AsyncExtract extends AsyncTask<Void, ExtractProgress, Integer> {
        private final Context mContext;
        private final int mRawResId;
        private final File mOutput;

        public AsyncExtract(Context context, int rawResId, File output) {
            mContext = context;
            mRawResId = rawResId;
            mOutput = output;
        }

        @Override
        protected Integer doInBackground(Void... params) {
            FileUtils.rmdir(CheckVoiceData.getDataPath(mContext));

            final InputStream stream = mContext.getResources().openRawResource(mRawResId);
            final ZipInputStream zipStream = new ZipInputStream(new BufferedInputStream(stream));

            try {
                ExtractProgress progress = new ExtractProgress(stream.available());
                publishProgress(progress);
                progress.state = PROGRESS_EXTRACTING;

                final byte[] buffer = new byte[10240];

                int bytesRead;
                ZipEntry entry;

                while (!isCancelled() && ((entry = zipStream.getNextEntry()) != null)) {
                    progress.file = new File(mOutput, entry.getName());
                    publishProgress(progress);

                    if (entry.isDirectory()) {
                        progress.file.mkdirs();
                        continue;
                    }

                    // Ensure the target path exists.
                    progress.file.getParentFile().mkdirs();

                    final FileOutputStream outputStream = new FileOutputStream(progress.file);
                    try {
                        while (!isCancelled() && ((bytesRead = zipStream.read(buffer)) != -1)) {
                            outputStream.write(buffer, 0, bytesRead);
                            progress.total += bytesRead;
                        }
                    } finally {
                        outputStream.close();
                    }
                    zipStream.closeEntry();
                }

                final String version = FileUtils.read(mContext.getResources().openRawResource(R.raw.espeakdata_version));
                final File outputFile = new File(mOutput, "espeak-ng-data/version");

                FileUtils.write(outputFile, version);
                return RESULT_OK;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    zipStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return RESULT_CANCELED;
        }
    }
}
