package cc.anr.peerless.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import cc.anr.peerless.R;

public class MainActivity extends Activity implements View.OnClickListener{

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.in_javacv).setOnClickListener(this);
    }


    @Override
    public void onClick(View view) {
        int vid=view.getId();
        Intent inten =new Intent();
        if(vid==R.id.in_javacv){
            inten.setClass(this,JavaCVActivity.class);
        }
        startActivity(inten);

    }
}
