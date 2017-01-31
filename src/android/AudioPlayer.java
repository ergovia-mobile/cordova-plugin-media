/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;

import org.apache.cordova.LOG;
import org.json.JSONException;
import org.json.JSONObject;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaRecorder;
import android.os.Environment;

/**
 * This class implements the audio playback and recording capabilities used by Cordova.
 * It is called by the AudioHandler Cordova class.
 * Only one file can be played or recorded per class instance.
 * <p>
 * Local audio files must reside in one of two places:
 * android_asset:      file name must start with /android_asset/sound.mp3
 * sdcard:             file name is just sound.mp3
 */
public class AudioPlayer implements OnCompletionListener, OnPreparedListener, OnErrorListener {

    // AudioPlayer modes
    public enum MODE {
        NONE, PLAY, RECORD
    }

    ;

    // AudioPlayer states
    public enum STATE {
        MEDIA_NONE,
        MEDIA_STARTING,
        MEDIA_RUNNING,
        MEDIA_PAUSED,
        MEDIA_STOPPED,
        MEDIA_LOADING
    }

    ;

    private static final String LOG_TAG = "AudioPlayer";

    // AudioPlayer message ids
    private static final int MEDIA_STATE = 1;
    private static final int MEDIA_DURATION = 2;
    private static final int MEDIA_POSITION = 3;
    private static final int MEDIA_ERROR = 9;

    // Media error codes
    private static final int MEDIA_ERR_NONE_ACTIVE = 0;
    private static final int MEDIA_ERR_ABORTED = 1;
//    private static int MEDIA_ERR_NETWORK        = 2;
//    private static int MEDIA_ERR_DECODE         = 3;
//    private static int MEDIA_ERR_NONE_SUPPORTED = 4;

    private final AudioHandler handler;           // The AudioHandler object
    private final String id;                      // The id of this player (used to identify Media object in JavaScript)
    private MODE mode = MODE.NONE;          // Playback or Recording mode
    private STATE state = STATE.MEDIA_NONE; // State of recording or playback

    private String audioFile = null;        // File name to play or record to
    private float duration = -1;            // Duration of audio

    private AudioRecord recorder = null;  // Audio recording object
    private LinkedList<String> tempFiles = null; // Temporary recording file name
    private String tempFile = null;

    private Thread recordingThread = null;

    private MediaPlayer player = null;      // Audio player object
    private boolean prepareOnly = true;     // playback after file prepare flag
    private int seekOnPrepared = 0;     // seek to this location once media is prepared

    private static final int RECORDER_BPP = 16;
    private static final String AUDIO_RECORDER_TEMP_FILE = "record_temp.raw";
    private static final int RECORDER_SAMPLERATE = 9600;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * Constructor.
     *
     * @param handler The audio handler object
     * @param id      The id of this audio player
     */
    public AudioPlayer(final AudioHandler handler, final String id, final String file) {
        this.handler = handler;
        this.id = id;
        this.audioFile = file;
        this.tempFiles = new LinkedList<String>();
    }

