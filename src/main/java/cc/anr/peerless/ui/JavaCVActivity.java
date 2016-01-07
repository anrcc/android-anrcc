package cc.anr.peerless.ui;

import android.app.Activity;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;

import com.googlecode.javacv.FrameRecorder;
import com.googlecode.javacv.cpp.opencv_core;

import java.io.File;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.List;

import cc.anr.peerless.R;
import cc.anr.peerless.javacv.CONSTANTS;
import cc.anr.peerless.javacv.CameraView;
import cc.anr.peerless.javacv.NewFFmpegFrameRecorder;
import cc.anr.peerless.javacv.RecorderParameters;
import cc.anr.peerless.javacv.RecorderThread;
import cc.anr.peerless.javacv.SavedFrames;
import cc.anr.peerless.javacv.Util;

import static com.googlecode.javacv.cpp.opencv_core.IPL_DEPTH_8U;

public class JavaCVActivity extends Activity implements View.OnClickListener {

    //neon库对opencv做了优化
    static {
        System.loadLibrary("checkneon");
    }
    //摄像头以及它的参数
    private Camera cameraDevice;
    private CameraView cameraView;
    Camera.Parameters cameraParameters = null;
    //IplImage对象,用于存储摄像头返回的byte[]，以及图片的宽高，depth，channel等
    private opencv_core.IplImage yuvIplImage = null;
    private int previewWidth = 480;
    private int previewHeight = 480;
    private byte[] bufferByte;


    //录制视频和保存音频的类
    private volatile NewFFmpegFrameRecorder videoRecorder;
    //当前录制的质量，会影响视频清晰度和文件大小
    private int currentResolution = CONSTANTS.RESOLUTION_MEDIUM_VALUE;
    //视频文件的存放地址
    private String strVideoPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "rec_video.mp4";
    //视频文件对象
    private File fileVideoPath = null;
    //视频文件在系统中存放的url
    private Uri uriVideoPath = null;
    //录视频进程
    private RecorderThread recorderThread;


    //音频的采样率，recorderParameters中会有默认值
    private int sampleRate = 44100;
    //调用系统的录制音频类
    private AudioRecord audioRecord;
    //录制音频的线程
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;




    //音频时间戳
    private volatile long mAudioTimestamp = 0L;
    private long mLastAudioTimestamp = 0L;
    private volatile long mAudioTimeRecorded;
    private long frameTime = 0L;



    //第一次按下屏幕时记录的时间
    long firstTime = 0;
    //视频帧率
    private int frameRate = 30;



    //判断是否录制
    boolean recording = false;
    //每一幀的数据结构
    private SavedFrames lastSavedframe = new SavedFrames(null, 0L);
    //视频时间戳
    private long mVideoTimestamp = 0L;
    //时候保存过视频文件
    private boolean isRecordingSaved = false;

