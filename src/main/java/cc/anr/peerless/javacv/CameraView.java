package cc.anr.peerless.javacv;

import android.content.Context;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

/**
 * 显示摄像头的内容，以及返回摄像头的每一帧数据
 *
 * @author QD
 */
public class CameraView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private SurfaceHolder mHolder;
    private Camera mCamera;
    OnSurfaceChanged cvOnSurfaceChanged;
    OnPreviewFrame cvOnPreviewFrame;
    public CameraView(Context context, Camera camera, OnSurfaceChanged cvOnSurfaceChanged, OnPreviewFrame cvOnPreviewFrame) {
        super(context);
        this.mCamera = camera;
        mHolder = getHolder();
        mHolder.addCallback(CameraView.this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mCamera.setPreviewCallbackWithBuffer(CameraView.this);
        this.cvOnPreviewFrame=cvOnPreviewFrame;
        this.cvOnSurfaceChanged=cvOnSurfaceChanged;
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            stopPreview();
            mCamera.setPreviewDisplay(holder);
        } catch (IOException exception) {
            mCamera.release();
            mCamera = null;
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
//            if (isPreviewOn)
//                mCamera.stopPreview();
        if(cvOnSurfaceChanged!=null){
            cvOnSurfaceChanged.onSurfaceChanged(holder,format,width,height);
        }
        startPreview();
        mCamera.autoFocus(null);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        try {
            mHolder.addCallback(null);
            mCamera.setPreviewCallback(null);

        } catch (RuntimeException e) {
        }
    }

    public void startPreview() {
        if ( mCamera != null) {
            mCamera.startPreview();
        }
    }

    public void stopPreview() {

    }







    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if(cvOnPreviewFrame!=null){
            cvOnPreviewFrame.onPreviewFrame(data,camera);
        }
    }



    public interface OnSurfaceChanged{
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height);
    }
    public interface OnPreviewFrame{
        public void onPreviewFrame(byte[] data, Camera camera);
    }
}