    private String generateTempFile() {
        String tempFileName = null;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            tempFileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + AUDIO_RECORDER_TEMP_FILE;
        } else {
            tempFileName = "/data/data/" + handler.cordova.getActivity().getPackageName() + "/cache/" + AUDIO_RECORDER_TEMP_FILE;
        }
        return tempFileName;
    }

    /**
     * Destroy player and stop audio playing or recording.
     */
    public void destroy() {
        // Stop any play or record
        if (this.player != null) {
            if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
                this.player.stop();
                this.setState(STATE.MEDIA_STOPPED);
            }
            this.player.release();
            this.player = null;
        }
        if (this.recorder != null) {
            this.stopRecording(true);
            this.recorder.release();
            this.recorder = null;
            this.recordingThread = null;
        }
    }

    /**
     * Start recording the specified file.
     *
     * @param file The name of the file
     */
    public void startRecording(final String file) {
        switch (this.mode) {
            case PLAY:
                LOG.d(LOG_TAG, "AudioPlayer Error: Can't record in play mode.");
                sendErrorStatus(MEDIA_ERR_ABORTED);
                break;
            case NONE:
                this.audioFile = file;

                final int bufferSize = AudioRecord.getMinBufferSize(8000,
                        AudioFormat.CHANNEL_CONFIGURATION_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                this.recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING, bufferSize);

                this.tempFile = generateTempFile();

                recordingThread = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        writeAudioDataToFile(tempFile, bufferSize);
                        recorder = null;
                    }

                }, "AudioRecorder Thread");

                try {
                    final int i = this.recorder.getState();

                    if (i == 1) {

                        this.recorder.startRecording();
                        this.setState(STATE.MEDIA_RUNNING);

                        recordingThread.start();
                    }
                    return;
                } catch (final IllegalStateException e) {
                    e.printStackTrace();
                }

                sendErrorStatus(MEDIA_ERR_ABORTED);
                break;
            case RECORD:
                LOG.d(LOG_TAG, "AudioPlayer Error: Already recording.");
                sendErrorStatus(MEDIA_ERR_ABORTED);
        }
    }

    private void writeAudioDataToFile(final String filename, final int bufferSize) {

        final byte[] data = new byte[bufferSize];
        FileOutputStream os = null;

        try {

            os = new FileOutputStream(filename);
            int read = 0;

            while (STATE.MEDIA_RUNNING.ordinal() == getState() && this.recorder.getState() == 1) {

                read = this.recorder.read(data, 0, bufferSize);

                if (AudioRecord.ERROR_INVALID_OPERATION != read) {
                    os.write(data);
                }
            }

        } catch (final FileNotFoundException e) {
            e.printStackTrace();
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {

            try {
                if (os != null) {
                    os.close();
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }


    }

    /**
     * Save temporary recorded file to specified name
     *
     * @param file
     */
    public void moveFile(String file) {
        /* this is a hack to save the file as the specified name */

        if (!file.startsWith("/")) {
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                file = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + file;
            } else {
                file = "/data/data/" + handler.cordova.getActivity().getPackageName() + "/cache/" + file;
            }
        }

        final int size = this.tempFiles.size();
        LOG.d(LOG_TAG, "size = " + size);

        // only one file so just copy it
        final String logMsg = "renaming " + this.tempFile + " to " + file;
        LOG.d(LOG_TAG, logMsg);

        FileInputStream in = null;
        FileOutputStream out = null;
        long totalAudioLen = 0;
        long totalDataLen = totalAudioLen + 36;
        final long longSampleRate = RECORDER_SAMPLERATE;
        final int channels = 1;
        final long byteRate = RECORDER_BPP * RECORDER_SAMPLERATE * channels / 8;

        File inputFile = null;

        try {
            out = new FileOutputStream(new File(file));

            inputFile = new File(this.tempFiles.get(0));
            in = new FileInputStream(inputFile);

            totalAudioLen = in.getChannel().size();
            totalDataLen = totalAudioLen + 36;

            writeWaveFileHeader(out, totalAudioLen, totalDataLen,
                    longSampleRate, channels, byteRate);

            copy(in, out, false);

        } catch (final Exception e) {
            LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
        } finally {
            if (in != null) try {
                in.close();
                inputFile.delete();
                inputFile = null;
            } catch (final Exception e) {
                LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
            }

            if (out != null) try {
                out.close();
            } catch (final Exception e) {
                LOG.e(LOG_TAG, e.getLocalizedMessage(), e);
            }
            this.tempFiles.clear();
        }

    }

    private static long copy(final InputStream from, final OutputStream to, final boolean skipHeader)
            throws IOException {
        final byte[] buf = new byte[8096];
        long total = 0;
        if (skipHeader) {
            from.skip(6);
        }
        while (true) {
            final int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }

    /**
     * Stop/Pause recording and save to the file specified when recording started.
     */
    public void stopRecording(final boolean stop) {
        if (this.recorder != null) {
            try {
                final int i = recorder.getState();
                if (this.state == STATE.MEDIA_RUNNING && i == 1) {
                    this.recorder.stop();
                    this.recorder.release();

                    LOG.d(LOG_TAG, "stopping recording");
                    this.setState(STATE.MEDIA_STOPPED);

                    recordingThread = null;
                }

                this.tempFiles.add(this.tempFile);
                if (stop) {

                    this.moveFile(this.audioFile);
                } else {
                    LOG.d(LOG_TAG, "pause recording");
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Resume recording and save to the file specified when recording started.
     */
    public void resumeRecording() {
        startRecording(this.audioFile);
    }

    //==========================================================================
    // Playback
    //==========================================================================

    /**
     * Start or resume playing audio file.
     *
     * @param file The name of the audio file.
     */
    public void startPlaying(final String file) {
        if (this.readyPlayer(file) && this.player != null) {
            this.player.start();
            this.setState(STATE.MEDIA_RUNNING);
            this.seekOnPrepared = 0; //insures this is always reset
        } else {
            this.prepareOnly = false;
        }
    }

    /**
     * Seek or jump to a new time in the track.
     */
    public void seekToPlaying(final int milliseconds) {
        if (this.readyPlayer(this.audioFile)) {
            if (milliseconds > 0) {
                this.player.seekTo(milliseconds);
            }
            LOG.d(LOG_TAG, "Send a onStatus update for the new seek");
            sendStatusChange(MEDIA_POSITION, null, (milliseconds / 1000.0f));
        } else {
            this.seekOnPrepared = milliseconds;
        }
    }

    /**
     * Pause playing.
     */
    public void pausePlaying() {

        // If playing, then pause
        if (this.state == STATE.MEDIA_RUNNING && this.player != null) {
            this.player.pause();
            this.setState(STATE.MEDIA_PAUSED);
        } else {
            LOG.d(LOG_TAG, "AudioPlayer Error: pausePlaying() called during invalid state: " + this.state.ordinal());
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * Stop playing the audio file.
     */
    public void stopPlaying() {
        if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
            this.player.pause();
            this.player.seekTo(0);
            LOG.d(LOG_TAG, "stopPlaying is calling stopped");
            this.setState(STATE.MEDIA_STOPPED);
        } else {
            LOG.d(LOG_TAG, "AudioPlayer Error: stopPlaying() called during invalid state: " + this.state.ordinal());
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * Callback to be invoked when playback of a media source has completed.
     *
     * @param player The MediaPlayer that reached the end of the file
     */
    public void onCompletion(final MediaPlayer player) {
        LOG.d(LOG_TAG, "on completion is calling stopped");
        this.setState(STATE.MEDIA_STOPPED);
    }

    /**
     * Get current position of playback.
     *
     * @return position in msec or -1 if not playing
     */
    public long getCurrentPosition() {
        if ((this.state == STATE.MEDIA_RUNNING) || (this.state == STATE.MEDIA_PAUSED)) {
            final int curPos = this.player.getCurrentPosition();
            sendStatusChange(MEDIA_POSITION, null, (curPos / 1000.0f));
            return curPos;
        } else {
            return -1;
        }
    }

    /**
     * Determine if playback file is streaming or local.
     * It is streaming if file name starts with "http://"
     *
     * @param file The file name
     * @return T=streaming, F=local
     */
    public boolean isStreaming(final String file) {
        if (file.contains("http://") || file.contains("https://") || file.contains("rtsp://")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Get the duration of the audio file.
     *
     * @param file The name of the audio file.
     * @return The duration in msec.
     * -1=can't be determined
     * -2=not allowed
     */
    public float getDuration(final String file) {

        // Can't get duration of recording
        if (this.recorder != null) {
            return (-2); // not allowed
        }

        // If audio file already loaded and started, then return duration
        if (this.player != null) {
            return this.duration;
        }

        // If no player yet, then create one
        else {
            this.prepareOnly = true;
            this.startPlaying(file);

            // This will only return value for local, since streaming
            // file hasn't been read yet.
            return this.duration;
        }
    }

    /**
     * Callback to be invoked when the media source is ready for playback.
     *
     * @param player The MediaPlayer that is ready for playback
     */
    public void onPrepared(final MediaPlayer player) {
        // Listen for playback completion
        this.player.setOnCompletionListener(this);
        // seek to any location received while not prepared
        this.seekToPlaying(this.seekOnPrepared);
        // If start playing after prepared
        if (!this.prepareOnly) {
            this.player.start();
            this.setState(STATE.MEDIA_RUNNING);
            this.seekOnPrepared = 0; //reset only when played
        } else {
            this.setState(STATE.MEDIA_STARTING);
        }
        // Save off duration
        this.duration = getDurationInSeconds();
        // reset prepare only flag
        this.prepareOnly = true;

        // Send status notification to JavaScript
        sendStatusChange(MEDIA_DURATION, null, this.duration);
    }

    /**
     * By default Android returns the length of audio in mills but we want seconds
     *
     * @return length of clip in seconds
     */
    private float getDurationInSeconds() {
        return (this.player.getDuration() / 1000.0f);
    }

    /**
     * Callback to be invoked when there has been an error during an asynchronous operation
     * (other errors will throw exceptions at method call time).
     *
     * @param player the MediaPlayer the error pertains to
     * @param arg1   the type of error that has occurred: (MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_SERVER_DIED)
     * @param arg2   an extra code, specific to the error.
     */
    public boolean onError(final MediaPlayer player, final int arg1, final int arg2) {
        LOG.d(LOG_TAG, "AudioPlayer.onError(" + arg1 + ", " + arg2 + ")");

        // we don't want to send success callback
        // so we don't call setState() here
        this.state = STATE.MEDIA_STOPPED;
        this.destroy();
        // Send error notification to JavaScript
        sendErrorStatus(arg1);

        return false;
    }

    /**
     * Set the state and send it to JavaScript.
     *
     * @param state
     */
    private void setState(final STATE state) {
        if (this.state != state) {
            sendStatusChange(MEDIA_STATE, null, (float) state.ordinal());
        }
        this.state = state;
    }

    /**
     * Set the mode and send it to JavaScript.
     *
     * @param mode
     */
    private void setMode(final MODE mode) {
        if (this.mode != mode) {
            //mode is not part of the expected behavior, so no notification
            //this.handler.webView.sendJavascript("cordova.require('cordova-plugin-media.Media').onStatus('" + this.id + "', " + MEDIA_STATE + ", " + mode + ");");
        }
        this.mode = mode;
    }

    /**
     * Get the audio state.
     *
     * @return int
     */
    public int getState() {
        return this.state.ordinal();
    }

    /**
     * Set the volume for audio player
     *
     * @param volume
     */
    public void setVolume(final float volume) {
        if (this.player != null) {
            this.player.setVolume(volume, volume);
        } else {
            LOG.d(LOG_TAG, "AudioPlayer Error: Cannot set volume until the audio file is initialized.");
            sendErrorStatus(MEDIA_ERR_NONE_ACTIVE);
        }
    }

    /**
     * attempts to put the player in play mode
     *
     * @return true if in playmode, false otherwise
     */
    private boolean playMode() {
        switch (this.mode) {
            case NONE:
                this.setMode(MODE.PLAY);
                break;
            case PLAY:
                break;
            case RECORD:
                LOG.d(LOG_TAG, "AudioPlayer Error: Can't play in record mode.");
                sendErrorStatus(MEDIA_ERR_ABORTED);
                return false; //player is not ready
        }
        return true;
    }

    /**
     * attempts to initialize the media player for playback
     *
     * @param file the file to play
     * @return false if player not ready, reports if in wrong mode or state
     */
    private boolean readyPlayer(final String file) {
        if (playMode()) {
            switch (this.state) {
                case MEDIA_NONE:
                    if (this.player == null) {
                        this.player = new MediaPlayer();
                        this.player.setOnErrorListener(this);
                    }
                    try {
                        this.loadAudioFile(file);
                    } catch (final Exception e) {
                        sendErrorStatus(MEDIA_ERR_ABORTED);
                    }
                    return false;
                case MEDIA_LOADING:
                    //cordova js is not aware of MEDIA_LOADING, so we send MEDIA_STARTING instead
                    LOG.d(LOG_TAG, "AudioPlayer Loading: startPlaying() called during media preparation: " + STATE.MEDIA_STARTING.ordinal());
                    this.prepareOnly = false;
                    return false;
                case MEDIA_STARTING:
                case MEDIA_RUNNING:
                case MEDIA_PAUSED:
                    return true;
                case MEDIA_STOPPED:
                    //if we are readying the same file
                    if (this.audioFile.compareTo(file) == 0) {
                        //maybe it was recording?
                        if (this.recorder != null && player == null) {
                            this.player = new MediaPlayer();
                            this.player.setOnErrorListener(this);
                            this.prepareOnly = false;

                            try {
                                this.loadAudioFile(file);
                            } catch (final Exception e) {
                                sendErrorStatus(MEDIA_ERR_ABORTED);
                            }
                            return false;//we´re not ready yet
                        } else {
                            //reset the audio file
                            player.seekTo(0);
                            player.pause();
                            return true;
                        }
                    } else {
                        //reset the player
                        this.player.reset();
                        try {
                            this.loadAudioFile(file);
                        } catch (final Exception e) {
                            sendErrorStatus(MEDIA_ERR_ABORTED);
                        }
                        //if we had to prepare the file, we won't be in the correct state for playback
                        return false;
                    }
                default:
                    LOG.d(LOG_TAG, "AudioPlayer Error: startPlaying() called during invalid state: " + this.state);
                    sendErrorStatus(MEDIA_ERR_ABORTED);
            }
        }
        return false;
    }

    /**
     * load audio file
     *
     * @throws IOException
     * @throws IllegalStateException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private void loadAudioFile(final String file) throws IllegalArgumentException, SecurityException, IllegalStateException, IOException {
        if (this.isStreaming(file)) {
            this.player.setDataSource(file);
            this.player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            //if it's a streaming file, play mode is implied
            this.setMode(MODE.PLAY);
            this.setState(STATE.MEDIA_STARTING);
            this.player.setOnPreparedListener(this);
            this.player.prepareAsync();
        } else {
            if (file.startsWith("/android_asset/")) {
                final String f = file.substring(15);
                final android.content.res.AssetFileDescriptor fd = this.handler.cordova.getActivity().getAssets().openFd(f);
                this.player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            } else {
                final File fp = new File(file);
                if (fp.exists()) {
                    final FileInputStream fileInputStream = new FileInputStream(file);
                    this.player.setDataSource(fileInputStream.getFD());
                    fileInputStream.close();
                } else {
                    this.player.setDataSource(Environment.getExternalStorageDirectory().getPath() + "/" + file);
                }
            }
            this.setState(STATE.MEDIA_STARTING);
            this.player.setOnPreparedListener(this);
            this.player.prepare();

            // Get duration
            this.duration = getDurationInSeconds();
        }
    }

    private void sendErrorStatus(final int errorCode) {
        sendStatusChange(MEDIA_ERROR, errorCode, null);
    }

    private void sendStatusChange(final int messageType, final Integer additionalCode, final Float value) {

        if (additionalCode != null && value != null) {
            throw new IllegalArgumentException("Only one of additionalCode or value can be specified, not both");
        }

        final JSONObject statusDetails = new JSONObject();
        try {
            statusDetails.put("id", this.id);
            statusDetails.put("msgType", messageType);
            if (additionalCode != null) {
                final JSONObject code = new JSONObject();
                code.put("code", additionalCode.intValue());
                statusDetails.put("value", code);
            } else if (value != null) {
                statusDetails.put("value", value.floatValue());
            }
        } catch (final JSONException e) {
            LOG.e(LOG_TAG, "Failed to create status details", e);
        }

        this.handler.sendEventMessage("status", statusDetails);
    }

    private void writeWaveFileHeader(final FileOutputStream out, final long totalAudioLen, final long totalDataLen, final long longSampleRate, final int channels, final long byteRate) throws IOException {

        final byte[] header = new byte[44];

        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8); // block align
        header[33] = 0;
        header[34] = RECORDER_BPP; // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }
}