    RelativeLayout takeVideoLayout;
    Button recordBtn;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_cv);

        recordBtn=(Button)findViewById(R.id.btn_record);
        recordBtn.setOnClickListener(this);
        //javacv
        takeVideoLayout = (RelativeLayout)findViewById(R.id.rl_take_video_layout);
        if (takeVideoLayout != null && takeVideoLayout.getChildCount() > 0)
            takeVideoLayout.removeAllViews();


        if(setCamera()) {
            initVideoRecorder();
            cameraView = new CameraView(JavaCVActivity.this, cameraDevice, new CameraView.OnSurfaceChanged() {
                @Override
                public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    handleSurfaceChanged();
                }
            }, new CameraView.OnPreviewFrame() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {

                    //计算时间戳
                    long frameTimeStamp = 0L;
                    if (mAudioTimestamp == 0L && firstTime > 0L) {
                        frameTimeStamp = 1000L * (System.currentTimeMillis() - firstTime);
                    } else if (mLastAudioTimestamp == mAudioTimestamp) {
                        frameTimeStamp = mAudioTimestamp + frameTime;
                    } else {
                        long l2 = (System.nanoTime() - mAudioTimeRecorded) / 1000L;
                        frameTimeStamp = l2 + mAudioTimestamp;
                        mLastAudioTimestamp = mAudioTimestamp;
                    }

                    //录制视频
                    if (recording) {
                        if (lastSavedframe != null && lastSavedframe.getFrameBytesData() != null && yuvIplImage != null) {


                            mVideoTimestamp += frameTime;
                            if (lastSavedframe.getTimeStamp() > mVideoTimestamp) {
                                mVideoTimestamp = lastSavedframe.getTimeStamp();
                            }

                            recorderThread.putByteData(lastSavedframe);
                        }
                        Log.i("wcl","rotateYUV420Degree90 previewWidth======" + previewWidth + "  previewHeight======" + previewHeight);

                        byte[] tempData = rotateYUV420Degree90(data, previewWidth, previewHeight);

                        lastSavedframe = new SavedFrames(tempData, frameTimeStamp);
                    }

                    cameraDevice.addCallbackBuffer(bufferByte);

                }
            });

            handleSurfaceChanged();

            if (recorderThread == null) {
                recorderThread = new RecorderThread(yuvIplImage, videoRecorder);
                recorderThread.start();
            }

            //设置surface的宽高
            RelativeLayout.LayoutParams layoutParam = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,RelativeLayout.LayoutParams.MATCH_PARENT);
            layoutParam.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
            takeVideoLayout.addView(cameraView, layoutParam);
        }



    }

    private boolean setCamera() {
        try {
            if (cameraDevice != null)
                cameraDevice.release();

            cameraDevice = Camera.open();
            cameraParameters = cameraDevice.getParameters();

        } catch (Exception e) {
            return false;
        }
        return true;
    }


    public void handleSurfaceChanged(){
        List<Camera.Size> tempList = cameraParameters.getSupportedPreviewSizes();
        Camera.Size csize = getOptimalPreviewSize(tempList, 320, 480);

        //获取计算过的摄像头分辨率
        if (csize != null) {
            previewWidth = csize.width;
            previewHeight = csize.height;
            cameraParameters.setPreviewSize(previewWidth, previewHeight);
            if (videoRecorder != null) {
                //videoRecorder.setImageWidth(previewWidth);
                //videoRecorder.setImageHeight(previewHeight);
            }
        }
        bufferByte = new byte[previewWidth * previewHeight * 3 / 2];
        cameraDevice.addCallbackBuffer(bufferByte);

        //设置预览帧率
        cameraParameters.setPreviewFrameRate(frameRate);

        //构建一个IplImage对象，用于录制视频
        //和opencv中的cvCreateImage方法一样
        yuvIplImage = opencv_core.IplImage.create(previewHeight, previewWidth, IPL_DEPTH_8U, 2);

        List<String> focusModes = cameraParameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        cameraDevice.setDisplayOrientation(90);
        cameraDevice.setParameters(cameraParameters);



    }

    /**
     * 开始录制
     */
    public void startRecording() {
        try {
            recording=true;
            videoRecorder.start();
            audioThread.start();
        } catch (NewFFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存录制的视频文件
     */
    private void saveRecording() {
        if (!isRecordingSaved) {
            isRecordingSaved = true;
            new AsyncStopRecording().execute();
        }

    }





    private void initVideoRecorder() {

        strVideoPath = Util.createFinalPath(this);//Util.createTempPath(tempFolderPath);

        RecorderParameters recorderParameters = Util.getRecorderParameter(currentResolution);
        sampleRate = recorderParameters.getAudioSamplingRate();
        frameRate = recorderParameters.getVideoFrameRate();
        frameTime = (1000000L / frameRate);

        fileVideoPath = new File(strVideoPath);
        videoRecorder = new NewFFmpegFrameRecorder(strVideoPath, 480, 480, 1);
        videoRecorder.setFormat(recorderParameters.getVideoOutputFormat());
        videoRecorder.setSampleRate(recorderParameters.getAudioSamplingRate());
        videoRecorder.setFrameRate(recorderParameters.getVideoFrameRate());
        videoRecorder.setVideoCodec(recorderParameters.getVideoCodec());
        videoRecorder.setVideoQuality(recorderParameters.getVideoQuality());
        videoRecorder.setAudioQuality(recorderParameters.getVideoQuality());
        videoRecorder.setAudioCodec(recorderParameters.getAudioCodec());
        videoRecorder.setVideoBitrate(recorderParameters.getVideoBitrate());
        videoRecorder.setAudioBitrate(recorderParameters.getAudioBitrate());



        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);



    }

    @Override
    public void onClick(View view) {

        if(view.getId()==R.id.btn_record){
            if(!recording){
                startRecording();
                recordBtn.setText("停止");
            }else{
                saveRecording();
                recordBtn.setText("开始");
            }
        }
    }


    /**
     * 录制音频的线程
     *
     * @author QD
     */
    class AudioRecordRunnable implements Runnable {

        int bufferSize;
        short[] audioData;
        int bufferReadResult;
        private final AudioRecord audioRecord;
        public volatile boolean isInitialized;
        private int mCount = 0;

        private AudioRecordRunnable() {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioData = new short[bufferSize];
        }

        /**
         * shortBuffer包含了音频的数据和起始位置
         *
         * @param shortBuffer
         */
        private void record(ShortBuffer shortBuffer) {
            try {
                if (videoRecorder != null) {
                    this.mCount += shortBuffer.limit();
                    videoRecorder.record(0, new Buffer[]{shortBuffer});
                }
            } catch (FrameRecorder.Exception localException) {

            }
            return;
        }

        /**
         * 更新音频的时间戳
         */
        private void updateTimestamp() {
            if (videoRecorder != null) {
                int i = Util.getTimeStampInNsFromSampleCounted(this.mCount);
                if (mAudioTimestamp != i) {
                    mAudioTimestamp = i;
                    mAudioTimeRecorded = System.nanoTime();
                }
            }
        }

        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            this.isInitialized = false;
            if (audioRecord != null) {
                //判断音频录制是否被初始化
                while (this.audioRecord.getState() == 0) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException localInterruptedException) {
                    }
                }
                this.isInitialized = true;
                this.audioRecord.startRecording();
                while (mVideoTimestamp > mAudioTimestamp) {
                    updateTimestamp();
                    bufferReadResult = this.audioRecord.read(audioData, 0, audioData.length);
                    if ((bufferReadResult > 0) && ((recording ) || (mVideoTimestamp > mAudioTimestamp)))
                        record(ShortBuffer.wrap(audioData, 0, bufferReadResult));
                }
                this.audioRecord.stop();
                this.audioRecord.release();
            }
        }
    }



    /**
     * 停止录制
     *
     * @author QD
     */
    public class AsyncStopRecording extends AsyncTask<Void, Integer, Void> {

        @Override
        protected void onPreExecute() {

            super.onPreExecute();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            Log.i("wcl", "处理进度===" + values[0] + "%");
        }


        @Override
        protected Void doInBackground(Void... params) {
            recorderThread.stopRecord(this);
            if (videoRecorder != null && recording) {
                recording = false;
                releaseResources();
            }
            publishProgress(100);
            return null;
        }

        public void publishProgressFromOther(int progress) {
            publishProgress(progress);
        }

        @Override
        protected void onPostExecute(Void result) {
            registerVideo();
            videoRecorder = null;
            //LogUtil.i("wcl","录制结束");
        }

    }

    /**
     * 释放资源，停止录制视频和音频
     */
    private void releaseResources() {
        recorderThread.finish();
        isRecordingSaved = true;
        try {
            if (videoRecorder != null) {
                videoRecorder.stop();
                videoRecorder.release();
            }
        } catch (com.googlecode.javacv.FrameRecorder.Exception e) {
            e.printStackTrace();
        }

        yuvIplImage = null;
        videoRecorder = null;
        lastSavedframe = null;

    }

    /**
     * 向系统注册我们录制的视频文件，这样文件才会在sd卡中显示
     */
    private void registerVideo() {
        Uri videoTable = Uri.parse(CONSTANTS.VIDEO_CONTENT_URI);

        Util.videoContentValues.put(MediaStore.Video.Media.SIZE, new File(strVideoPath).length());
        try {
            uriVideoPath = getContentResolver().insert(videoTable, Util.videoContentValues);
        } catch (Throwable e) {
            uriVideoPath = null;
            strVideoPath = null;
            e.printStackTrace();
        } finally {
        }
        Util.videoContentValues = null;
    }


    public Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }



    /**
     * 视频旋转
     * @param data
     * @param imageWidth
     * @param imageHeight
     * @return
     */
    private byte[] rotateYUV420Degree90(byte[] data, int imageWidth, int imageHeight) {

        final byte[] yuv = new byte[imageWidth * imageHeight * 3 / 2];
        // Rotate the Y luma
        int i = 0;
        for (int x = 0; x < imageWidth; x++) {
            for (int y = imageHeight - 1; y >= 0; y--) {
                yuv[i] = data[y * imageWidth + x];
                i++;
            }

        }
        // Rotate the U and V color components
        i = imageWidth * imageHeight * 3 / 2 - 1;
        for (int x = imageWidth - 1; x > 0; x = x - 2) {
            for (int y = 0; y < imageHeight / 2; y++) {
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth) + x];
                i--;
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth) + (x - 1)];
                i--;
            }
        }
        return yuv;
    }

}
