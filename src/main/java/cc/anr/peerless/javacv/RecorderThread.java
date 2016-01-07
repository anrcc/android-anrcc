package cc.anr.peerless.javacv;

import android.os.AsyncTask;
import android.util.Log;

import com.googlecode.javacv.FrameRecorder;
import com.googlecode.javacv.cpp.opencv_core;

import java.util.concurrent.atomic.AtomicBoolean;

public class RecorderThread extends Thread {

    private opencv_core.IplImage mYuvIplImage;
    private NewFFmpegFrameRecorder mVideoRecorder;

    private AtomicBoolean mIsStop = new AtomicBoolean(false);
    private AtomicBoolean mIsFinish = new AtomicBoolean(false);

    public RecorderThread(opencv_core.IplImage yuvIplImage,NewFFmpegFrameRecorder videoRecorder){
        this.mYuvIplImage = yuvIplImage;
        this.mVideoRecorder = videoRecorder;
    }

    public void putByteData(SavedFrames lastSavedframe){
            mYuvIplImage.getByteBuffer().put(lastSavedframe.getFrameBytesData());
            try {
                mVideoRecorder.record(mYuvIplImage);
            } catch (FrameRecorder.Exception e) {
                Log.i("recorder", "录制错误" + e.getMessage());
                e.printStackTrace();
            }

    }

    @Override
    public void run() {
        try {
            while (!mIsFinish.get()) {
                    if(mIsStop.get()){
                        break;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (Exception e) {
                        e.printStackTrace();
                }
            }
        }finally {
            release();
        }
    }

    public void stopRecord(AsyncTask asyncTask){
        mIsStop.set(true);
        try {
            this.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void finish(){
        mIsFinish.set(true);
        try {
            this.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void release(){
        mYuvIplImage = null;
        mVideoRecorder = null;

    }
}
